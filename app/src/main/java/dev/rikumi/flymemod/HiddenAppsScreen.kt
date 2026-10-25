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
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import java.text.Collator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
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
        val drawable = SystemIconPackClient.applicationIcon(context, info)
        val bitmap = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, 192, 192)
        drawable.draw(Canvas(bitmap))
        HiddenAppEntry(pkg, pm.getApplicationLabel(info).toString(), bitmap)
    }.getOrElse { HiddenAppEntry(pkg, pkg, null) }
}

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
    val preferences = LocalModulePreferences.current
    val packagesKey = if (landscapeGames) ModuleSettings.LANDSCAPE_GAME_PACKAGES else if (navigationApplications) ModuleSettings.APP_IMMERSIVE_NAVIGATION_PACKAGES else ModuleSettings.HIDDEN_LAUNCHER_APPS
    var hidden by remember(packagesKey) { mutableStateOf(preferences.getStringSet(packagesKey, emptySet()).orEmpty().toSet()) }
    var savedOrder by remember { mutableStateOf(preferences.getString(ModuleSettings.HIDDEN_LAUNCHER_APP_ORDER, "").orEmpty()) }
    DisposableEffect(preferences, packagesKey) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key == packagesKey) hidden = prefs.getStringSet(packagesKey, emptySet()).orEmpty().toSet()
            if (key == ModuleSettings.HIDDEN_LAUNCHER_APP_ORDER) savedOrder = prefs.getString(key, "").orEmpty()
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    var picking by remember { mutableStateOf(false) }
    var choices by remember { mutableStateOf<List<HiddenAppEntry>?>(null) }
    var entries by remember { mutableStateOf<List<HiddenAppEntry>>(emptyList()) }
    val listState = rememberScrollState()
    val overscroll = remember { mutableFloatStateOf(0f) }
    fun save(packages: Set<String>) {
        val editor = preferences.edit().putStringSet(packagesKey, packages)
        if (landscapeGames) editor.putBoolean(ModuleSettings.LANDSCAPE_GAME_ENABLED, true)
        if (!navigationApplications && !landscapeGames) editor.putBoolean(ModuleSettings.HIDDEN_LAUNCHER_APPS_ENABLED, true)
        if (!navigationApplications && !landscapeGames) editor.putString(ModuleSettings.HIDDEN_LAUNCHER_APP_ORDER,
            HiddenAppOrder.reconcile(packages, savedOrder).joinToString("\n"))
        editor.apply()
        hidden = packages
        context.contentResolver.notifyChange(ModuleSettings.URI, null)
    }
    BackHandler(enabled = picking) { picking = false }
    LaunchedEffect(hidden, savedOrder) {
        val previous = entries.associateBy { it.pkg }
        val ordered = if (navigationApplications || landscapeGames) hidden.sorted()
            else HiddenAppOrder.reconcile(hidden, savedOrder)
        entries = withContext(Dispatchers.IO) { ordered.map { previous[it] ?: appEntry(context, it) } }
    }
    LaunchedEffect(picking) {
        listState.scrollTo(0)
        if (picking) {
            choices = null
            val result = withContext(Dispatchers.IO) { readLauncherApps(context, navigationApplications || landscapeGames) }
            choices = result.orEmpty()
            if (result == null) Toast.makeText(context, "读取失败，请检查 root 授权", Toast.LENGTH_SHORT).show()
        }
    }
    Scaffold(topBar = {
        FluixTopAppBar(
            title = if (picking) "选择应用" else if (landscapeGames) "横屏游戏宽度适配" else if (navigationApplications) "强制沉浸应用列表" else "隐藏应用",
            navigationIcon = {
                IconButton(onClick = { if (picking) picking = false else onBack() }) {
                    Icon(MiuixIcons.Back, "返回", tint = MiuixTheme.colorScheme.onSurface, modifier = Modifier.size(FLUIX_BACK_ICON))
                }
            },
            actions = {
                if (!picking) BasicText("添加", style = MiuixTheme.textStyles.body1.copy(color = MiuixTheme.colorScheme.primary),
                    modifier = Modifier.clickable { picking = true }.padding(horizontal = 12.dp, vertical = 10.dp))
                if (navigationApplications || landscapeGames) RestartMenu()
            },
        )
    }) { padding ->
        if (!picking && !navigationApplications && !landscapeGames) {
            HiddenAppPages(context, entries, Modifier.fillMaxSize().padding(padding), onRemove = { save(hidden - it) },
                onOrder = { order ->
                    val packages = preferences.getStringSet(packagesKey, emptySet()).orEmpty()
                    savedOrder = HiddenAppOrder.reconcile(packages, order.joinToString("\n")).joinToString("\n")
                    preferences.edit().putString(ModuleSettings.HIDDEN_LAUNCHER_APP_ORDER, savedOrder).apply()
                })
        } else Column(modifier = Modifier.fillMaxSize().padding(padding).fluixOverscroll(listState, overscroll).verticalScroll(listState)) {
            FluixSmallTitle(if (navigationApplications || landscapeGames) {
                if (picking) "非系统应用" else if (landscapeGames) "横屏游戏宽度适配列表" else "强制沉浸应用列表"
            } else if (picking) "有桌面图标的应用" else "彻底隐藏应用列表")
            val current = if (picking) choices?.filter { it.pkg !in hidden } else entries
            when {
                current == null -> FluixSmallTitle("加载中…")
                current.isEmpty() -> FluixSmallTitle("暂无应用")
                else -> current.forEachIndexed { index, app ->
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
                                        preferences.edit().putInt(ModuleSettings.LANDSCAPE_GAME_MODE_PREFIX + app.pkg, mode)
                                            .putBoolean(ModuleSettings.LANDSCAPE_GAME_ENABLED, true).apply()
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
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun HiddenAppPages(context: Context, entries: List<HiddenAppEntry>, modifier: Modifier,
    onRemove: (String) -> Unit, onOrder: (List<String>) -> Unit) {
    val pages = rememberPagerState(pageCount = { ((entries.size + 11) / 12).coerceAtLeast(1) })
    val preferences = LocalModulePreferences.current
    var metricsVersion by remember { mutableIntStateOf(0) }
    DisposableEffect(preferences) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key?.startsWith(LauncherFolderMetrics.PREFIX) == true) metricsVersion++
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val metrics = remember(metricsVersion) {
        fun size(key: String, default: Float) = preferences.getFloat(LauncherFolderMetrics.PREFIX + key, default)
        HiddenFolderLayout(size("folderChildIconSizePx", 60f), size("folderChildTextSizePx", 12f),
            size("folderChildDrawablePaddingPx", 4f), size("folderCellWidthPx", 104f),
            size("folderCellHeightPx", 108f), size("top", 18f),
            size("textWidth", size("folderCellWidthPx", 104f)))
    }
    // The recognizer belongs to this stable container, never to a moving keyed cell.
    var ordered by remember(entries) { mutableStateOf(entries) }
    var held by remember { mutableStateOf<HiddenAppEntry?>(null) }
    var moved by remember { mutableStateOf(false) }
    var pointer by remember { mutableStateOf(Offset.Zero) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var grab by remember { mutableStateOf(Offset.Zero) }
    var gridSize by remember { mutableStateOf(IntSize.Zero) }
    var gestureVersion by remember { mutableIntStateOf(0) }
    var menuPackage by remember { mutableStateOf<String?>(null) }
    val persist by rememberUpdatedState(onOrder)
    fun cancelDrag() {
        held = null
        moved = false
        ordered = entries
        gestureVersion++
    }
    fun insertAtPointer() {
        val from = ordered.indexOfFirst { it.pkg == held?.pkg }
        val target = HiddenAppOrder.slot(pointer.x, pointer.y, gridSize.width, gridSize.height,
            pages.currentPage, ordered.size)
        if (from >= 0 && target >= 0 && from != target) ordered = HiddenAppOrder.move(ordered, from, target)
    }
    fun hit(position: Offset): HiddenAppEntry? {
        val index = HiddenAppOrder.hit(position.x, position.y, gridSize.width, gridSize.height,
            pages.currentPage, ordered.size)
        return ordered.getOrNull(index)
    }
    BackHandler(enabled = held != null) { cancelDrag() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, entries) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) { cancelDrag(); menuPackage = null }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(entries) { cancelDrag(); menuPackage = null }
    val edge = if (moved && held != null) HiddenAppOrder.edge(pointer.x, pointer.y,
        gridSize.width, gridSize.height, pages.settledPage, pages.pageCount) else 0
    LaunchedEffect(pages.settledPage) {
        if (moved && held != null) insertAtPointer()
    }
    LaunchedEffect(edge, held?.pkg) {
        while (edge != 0 && held != null && moved) {
            delay(600)
            val direction = HiddenAppOrder.edge(pointer.x, pointer.y, gridSize.width,
                gridSize.height, pages.currentPage, pages.pageCount)
            if (direction == 0) break
            pages.animateScrollToPage(pages.currentPage + direction)
            insertAtPointer()
        }
    }
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
      val availableHeight = maxHeight
      Column(Modifier.width((maxOf(metrics.cellWidth, metrics.textWidth) * 3).dp.coerceAtMost(maxWidth)).heightIn(max = availableHeight)) {
        if (entries.isEmpty()) {
            FluixSmallTitle("暂无应用")
        } else {
            Box(Modifier.height((metrics.cellHeight * 4).dp.coerceAtMost((availableHeight - 46.dp).coerceAtLeast(0.dp)))
                .onSizeChanged { gridSize = it }
                .pointerInput(entries, gestureVersion) {
                    detectTapGestures(onTap = { position ->
                        hit(position)?.let { app ->
                            val launch = context.packageManager.getLaunchIntentForPackage(app.pkg)
                            if (launch == null || runCatching { context.startActivity(launch) }.isFailure)
                                Toast.makeText(context, "无法打开应用", Toast.LENGTH_SHORT).show()
                        }
                    })
                }
                .pointerInput(entries, gestureVersion) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { position ->
                            held = hit(position)
                            moved = false
                            pointer = position
                            origin = position
                            menuPackage = null
                            val index = ordered.indexOfFirst { it.pkg == held?.pkg } % 12
                            grab = position - Offset((index % 3) * gridSize.width / 3f,
                                (index / 3) * gridSize.height / 4f)
                        },
                        onDrag = { change, _ ->
                            if (held != null) {
                                change.consume()
                                pointer = change.position
                                if (!moved && (pointer - origin).getDistance() > viewConfiguration.touchSlop) moved = true
                                if (moved && !pages.isScrollInProgress) insertAtPointer()
                            }
                        },
                        onDragEnd = {
                            val app = held
                            if (moved) insertAtPointer()
                            held = null
                            if (app != null) {
                                if (moved) persist(ordered.map { it.pkg }) else menuPackage = app.pkg
                            }
                            moved = false
                        },
                        onDragCancel = { if (held != null) cancelDrag() })
                }) {
                HorizontalPager(state = pages, userScrollEnabled = held == null,
                    modifier = Modifier.fillMaxSize(), key = { it }) { page ->
                    Column(Modifier.fillMaxSize()) {
                        repeat(4) { row ->
                            Row(Modifier.fillMaxWidth().weight(1f)) {
                                repeat(3) { column ->
                                    val app = ordered.getOrNull(page * 12 + row * 3 + column)
                                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                                        if (app != null) key(app.pkg) {
                                            HiddenAppIcon(context, app, metrics,
                                                modifier = Modifier.alpha(if (moved && held?.pkg == app.pkg) 0f else 1f),
                                                expanded = menuPackage == app.pkg,
                                                onDismiss = { menuPackage = null }, onRemove = { onRemove(app.pkg) })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (moved) held?.let { app ->
                    Box(Modifier.offset { IntOffset((pointer.x - grab.x).roundToInt(), (pointer.y - grab.y).roundToInt()) }
                        .width(with(LocalDensity.current) { (gridSize.width / 3f).toDp() })
                        .height(with(LocalDensity.current) { (gridSize.height / 4f).toDp() }),
                        contentAlignment = Alignment.TopCenter) {
                        HiddenAppIcon(context, app, metrics, onRemove = {})
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalArrangement = Arrangement.Center) {
                repeat(pages.pageCount) { page ->
                    Box(Modifier.padding(horizontal = 4.dp).size(6.dp).background(
                        MiuixTheme.colorScheme.onSurface.copy(alpha = if (pages.currentPage == page) .8f else .25f), CircleShape))
                }
            }
        }
      }
    }
}

private data class HiddenFolderLayout(val iconSize: Float, val textSize: Float, val drawablePadding: Float,
    val cellWidth: Float, val cellHeight: Float, val top: Float, val textWidth: Float)

@Composable
private fun HiddenAppIcon(context: Context, app: HiddenAppEntry, layout: HiddenFolderLayout,
    modifier: Modifier = Modifier, expanded: Boolean = false, onDismiss: () -> Unit = {}, onRemove: () -> Unit) {
    val textColor = MiuixTheme.colorScheme.onSurface.toArgb()
    Box(modifier) {

        AndroidView(modifier = Modifier.width(layout.textWidth.dp).fillMaxHeight(), factory = { ctx ->
            android.widget.TextView(ctx).apply {
                includeFontPadding = false
                gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
                textAlignment = android.view.View.TEXT_ALIGNMENT_CENTER
                maxLines = 1
                setHorizontallyScrolling(false)
                ellipsize = android.text.TextUtils.TruncateAt.END
                // The stable parent owns taps and long presses; native views must not intercept.
                isClickable = false
                isLongClickable = false

            }
        }, update = { label ->
            val density = label.resources.displayMetrics.density
            label.text = app.name
            label.setTextColor(textColor)
            label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, layout.textSize * density)
            label.setPadding(0, (layout.top * density).toInt(), 0, 0)
            label.compoundDrawablePadding = (layout.drawablePadding * density).toInt()
            val drawable = app.icon?.let { android.graphics.drawable.BitmapDrawable(label.resources, it) }
                ?: android.graphics.drawable.ColorDrawable(android.graphics.Color.GRAY)
            val size = (layout.iconSize * density).toInt()
            drawable.setBounds(0, 0, size, size)
            label.setCompoundDrawables(null, drawable, null, null)
        })
        if (expanded) Popup(alignment = Alignment.TopCenter, onDismissRequest = onDismiss,
            properties = PopupProperties(focusable = true)) {
            val shape = RoundedCornerShape(12.dp)
            Column(Modifier.width(160.dp).shadow(8.dp, shape)
                .background(if (isSystemInDarkTheme()) FLUIX_POPUP_DARK else FLUIX_POPUP_LIGHT, shape).clip(shape)) {
                listOf("应用信息", "取消隐藏").forEach { label ->
                    BasicText(label, style = MiuixTheme.textStyles.body1.copy(color = MiuixTheme.colorScheme.onSurface),
                        modifier = Modifier.fillMaxWidth().clickable {
                            onDismiss()
                            if (label == "取消隐藏") onRemove()
                            else runCatching { context.startActivity(android.content.Intent(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.parse("package:${app.pkg}"))) }.onFailure {
                                Toast.makeText(context, "无法打开应用信息：${it.message}", Toast.LENGTH_LONG).show()
                            }
                        }.padding(horizontal = 20.dp, vertical = 12.dp))
                }
            }
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
