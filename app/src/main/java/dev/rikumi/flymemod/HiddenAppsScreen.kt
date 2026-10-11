package dev.rikumi.flymemod

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import java.text.Collator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

private data class HiddenAppEntry(val pkg: String, val name: String, val icon: Bitmap?)

private fun appEntry(context: Context, pkg: String): HiddenAppEntry {
    val pm = context.packageManager
    return runCatching {
        @Suppress("DEPRECATION") val info = pm.getApplicationInfo(pkg, 0)
        val drawable = pm.getApplicationIcon(info)
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, 96, 96)
        drawable.draw(Canvas(bitmap))
        HiddenAppEntry(pkg, pm.getApplicationLabel(info).toString(), bitmap)
    }.getOrElse { HiddenAppEntry(pkg, pkg, null) }
}

/** Resolve launchable activities as root; package visibility does not determine this list. */
private fun readLauncherApps(context: Context, allApplications: Boolean): List<HiddenAppEntry>? {
    val output = runRoot(if (allApplications) "cmd package list packages -3 --user current" else
        "cmd package query-activities --components --user current -a android.intent.action.MAIN -c android.intent.category.LAUNCHER")
        ?: return null
    val packages = output.lineSequence().mapNotNull {
        if (allApplications) it.trim().takeIf { line -> line.startsWith("package:") }?.removePrefix("package:")
        else ComponentName.unflattenFromString(it.trim())?.packageName
    }.toSet()
    val collator = Collator.getInstance(context.resources.configuration.locales[0])
    return packages.map { appEntry(context, it) }.sortedWith { a, b ->
        val result = collator.compare(a.name, b.name)
        if (result == 0) a.pkg.compareTo(b.pkg) else result
    }
}

