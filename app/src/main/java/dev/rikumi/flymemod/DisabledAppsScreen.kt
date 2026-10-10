package dev.rikumi.flymemod

import android.content.Context
import android.content.Intent
import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import java.text.Collator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Share
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val DISABLED_APPS_HINT =
    "为防止误操作损害设备，我们不提供直接停用应用功能；这里列出了已通过其它途径停用和用户级卸载的应用列表，可在右上角导出成脚本方便重复执行。请注意：\n" +
    "1. 取决于停用应用的途径，导出的脚本仍然可能需要 adb root；\n" +
    "2. 模块获取的信息可能并不准确，因此脚本可能会导致系统崩溃或不稳定。请始终在无数据的全新系统上执行脚本。"

// 停用应用条目: label 取不到时为 null(列表退回只显示包名);
// uninstalled=true 表示用户级卸载(pm uninstall -k --user 0), false 表示停用(pm disable-user)。
private data class DisabledAppEntry(
    val pkg: String,
    val label: String?,
    val uninstalled: Boolean,
    val icon: Bitmap? = null,
)

// 读取已停用与用户级卸载的应用列表(只读 pm 命令, IO 线程调用)。无 root 或输出异常时返回 null。
// -d: 已停用; -u: 含已卸载但保留数据的包; 无参: 当前用户已安装。用户级卸载 = -u 与已安装的差集;
// 同一包只归入一个类别, 卸载优先(停用列表剔除已卸载项)。
private fun listDisabledApps(ctx: Context): List<DisabledAppEntry>? {
    val marker = "---FLYMEMOD-SECTION---"
    val out = runRoot(
        "pm list packages -f -d --user 0; echo $marker; pm list packages -f -u --user 0; echo $marker; pm list packages -f --user 0"
    ) ?: return null
    // 按分隔行切成三段; 每段解析 -f 输出: package:/路径/base.apk=包名。
    val sections = mutableListOf<List<Pair<String, String>>>()
    var current = mutableListOf<Pair<String, String>>()
    out.lines().forEach { line ->
        val t = line.trim()
        if (t == marker) {
            sections.add(current)
            current = mutableListOf()
        } else if (t.startsWith("package:")) {
            val eq = t.lastIndexOf('=')
            if (eq > "package:".length) {
                val pkg = t.substring(eq + 1)
                if (Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*").matches(pkg))
                    current.add(t.substring("package:".length, eq) to pkg)
            }
        }
    }
    sections.add(current)
    if (sections.size != 3) return null
    val (disabled, withUninstalled, installed) = sections
    val installedPkgs = installed.map { it.second }.toSet()
    val uninstalled = withUninstalled.filter { it.second !in installedPkgs }
    val uninstalledPkgs = uninstalled.map { it.second }.toSet()
    val entries = mutableListOf<DisabledAppEntry>()
    disabled.forEach { (path, pkg) ->
        if (pkg !in uninstalledPkgs) {
            entries += appEntry(ctx, path, pkg, uninstalled = false)
        }
    }
    uninstalled.forEach { (path, pkg) ->
        entries += appEntry(ctx, path, pkg, uninstalled = true)
    }
    // 按当前语言的本地化规则排序(中文按拼音等), 而非按码位; 名称相同时以包名兜底, 保证顺序稳定。
    val collator = Collator.getInstance(ctx.resources.configuration.locales[0])
    return entries.sortedWith { a, b ->
        val c = collator.compare(a.label ?: a.pkg, b.label ?: b.pkg)
        if (c != 0) c else a.pkg.compareTo(b.pkg)
    }
}

/** Root enumeration includes system apps and packages without launcher activities. */
private fun listAllApps(ctx: Context): List<DisabledAppEntry>? {
    val output = runRoot("pm list packages -f --user 0") ?: return null
    val packagePattern = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*")
    val entries = output.lineSequence().mapNotNull { line ->
        val text = line.trim()
        val separator = text.lastIndexOf('=')
        if (!text.startsWith("package:") || separator <= "package:".length) return@mapNotNull null
        val pkg = text.substring(separator + 1)
        if (!packagePattern.matches(pkg)) return@mapNotNull null
        appEntry(ctx, text.substring("package:".length, separator), pkg, uninstalled = false)
    }.distinctBy { it.pkg }.toList()
    val collator = Collator.getInstance(ctx.resources.configuration.locales[0])
    return entries.sortedWith { a, b ->
        val result = collator.compare(a.label ?: a.pkg, b.label ?: b.pkg)
        if (result == 0) a.pkg.compareTo(b.pkg) else result
    }
}

private enum class AppOperation(val title: String) {
    DISABLE("停用"), UNINSTALL("用户级卸载"), ENABLE("取消停用"), INSTALL_EXISTING("取消停用");

    fun execute(pkg: String): Boolean {
        // Validate again at the command boundary; never interpolate an arbitrary label.
        if (!Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*").matches(pkg)) return false
        val command = when (this) {
            DISABLE -> "pm disable-user --user 0 $pkg"
            UNINSTALL -> "pm uninstall -k --user 0 $pkg"
            ENABLE -> "pm enable --user 0 $pkg"
            INSTALL_EXISTING -> "cmd package install-existing --user 0 $pkg"
        }
        val result = runRoot(command) ?: return false
        return when (this) {
            DISABLE -> result.lineSequence().any { it.trim() == "Package $pkg new state: disabled-user" }
            UNINSTALL -> result.lineSequence().any { it.trim() == "Success" }
            ENABLE -> result.lineSequence().any { it.trim() == "Package $pkg new state: enabled" }
            INSTALL_EXISTING -> result.lineSequence().any { it.trim() == "Package $pkg installed for user: 0" }
        }
    }
}

// 从 APK 解析名称和图标，不受包可见性限制；解析失败时使用包名和默认图标。
private fun appEntry(ctx: Context, apkPath: String, pkg: String, uninstalled: Boolean): DisabledAppEntry {
    val pm = ctx.packageManager
    return runCatching {
        @Suppress("DEPRECATION") val info = pm.getPackageArchiveInfo(apkPath, 0)
        val ai = info?.applicationInfo ?: return@runCatching DisabledAppEntry(
            pkg, null, uninstalled, iconBitmap(pm.defaultActivityIcon))
        ai.sourceDir = apkPath
        ai.publicSourceDir = apkPath
        val label = pm.getApplicationLabel(ai).toString().takeIf { it.isNotBlank() }
        val icon = runCatching { iconBitmap(pm.getApplicationIcon(ai)) }
            .getOrElse { iconBitmap(pm.defaultActivityIcon) }
        DisabledAppEntry(pkg, label, uninstalled, icon)
    }.getOrElse { DisabledAppEntry(pkg, null, uninstalled, iconBitmap(pm.defaultActivityIcon)) }
}

private fun iconBitmap(drawable: Drawable): Bitmap =
    Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).also {
        drawable.setBounds(0, 0, 64, 64)
        drawable.draw(Canvas(it))
    }

// 生成导出脚本: 每行一条 adb 命令, 按模式分段并以注释标出(停用 / 用户级卸载)。
private fun buildExportScript(apps: List<DisabledAppEntry>): String {
    val sb = StringBuilder("#!/bin/sh\n")
    val disabled = apps.filter { !it.uninstalled }
    val uninstalled = apps.filter { it.uninstalled }
    if (disabled.isNotEmpty()) {
        sb.append("\n# 停用\n")
        disabled.forEach { sb.append("adb shell pm disable-user --user 0 ").append(it.pkg).append('\n') }
    }
    if (uninstalled.isNotEmpty()) {
        sb.append("\n# 用户级卸载\n")
        uninstalled.forEach { sb.append("adb shell pm uninstall -k --user 0 ").append(it.pkg).append('\n') }
    }
    return sb.toString()
}

/** List/export disabled apps; five taps on the warning unlock the root app picker. */
@Composable
internal fun DisabledAppsScreen(ctx: Context, onBack: () -> Unit) {
    val listState = rememberLazyListState()
    val overscrollOffset = remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf(false) }
    var choices by remember { mutableStateOf<List<DisabledAppEntry>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var loadGeneration by remember { mutableIntStateOf(0) }
    val pickerState = rememberLazyListState()
    val currentListState = if (picking) pickerState else listState
    BackHandler(enabled = picking || busy) { if (!busy) picking = false }
    // null = 加载中; 加载失败(无 root 等)toast 后按空列表展示。
    var apps by remember { mutableStateOf<List<DisabledAppEntry>?>(null) }
    LaunchedEffect(Unit) {
        val generation = loadGeneration
        val result = withContext(Dispatchers.IO) { listDisabledApps(ctx) }
        // A quick operation must not be overwritten by the earlier initial scan.
        if (generation != loadGeneration) return@LaunchedEffect
        if (result == null) {
            android.widget.Toast.makeText(ctx, "未授予 root 权限", android.widget.Toast.LENGTH_SHORT).show()
        }
        apps = result.orEmpty()
    }
    LaunchedEffect(picking) {
        overscrollOffset.floatValue = 0f
        if (picking) {
            choices = null
            pickerState.scrollToItem(0)
            val result = withContext(Dispatchers.IO) { listAllApps(ctx) }
            choices = result.orEmpty()
            if (result == null) android.widget.Toast.makeText(ctx,
                "读取失败，请检查 root 授权", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    fun perform(app: DisabledAppEntry, action: AppOperation) {
        if (busy) return
        busy = true
        loadGeneration++
        scope.launch {
            try {
                val success = withContext(Dispatchers.IO) { action.execute(app.pkg) }
                val reloaded = withContext(Dispatchers.IO) { listDisabledApps(ctx) }
                if (reloaded != null) apps = reloaded
                if (picking) {
                    // Refresh in place without resetting the picker or its scroll position.
                    val refreshedChoices = withContext(Dispatchers.IO) { listAllApps(ctx) }
                    if (refreshedChoices != null) choices = refreshedChoices
                }
                android.widget.Toast.makeText(ctx, when {
                    !success -> "${action.title}失败，请检查 root 授权或系统限制"
                    else -> "成功"
                }, android.widget.Toast.LENGTH_SHORT).show()
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
                        out.write(buildExportScript(current).toByteArray())
                    } != null
                }.getOrDefault(false)
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        ctx,
                        if (ok) "已导出" else "导出失败",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }
    Scaffold(
        topBar = {
            FluixTopAppBar(
                title = if (busy) "执行中…" else if (picking) "选择应用" else "停用应用",
                dividerProgress = fluixTopBarDividerProgress(currentListState, overscrollOffset),
                navigationIcon = {
                    IconButton(onClick = { if (!busy) { if (picking) picking = false else onBack() } }) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = "返回",
                            tint = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.size(FLUIX_BACK_ICON),
                        )
                    }
                },
                actions = {
                    if (!picking && !busy) {
                        IconButton(onClick = {
                            if (apps.isNullOrEmpty()) {
                                android.widget.Toast.makeText(ctx, "暂无可导出的应用", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                exportLauncher.launch("disabled_apps.sh")
                            }
                        }) {
                            Icon(
                                imageVector = MiuixIcons.Share,
                                contentDescription = "导出",
                                tint = MiuixTheme.colorScheme.onSurface,
                            )
                        }
                        RestartMenu()
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            state = currentListState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .fluixOverscroll(currentListState, overscrollOffset),
        ) {
            if (picking) {
                item { FluixSmallTitle(text = "所有应用") }
                val current = choices
                when {
                    current == null -> item { FluixSmallTitle(text = "加载中…") }
                    current.isEmpty() -> item { FluixSmallTitle(text = "暂无应用") }
                    else -> itemsIndexed(current, key = { _, entry -> entry.pkg }) { index, entry ->
                        FluixCardRow(first = index == 0, last = index == current.lastIndex) {
                            AppRemovalRow(entry, busy) { action -> perform(entry, action) }
                        }
                    }
                }
            } else {
                item { FluixCard { DisabledAppsHintRow { if (!busy) picking = true } } }
                item { FluixSmallTitle(text = "停用应用设置") }
                item {
                    FluixCard {
                        HideDisabledAppsRow(ctx)
                    }
                }
                item { FluixSmallTitle(text = "已停用的应用") }
                val current = apps
                when {
                    current == null -> item { FluixSmallTitle(text = "加载中…") }
                    current.isEmpty() -> item { FluixSmallTitle(text = "无已停用或用户级卸载的应用") }
                    // 每行一个独立的惰性 item: 行数上百时不能共用一个容器(见 FluixCardRow 注释)。
                    else -> itemsIndexed(current, key = { _, entry -> entry.pkg }) { index, entry ->
                        FluixCardRow(first = index == 0, last = index == current.lastIndex) {
                            if (index > 0) FluixItemDivider()
                            DisabledAppActionsRow(entry, busy,
                                onInfo = { openAppDetails(ctx, entry.pkg) },
                                onRestore = { perform(entry, if (entry.uninstalled)
                                    AppOperation.INSTALL_EXISTING else AppOperation.ENABLE) })
                        }
                    }
                }
            }
            item { Box(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun DisabledAppsHintRow(onAdd: () -> Unit) {
    var taps by remember { mutableIntStateOf(0) }
    var lastTap by remember { mutableLongStateOf(0L) }
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(lastTap) {
        if (taps > 0) {
            delay(1_000)
            taps = 0
        }
    }
    Box {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastTap >= 1_000) taps = 0
                    lastTap = now
                    taps++
                    if (taps == 5) { taps = 0; expanded = true }
                }
                .padding(horizontal = FLUIX_ROW_HPADDING, vertical = FLUIX_ROW_VPADDING),
        ) {
            BasicText(
                text = "功能说明",
                style = MiuixTheme.textStyles.body1.copy(color = MiuixTheme.colorScheme.onSurface),
            )
            DISABLED_APPS_HINT.split('\n').forEach { paragraph ->
                BasicText(
                    text = paragraph,
                    style = MiuixTheme.textStyles.body2.copy(
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontFeatureSettings = "tnum",
                    ),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        if (expanded) DisabledAppsDropdown(
            items = listOf("忽略警告并新增停用应用", "取消"),
            warningFirst = true,
            onDismiss = { expanded = false },
        ) { index ->
            expanded = false
            if (index == 0) onAdd()
        }
    }
}

@Composable
private fun AppRemovalRow(app: DisabledAppEntry, busy: Boolean, onAction: (AppOperation) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val actions = listOf(AppOperation.DISABLE, AppOperation.UNINSTALL)
    Box {
        DisabledAppRow(app, showState = false) { if (!busy) expanded = true }
        if (expanded && !busy) DisabledAppsDropdown(
            items = actions.map { it.title },
            onDismiss = { expanded = false },
        ) { index -> expanded = false; onAction(actions[index]) }
    }
}

@Composable
private fun DisabledAppActionsRow(app: DisabledAppEntry, busy: Boolean,
    onInfo: () -> Unit, onRestore: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        DisabledAppRow(app) { if (!busy) expanded = true }
        if (expanded && !busy) DisabledAppsDropdown(
            items = listOf("应用信息", "取消停用"),
            onDismiss = { expanded = false },
        ) { index -> expanded = false; if (index == 0) onInfo() else onRestore() }
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

/** 隐藏已停用应用开关: 开启后设置的应用管理页不再列出已停用的应用。 */
@Composable
private fun HideDisabledAppsRow(ctx: Context) {
    var checked by remember {
        mutableStateOf(ModuleSettings.preferences(ctx).getBoolean(ModuleSettings.HIDE_DISABLED_APPS, false))
    }
    FluixSwitchPreference(
        checked = checked,
        onCheckedChange = {
            checked = it
            ModuleSettings.preferences(ctx).edit().putBoolean(ModuleSettings.HIDE_DISABLED_APPS, it).apply()
            ctx.contentResolver.notifyChange(ModuleSettings.URI, null)
        },
        title = "在应用管理中隐藏",
    )
}

// 跳转到设置的应用详情页。标准 Intent 由 com.android.settings.applications
// .InstalledAppDetails 响应(实测停用与用户级卸载的包都能解析到它)。用户级卸载的包已不在当前
// 用户下, 设置可能打不开, 因此兜住 ActivityNotFoundException 并提示。
private fun openAppDetails(ctx: Context, pkg: String) {
    try {
        ctx.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
        )
    } catch (_: ActivityNotFoundException) {
        android.widget.Toast.makeText(ctx, "无法打开该应用的设置页", android.widget.Toast.LENGTH_SHORT)
            .show()
    }
}

/** 带图标的应用列表行，点击显示操作菜单。 */
@Composable
private fun DisabledAppRow(entry: DisabledAppEntry, showState: Boolean = true, onClick: () -> Unit) {
    val onSurface = MiuixTheme.colorScheme.onSurface
    val summary = MiuixTheme.colorScheme.onSurfaceVariantSummary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = FLUIX_ROW_MIN_HEIGHT)
            .clickable { onClick() }
            .padding(horizontal = FLUIX_ROW_HPADDING, vertical = FLUIX_ROW_VPADDING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        entry.icon?.let {
            Image(it.asImageBitmap(), contentDescription = null,
                modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)))
            Box(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            if (entry.label != null) {
                BasicText(
                    text = entry.label,
                    style = MiuixTheme.textStyles.body1.copy(color = onSurface),
                )
                BasicText(
                    text = entry.pkg,
                    style = MiuixTheme.textStyles.body2.copy(color = summary),
                    modifier = Modifier.padding(top = 2.dp),
                )
            } else {
                BasicText(
                    text = entry.pkg,
                    style = MiuixTheme.textStyles.body1.copy(color = onSurface),
                )
            }
        }
        if (showState) BasicText(
            text = if (entry.uninstalled) "用户级卸载" else "停用",
            style = MiuixTheme.textStyles.body2.copy(color = summary),
            modifier = Modifier.padding(start = 12.dp),
        )
        Icon(
            imageVector = MiuixIcons.ChevronForward,
            contentDescription = null,
            // 与分类入口行同款: 在 summary 之上再压低透明度, 只作指示不抢视觉。
            tint = summary.copy(alpha = summary.alpha * 0.6f),
            modifier = Modifier
                .padding(start = 12.dp)
                .size(16.dp),
        )
    }
}
