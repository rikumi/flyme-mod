package com.rikumi.flymemod

import android.os.Bundle
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
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
        Category("desktop", "桌面", R.drawable.ic_flyme_home_apps, SettingsHomeColors.blueForeground, listOf("壁纸设置", "桌面动画设置", "桌面文件夹设置")),
        Category("quick_settings", "控制中心", R.drawable.ic_flyme_home_wireless, SettingsHomeColors.cyanForeground, listOf("控制中心背景", "控制中心主题", "控制中心布局", "控制中心动画", "音量调节设置")),
        Category("notification", "通知中心与状态栏", R.drawable.ic_flyme_home_notifications, SettingsHomeColors.cyanForeground, listOf("通知中心设置", "状态栏设置")),
        Category("lockscreen", "锁屏", R.drawable.ic_flyme_home_security, SettingsHomeColors.greenForeground, listOf("锁屏视觉")),
    ),
    listOf(
        Category("hidden_apps", "隐藏应用", R.drawable.ic_flyme_home_apps, SettingsHomeColors.yellowForeground, listOf("特殊应用隐藏")),
        Category("navigation", "导航与手势", R.drawable.ic_flyme_home_gestures, SettingsHomeColors.yellowForeground, listOf("手势行为", "多任务切换")),
    ),
    listOf(
        Category("system_cleanup", "系统净化", R.drawable.ic_flyme_home_cleaner, SettingsHomeColors.orangeForeground, listOf("应用商店内容", "应用商店搜索", "应用商店详情页", "天气", "应用安装器")),
        Category("storage", "存储管理", R.drawable.ic_flyme_home_storage, SettingsHomeColors.redForeground, listOf("不自动创建以下文件夹")),
        Category(DISABLED_APPS_ID, "停用应用", R.drawable.ic_flyme_home_disabled, SettingsHomeColors.redForeground, listOf("停用应用设置", "已停用的应用")),
    ),
)

private val CATEGORIES = CATEGORY_GROUPS.flatten()

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                FluixOverscrollHost { SettingsScreen() }
            }
        }
    }
}

