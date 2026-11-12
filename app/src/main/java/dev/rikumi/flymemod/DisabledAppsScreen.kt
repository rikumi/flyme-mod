package dev.rikumi.flymemod

import android.content.Context
import android.text.Annotation
import android.text.Spanned
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.CancellationException
import android.content.Intent
import android.content.ActivityNotFoundException
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.key
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

import dev.rikumi.flymemod.ModuleSettings.HIDE_DISABLED_APPS

private enum class DisabledAppsWarningAction { ADD, EXPORT }

// 生成导出脚本: 每行一条 adb 命令, 按模式分段并以注释标出(停用 / 用户级卸载)。
private fun buildExportScript(ctx: Context, apps: List<ManagedAppEntry>): String {
    val sb = StringBuilder("#!/bin/sh\n")
    val disabled = apps.filter { !it.uninstalled }
    val uninstalled = apps.filter { it.uninstalled }
    if (disabled.isNotEmpty()) {
        sb.append("\n# ").append(ctx.getString(R.string.ui_disabled_state)).append("\n")
        disabled.forEach { sb.append("adb shell pm disable-user --user 0 ").append(it.pkg).append('\n') }
    }
    if (uninstalled.isNotEmpty()) {
        sb.append("\n# ").append(ctx.getString(R.string.ui_user_uninstalled)).append("\n")
        uninstalled.forEach { sb.append("adb shell pm uninstall -k --user 0 ").append(it.pkg).append('\n') }
    }
    return sb.toString()
}

