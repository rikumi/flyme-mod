package dev.rikumi.flymemod

import android.os.Bundle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

private const val PAGE_TRANSITION_MS = 240
private const val DISABLED_APPS_ID = "disabled_apps"

private data class Category(
    val id: String,
    val title: String,
    val icon: Int,
    val iconForeground: Color,
    val groups: List<String>,
)

private object SettingsHomeColors {
    // Flyme Settings 的 fd_sys_color_primary_* 主题色。
    val blueForeground = Color(0xFF206CFF)
    val cyanForeground = Color(0xFF02BEBF)
    val greenForeground = Color(0xFF35C44F)
    // Extracted from Flyme Settings res/drawable/mz_function_list_ic_display.xml.
    val yellowForeground = Color(0xFFFFBE0A)
    val orangeForeground = Color(0xFFFF7024)
    val redForeground = Color(0xFFE42D22)
}

private val CATEGORY_GROUPS: List<List<Category>> = listOf(
    listOf(
        Category("desktop", "桌面", R.drawable.ic_flyme_home_apps, SettingsHomeColors.blueForeground, listOf("桌面图标设置", "壁纸设置", "桌面动画设置", "桌面文件夹设置")),
        Category("quick_settings", "控制中心", R.drawable.ic_flyme_home_wireless, SettingsHomeColors.cyanForeground, listOf("控制中心背景", "控制中心主题", "控制中心布局", "控制中心动画", "音量调节设置")),
        Category("notification", "通知中心与状态栏", R.drawable.ic_flyme_home_notifications, SettingsHomeColors.cyanForeground, listOf("通知中心主题", "通知中心设置", "状态栏设置")),
        Category("lockscreen", "锁屏", R.drawable.ic_flyme_home_security, SettingsHomeColors.greenForeground, listOf("锁屏操作", "锁屏视觉")),
    ),
    listOf(
        Category("hidden_apps", "隐藏应用", R.drawable.ic_flyme_home_apps, SettingsHomeColors.yellowForeground, listOf("特殊应用隐藏")),
        Category(DISABLED_APPS_ID, "停用应用", R.drawable.ic_flyme_home_disabled, SettingsHomeColors.yellowForeground, listOf("停用应用设置", "已停用的应用")),
        Category("navigation", "导航与手势", R.drawable.ic_flyme_home_gestures, SettingsHomeColors.yellowForeground, listOf("手势行为", "多任务切换")),
    ),
    listOf(
        Category("system_cleanup", "系统净化", R.drawable.ic_flyme_home_cleaner, SettingsHomeColors.orangeForeground, listOf("应用商店净化", "天气", "应用安装器")),
        Category("camera", "相机与杂项", R.drawable.ic_flyme_home_install, SettingsHomeColors.orangeForeground, listOf("相机设置", "系统杂项")),
        Category("storage", "存储管理", R.drawable.ic_flyme_home_storage, SettingsHomeColors.redForeground, listOf("不自动创建以下文件夹")),
    ),
)

private val CATEGORIES = CATEGORY_GROUPS.flatten()

class MainActivity : ComponentActivity() {
    private var requestedPage by mutableStateOf<String?>(null)
    private var pageRequest by mutableIntStateOf(0)
    private var launcherEntry by mutableStateOf(false)

    private fun readPageRequest() {
        requestedPage = intent.getStringExtra(ModuleSettings.SETTINGS_PAGE_EXTRA)
        launcherEntry = requestedPage == "hidden_apps" &&
            intent.getBooleanExtra(ModuleSettings.LAUNCHER_ENTRY_EXTRA, false)
        if (launcherEntry) overridePendingTransition(0, 0)
    }

    private fun returnToLauncher() {
        startActivity(
            android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_HOME)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION),
            android.app.ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle(),
        )
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readPageRequest()
        pageRequest++
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readPageRequest()
        setContent {
            MiuixTheme(
                controller = remember {
                    ThemeController(
                        colorSchemeMode = ColorSchemeMode.System,
                        lightColors = lightColorScheme(
                            primary = Color(0xFF206CFF),
                            background = FLUIX_PAGE_LIGHT,
                            surface = FLUIX_PAGE_LIGHT,
                            onSurface = FLUIX_TEXT_LIGHT,
                            onSurfaceVariantSummary = FLUIX_SUMMARY_LIGHT,
                            surfaceContainer = FLUIX_GROUP_LIGHT,
                            dividerLine = Color.Transparent,
                        ),
                        darkColors = darkColorScheme(
                            primary = Color(0xFF1564ED),
                            background = FLUIX_PAGE_DARK,
                            surface = FLUIX_PAGE_DARK,
                            onSurface = FLUIX_TEXT_DARK,
                            onSurfaceVariantSummary = FLUIX_SUMMARY_DARK,
                            surfaceContainer = FLUIX_GROUP_DARK,
                            dividerLine = Color.Transparent,
                        ),
                    )
                },
                textStyles = fluixTextStyles(),
            ) {
                FluixStatusBar()
                FluixOverscrollHost { SettingsScreen(requestedPage, pageRequest, launcherEntry, ::returnToLauncher) }
            }
        }
    }
}