@Composable
internal fun HiddenAppsScreen(context: Context, onBack: () -> Unit,
    navigationApplications: Boolean = false, landscapeGames: Boolean = false) {
    val preferences = remember(context) { ModuleSettings.preferences(context) }
    val packagesKey = if (landscapeGames) ModuleSettings.LANDSCAPE_GAME_PACKAGES else if (navigationApplications) ModuleSettings.APP_IMMERSIVE_NAVIGATION_PACKAGES else ModuleSettings.HIDDEN_LAUNCHER_APPS
    var hidden by remember(packagesKey) { mutableStateOf(preferences.getStringSet(packagesKey, emptySet()).orEmpty().toSet()) }
    var picking by remember { mutableStateOf(false) }
    var choices by remember { mutableStateOf<List<HiddenAppEntry>?>(null) }
    var entries by remember { mutableStateOf<List<HiddenAppEntry>>(emptyList()) }
    val listState = rememberLazyListState()
    val overscroll = remember { mutableFloatStateOf(0f) }
    fun save(packages: Set<String>) {
        val editor = preferences.edit().putStringSet(packagesKey, packages)
        if (!navigationApplications && !landscapeGames) editor.putBoolean(ModuleSettings.HIDDEN_LAUNCHER_APPS_ENABLED, true)
        editor.apply()
        hidden = packages
        context.contentResolver.notifyChange(ModuleSettings.URI, null)
    }
    BackHandler(enabled = picking) { picking = false }
    LaunchedEffect(hidden) {
        entries = withContext(Dispatchers.IO) { hidden.sorted().map { appEntry(context, it) } }
    }
    LaunchedEffect(picking) {
        listState.scrollToItem(0)
        if (picking) {
            choices = null
            val result = withContext(Dispatchers.IO) { readLauncherApps(context, navigationApplications || landscapeGames) }
            choices = result.orEmpty()
            if (result == null) Toast.makeText(context, "读取失败，请检查 root 授权", Toast.LENGTH_SHORT).show()
        }
    }
    Scaffold(topBar = {
        FluixTopAppBar(
            title = if (picking) "选择应用" else if (landscapeGames) "横屏游戏宽度适配" else if (navigationApplications) "应用列表" else "隐藏应用",
            dividerProgress = fluixTopBarDividerProgress(listState, overscroll),
            navigationIcon = {
                IconButton(onClick = { if (picking) picking = false else onBack() }) {
                    Icon(MiuixIcons.Back, "返回", tint = MiuixTheme.colorScheme.onSurface, modifier = Modifier.size(FLUIX_BACK_ICON))
                }
            },
            actions = {
                if (!picking) BasicText("添加", style = MiuixTheme.textStyles.body1.copy(color = MiuixTheme.colorScheme.primary),
                    modifier = Modifier.clickable { picking = true }.padding(horizontal = 12.dp, vertical = 10.dp))
                RestartMenu()
            },
        )
    }) { padding ->
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(padding).fluixOverscroll(listState, overscroll)) {
            item { FluixSmallTitle(if (navigationApplications || landscapeGames) {
                if (picking) "非系统应用" else if (landscapeGames) "横屏游戏宽度适配列表" else "强制沉浸应用列表"
            } else if (picking) "有桌面图标的应用" else "彻底隐藏应用列表") }
            val current = if (picking) choices?.filter { it.pkg !in hidden } else entries
            when {
                current == null -> item { FluixSmallTitle("加载中…") }
                current.isEmpty() -> item { FluixSmallTitle("暂无应用") }
                else -> itemsIndexed(current, key = { _, app -> app.pkg }) { index, app ->
                    FluixCardRow(first = index == 0, last = index == current.lastIndex) {
                        if (landscapeGames && !picking) {
                            val displayMode = context.getSystemService(android.hardware.display.DisplayManager::class.java)
                                .getDisplay(android.view.Display.DEFAULT_DISPLAY)?.mode
                            val options = GameWidthGeometry.options(displayMode?.physicalWidth ?: 0, displayMode?.physicalHeight ?: 0).toList()
                            var mode by remember(app.pkg) { mutableIntStateOf(preferences.getInt(ModuleSettings.LANDSCAPE_GAME_MODE_PREFIX + app.pkg, 0)) }
                            FluixDropdownPreference(title = app.name,
                                options = options.map { GameWidthGeometry.NAMES[it] } + "移除",
                                selected = options.indexOf(mode).coerceAtLeast(0),
                                onSelected = { selected ->
                                    if (selected == options.size) {
                                        preferences.edit().remove(ModuleSettings.LANDSCAPE_GAME_MODE_PREFIX + app.pkg).apply()
                                        save(hidden - app.pkg)
                                    } else {
                                        mode = options[selected]
                                        preferences.edit().putInt(ModuleSettings.LANDSCAPE_GAME_MODE_PREFIX + app.pkg, mode).apply()
                                        context.contentResolver.notifyChange(ModuleSettings.URI, null)
                                    }
                                })
                        } else HiddenAppRow(app, picking, removeLabel = if (navigationApplications) "移除" else "取消隐藏",
                            onAdd = { save(hidden + app.pkg); picking = false },
                            onRemove = { save(hidden - app.pkg) },
                            onOpen = {
                                val launch = context.packageManager.getLaunchIntentForPackage(app.pkg)
                                if (launch == null || runCatching { context.startActivity(launch); true }.getOrDefault(false).not())
                                    Toast.makeText(context, "无法打开应用", Toast.LENGTH_SHORT).show()
                            })
                    }
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

@Composable
private fun HiddenAppRow(app: HiddenAppEntry, picking: Boolean, removeLabel: String, onAdd: () -> Unit, onRemove: () -> Unit, onOpen: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.fillMaxWidth().heightIn(min = FLUIX_ROW_MIN_HEIGHT)
            .clickable { if (picking) onAdd() else expanded = true }
            .padding(horizontal = FLUIX_ROW_HPADDING, vertical = FLUIX_ROW_VPADDING),
            verticalAlignment = Alignment.CenterVertically) {
            app.icon?.let { Image(it.asImageBitmap(), null, Modifier.size(32.dp)) }
                ?: Box(Modifier.size(32.dp).background(MiuixTheme.colorScheme.onSurfaceVariantSummary, RoundedCornerShape(8.dp)))
            BasicText(app.name, style = MiuixTheme.textStyles.body1.copy(color = MiuixTheme.colorScheme.onSurface),
                modifier = Modifier.padding(start = 12.dp).weight(1f))
        }
        if (expanded) Popup(alignment = Alignment.TopEnd, onDismissRequest = { expanded = false }, properties = PopupProperties(focusable = true)) {
            val shape = RoundedCornerShape(12.dp)
            Column(Modifier.width(160.dp).shadow(8.dp, shape).background(if (isSystemInDarkTheme()) FLUIX_POPUP_DARK else FLUIX_POPUP_LIGHT, shape).clip(shape)) {
                listOf("打开" to onOpen, removeLabel to onRemove).forEach { (label, action) ->
                    BasicText(label, style = MiuixTheme.textStyles.body1.copy(color = MiuixTheme.colorScheme.onSurface),
                        modifier = Modifier.fillMaxWidth().clickable { expanded = false; action() }.padding(horizontal = 20.dp, vertical = 12.dp))
                }
            }
        }
    }
}
