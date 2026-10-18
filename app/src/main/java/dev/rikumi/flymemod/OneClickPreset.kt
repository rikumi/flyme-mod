package dev.rikumi.flymemod

import android.content.SharedPreferences

/** Developer preset; app lists and system Settings values are preserved. */
internal object OneClickPreset {
    // Only these switches are disabled by the developer preset.
    private val disabledSwitches = setOf(
        ModuleSettings.LAUNCHER_EXTRA_ROW,
        // Font monospace and spacing are left disabled by default except lockscreen clock.
        ModuleSettings.CONTROL_CENTER_CLOCK_MONOSPACE,
        ModuleSettings.CONTROL_CENTER_CLOCK_SPACING_ENABLED,
        ModuleSettings.STATUS_BAR_CLOCK_MONOSPACE,
        ModuleSettings.STATUS_BAR_CLOCK_SPACING_ENABLED,

        // Contour switches are left disabled by default.
        ModuleSettings.TILES_CONTOUR,
        ModuleSettings.NOTIFICATION_CONTOUR,
        ModuleSettings.ICON_CONTOUR,
        ModuleSettings.MENU_CONTOUR,
        ModuleSettings.WIDGET_CONTOUR,

        // Hide lunar switch is left disabled by default.
        ModuleSettings.HIDE_LUNAR,
    )

    // Legacy and custom controls have no SwitchItem in the settings catalog.
    private val additionalSwitches = setOf(
        ModuleSettings.AOD_CLOCK_MONOSPACE,
        ModuleSettings.AOD_CLOCK_SPACING_ENABLED,
        ModuleSettings.BACK_GESTURE_WIDTH_ENABLED,
        ModuleSettings.NETWORK_DARK_SPINNER,
        ModuleSettings.STORE_HIDE_DOWNLOAD_RECOMMENDATIONS,
        ModuleSettings.STORE_HIDE_EVERYONE_WATCHING,
        ModuleSettings.STORE_HIDE_HIGH_RATED,
        ModuleSettings.STORE_HIDE_STAR_APPS,
        ModuleSettings.STORE_HIDE_YOU_MAY_LIKE,
        ModuleSettings.STORE_DETAIL_HIDE_REVIEWS,
        ModuleSettings.STORE_DETAIL_HIDE_SAME_MODEL,
        ModuleSettings.STORE_DETAIL_HIDE_TOPICS,
        ModuleSettings.STORE_EMPTY_APPLICATION_PAGE,
        ModuleSettings.STORE_HIDE_COMMUNITY,
        ModuleSettings.STORE_HIDE_DAILY,
        ModuleSettings.STORE_HIDE_POPULAR,
        ModuleSettings.STORE_HIDE_SEARCH_HOT,
        ModuleSettings.STORE_HIDE_SEARCH_RECOMMENDATIONS,
        ModuleSettings.WHITE_ACTIVE,
    )