@Composable
private fun SettingsScreen(requestedPage: String?, pageRequest: Int, launcherEntry: Boolean, onReturnToLauncher: () -> Unit) {
    var openCategoryId by remember { mutableStateOf(requestedPage?.takeIf { page -> CATEGORIES.any { it.id == page } }) }
    LaunchedEffect(pageRequest) {
        if (requestedPage != null && CATEGORIES.any { it.id == requestedPage }) openCategoryId = requestedPage
    }
    val onPageBack: () -> Unit = {
        if (launcherEntry && openCategoryId == "hidden_apps") onReturnToLauncher()
        else openCategoryId = null
    }
    BackHandler(enabled = openCategoryId != null, onBack = onPageBack)

    AnimatedContent(
        targetState = openCategoryId,
        transitionSpec = {
            if (launcherEntry) {
                androidx.compose.animation.EnterTransition.None.togetherWith(androidx.compose.animation.ExitTransition.None)
            } else {
            val direction = if (targetState != null) 1 else -1
            val spec = tween<IntOffset>(durationMillis = PAGE_TRANSITION_MS)
            (fadeIn(animationSpec = tween(PAGE_TRANSITION_MS)) +
                slideInHorizontally(animationSpec = spec) { direction * it / 5 })
                .togetherWith(
                    fadeOut(animationSpec = tween(PAGE_TRANSITION_MS)) +
                        slideOutHorizontally(animationSpec = spec) { -direction * it / 5 },
                )
            }
        },
        label = "settings_pages",
    ) { pageId ->
        val category = CATEGORIES.firstOrNull { it.id == pageId }
        if (category == null) {
            HomeScreen(onOpenCategory = { openCategoryId = it })
        } else if (category.id == DISABLED_APPS_ID) {
            DisabledAppsScreen(LocalContext.current, onBack = { openCategoryId = null })
        } else if (category.id == "hidden_apps") {
            HiddenAppsScreen(LocalContext.current, onBack = onPageBack)
        } else {
            CategoryScreen(category, onBack = { openCategoryId = null })
        }
    }
}

