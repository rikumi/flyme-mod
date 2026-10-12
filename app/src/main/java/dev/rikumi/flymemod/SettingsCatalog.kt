package dev.rikumi.flymemod

// 声明决定分组、顺序和存储键；绘制逻辑统一位于 SettingsItems.kt。
private val FONT_WEIGHTS = listOf(0, 100, 200, 300, 400, 500, 600, 700, 800, 900)
private val FONT_WEIGHT_NAMES = listOf("跟随系统", "极细（100）", "纤细（200）", "细体（300）", "常规（400）", "中等（500）", "半粗（600）", "粗体（700）", "特粗（800）", "超粗（900）")

internal val HIDE_DISABLED_APPS_ITEM = SwitchItem(ModuleSettings.HIDE_DISABLED_APPS, "在应用管理中隐藏已停用应用")

internal val SETTINGS_GROUPS: Map<String, List<SettingsGroup>> = mapOf(
    "desktop" to listOf(
        SettingsGroup("桌面图标设置", listOf(
            SwitchItem(ModuleSettings.ICON_CONTOUR, "图标轮廓光"),
            SwitchItem(ModuleSettings.EDIT_APP_ICON_NAME, "允许编辑图标和名称"),
            SwitchItem(ModuleSettings.MENU_CONTOUR, "长按菜单轮廓光"),
            SwitchItem(ModuleSettings.LAUNCHER_HIDE_APP_MENU, "允许隐藏应用"),
            SwitchItem(ModuleSettings.LAUNCHER_SPREAD_HIDDEN_APPS, "桌面双指张开查看隐藏应用"),
        )),
        SettingsGroup("桌面文件夹设置", listOf(
            SwitchItem(ModuleSettings.FOLDER_PAGING, "桌面文件夹水平分页", notifyChange = false),
            SwitchItem(ModuleSettings.FOLDER_CENTER, "桌面文件夹垂直居中", notifyChange = false),
            SwitchItem(ModuleSettings.FOLDER_MENU_ALIGNMENT, "修复文件夹长按菜单对齐"),
            SwitchItem(ModuleSettings.FOLDER_CONTOUR, "文件夹背景轮廓光"),
            SwitchItem(ModuleSettings.FOLDER_RESTORE_COLOR, "恢复文件夹背景色"),
            SwitchItem(ModuleSettings.FOLDER_RADIUS_ENABLED, "调整文件夹圆角大小",
                slider = SliderSpec(
                    ModuleSettings.FOLDER_RADIUS, ModuleSettings.FOLDER_RADIUS_DEFAULT, 0, ModuleSettings.FOLDER_RADIUS_MAX,
                    unit = "dp",
                ),
            ),
        )),
        SettingsGroup("桌面小组件设置", listOf(
            SwitchItem(ModuleSettings.WIDGET_CONTOUR, "小组件背景轮廓光"),
        )),
        SettingsGroup("壁纸设置", listOf(
            SwitchItem(ModuleSettings.WALLPAPER_STARTUP_FIX, "修复开机时壁纸强制调暗", notifyChange = false),
        )),
        SettingsGroup("桌面动画设置", listOf(
            SwitchItem(ModuleSettings.FOLDER_CLOSE_TARGET, "恢复关闭到文件夹动画目标"),
            SwitchItem(ModuleSettings.LOCK_WIDGET_STARTUP_FIX, "修复启动时锁屏小组件异常显示"),
        )),
    ),
    "quick_settings" to listOf(
        SettingsGroup("控制中心背景", listOf(
            SwitchItem(ModuleSettings.DARKEN, "控制中心背景压暗"),
            SwitchItem(ModuleSettings.BLUR_ENABLED, "自定义背景模糊半径",
                slider = SliderSpec(
                    ModuleSettings.BLUR_RADIUS, ModuleSettings.BLUR_DEFAULT, 0, ModuleSettings.BLUR_MAX,
                    unit = "",
                ),
            ),
        )),
        SettingsGroup("控制中心主题", listOf(
            SwitchItem(ModuleSettings.COLOROS_CONTOUR, "ColorOS 轮廓光"),
            SwitchItem(ModuleSettings.LIGHT, "控制中心磁贴亮色背景",
                slider = SliderSpec(
                    ModuleSettings.LIGHT_OPACITY, ModuleSettings.LIGHT_OPACITY_DEFAULT, 0, ModuleSettings.LIGHT_OPACITY_MAX,
                    unit = "%",
                ),
            ),
            SwitchItem(ModuleSettings.TILE_BACKGROUND_COLOR, "控制中心磁贴背景增色", subtitle = "可能影响功耗",
                slider = SliderSpec(
                    ModuleSettings.TILE_BACKGROUND_SATURATION, ModuleSettings.TILE_BACKGROUND_SATURATION_DEFAULT, ModuleSettings.TILE_BACKGROUND_SATURATION_MIN, ModuleSettings.TILE_BACKGROUND_SATURATION_MAX,
                    format = { String.format(java.util.Locale.ROOT, "%.2f", it / 100f) },
                ),
            ),
            SwitchItem(ModuleSettings.TILE_CORNERS, "控制中心磁贴圆角大小",
                slider = SliderSpec(
                    ModuleSettings.TILE_CORNER_PERCENT, ModuleSettings.TILE_CORNER_PERCENT_DEFAULT, ModuleSettings.TILE_CORNER_PERCENT_MIN, ModuleSettings.TILE_CORNER_PERCENT_MAX,
                    unit = "%",
                ),
            ),
            CustomItem("control_center_style") { ControlCenterStylePreference() },
            SwitchItem(ModuleSettings.SLIDER_ACTIVE_CORNERS, "音量/亮度条激活区域圆角"),
            SwitchItem(ModuleSettings.CIRCLE_SMALL_TILES, "1×1 磁贴强制圆形"),
        )),
        SettingsGroup("控制中心时钟", listOf(
            SelectItem(ModuleSettings.CONTROL_CENTER_CLOCK_FONT, "控制中心时钟字体", ModuleSettings.CLOCK_FONT_NAMES.toList()),
            SelectItem(ModuleSettings.CONTROL_CENTER_CLOCK_WEIGHT, "控制中心时钟字重", FONT_WEIGHT_NAMES, FONT_WEIGHTS),
            SwitchItem(ModuleSettings.CONTROL_CENTER_CLOCK_MONOSPACE, "控制中心时钟字体等宽"),
            SwitchItem(ModuleSettings.CONTROL_CENTER_CLOCK_DATE_UP, "控制中心时钟日期上移",
                slider = SliderSpec(
                    ModuleSettings.CONTROL_CENTER_CLOCK_DATE_UP_DISTANCE, 16, 0, 40,
                    unit = "dp",
                    notifyOnRelease = true,
                ),
            ),
            SwitchItem(ModuleSettings.HIDE_LUNAR, "隐藏农历日期"),
        )),
        SettingsGroup("控制中心布局", listOf(
            SwitchItem(ModuleSettings.SCALE, "控制中心操作区域放大"),
            SwitchItem(ModuleSettings.RESTORE_COLLAPSED_CARD_HEIGHT, "恢复合并控制中心折叠状态磁贴高度"),
            SwitchItem(ModuleSettings.COMBINED_FILTER_SETTINGS, "合并中心不重要通知按钮替换为设置"),
            SwitchItem(ModuleSettings.COMBINED_NOTIFICATION_WIDTH, "增大合并控制中心通知宽度"),
            SwitchItem(ModuleSettings.SPLIT_NETWORK_CARD, "网络卡片分离", children = listOf(
                SwitchItem(ModuleSettings.FOLD_IDLE_MEDIA, "折叠吞并同行未播放音乐控制"),
            )),
            SwitchItem(ModuleSettings.WIFI_LABEL, "无线网络替换为 Wi-Fi"),
            SwitchItem(ModuleSettings.ANIMATED_MUTE_SLASH, "动画式斜划线静音图标"),
            SwitchItem(ModuleSettings.OPTIMIZE_2X1_TEXT, "2×1 卡片文字布局优化"),
            SwitchItem(ModuleSettings.CONTROL_CENTER_BUTTONS_UP, "控制中心设置按钮上移",
                slider = SliderSpec(
                    ModuleSettings.CONTROL_CENTER_BUTTONS_UP_DISTANCE, 8, 0, 40,
                    unit = "dp",
                    notifyOnRelease = true,
                ),
            ),
        )),
        SettingsGroup("控制中心动画", listOf(
            SwitchItem(ModuleSettings.COMBINED_PULL_ANIMATION, "修复控制中心开启/关闭动画"),
            SwitchItem(ModuleSettings.QS_TRANSLATION_ORIGIN, "修复控制中心位移基准点"),
            SwitchItem(ModuleSettings.COMBINED_COLLAPSE_FIX, "修复合并控制中心上拉动画起始位置"),
            SwitchItem(ModuleSettings.SECONDARY_EXPANSION_FIX, "修复合并控制中心二次展开动画"),
            SwitchItem(ModuleSettings.COMBINED_EMPTY_SHADE_KEEP_OPEN, "合并控制中心清空通知后保持开启"),
        )),
        SettingsGroup("音量调节设置", listOf(
            SelectItem(ModuleSettings.VOLUME_FIRST_FOUR, "修改音量调节前四小格", listOf("不修改", "合并为两中格", "合并为一大格")),
        )),
    ),
    "notification" to listOf(
        SettingsGroup("通知中心主题", listOf(
            SwitchItem(ModuleSettings.NOTIFICATION_LIGHT, "通知亮色背景",
                slider = SliderSpec(
                    ModuleSettings.NOTIFICATION_LIGHT_OPACITY, ModuleSettings.LIGHT_OPACITY_DEFAULT, 0, ModuleSettings.LIGHT_OPACITY_MAX,
                    unit = "%",
                ),
            ),
            SwitchItem(ModuleSettings.NOTIFICATION_BACKGROUND_COLOR, "通知背景增色", subtitle = "可能影响功耗",
                slider = SliderSpec(
                    ModuleSettings.NOTIFICATION_BACKGROUND_SATURATION, ModuleSettings.TILE_BACKGROUND_SATURATION_DEFAULT, ModuleSettings.TILE_BACKGROUND_SATURATION_MIN, ModuleSettings.TILE_BACKGROUND_SATURATION_MAX,
                    format = { String.format(java.util.Locale.ROOT, "%.2f", it / 100f) },
                ),
            ),
            SwitchItem(ModuleSettings.NOTIFICATION_CUSTOM_CORNERS, "通知圆角大小",
                slider = SliderSpec(
                    ModuleSettings.NOTIFICATION_CORNER_PERCENT, ModuleSettings.TILE_CORNER_PERCENT_DEFAULT, ModuleSettings.TILE_CORNER_PERCENT_MIN, ModuleSettings.TILE_CORNER_PERCENT_MAX,
                    unit = "%",
                ),
            ),
            SwitchItem(ModuleSettings.NOTIFICATION_CONTOUR, "ColorOS 通知轮廓光"),
        )),
        SettingsGroup("通知中心设置", listOf(
            SwitchItem(ModuleSettings.HEADS_UP_WIDTH, "增加浮动通知宽度"),
            SwitchItem(ModuleSettings.NOTIFICATION_READ_LAYER_FIX, "修复已读通知动画层级"),
            SwitchItem(ModuleSettings.NATIVE_NOTIFICATION_EXPANSION, "恢复原生通知下滑展开"),
            SwitchItem(ModuleSettings.MONOCHROME_NOTIFICATION_ACTIONS, "通知操作按钮改为黑白色"),
            SwitchItem(ModuleSettings.REMOVE_NOTIFICATION_ACTION_BACKGROUND, "去除通知操作栏的特殊背景"),
            SwitchItem(ModuleSettings.DISABLE_GOOGLE_NOTIFICATION_SUGGESTIONS, "禁用 Google 通知建议操作"),
        )),
        SettingsGroup("状态栏图标", listOf(
            SwitchItem(ModuleSettings.ORIGINAL_NOTIFICATION_ICONS, "恢复原生第三方通知图标"),
            SwitchItem(ModuleSettings.MERGE_DUAL_SIGNAL, "合并双卡信号"),
            SwitchItem(ModuleSettings.SEPARATE_NETWORK_TYPE, "独立显示网络制式"),
        )),
        SettingsGroup("状态栏图标隐藏", listOf(
            SwitchItem(ModuleSettings.HIDE_STATUS_ICON_KEYS[0], "隐藏睡眠模式图标"),
            SwitchItem(ModuleSettings.HIDE_STATUS_ICON_KEYS[1], "隐藏 VPN 图标"),
            SwitchItem(ModuleSettings.HIDE_STATUS_ICON_KEYS[2], "隐藏定位图标"),
            SwitchItem(ModuleSettings.HIDE_STATUS_ICON_KEYS[3], "隐藏蓝牙图标"),
            SwitchItem(ModuleSettings.HIDE_STATUS_ICON_KEYS[4], "隐藏投屏图标"),
            SwitchItem(ModuleSettings.HIDE_STATUS_ICON_KEYS[5], "隐藏热点图标"),
            SwitchItem(ModuleSettings.HIDE_STATUS_ICON_KEYS[6], "隐藏屏幕录制图标"),
            SwitchItem(ModuleSettings.HIDE_STATUS_ICON_KEYS[7], "隐藏摄像头使用图标"),
            SwitchItem(ModuleSettings.HIDE_STATUS_ICON_KEYS[8], "隐藏麦克风使用图标"),
        )),
        SettingsGroup("状态栏字体", listOf(
            SelectItem(ModuleSettings.STATUS_BAR_CLOCK_FONT, "状态栏字体", ModuleSettings.CLOCK_FONT_NAMES.toList()),
            SelectItem(ModuleSettings.STATUS_BAR_CLOCK_WEIGHT, "状态栏字体字重", FONT_WEIGHT_NAMES, FONT_WEIGHTS),
            SwitchItem(ModuleSettings.STATUS_BAR_CLOCK_MONOSPACE, "状态栏字体等宽"),
            SwitchItem(ModuleSettings.STATUS_BAR_CLOCK_SPACING_ENABLED, "状态栏字间距",
                slider = SliderSpec(
                    ModuleSettings.STATUS_BAR_CLOCK_SPACING, 0, -10, 10,
                    notifyOnRelease = true,
                    format = { String.format(java.util.Locale.US, "%.2fem", it / 100f) },
                ),
            ),
        )),
    ),
    "lockscreen" to listOf(
        SettingsGroup("锁屏操作", listOf(
            SwitchItem(ModuleSettings.BLOCK_LOCKSCREEN_LONG_PRESS_EDIT, "屏蔽锁屏长按编辑"),
        )),
        SettingsGroup("锁屏视觉", listOf(
            SwitchItem(ModuleSettings.HIDE_CARRIER, "隐藏锁屏和通知中心运营商"),
            SwitchItem(ModuleSettings.LIMIT_AOD_MOVEMENT, "限制息屏显示移动范围"),
            SelectItem(ModuleSettings.LOCK_CLOCK_FONT, "锁屏和息屏时钟字体", ModuleSettings.CLOCK_FONT_NAMES.toList()),
            SwitchItem(ModuleSettings.LOCK_CLOCK_MONOSPACE, "锁屏和息屏时钟字体等宽"),
            SwitchItem(ModuleSettings.LOCK_CLOCK_SPACING_ENABLED, "锁屏和息屏时钟字间距",
                slider = SliderSpec(
                    ModuleSettings.LOCK_CLOCK_SPACING, 0, -10, 10,
                    notifyOnRelease = true,
                    format = { String.format(java.util.Locale.US, "%.2fem", it / 100f) },
                ),
            ),
            SelectItem(ModuleSettings.CLOCK_FONT_WEIGHT, "锁屏和息屏时钟字重", FONT_WEIGHT_NAMES, FONT_WEIGHTS),
        )),
    ),
    "navigation" to listOf(
        SettingsGroup("导航与返回", listOf(
            CustomItem("app_navigation") { actions -> AppNavigationPreference(actions.onSelectNavigationApplications) },
            CustomItem("back_gesture_width") { BackGestureWidthPreference() },
        )),
        SettingsGroup("长按操作", listOf(
            SystemItem(SystemSetting.LONG_PRESS),
            SwitchItem(ModuleSettings.MBACK_SYSTEM_TIMEOUT, "非压感 mBack 跟随系统长按超时"),
            SwitchItem(ModuleSettings.MBACK_MISSING_ASSISTANT_HOME, "未安装助手长按 mBack 回桌面"),
        )),
        SettingsGroup("动画设置", listOf(
            SystemItem(SystemSetting.ANIMATION),
            SwitchItem(ModuleSettings.HOME_ICON_MINIMUM_SIZE, "限制回桌面动画最小图标尺寸"),
        )),
        SettingsGroup("多任务切换", listOf(
            SwitchItem(ModuleSettings.STACKED_RECENTS, "ColorOS 堆叠多任务"),
            SwitchItem(ModuleSettings.RECENTS_SWIPE_UP_KILL, "多任务上划彻底结束进程"),
            SwitchItem(ModuleSettings.RECENTS_HIDE_NOT_RUNNING, "多任务隐藏未在运行的应用"),
        )),
        SettingsGroup("小窗管理", listOf(
            SwitchItem(ModuleSettings.SMALL_WINDOW_LIMIT_ENABLED, "修改小窗数量上限",
                slider = SliderSpec(
                    ModuleSettings.SMALL_WINDOW_LIMIT, ModuleSettings.SMALL_WINDOW_LIMIT_DEFAULT, 1, ModuleSettings.SMALL_WINDOW_LIMIT_MAX,
                    unit = "",
                    dividerOnlyWhenExpanded = true,
                ),
            ),
        )),
    ),
    "system_cleanup" to listOf(
        SettingsGroup("应用商店净化", listOf(
            SwitchItem(ModuleSettings.BLOCK_STORE_SPLASH, "屏蔽开屏广告"),
            SwitchItem(ModuleSettings.STORE_HIDE_FEATURED, "去除精选 Tab"),
            SwitchItem(ModuleSettings.STORE_HIDE_GAMES, "去除游戏 Tab"),
            SwitchItem(ModuleSettings.STORE_EMPTY_HOME, "去除首页全部内容"),
            SwitchItem(ModuleSettings.STORE_HIDE_MINE_RECOMMENDATIONS, "去除我的页推荐"),
            SwitchItem(ModuleSettings.STORE_HIDE_MINE_SUBSCRIBE_WISH, "去除我的预约及心愿单"),
            SwitchItem(ModuleSettings.STORE_HIDE_MINE_CAMPAIGN_QUICK_APP, "去除福利活动及快应用"),
            SwitchItem(ModuleSettings.STORE_HIDE_MINE_PHONE_CLEAR, "去除手机瘦身入口"),
            SwitchItem(ModuleSettings.STORE_HIDE_DETAIL_RECOMMENDATIONS, "去除应用详情页推荐"),
            SwitchItem(ModuleSettings.STORE_HIDE_DOWNLOAD_PAGE_RECOMMENDATIONS, "去除下载管理页推荐"),
            SwitchItem(ModuleSettings.STORE_HIDE_UPDATE_PAGE_RECOMMENDATIONS, "去除应用更新页推荐"),
            SwitchItem(ModuleSettings.STORE_CLEAN_SEARCH, "去除搜索框热词及搜索页推荐"),
        )),
        SettingsGroup("天气", listOf(
            SwitchItem(ModuleSettings.BLOCK_WEATHER_ADS, "去除天气应用贴片广告"),
            SwitchItem(ModuleSettings.BLOCK_WEATHER_RECOMMENDATIONS, "屏蔽生活建议页内容推荐"),
            SwitchItem(ModuleSettings.WEATHER_DARK_BACKGROUND, "天气首页背景压暗"),
        )),
        SettingsGroup("应用安装器", listOf(
            SwitchItem(ModuleSettings.INSTALLER_SKIP_WARNINGS, "跳过安装警告"),
        )),
    ),
    "camera" to listOf(
        SettingsGroup("相机设置", listOf(
            SwitchItem(ModuleSettings.PHOTO_PREVIEW_SYSTEM_ROTATION, "图库预览跟随系统旋转锁定"),
            SwitchItem(ModuleSettings.CAMERA_KEEP_FILTER, "保留滤镜设置"),
            SwitchItem(ModuleSettings.CAMERA_LIVE_PREROLL_ENABLED, "调整动态照片向前追溯时长",
                slider = SliderSpec(
                    ModuleSettings.CAMERA_LIVE_PREROLL_MS, ModuleSettings.CAMERA_LIVE_PREROLL_DEFAULT,
                    0, 1000, unit = "秒", step = 100,
                    format = { "%.1f 秒".format(java.util.Locale.ROOT, it / 1000.0) },
                )),
            SwitchItem(ModuleSettings.CAMERA_DISABLE_AUTO_NIGHT, "关闭自动夜景"),
            SwitchItem(ModuleSettings.CAMERA_NIGHT_EXPOSURE_ENABLED, "调整夜景曝光时间",
                slider = SliderSpec(
                    ModuleSettings.CAMERA_NIGHT_EXPOSURE_MS, ModuleSettings.CAMERA_NIGHT_EXPOSURE_DEFAULT,
                    ModuleSettings.CAMERA_NIGHT_EXPOSURE_MIN, ModuleSettings.CAMERA_NIGHT_EXPOSURE_MAX,
                    unit = "秒", step = 100, format = { "%.1f 秒".format(java.util.Locale.ROOT, it / 1000.0) },
                )),
        )),
        SettingsGroup("系统杂项", listOf(
            ColorItem(ModuleSettings.SYSTEM_THEME_COLOR_ENABLED, "自定义系统主题色",
                ModuleSettings.SYSTEM_THEME_COLOR, ModuleSettings.SYSTEM_THEME_COLOR_DEFAULT),
            SwitchItem(ModuleSettings.DARK_SYSTEM_APP_BLACK_BACKGROUND, "深色模式系统应用背景纯黑"),
            SwitchItem(ModuleSettings.DARK_APP_SPECIAL_PACKAGES, "深色应用管理支持选择特殊应用"),
            ActionItem("landscape_games", "横屏游戏宽度适配") { it.onSelectGameApplications() },
        )),
    ),
    "storage" to listOf(
        SettingsGroup("不自动创建以下文件夹", ModuleSettings.MEDIA_FOLDERS.map { (folder, key) ->
            FolderBlockItem(key, folder)
        }),
    ),
)