@Composable
internal fun DisabledAppsScreen(ctx: Context, onBack: () -> Unit, showBack: Boolean = true) {
    val listState = rememberScrollState()
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf(false) }
    var choices by remember { mutableStateOf<List<ManagedAppEntry>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var warningAction by remember { mutableStateOf<DisabledAppsWarningAction?>(null) }
    var loadGeneration by remember { mutableStateOf(0) }
    val pickerState = rememberScrollState()
    val currentListState = if (picking) pickerState else listState
    val overscrollOffset = remember { mutableFloatStateOf(0f) }
    BackHandler(enabled = picking || busy) { if (!busy) picking = false }
    var apps by remember { mutableStateOf<List<ManagedAppEntry>?>(null) }
    LaunchedEffect(Unit) {
        val generation = loadGeneration
        try {
            val loaded = withContext(Dispatchers.IO) { loadDisabledManagedApps(ctx) }
            if (generation == loadGeneration) apps = loaded
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (generation == loadGeneration) {
                apps = emptyList()
                showManagedAppsError(ctx, R.string.app_management_read_disabled_failed, error)
            }
        }
    }
    LaunchedEffect(picking) {
        overscrollOffset.floatValue = 0f
        if (picking) {
            choices = null
            pickerState.scrollTo(0)
            try {
                choices = withContext(Dispatchers.IO) { loadSystemManagedApps(ctx) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                choices = emptyList()
                showManagedAppsError(ctx, R.string.app_management_read_system_failed, error)
            }
        }
    }
    fun perform(app: ManagedAppEntry, operation: ManagedAppOperation) {
        if (busy) return
        busy = true
        loadGeneration++
        android.widget.Toast.makeText(ctx, ctx.getString(R.string.app_management_progress,
            ctx.getString(operation.titleRes)), android.widget.Toast.LENGTH_SHORT).show()
        scope.launch {
            try {
                withContext(Dispatchers.IO) { executeManagedAppOperation(ctx, app, operation) }
                android.widget.Toast.makeText(ctx, ctx.getString(R.string.app_management_success),
                    android.widget.Toast.LENGTH_SHORT).show()
                try {
                    apps = withContext(Dispatchers.IO) { loadDisabledManagedApps(ctx) }
                    if (picking) choices = withContext(Dispatchers.IO) { loadSystemManagedApps(ctx) }
                } catch (error: Exception) {
                    showManagedAppsError(ctx, R.string.app_management_refresh_failed, error)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                android.widget.Toast.makeText(ctx, ctx.getString(R.string.app_management_action_failed,
                    ctx.getString(operation.titleRes), error.message ?: error.javaClass.simpleName),
                    android.widget.Toast.LENGTH_LONG).show()
            } finally { busy = false }
        }
    }
    // SAF 创建文件后写入脚本, 无需存储权限。
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-sh"),
    ) { uri ->
        if (uri != null) {
            val current = apps.orEmpty()
            scope.launch(Dispatchers.IO) {
                val ok = runCatching {
                    ctx.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(buildExportScript(ctx, current).toByteArray())
                    } != null
                }.getOrDefault(false)
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        ctx,
                        if (ok) ctx.getString(R.string.ui_exported) else ctx.getString(R.string.ui_export_failed),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }
    Scaffold(
        topBar = {
            FluixTopAppBar(
                title = ctx.getString(if (picking) R.string.app_management_select_system else R.string.ui_category_disabled),
                navigationIcon = {
                    if (showBack || picking) IconButton(onClick = { if (!busy) { if (picking) picking = false else onBack() } }) {
                        Icon(MiuixIcons.Back, ctx.getString(R.string.ui_back), tint = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.size(FLUIX_BACK_ICON))
                    }
                },
                actions = {
                    if (!picking && !busy) {
                        RestartMenu()
                    }
                },
            )
        },
    ) { padding ->
        // Prepare rows when the list loads, so scrolling does not compose new app rows.
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
                .fluixOverscroll(currentListState, overscrollOffset)
                .verticalScroll(currentListState),
        ) {
            if (picking) {
                FluixSmallTitle(ctx.getString(R.string.app_management_system_apps))
                val current = choices
                when {
                    current == null -> FluixSmallTitle(ctx.getString(R.string.ui_loading))
                    current.isEmpty() -> FluixSmallTitle(ctx.getString(R.string.ui_no_apps))
                    else -> current.forEachIndexed { index, app ->
                        key(app.pkg) {
                            FluixCardRow(first = index == 0, last = index == current.lastIndex) {
                                if (index > 0) FluixItemDivider()
                                ManagedAppActionsRow(app, busy, picking = true,
                                    onAction = { perform(app, it) })
                            }
                        }
                    }
                }
            } else {
                FluixCard {
                    DisabledAppsActionRow(R.string.ui_disabled_apps_add_title, enabled = !busy) {
                        warningAction = DisabledAppsWarningAction.ADD
                    }
                    FluixItemDivider()
                    DisabledAppsActionRow(R.string.ui_disabled_apps_export_title, enabled = !busy) {
                        warningAction = DisabledAppsWarningAction.EXPORT
                    }
                }
                FluixSmallTitle(ctx.getString(R.string.ui_disabled_settings))
                FluixCard { HideDisabledAppsRow(ctx) }
                FluixSmallTitle(ctx.getString(R.string.ui_disabled_apps))
                val current = apps
                when {
                    current == null -> FluixSmallTitle(ctx.getString(R.string.ui_loading))
                    current.isEmpty() -> FluixSmallTitle(ctx.getString(R.string.ui_no_disabled_apps))
                    else -> current.forEachIndexed { index, entry ->
                        key(entry.pkg) {
                            FluixCardRow(first = index == 0, last = index == current.lastIndex) {
                                if (index > 0) FluixItemDivider()
                                ManagedAppActionsRow(entry, busy,
                                    onInfo = { openAppDetails(ctx, entry.pkg) },
                                    onAction = { perform(entry, it) })
                            }
                        }
                    }
                }
            }
            Box(Modifier.height(24.dp))
        }
    }

    warningAction?.let { action ->
        FluixConfirmDialog(
            title = ctx.getString(if (action == DisabledAppsWarningAction.ADD)
                R.string.ui_disabled_apps_add_title else R.string.ui_disabled_apps_export_title),
            text = disabledAppsWarningText(if (action == DisabledAppsWarningAction.ADD)
                R.string.ui_disabled_apps_add_hint else R.string.ui_disabled_apps_export_hint),
            confirmLabel = ctx.getString(R.string.ui_disabled_apps_continue),
            onDismiss = { warningAction = null },
            onConfirm = {
                warningAction = null
                if (!busy) {
                    if (action == DisabledAppsWarningAction.ADD) picking = true
                    else if (apps.isNullOrEmpty()) {
                        android.widget.Toast.makeText(ctx, ctx.getString(R.string.ui_no_apps_to_export),
                            android.widget.Toast.LENGTH_SHORT).show()
                    } else exportLauncher.launch("disabled_apps.sh")
                }
            },
        )
    }

}

@Composable
private fun HideDisabledAppsRow(ctx: Context) {
    var checked by remember {
        mutableStateOf(ModuleSettings.preferences(ctx).getBoolean(HIDE_DISABLED_APPS, false))
    }
    FluixSwitchPreference(
        checked = checked,
        onCheckedChange = {
            checked = it
            ModuleSettings.preferences(ctx).edit().putBoolean(HIDE_DISABLED_APPS, it).apply()
            ctx.contentResolver.notifyChange(ModuleSettings.URI, null)
        },
        title = androidx.compose.ui.res.stringResource(R.string.ui_hide_in_app_management),
    )
}

@Composable
private fun DisabledAppsActionRow(
    @androidx.annotation.StringRes titleRes: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    BasicText(
        text = androidx.compose.ui.res.stringResource(titleRes),
        style = MiuixTheme.textStyles.body1.copy(color = MiuixTheme.colorScheme.onSurface),
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
    )
}

@Composable
private fun disabledAppsWarningText(
    @androidx.annotation.StringRes descriptionRes: Int,
): androidx.compose.ui.text.AnnotatedString {
    val description = androidx.compose.ui.platform.LocalContext.current.resources.getText(descriptionRes)
    val warningColor = Color(0xFFE5484D)
    return buildAnnotatedString {
        append(description.toString())
        if (description is Spanned) {
            description.getSpans(0, description.length, Annotation::class.java).forEach { annotation ->
                if (annotation.key == "warning") {
                    addStyle(SpanStyle(color = warningColor), description.getSpanStart(annotation),
                        description.getSpanEnd(annotation))
                }
            }
        }
    }
}

private fun openAppDetails(ctx: Context, pkg: String) {
    try {
        ctx.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
        )
    } catch (_: ActivityNotFoundException) {
        android.widget.Toast.makeText(ctx, ctx.getString(R.string.ui_cannot_open_app_settings), android.widget.Toast.LENGTH_SHORT)
            .show()
    }
}