@Composable
private fun HomeScreen(onOpenCategory: (String) -> Unit) {
    val listState = rememberLazyListState()
    val overscrollOffset = remember { mutableFloatStateOf(0f) }
    Scaffold(
        topBar = {
            FluixLargeTitle(
                title = "Flyme Mod",
                dividerProgress = fluixTopBarDividerProgress(listState, overscrollOffset),
                actions = { RestartMenu() },
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .fluixOverscroll(listState, overscrollOffset),
        ) {
            item {
                HomeModuleControls()
            }
            CATEGORY_GROUPS.forEach { group ->
                item {
                    FluixCard {
                        group.forEachIndexed { index, category ->
                            if (index > 0) FluixItemDivider(startInset = FLUIX_CATEGORY_TEXT_START)
                            FluixCategoryRow(
                                icon = category.icon,
                                iconForeground = category.iconForeground,
                                title = category.title,
                                onClick = { onOpenCategory(category.id) },
                            )
                        }
                    }
                }
            }
            item { Box(Modifier.height(20.dp)) }
        }
    }
}

@Composable
private fun CategoryScreen(category: Category, onBack: () -> Unit) {
    var pickingNavigationApplications by remember { mutableStateOf(false) }
    var pickingGameApplications by remember { mutableStateOf(false) }
    if (pickingGameApplications) {
        BackHandler { pickingGameApplications = false }
        HiddenAppsScreen(LocalContext.current, onBack = { pickingGameApplications = false }, landscapeGames = true)
        return
    }
    if (pickingNavigationApplications) {
        BackHandler { pickingNavigationApplications = false }
        HiddenAppsScreen(LocalContext.current, onBack = { pickingNavigationApplications = false },
            navigationApplications = true)
        return
    }
    val listState = rememberLazyListState()
    val overscrollOffset = remember { mutableFloatStateOf(0f) }
    Scaffold(
        topBar = {
            FluixTopAppBar(
                title = category.title,
                dividerProgress = fluixTopBarDividerProgress(listState, overscrollOffset),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = "返回",
                            tint = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.size(FLUIX_BACK_ICON),
                        )
                    }
                },
                actions = { RestartMenu() },
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .fluixOverscroll(listState, overscrollOffset),
        ) {
            category.groups.forEach { groupTitle ->
                item { FluixSmallTitle(text = groupTitle) }
                if (category.id == "system_cleanup" && groupTitle == "应用商店净化") {
                    item { AppStoreContentPreferences() }
                }
                if (category.id == "system_cleanup" && groupTitle == "天气") {
                    item { WeatherPreferences() }
                }
                if (category.id == "camera" && groupTitle == "相机设置") {
                    item { PreferenceCard(listOf(
                        ModuleSettings.PHOTO_PREVIEW_SYSTEM_ROTATION to "图库预览跟随系统旋转锁定",
                        ModuleSettings.CAMERA_KEEP_FILTER to "保留滤镜设置",
                    )) }
                }
                if (category.id == "camera" && groupTitle == "系统杂项") {
                    item { FluixCard {
                        ApplicationPreference(ModuleSettings.DARK_APP_SPECIAL_PACKAGES, "深色应用管理支持选择特殊应用")
                        FluixItemDivider()
                        Row(Modifier.fillMaxWidth().heightIn(min = FLUIX_ROW_MIN_HEIGHT)
                            .clickable { pickingGameApplications = true }
                            .padding(horizontal = FLUIX_ROW_HPADDING, vertical = FLUIX_ROW_VPADDING),
                            verticalAlignment = Alignment.CenterVertically) {
                            BasicText("横屏游戏宽度适配", style = MiuixTheme.textStyles.body1.copy(color = MiuixTheme.colorScheme.onSurface))
                        }
                    } }
                }
                if (category.id == "system_cleanup" && groupTitle == "应用安装器") {
                    item {
                        FluixCard {
                            ApplicationPreference(ModuleSettings.INSTALLER_SKIP_WARNINGS, "跳过安装警告")
                        }
                    }
                }
                if (category.id == "storage") {
                    item { StoragePreferences() }
                }
                if (category.id == "lockscreen" && groupTitle == "锁屏操作") {
                    item {
                        FluixCard {
                            ApplicationPreference(ModuleSettings.BLOCK_LOCKSCREEN_LONG_PRESS_EDIT, "屏蔽锁屏长按编辑")
                        }
                    }
                }
                if (category.id == "lockscreen" && groupTitle == "锁屏视觉") {
                    item {
                        FluixCard {
                            ApplicationPreference(ModuleSettings.HIDE_CARRIER, "隐藏锁屏和通知中心运营商")
                            FluixItemDivider()
                            ApplicationPreference(ModuleSettings.LIMIT_AOD_MOVEMENT, "限制息屏显示移动范围")
                            FluixItemDivider()
                            ClockFontPreference(ModuleSettings.LOCK_CLOCK_FONT, "锁屏和息屏时钟字体")
                            FluixItemDivider()
                            ClockMonospacePreference(ModuleSettings.LOCK_CLOCK_MONOSPACE, "锁屏和息屏时钟字体等宽")
                            FluixItemDivider()
                            ClockSpacingPreference(ModuleSettings.LOCK_CLOCK_SPACING, ModuleSettings.LOCK_CLOCK_SPACING_ENABLED, "锁屏和息屏时钟字间距")
                            FluixItemDivider()
                            ClockWeightPreference(title = "锁屏和息屏时钟字重")
                        }
                    }
                }
                if (category.id == "quick_settings" && groupTitle != "音量调节设置") {
                    item { ControlCenterPreferences(groupTitle) }
                }
                if (category.id == "quick_settings" && groupTitle == "音量调节设置") {
                    item { VolumeStepsPreference() }
                }
                if (category.id == "notification" && groupTitle == "通知中心主题") {
                    item { NotificationThemePreferences() }
                }
                if (category.id == "notification" && groupTitle == "通知中心设置") {
                    item { NotificationPreferences() }
                }
                if (category.id == "notification" && groupTitle == "状态栏设置") {
                    item { StatusBarPreferences() }
                }
                if (category.id == "desktop" && groupTitle == "壁纸设置") {
                    item { WallpaperPreferences() }
                }
                if (category.id == "desktop" && groupTitle == "桌面图标设置") {
                    item { PreferenceCard(listOf(
                        ModuleSettings.EDIT_APP_ICON_NAME to "允许编辑图标和名称",
                        ModuleSettings.LAUNCHER_HIDE_APP_MENU to "允许隐藏应用",
                        ModuleSettings.LAUNCHER_SPREAD_HIDDEN_APPS to "桌面双指张开查看隐藏应用",
                    )) }
                }
                if (category.id == "desktop" && groupTitle == "桌面动画设置") {
                    item {
                        FluixCard {
                            ApplicationPreference(ModuleSettings.FOLDER_CLOSE_TARGET, "恢复关闭到文件夹动画目标")
                            FluixItemDivider()
                            ApplicationPreference(ModuleSettings.LOCK_WIDGET_STARTUP_FIX, "修复启动时锁屏小组件异常显示")
                        }
                    }
                }
                if (category.id == "desktop" && groupTitle == "桌面文件夹设置") {
                    item { FolderPreferences() }
                }
                if (category.id == "navigation" && groupTitle == "手势行为") {
                    item { FluixCard {
                        AppNavigationPreference { pickingNavigationApplications = true }
                    } }
                    item { SystemSettingsPreferences() }
                    item { PreferenceCard(listOf(
                        ModuleSettings.MBACK_SYSTEM_TIMEOUT to "非压感 mBack 跟随系统长按超时",
                        ModuleSettings.MBACK_MISSING_ASSISTANT_HOME to "未安装助手长按 mBack 回桌面",
                        ModuleSettings.HOME_ICON_MINIMUM_SIZE to "限制回桌面动画最小图标尺寸",
                    )) }
                }
                if (category.id == "navigation" && groupTitle == "多任务切换") {
                    item {
                        FluixCard {
                            ApplicationPreference(ModuleSettings.STACKED_RECENTS, "ColorOS 堆叠多任务")
                            FluixItemDivider()
                            ApplicationPreference(ModuleSettings.RECENTS_SWIPE_UP_KILL, "多任务上划彻底结束进程")
                            FluixItemDivider()
                            ApplicationPreference(ModuleSettings.RECENTS_HIDE_NOT_RUNNING, "多任务隐藏未在运行的应用")
                            FluixItemDivider()
                            SmallWindowLimitPreference()
                        }
                    }
                }
            }
            item { Box(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun SmallWindowLimitPreference() {
    val context = LocalContext.current
    val preferences = remember(context) { ModuleSettings.preferences(context) }
    var enabled by remember { mutableStateOf(preferences.getBoolean(ModuleSettings.SMALL_WINDOW_LIMIT_ENABLED, false)) }
    var limit by remember { mutableStateOf(preferences.getInt(ModuleSettings.SMALL_WINDOW_LIMIT,
        ModuleSettings.SMALL_WINDOW_LIMIT_DEFAULT).coerceIn(1, ModuleSettings.SMALL_WINDOW_LIMIT_MAX)) }
    var expanded by remember { mutableStateOf(false) }
    FluixSwitchPreference(
        title = "修改小窗数量上限",
        checked = enabled,
        onCheckedChange = {
            enabled = it
            expanded = it
            preferences.edit().putBoolean(ModuleSettings.SMALL_WINDOW_LIMIT_ENABLED, it).apply()
            context.contentResolver.notifyChange(ModuleSettings.URI, null)
        },
        onTitleClick = if (enabled) ({ expanded = !expanded }) else null,
        leftTrailingContent = {
            if (enabled) BasicText("$limit",
                style = MiuixTheme.textStyles.body2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary),
                modifier = Modifier.padding(start = 12.dp))
        },
        showDivider = enabled && expanded,
    )
    AnimatedVisibility(visible = enabled && expanded, enter = expandVertically(), exit = shrinkVertically()) {
        FluixSlider(
            value = (limit - 1) / (ModuleSettings.SMALL_WINDOW_LIMIT_MAX - 1).toFloat(),
            onValueChange = {
                limit = (it * (ModuleSettings.SMALL_WINDOW_LIMIT_MAX - 1)).roundToInt() + 1
                limit = limit.coerceIn(1, ModuleSettings.SMALL_WINDOW_LIMIT_MAX)
                preferences.edit().putInt(ModuleSettings.SMALL_WINDOW_LIMIT, limit).apply()
                context.contentResolver.notifyChange(ModuleSettings.URI, null)
            },
            modifier = Modifier.padding(horizontal = 26.dp, vertical = 18.dp),
        )
    }
}

@Composable
private fun FolderPreferences() {
    val context = LocalContext.current
    val preferences = remember(context) { ModuleSettings.preferences(context) }
    var paging by remember { mutableStateOf(preferences.getBoolean(ModuleSettings.FOLDER_PAGING, false)) }
    var center by remember { mutableStateOf(preferences.getBoolean(ModuleSettings.FOLDER_CENTER, false)) }
    var radiusEnabled by remember { mutableStateOf(preferences.getBoolean(ModuleSettings.FOLDER_RADIUS_ENABLED, false)) }
    var radius by remember {
        mutableStateOf(preferences.getInt(ModuleSettings.FOLDER_RADIUS, ModuleSettings.FOLDER_RADIUS_DEFAULT)
            .coerceIn(0, ModuleSettings.FOLDER_RADIUS_MAX))
    }
    var radiusExpanded by remember { mutableStateOf(false) }
    FluixCard {
        FluixSwitchPreference(
            checked = paging,
            onCheckedChange = {
                preferences.edit().putBoolean(ModuleSettings.FOLDER_PAGING, it).apply()
                paging = it
            },
            title = "桌面文件夹水平分页",
        )
        FluixItemDivider()
        FluixSwitchPreference(
            checked = center,
            onCheckedChange = {
                preferences.edit().putBoolean(ModuleSettings.FOLDER_CENTER, it).apply()
                center = it
            },
            title = "桌面文件夹垂直居中",
        )
        FluixItemDivider()
        ApplicationPreference(ModuleSettings.FOLDER_RESTORE_COLOR, "恢复文件夹背景色")
        FluixItemDivider()
        FluixSwitchPreference(
            checked = radiusEnabled,
            onCheckedChange = {
                preferences.edit().putBoolean(ModuleSettings.FOLDER_RADIUS_ENABLED, it).apply()
                radiusEnabled = it
                radiusExpanded = it
                context.contentResolver.notifyChange(ModuleSettings.URI, null)
            },
            title = "调整文件夹圆角大小",
            onTitleClick = if (radiusEnabled) ({ radiusExpanded = !radiusExpanded }) else null,
            leftTrailingContent = {
                if (radiusEnabled) BasicText(
                    text = "${radius}dp",
                    style = MiuixTheme.textStyles.body2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary),
                    modifier = Modifier.padding(start = 12.dp),
                )
            },
            showDivider = radiusEnabled,
        )
        AnimatedVisibility(
            visible = radiusEnabled && radiusExpanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            FluixSlider(
                value = radius.toFloat() / ModuleSettings.FOLDER_RADIUS_MAX,
                onValueChange = {
                    radius = (it * ModuleSettings.FOLDER_RADIUS_MAX).roundToInt().coerceIn(0, ModuleSettings.FOLDER_RADIUS_MAX)
                    preferences.edit().putInt(ModuleSettings.FOLDER_RADIUS, radius).apply()
                    context.contentResolver.notifyChange(ModuleSettings.URI, null)
                },
                modifier = Modifier.padding(horizontal = 26.dp, vertical = 18.dp),
            )
        }
    }
}

@Composable
private fun WallpaperPreferences() {
    val context = LocalContext.current
    val preferences = remember(context) { ModuleSettings.preferences(context) }
    var startupFix by remember {
        mutableStateOf(preferences.getBoolean(ModuleSettings.WALLPAPER_STARTUP_FIX, false))
    }
    FluixCard {
        FluixSwitchPreference(
            checked = startupFix,
            onCheckedChange = {
                preferences.edit().putBoolean(ModuleSettings.WALLPAPER_STARTUP_FIX, it).apply()
                startupFix = it
            },
            title = "修复开机时壁纸强制调暗",
        )
    }
}

@Composable
private fun StatusBarPreferences() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PreferenceCard(
            listOf(
                ModuleSettings.ORIGINAL_NOTIFICATION_ICONS to "恢复原生第三方通知图标",
                ModuleSettings.MERGE_DUAL_SIGNAL to "合并双卡信号",
                ModuleSettings.SEPARATE_NETWORK_TYPE to "独立显示网络制式",
            ),
        )
        FluixCard {
            val statusIconOptions = listOf(
                ModuleSettings.HIDE_STATUS_ICON_KEYS[0] to "隐藏睡眠模式图标",
                ModuleSettings.HIDE_STATUS_ICON_KEYS[1] to "隐藏 VPN 图标",
                ModuleSettings.HIDE_STATUS_ICON_KEYS[2] to "隐藏定位图标",
                ModuleSettings.HIDE_STATUS_ICON_KEYS[3] to "隐藏蓝牙图标",
                ModuleSettings.HIDE_STATUS_ICON_KEYS[4] to "隐藏投屏图标",
                ModuleSettings.HIDE_STATUS_ICON_KEYS[5] to "隐藏热点图标",
                ModuleSettings.HIDE_STATUS_ICON_KEYS[6] to "隐藏屏幕录制图标",
                ModuleSettings.HIDE_STATUS_ICON_KEYS[7] to "隐藏摄像头使用图标",
                ModuleSettings.HIDE_STATUS_ICON_KEYS[8] to "隐藏麦克风使用图标",
            )
            statusIconOptions.forEachIndexed { index, (key, title) ->
                if (index > 0) FluixItemDivider()
                ApplicationPreference(key, title)
            }
        }
        FluixCard {
            ClockFontPreference(ModuleSettings.STATUS_BAR_CLOCK_FONT, "状态栏字体")
            FluixItemDivider()
            ClockWeightPreference(ModuleSettings.STATUS_BAR_CLOCK_WEIGHT, "状态栏字体字重")
            FluixItemDivider()
            ClockMonospacePreference(ModuleSettings.STATUS_BAR_CLOCK_MONOSPACE, "状态栏字体等宽")
            FluixItemDivider()
            ClockSpacingPreference(ModuleSettings.STATUS_BAR_CLOCK_SPACING, ModuleSettings.STATUS_BAR_CLOCK_SPACING_ENABLED, "状态栏字间距")
        }
    }
}