    val switches: Map<String, Boolean> by lazy {
        buildSet {
            fun collect(items: List<SettingsItem>) {
                items.forEach { item ->
                    when (item) {
                        is SwitchItem -> {
                            add(item.key)
                            collect(item.children)
                        }
                        is FolderBlockItem -> add(item.key)
                        is ColorItem -> add(item.key)
                        else -> Unit
                    }
                }
            }
            SETTINGS_GROUPS.values.flatten().forEach { collect(it.items) }
            add(HIDE_DISABLED_APPS_ITEM.key)
            addAll(additionalSwitches)
            addAll(applicationSwitches)
            addAll(disabledSwitches)
        }.associateWith { it !in disabledSwitches }
    }
    val values = mapOf(
        ModuleSettings.AUTO_BRIGHTNESS_OFFSET to ModuleSettings.AUTO_BRIGHTNESS_OFFSET_DEFAULT,
        ModuleSettings.STATUS_BAR_CHARGING_INFO to 1,
        ModuleSettings.AOD_CLOCK_FONT to 2,
        ModuleSettings.AOD_CLOCK_SPACING to 0,
        ModuleSettings.BACK_GESTURE_DARK_OPACITY to ModuleSettings.BACK_GESTURE_DARK_OPACITY_DEFAULT,
        ModuleSettings.BACK_GESTURE_WIDTH_DP to 32,
        ModuleSettings.BACKGROUND_SCALE to ModuleSettings.BACKGROUND_SCALE_DEFAULT,
        ModuleSettings.CAMERA_NIGHT_EXPOSURE_MS to 1000,
        ModuleSettings.CAMERA_LIVE_PREROLL_MS to ModuleSettings.CAMERA_LIVE_PREROLL_DEFAULT,
        ModuleSettings.SMALL_WINDOW_LIMIT to ModuleSettings.SMALL_WINDOW_LIMIT_DEFAULT,
        ModuleSettings.CLOCK_FONT_WEIGHT to 300,
        ModuleSettings.CONTROL_CENTER_ACTIVE_COLOR to -2132348161,
        ModuleSettings.CONTROL_CENTER_STYLE to 1,
        ModuleSettings.CONTROL_CENTER_BUTTONS_UP_DISTANCE to 8,
        ModuleSettings.CONTROL_CENTER_CLOCK_DATE_UP_DISTANCE to ModuleSettings.CONTROL_CENTER_CLOCK_DATE_UP_DISTANCE_DEFAULT,
        ModuleSettings.CONTROL_CENTER_CLOCK_FONT to 2,
        ModuleSettings.CONTROL_CENTER_CLOCK_SPACING to 0,
        ModuleSettings.CONTROL_CENTER_CLOCK_WEIGHT to 0,
        ModuleSettings.TILE_CORNER_PERCENT to 30,
        ModuleSettings.FOLDER_RADIUS to 16,
        ModuleSettings.GESTURE_BAR_WIDTH_DP to 100,
        ModuleSettings.LIGHT_OPACITY to 10,
        ModuleSettings.LOCK_CLOCK_FONT to 2,
        ModuleSettings.LOCK_CLOCK_SPACING to -4,
        ModuleSettings.NOTIFICATION_BACKGROUND_SATURATION to ModuleSettings.TILE_BACKGROUND_SATURATION_DEFAULT,
        ModuleSettings.NOTIFICATION_CORNER_PERCENT to 30,
        ModuleSettings.NOTIFICATION_LIGHT_OPACITY to 10,
        ModuleSettings.BLUR_RADIUS to 15,
        ModuleSettings.STATUS_BAR_CLOCK_FONT to 2,
        ModuleSettings.STATUS_BAR_CLOCK_SPACING to 0,
        ModuleSettings.STATUS_BAR_CLOCK_WEIGHT to 0,
        ModuleSettings.TILE_BACKGROUND_SATURATION to ModuleSettings.TILE_BACKGROUND_SATURATION_DEFAULT,
        ModuleSettings.VOLUME_FIRST_FOUR to 1,
    )
    private val modeKeys = setOf(
        ModuleSettings.TICKER_DISPLAY_MODE,
        ModuleSettings.STATUS_BAR_CHARGING_INFO,
        ModuleSettings.CONTROL_CENTER_STYLE, ModuleSettings.VOLUME_FIRST_FOUR,
        ModuleSettings.LOCK_CLOCK_FONT, ModuleSettings.AOD_CLOCK_FONT,
        ModuleSettings.STATUS_BAR_CLOCK_FONT, ModuleSettings.CONTROL_CENTER_CLOCK_FONT,
        ModuleSettings.CLOCK_FONT_WEIGHT, ModuleSettings.STATUS_BAR_CLOCK_WEIGHT,
        ModuleSettings.CONTROL_CENTER_CLOCK_WEIGHT,
    )
    private val applicationSwitches = setOf(
        ModuleSettings.APP_IMMERSIVE_NAVIGATION, ModuleSettings.DARK_APP_SPECIAL_PACKAGES,
        ModuleSettings.HIDDEN_LAUNCHER_APPS_ENABLED,
        ModuleSettings.LANDSCAPE_GAME_ENABLED,
    )

    fun isEnabled(preferences: SharedPreferences): Boolean =
        switches.keys.any { preferences.getBoolean(it, false) } ||
            preferences.getBoolean(ModuleSettings.APP_IMMERSIVE_NAVIGATION, false) ||
            preferences.getBoolean(ModuleSettings.DARK_APP_SPECIAL_PACKAGES, false) ||
            (preferences.getBoolean(ModuleSettings.HIDDEN_LAUNCHER_APPS_ENABLED, false) &&
                !preferences.getStringSet(ModuleSettings.HIDDEN_LAUNCHER_APPS, emptySet()).isNullOrEmpty()) ||
            (preferences.getBoolean(ModuleSettings.LANDSCAPE_GAME_ENABLED, false) &&
                !preferences.getStringSet(ModuleSettings.LANDSCAPE_GAME_PACKAGES, emptySet()).isNullOrEmpty()) ||
            modeKeys.any { preferences.getInt(it, if (it == ModuleSettings.STATUS_BAR_CHARGING_INFO) ModuleSettings.STATUS_BAR_CHARGING_INFO_DEFAULT else 0) != 0 }

    fun apply(editor: SharedPreferences.Editor, enabled: Boolean) {
        if (enabled) {
            switches.forEach { (key, value) -> editor.putBoolean(key, value) }
            // Presentation override stays at the native policy in the developer preset.
            editor.putInt(ModuleSettings.TICKER_DISPLAY_MODE, 0)
            values.forEach { (key, value) ->
                if (key !in modeKeys || value != 0) editor.putInt(key, value)
            }
        } else {
            switches.keys.forEach { editor.putBoolean(it, false) }
            applicationSwitches.forEach { editor.putBoolean(it, false) }
            modeKeys.forEach { editor.putInt(it, 0) }
        }
    }
}