@Composable
private fun DisabledAppRow(entry: ManagedAppEntry, showState: Boolean = true, onClick: () -> Unit) {
    val onSurface = MiuixTheme.colorScheme.onSurface
    val summary = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val detailStyle = MiuixTheme.textStyles.body2.copy(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = summary,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        entry.icon?.let {
            Image(it, contentDescription = null,
                modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)))
            Box(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            val appName = entry.label?.takeIf { it.isNotBlank() && it != entry.pkg }
            BasicText(
                text = appName ?: entry.pkg,
                style = MiuixTheme.textStyles.body1.copy(color = onSurface),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (appName != null) BasicText(
                text = entry.pkg,
                style = detailStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            val annotation = remember(entry.pkg) { SystemAppDescriptions.find(entry.pkg) }
            if (annotation != null) {
                val dark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
                val prefixColor = if (annotation.recommendation == R.string.system_app_recommendation_keep) {
                    if (dark) Color(0xFFFFAA62) else Color(0xFFC15C00)
                } else {
                    if (dark) Color(0xFFF2D35E) else Color(0xFFA17A00)
                }
                val prefix = androidx.compose.ui.res.stringResource(annotation.recommendation, "")
                val description = androidx.compose.ui.res.stringResource(annotation.description)
                BasicText(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(color = prefixColor)) { append(prefix) }
                        append(description)
                    },
                    style = detailStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (showState) BasicText(
            text = if (entry.uninstalled) androidx.compose.ui.res.stringResource(R.string.ui_user_uninstalled) else androidx.compose.ui.res.stringResource(R.string.ui_disabled_state),
            style = detailStyle,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

private val ManagedAppOperation.titleRes: Int
    get() = when (this) {
        ManagedAppOperation.DISABLE -> R.string.app_management_disable
        ManagedAppOperation.UNINSTALL -> R.string.app_management_uninstall
        ManagedAppOperation.ENABLE, ManagedAppOperation.INSTALL_EXISTING -> R.string.app_management_restore
    }

private fun showManagedAppsError(ctx: Context, title: Int, error: Exception) {
    if (error is CancellationException) throw error
    android.widget.Toast.makeText(ctx, ctx.getString(R.string.app_management_error,
        ctx.getString(title), error.message ?: error.javaClass.simpleName),
        android.widget.Toast.LENGTH_LONG).show()
}

@Composable
private fun ManagedAppActionsRow(app: ManagedAppEntry, busy: Boolean, picking: Boolean = false,
    onInfo: () -> Unit = {}, onAction: (ManagedAppOperation) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        DisabledAppRow(app, showState = !picking) { if (!busy) expanded = true }
        // Resolve labels and allocate action callbacks only for the opened menu.
        if (expanded && !busy) {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val actions = if (picking) listOf(
                ActionMenuItem(ctx.getString(R.string.app_management_disable), onClick = { onAction(ManagedAppOperation.DISABLE) }),
                ActionMenuItem(ctx.getString(R.string.app_management_uninstall), onClick = { onAction(ManagedAppOperation.UNINSTALL) }),
            ) else listOf(
                ActionMenuItem(ctx.getString(R.string.ui_app_info), onInfo),
                ActionMenuItem(ctx.getString(R.string.app_management_restore), onClick = {
                    onAction(if (app.uninstalled) ManagedAppOperation.INSTALL_EXISTING else ManagedAppOperation.ENABLE)
                }),
            )
            DisabledAppsDropdown(actions.map { it.label }, onDismiss = { expanded = false }, onSelected = { index ->
                expanded = false
                actions[index].onClick()
            })
        }
    }
}

@Composable
private fun DisabledAppsDropdown(items: List<String>, warningFirst: Boolean = false,
    onDismiss: () -> Unit, onSelected: (Int) -> Unit) {
    Popup(alignment = Alignment.TopEnd, onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)) {
        val shape = RoundedCornerShape(12.dp)
        Column(Modifier.width(if (warningFirst) 260.dp else 160.dp).shadow(8.dp, shape)
            .background(if (isSystemInDarkTheme()) FLUIX_POPUP_DARK else FLUIX_POPUP_LIGHT, shape)
            .clip(shape)) {
            items.forEachIndexed { index, title ->
                BasicText(title, style = MiuixTheme.textStyles.body1.copy(
                    color = if (warningFirst && index == 0) Color(0xFFE5484D) else MiuixTheme.colorScheme.onSurface),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .clickable { onSelected(index) }.padding(horizontal = 20.dp, vertical = 16.dp))
            }
        }
    }
}