@Composable
private fun NotificationThemePreferences() {
    FluixCard {
        LightBackgroundOpacityPreference(notification = true)
        FluixItemDivider()
        TileCornerPreference(notification = true)
        FluixItemDivider()
        ApplicationPreference(ModuleSettings.NOTIFICATION_CONTOUR, "ColorOS 通知轮廓光")
    }
}

@Composable
private fun NotificationPreferences() {
        PreferenceCard(
            listOf(
                ModuleSettings.HEADS_UP_WIDTH to "增加浮动通知宽度",
                ModuleSettings.NOTIFICATION_READ_LAYER_FIX to "修复已读通知动画层级",
                ModuleSettings.NATIVE_NOTIFICATION_EXPANSION to "恢复原生通知下滑展开",
                ModuleSettings.MONOCHROME_NOTIFICATION_ACTIONS to "通知操作按钮改为黑白色",
                ModuleSettings.DISABLE_GOOGLE_NOTIFICATION_SUGGESTIONS to "禁用 Google 通知建议操作",
            ),
        )
}

@Composable
private fun PreferenceCard(items: List<Pair<String, String>>) {
    if (items.isEmpty()) return
    FluixCard {
        items.forEachIndexed { index, (key, title) ->
            if (index > 0) FluixItemDivider()
            if (key == ModuleSettings.SPLIT_NETWORK_CARD) SplitNetworkPreference()
            else ApplicationPreference(key, title)
        }
    }
}