@Composable
private fun SettingsScreen() {
    var openCategoryId by remember { mutableStateOf<String?>(null) }
    BackHandler(enabled = openCategoryId != null) { openCategoryId = null }

    AnimatedContent(
        targetState = openCategoryId,
        transitionSpec = {
            val direction = if (targetState != null) 1 else -1
            val spec = tween<IntOffset>(durationMillis = PAGE_TRANSITION_MS)
            (fadeIn(animationSpec = tween(PAGE_TRANSITION_MS)) +
                slideInHorizontally(animationSpec = spec) { direction * it / 5 })
                .togetherWith(
                    fadeOut(animationSpec = tween(PAGE_TRANSITION_MS)) +
                        slideOutHorizontally(animationSpec = spec) { -direction * it / 5 },
                )
        },
        label = "settings_pages",
    ) { pageId ->
        val category = CATEGORIES.firstOrNull { it.id == pageId }
        if (category == null) {
            HomeScreen(onOpenCategory = { openCategoryId = it })
        } else if (category.id == DISABLED_APPS_ID) {
            DisabledAppsScreen(LocalContext.current, onBack = { openCategoryId = null })
        } else if (category.id == "hidden_apps") {
            HiddenAppsScreen(LocalContext.current, onBack = { openCategoryId = null })
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
                if (category.id == "system_cleanup" && groupTitle == "应用商店内容") {
                    item { AppStoreContentPreferences() }
                }
                if (category.id == "system_cleanup" && groupTitle == "应用商店搜索") {
                    item { AppStoreSearchPreferences() }
                }
                if (category.id == "system_cleanup" && groupTitle == "应用商店详情页") {
                    item { AppStoreDetailPreferences() }
                }
                if (category.id == "system_cleanup" && groupTitle == "天气") {
                    item { WeatherPreferences() }
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
                if (category.id == "lockscreen" && groupTitle == "锁屏视觉") {
                    item {
                        FluixCard {
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
                if (category.id == "notification" && groupTitle == "通知中心设置") {
                    item { NotificationPreferences() }
                }
                if (category.id == "notification" && groupTitle == "状态栏设置") {
                    item { StatusBarPreferences() }
                }
                if (category.id == "desktop" && groupTitle == "壁纸设置") {
                    item { WallpaperPreferences() }
                }
                if (category.id == "desktop" && groupTitle == "桌面动画设置") {
                    item {
                        FluixCard {
                            ApplicationPreference(ModuleSettings.HOME_SWIPE_DAMPING, "降低回桌面上抛阻尼")
                            FluixItemDivider()
                            ApplicationPreference(ModuleSettings.FOLDER_CLOSE_TARGET, "恢复关闭到文件夹动画目标")
                        }
                    }
                }
                if (category.id == "desktop" && groupTitle == "桌面文件夹设置") {
                    item { FolderPreferences() }
                }
                if (category.id == "navigation" && groupTitle == "手势行为") {
                    item { SystemSettingsPreferences() }
                }
                if (category.id == "navigation" && groupTitle == "多任务切换") {
                    item {
                        FluixCard {
                            ApplicationPreference(ModuleSettings.RECENTS_SWIPE_UP_KILL, "多任务上划彻底结束进程")
                            FluixItemDivider()
                            ApplicationPreference(ModuleSettings.RECENTS_HIDE_NOT_RUNNING, "多任务隐藏未在运行的应用")
                        }
                    }
                }
            }
            item { Box(Modifier.height(24.dp)) }
        }
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
private fun NotificationPreferences() {
    PreferenceCard(
        listOf(
            ModuleSettings.HEADS_UP_WIDTH to "增加浮动通知宽度",
            ModuleSettings.NOTIFICATION_CORNERS to "增大通知圆角",
            ModuleSettings.NATIVE_NOTIFICATION_EXPANSION to "恢复原生通知下滑展开",
            ModuleSettings.MONOCHROME_NOTIFICATION_ACTIONS to "通知操作按钮改为黑白色",
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
                ApplicationPreference(ModuleSettings.COLOROS_MATERIAL, "ColorOS 背景材质")
                FluixItemDivider()
                LightBackgroundOpacityPreference()
                FluixItemDivider()
                WhiteActiveOpacityPreference()
                FluixItemDivider()
                ApplicationPreference(ModuleSettings.NETWORK_DARK_SPINNER, "修复网络磁贴展开态深色模式适配")
                FluixItemDivider()
                ApplicationPreference(ModuleSettings.SLIDER_ACTIVE_CORNERS, "音量/亮度条激活区域圆角")
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
                ModuleSettings.SPLIT_NETWORK_CARD to "网络卡片分离",
                ModuleSettings.ANIMATED_MUTE_SLASH to "动画式斜划线静音图标",
                ModuleSettings.OPTIMIZE_2X1_TEXT to "2×1 卡片文字布局优化",
                ModuleSettings.SOLID_2X1_CARDS to "2×1 卡片改为实心（HyperOS 模式）",
                ModuleSettings.CIRCLE_SMALL_TILES to "1×1 磁贴改为圆形",
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
        ApplicationPreference(ModuleSettings.STORE_EMPTY_APPLICATION_PAGE, "去除应用页所有内容")
        FluixItemDivider()
        ApplicationPreference(ModuleSettings.STORE_HIDE_POPULAR, "去除大家都在用")
        FluixItemDivider()
        ApplicationPreference(ModuleSettings.STORE_HIDE_COMMUNITY, "去除魅友安利")
        FluixItemDivider()
        ApplicationPreference(ModuleSettings.STORE_HIDE_DAILY, "去除每日推荐")
        FluixItemDivider()
    }
}

@Composable
private fun AppStoreSearchPreferences() {
    FluixCard {
        ApplicationPreference(ModuleSettings.STORE_HIDE_SEARCH_HOT, "去除搜索框热词")
        FluixItemDivider()
        ApplicationPreference(ModuleSettings.STORE_HIDE_SEARCH_RECOMMENDATIONS, "去除搜索推荐")
    }
}

@Composable
private fun AppStoreDetailPreferences() {
    FluixCard {
        ApplicationPreference(ModuleSettings.STORE_DETAIL_HIDE_SAME_MODEL, "去除同机型用户喜爱")
        FluixItemDivider()
        ApplicationPreference(ModuleSettings.STORE_DETAIL_HIDE_TOPICS, "去除所在专题")
        FluixItemDivider()
        ApplicationPreference(ModuleSettings.STORE_DETAIL_HIDE_REVIEWS, "去除应用评测")
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
private fun LightBackgroundOpacityPreference() {
    val context = LocalContext.current
    val preferences = remember(context) { ModuleSettings.preferences(context) }
    var enabled by remember { mutableStateOf(preferences.getBoolean(ModuleSettings.LIGHT, false)) }
    var opacity by remember { mutableStateOf(preferences.getInt(ModuleSettings.LIGHT_OPACITY,
        ModuleSettings.LIGHT_OPACITY_DEFAULT).coerceIn(0, ModuleSettings.LIGHT_OPACITY_MAX)) }
    var expanded by remember { mutableStateOf(false) }
    FluixSwitchPreference(
        title = "控制中心磁贴亮色背景",
        checked = enabled,
        onCheckedChange = {
            enabled = it
            expanded = it
            preferences.edit().putBoolean(ModuleSettings.LIGHT, it).apply()
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
                preferences.edit().putInt(ModuleSettings.LIGHT_OPACITY, opacity).apply()
                context.contentResolver.notifyChange(ModuleSettings.URI, null)
            },
            modifier = Modifier.padding(horizontal = 26.dp, vertical = 18.dp),
        )
    }
}

@Composable
private fun WhiteActiveOpacityPreference() {
    val context = LocalContext.current
    val preferences = remember(context) { ModuleSettings.preferences(context) }
    var enabled by remember { mutableStateOf(preferences.getBoolean(ModuleSettings.WHITE_ACTIVE, false)) }
    var opacity by remember { mutableStateOf(preferences.getInt(ModuleSettings.WHITE_ACTIVE_OPACITY, 90).coerceIn(50, 100)) }
    var expanded by remember { mutableStateOf(false) }
    FluixSwitchPreference(
        title = "控制中心激活态改为白色",
        checked = enabled,
        onCheckedChange = {
            enabled = it
            expanded = it
            preferences.edit().putBoolean(ModuleSettings.WHITE_ACTIVE, it).apply()
            context.contentResolver.notifyChange(ModuleSettings.URI, null)
        },
        onTitleClick = if (enabled) ({ expanded = !expanded }) else null,
        leftTrailingContent = {
            if (enabled) BasicText(String.format(java.util.Locale.US, "%.2f", opacity / 100f),
                style = MiuixTheme.textStyles.body2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary),
                modifier = Modifier.padding(start = 12.dp))
        },
        showDivider = enabled,
    )
    AnimatedVisibility(visible = enabled && expanded, enter = expandVertically(), exit = shrinkVertically()) {
        FluixSlider(
            value = (opacity - 50) / 50f,
            onValueChange = {
                opacity = (it * 50).roundToInt().plus(50).coerceIn(50, 100)
                preferences.edit().putInt(ModuleSettings.WHITE_ACTIVE_OPACITY, opacity).apply()
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