@Composable
private fun ControlCenterPreferences(group: String) {
    when (group) {
        "控制中心背景" -> ControlCenterBackgroundPreferences()
        "控制中心主题" -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FluixCard {
                ApplicationPreference(ModuleSettings.COLOROS_CONTOUR, "ColorOS 轮廓光")
                FluixItemDivider()
                LightBackgroundOpacityPreference()
                FluixItemDivider()
                TileBackgroundColorPreference()
                FluixItemDivider()
                TileCornerPreference()
                FluixItemDivider()
                ControlCenterStylePreference()
                FluixItemDivider()
                ApplicationPreference(ModuleSettings.SLIDER_ACTIVE_CORNERS, "音量/亮度条激活区域圆角")
                FluixItemDivider()
                ApplicationPreference(ModuleSettings.CIRCLE_SMALL_TILES, "1×1 磁贴强制圆形")
            }
            FluixCard {
                ClockFontPreference(ModuleSettings.CONTROL_CENTER_CLOCK_FONT, "控制中心时钟字体")
                FluixItemDivider()
                ClockWeightPreference(ModuleSettings.CONTROL_CENTER_CLOCK_WEIGHT, "控制中心时钟字重")
                FluixItemDivider()
                ClockMonospacePreference(ModuleSettings.CONTROL_CENTER_CLOCK_MONOSPACE, "控制中心时钟字体等宽")
                FluixItemDivider()
                ControlCenterClockDateOffsetPreference()
                FluixItemDivider()
                ControlCenterVerticalOffsetPreference(ModuleSettings.CONTROL_CENTER_BUTTONS_UP,
                    ModuleSettings.CONTROL_CENTER_BUTTONS_UP_DISTANCE, "控制中心设置按钮上移", 8)
            }
        }
        "控制中心布局" -> PreferenceCard(
            listOf(
                ModuleSettings.HIDE_LUNAR to "隐藏农历日期",
                ModuleSettings.WIFI_LABEL to "无线网络替换为 Wi-Fi",
                ModuleSettings.SCALE to "控制中心操作区域放大",
                ModuleSettings.RESTORE_COLLAPSED_CARD_HEIGHT to "恢复合并控制中心折叠状态磁贴高度",
                ModuleSettings.COMBINED_FILTER_SETTINGS to "合并中心不重要通知按钮替换为设置",
                ModuleSettings.SPLIT_NETWORK_CARD to "网络卡片分离",
                ModuleSettings.ANIMATED_MUTE_SLASH to "动画式斜划线静音图标",
                ModuleSettings.OPTIMIZE_2X1_TEXT to "2×1 卡片文字布局优化",
            ),
        )
        "控制中心动画" -> PreferenceCard(
            listOf(
                ModuleSettings.COMBINED_PULL_ANIMATION to "修复控制中心开启/关闭动画",
                ModuleSettings.QS_TRANSLATION_ORIGIN to "修复控制中心位移基准点",
                ModuleSettings.COMBINED_COLLAPSE_FIX to "修复合并控制中心上拉动画起始位置",
                ModuleSettings.SECONDARY_EXPANSION_FIX to "修复合并控制中心二次展开动画",
                ModuleSettings.COMBINED_EMPTY_SHADE_KEEP_OPEN to "合并控制中心清空通知后保持开启",
            ),
        )
    }
}

@Composable
private fun ControlCenterBackgroundPreferences() {
    val context = LocalContext.current
    val preferences = remember(context) { ModuleSettings.preferences(context) }
    var blurEnabled by remember { mutableStateOf(preferences.getBoolean(ModuleSettings.BLUR_ENABLED, false)) }
    var blurRadius by remember {
        mutableStateOf(preferences.getInt(ModuleSettings.BLUR_RADIUS, ModuleSettings.BLUR_DEFAULT)
            .coerceIn(0, ModuleSettings.BLUR_MAX))
    }
    var blurExpanded by remember { mutableStateOf(false) }
    FluixCard {
        ApplicationPreference(ModuleSettings.DARKEN, "控制中心背景压暗")
        FluixItemDivider()
        FluixSwitchPreference(
            checked = blurEnabled,
            onCheckedChange = {
                preferences.edit().putBoolean(ModuleSettings.BLUR_ENABLED, it).apply()
                blurEnabled = it
                blurExpanded = it
                context.contentResolver.notifyChange(ModuleSettings.URI, null)
            },
            title = "自定义背景模糊半径",
            onTitleClick = if (blurEnabled) ({ blurExpanded = !blurExpanded }) else null,
            leftTrailingContent = {
                if (blurEnabled) BasicText(
                    text = blurRadius.toString(),
                    style = MiuixTheme.textStyles.body2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary),
                    modifier = Modifier.padding(start = 12.dp),
                )
            },
            showDivider = blurEnabled,
        )
        AnimatedVisibility(
            visible = blurEnabled && blurExpanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            FluixSlider(
                value = blurRadius.toFloat() / ModuleSettings.BLUR_MAX,
                onValueChange = {
                    blurRadius = (it * ModuleSettings.BLUR_MAX).roundToInt().coerceIn(0, ModuleSettings.BLUR_MAX)
                    preferences.edit().putInt(ModuleSettings.BLUR_RADIUS, blurRadius).apply()
                    context.contentResolver.notifyChange(ModuleSettings.URI, null)
                },
                modifier = Modifier.padding(horizontal = 26.dp, vertical = 18.dp),
            )
        }
    }
}

@Composable
private fun AppStoreContentPreferences() {
    FluixCard {
        ApplicationPreference(ModuleSettings.BLOCK_STORE_SPLASH, "屏蔽开屏广告")
        FluixItemDivider()
        ApplicationPreference(ModuleSettings.STORE_HIDE_FEATURED, "去除精选 Tab")
        FluixItemDivider()
        ApplicationPreference(ModuleSettings.STORE_HIDE_GAMES, "去除游戏 Tab")
        FluixItemDivider()
        ApplicationPreference(ModuleSettings.STORE_EMPTY_HOME, "去除首页全部内容")
        FluixItemDivider()
        ApplicationPreference(ModuleSettings.STORE_HIDE_MINE_RECOMMENDATIONS, "去除我的页推荐")
        FluixItemDivider()
        ApplicationPreference(ModuleSettings.STORE_HIDE_DETAIL_RECOMMENDATIONS, "去除应用详情页推荐")
        FluixItemDivider()
        ApplicationPreference(ModuleSettings.STORE_HIDE_DOWNLOAD_PAGE_RECOMMENDATIONS, "去除下载管理页推荐")
        FluixItemDivider()
        ApplicationPreference(ModuleSettings.STORE_HIDE_UPDATE_PAGE_RECOMMENDATIONS, "去除应用更新页推荐")
        FluixItemDivider()
        ApplicationPreference(ModuleSettings.STORE_CLEAN_SEARCH, "去除搜索框热词及搜索页推荐")
    }
}

@Composable
private fun WeatherPreferences() {
    FluixCard {
        ApplicationPreference(ModuleSettings.BLOCK_WEATHER_ADS, "去除天气应用贴片广告")
        FluixItemDivider()
        ApplicationPreference(ModuleSettings.BLOCK_WEATHER_RECOMMENDATIONS, "屏蔽生活建议页内容推荐")
        FluixItemDivider()
        ApplicationPreference(ModuleSettings.WEATHER_DARK_BACKGROUND, "天气首页背景压暗")
    }
}

@Composable
private fun SplitNetworkPreference() {
    val context = LocalContext.current
    val preferences = remember(context) { ModuleSettings.preferences(context) }
    var checked by remember { mutableStateOf(preferences.getBoolean(ModuleSettings.SPLIT_NETWORK_CARD, false)) }
    FluixSwitchPreference(
        title = "网络卡片分离",
        checked = checked,
        onCheckedChange = {
            preferences.edit().putBoolean(ModuleSettings.SPLIT_NETWORK_CARD, it).apply()
            checked = it
            context.contentResolver.notifyChange(ModuleSettings.URI, null)
        },
    )
    if (checked) {
        FluixItemDivider()
        ApplicationPreference(ModuleSettings.FOLD_IDLE_MEDIA, "折叠吞并同行未播放音乐控制")
    }
}

@Composable
private fun ApplicationPreference(key: String, title: String) {
    val context = LocalContext.current
    val preferences = remember(context) { ModuleSettings.preferences(context) }
    var checked by remember(key) { mutableStateOf(preferences.getBoolean(key, false)) }
    FluixSwitchPreference(
        checked = checked,
        onCheckedChange = {
            preferences.edit().putBoolean(key, it).apply()
            checked = it
            context.contentResolver.notifyChange(ModuleSettings.URI, null)
        },
        title = title,
    )
}

@Composable
private fun AppNavigationPreference(onSelectApplications: () -> Unit) {
    val context = LocalContext.current
    val preferences = remember(context) { ModuleSettings.preferences(context) }
    var enabled by remember { mutableStateOf(preferences.getBoolean(ModuleSettings.APP_IMMERSIVE_NAVIGATION, false)) }
    FluixSwitchPreference(
        title = "应用导航栏强制沉浸",
        subtitle = "由 SystemUI 实现，无需勾选应用作用域",
        checked = enabled,
        onCheckedChange = {
            preferences.edit().putBoolean(ModuleSettings.APP_IMMERSIVE_NAVIGATION, it).apply()
            enabled = it
            context.contentResolver.notifyChange(ModuleSettings.URI, null)
        },
    )
    if (enabled) {
        FluixItemDivider()
        Row(Modifier.fillMaxWidth().heightIn(min = FLUIX_ROW_MIN_HEIGHT)
            .clickable(onClick = onSelectApplications)
            .padding(horizontal = FLUIX_ROW_HPADDING, vertical = FLUIX_ROW_VPADDING),
            verticalAlignment = Alignment.CenterVertically) {
            BasicText("应用列表", style = MiuixTheme.textStyles.body1.copy(color = MiuixTheme.colorScheme.onSurface),
                modifier = Modifier.weight(1f))
            BasicText("${ModuleSettings.navigationApplications(preferences).size} 个应用",
                style = MiuixTheme.textStyles.body2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
        }
    }
}

@Composable
private fun ClockFontPreference(key: String, title: String) {
    val context = LocalContext.current
    val preferences = remember(context) { ModuleSettings.preferences(context) }
    val options = ModuleSettings.CLOCK_FONT_NAMES.toList()
    var selected by remember { mutableStateOf(preferences.getInt(key, 0).coerceIn(options.indices)) }
    FluixDropdownPreference(
        title = title,
        options = options,
        selected = selected,
        onSelected = {
            selected = it
            preferences.edit().putInt(key, it).apply()
            context.contentResolver.notifyChange(ModuleSettings.URI, null)
        },
    )
}

@Composable
private fun ClockMonospacePreference(key: String, title: String) {
    val context = LocalContext.current
    val preferences = remember(context) { ModuleSettings.preferences(context) }
    var checked by remember { mutableStateOf(preferences.getBoolean(key, false)) }
    FluixSwitchPreference(
        checked = checked,
        onCheckedChange = {
            checked = it
            preferences.edit().putBoolean(key, it).apply()
            context.contentResolver.notifyChange(ModuleSettings.URI, null)
        },
        title = title,
    )
}

@Composable
private fun ClockWeightPreference(key: String = ModuleSettings.CLOCK_FONT_WEIGHT, title: String = "时钟字体字重") {
    val context = LocalContext.current
    val preferences = remember(context) { ModuleSettings.preferences(context) }
    val weights = listOf(0, 100, 200, 300, 400, 500, 600, 700, 800, 900)
    var selected by remember(key) {
        mutableStateOf(weights.indexOf(preferences.getInt(key, 0)).coerceAtLeast(0))
    }
    FluixDropdownPreference(
        title = title,
        options = listOf("跟随系统", "极细（100）", "纤细（200）", "细体（300）", "常规（400）", "中等（500）", "半粗（600）", "粗体（700）", "特粗（800）", "超粗（900）"),
        selected = selected,
        onSelected = {
            selected = it
            preferences.edit().putInt(key, weights[it]).apply()
            context.contentResolver.notifyChange(ModuleSettings.URI, null)
        },
    )
}

@Composable
private fun LightBackgroundOpacityPreference(notification: Boolean = false) {
    val enabledKey = if (notification) ModuleSettings.NOTIFICATION_LIGHT else ModuleSettings.LIGHT
    val amountKey = if (notification) ModuleSettings.NOTIFICATION_LIGHT_OPACITY else ModuleSettings.LIGHT_OPACITY
    val context = LocalContext.current
    val preferences = remember(context) { ModuleSettings.preferences(context) }
    var enabled by remember { mutableStateOf(preferences.getBoolean(enabledKey, false)) }
    var opacity by remember { mutableStateOf(preferences.getInt(amountKey,
        ModuleSettings.LIGHT_OPACITY_DEFAULT).coerceIn(0, ModuleSettings.LIGHT_OPACITY_MAX)) }
    var expanded by remember { mutableStateOf(false) }
    FluixSwitchPreference(
        title = if (notification) "通知亮色背景" else "控制中心磁贴亮色背景",
        checked = enabled,
        onCheckedChange = {
            enabled = it
            expanded = it
            preferences.edit().putBoolean(enabledKey, it).apply()
            context.contentResolver.notifyChange(ModuleSettings.URI, null)
        },
        onTitleClick = if (enabled) ({ expanded = !expanded }) else null,
        leftTrailingContent = {
            if (enabled) BasicText("${opacity}%",
                style = MiuixTheme.textStyles.body2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary),
                modifier = Modifier.padding(start = 12.dp))
        },
        showDivider = enabled,
    )
    AnimatedVisibility(visible = enabled && expanded, enter = expandVertically(), exit = shrinkVertically()) {
        FluixSlider(
            value = opacity / ModuleSettings.LIGHT_OPACITY_MAX.toFloat(),
            onValueChange = {
                opacity = (it * ModuleSettings.LIGHT_OPACITY_MAX).roundToInt().coerceIn(0, ModuleSettings.LIGHT_OPACITY_MAX)
                preferences.edit().putInt(amountKey, opacity).apply()
                context.contentResolver.notifyChange(ModuleSettings.URI, null)
            },
            modifier = Modifier.padding(horizontal = 26.dp, vertical = 18.dp),
        )
    }
}

@Composable
private fun TileBackgroundColorPreference() {
    val context = LocalContext.current
    val preferences = remember(context) { ModuleSettings.preferences(context) }
    var enabled by remember { mutableStateOf(preferences.getBoolean(ModuleSettings.TILE_BACKGROUND_COLOR, false)) }
    var amount by remember { mutableStateOf(preferences.getInt(ModuleSettings.TILE_BACKGROUND_SATURATION,
        ModuleSettings.TILE_BACKGROUND_SATURATION_DEFAULT).coerceIn(
        ModuleSettings.TILE_BACKGROUND_SATURATION_MIN, ModuleSettings.TILE_BACKGROUND_SATURATION_MAX)) }
    var expanded by remember { mutableStateOf(false) }
    FluixSwitchPreference(
        title = "控制中心磁贴背景增色",
        subtitle = "可能影响功耗",
        checked = enabled,
        onCheckedChange = {
            enabled = it
            expanded = it
            preferences.edit().putBoolean(ModuleSettings.TILE_BACKGROUND_COLOR, it).apply()
            context.contentResolver.notifyChange(ModuleSettings.URI, null)
        },
        onTitleClick = if (enabled) ({ expanded = !expanded }) else null,
        leftTrailingContent = {
            if (enabled) BasicText(String.format(java.util.Locale.ROOT, "%.2f", amount / 100f),
                style = MiuixTheme.textStyles.body2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary),
                modifier = Modifier.padding(start = 12.dp))
        },
        showDivider = enabled,
    )
    AnimatedVisibility(visible = enabled && expanded, enter = expandVertically(), exit = shrinkVertically()) {
        val minimum = ModuleSettings.TILE_BACKGROUND_SATURATION_MIN
        val range = ModuleSettings.TILE_BACKGROUND_SATURATION_MAX - minimum
        FluixSlider(
            value = (amount - minimum) / range.toFloat(),
            onValueChange = {
                amount = (minimum + it * range).roundToInt().coerceIn(
                    minimum, ModuleSettings.TILE_BACKGROUND_SATURATION_MAX)
                preferences.edit().putInt(ModuleSettings.TILE_BACKGROUND_SATURATION, amount).apply()
                context.contentResolver.notifyChange(ModuleSettings.URI, null)
            },
            modifier = Modifier.padding(horizontal = 26.dp, vertical = 18.dp),
        )
    }
}

@Composable
private fun TileCornerPreference(notification: Boolean = false) {
    val enabledKey = if (notification) ModuleSettings.NOTIFICATION_CUSTOM_CORNERS else ModuleSettings.TILE_CORNERS
    val amountKey = if (notification) ModuleSettings.NOTIFICATION_CORNER_PERCENT else ModuleSettings.TILE_CORNER_PERCENT
    val context = LocalContext.current
    val preferences = remember(context) { ModuleSettings.preferences(context) }
    var enabled by remember { mutableStateOf(preferences.getBoolean(enabledKey, false)) }
    var amount by remember { mutableStateOf(preferences.getInt(amountKey,
        ModuleSettings.TILE_CORNER_PERCENT_DEFAULT).coerceIn(
        ModuleSettings.TILE_CORNER_PERCENT_MIN, ModuleSettings.TILE_CORNER_PERCENT_MAX)) }
    var expanded by remember { mutableStateOf(false) }
    FluixSwitchPreference(
        title = if (notification) "通知圆角大小" else "控制中心磁贴圆角大小",
        checked = enabled,
        onCheckedChange = {
            enabled = it
            expanded = it
            preferences.edit().putBoolean(enabledKey, it).apply()
            context.contentResolver.notifyChange(ModuleSettings.URI, null)
        },
        onTitleClick = if (enabled) ({ expanded = !expanded }) else null,
        leftTrailingContent = {
            if (enabled) BasicText("${amount}%",
                style = MiuixTheme.textStyles.body2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary),
                modifier = Modifier.padding(start = 12.dp))
        },
        showDivider = enabled,
    )
    AnimatedVisibility(visible = enabled && expanded, enter = expandVertically(), exit = shrinkVertically()) {
        val minimum = ModuleSettings.TILE_CORNER_PERCENT_MIN
        val range = ModuleSettings.TILE_CORNER_PERCENT_MAX - minimum
        FluixSlider(
            value = (amount - minimum) / range.toFloat(),
            onValueChange = {
                amount = (minimum + it * range).roundToInt().coerceIn(
                    minimum, ModuleSettings.TILE_CORNER_PERCENT_MAX)
                preferences.edit().putInt(amountKey, amount).apply()
                context.contentResolver.notifyChange(ModuleSettings.URI, null)
            },
            modifier = Modifier.padding(horizontal = 26.dp, vertical = 18.dp),
        )
    }
}

@Composable
private fun ControlCenterClockDateOffsetPreference() {
    ControlCenterVerticalOffsetPreference(ModuleSettings.CONTROL_CENTER_CLOCK_DATE_UP,
        ModuleSettings.CONTROL_CENTER_CLOCK_DATE_UP_DISTANCE, "控制中心时钟日期上移", 16)
}

@Composable
private fun ControlCenterVerticalOffsetPreference(enabledKey: String, distanceKey: String, title: String, defaultDistance: Int) {
    val context = LocalContext.current
    val preferences = remember(context) { ModuleSettings.preferences(context) }
    var enabled by remember { mutableStateOf(preferences.getBoolean(enabledKey, false)) }
    var distance by remember { mutableStateOf(preferences.getInt(distanceKey, defaultDistance).coerceIn(0, 40)) }
    var expanded by remember { mutableStateOf(false) }
    FluixSwitchPreference(
        title = title,
        checked = enabled,
        onCheckedChange = {
            enabled = it
            expanded = it
            preferences.edit().putBoolean(enabledKey, it).apply()
            context.contentResolver.notifyChange(ModuleSettings.URI, null)
        },
        onTitleClick = if (enabled) ({ expanded = !expanded }) else null,
        leftTrailingContent = {
            if (enabled) BasicText("${distance}dp",
                style = MiuixTheme.textStyles.body2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary),
                modifier = Modifier.padding(start = 12.dp))
        },
        showDivider = enabled,
    )
    AnimatedVisibility(visible = enabled && expanded, enter = expandVertically(), exit = shrinkVertically()) {
        FluixSlider(
            value = distance / 40f,
            onValueChange = {
                distance = (it * 40).roundToInt().coerceIn(0, 40)
                preferences.edit().putInt(distanceKey, distance).apply()
            },
            onValueChangeFinished = { context.contentResolver.notifyChange(ModuleSettings.URI, null) },
            modifier = Modifier.padding(horizontal = 26.dp, vertical = 18.dp),
        )
    }
}

@Composable
private fun ClockSpacingPreference(key: String, enabledKey: String, title: String) {
    val context = LocalContext.current
    val preferences = remember(context) { ModuleSettings.preferences(context) }
    var enabled by remember(enabledKey) { mutableStateOf(preferences.getBoolean(enabledKey, false)) }
    var spacing by remember(key) { mutableStateOf(preferences.getInt(key, 0).coerceIn(-10, 10)) }
    var expanded by remember(key) { mutableStateOf(false) }
    FluixSwitchPreference(
        checked = enabled,
        onCheckedChange = {
            enabled = it
            expanded = it
            preferences.edit().putBoolean(enabledKey, it).apply()
            context.contentResolver.notifyChange(ModuleSettings.URI, null)
        },
        title = title,
        onTitleClick = if (enabled) ({ expanded = !expanded }) else null,
        leftTrailingContent = {
            if (enabled) BasicText(
                text = String.format(java.util.Locale.US, "%.2fem", spacing / 100f),
                style = MiuixTheme.textStyles.body2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary),
                modifier = Modifier.padding(start = 12.dp),
            )
        },
        showDivider = enabled,
    )
    AnimatedVisibility(
        visible = enabled && expanded,
        enter = expandVertically(),
        exit = shrinkVertically(),
    ) {
        FluixSlider(
            value = (spacing + 10) / 20f,
            onValueChange = {
                spacing = ((it * 20).roundToInt() - 10).coerceIn(-10, 10)
                preferences.edit().putInt(key, spacing).apply()
            },
            onValueChangeFinished = { context.contentResolver.notifyChange(ModuleSettings.URI, null) },
            modifier = Modifier.padding(horizontal = 26.dp, vertical = 18.dp),
        )
    }
}

@Composable
private fun VolumeStepsPreference() {
    val context = LocalContext.current
    val preferences = remember(context) { ModuleSettings.preferences(context) }
    var selected by remember { mutableStateOf(preferences.getInt(ModuleSettings.VOLUME_FIRST_FOUR, 0).coerceIn(0, 2)) }
    FluixCard {
        FluixDropdownPreference(
            title = "修改音量调节前四小格",
            options = listOf("不修改", "合并为两中格", "合并为一大格"),
            selected = selected,
            onSelected = {
                selected = it
                preferences.edit().putInt(ModuleSettings.VOLUME_FIRST_FOUR, it).apply()
                context.contentResolver.notifyChange(ModuleSettings.URI, null)
            },
        )
    }
}
