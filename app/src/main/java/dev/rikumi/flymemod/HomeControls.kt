package dev.rikumi.flymemod

import android.content.SharedPreferences
import android.os.Build
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val MODULE_SWITCHES = listOf(
    ModuleSettings.DARK_APP_SPECIAL_PACKAGES,
    ModuleSettings.SECONDARY_EXPANSION_FIX, ModuleSettings.QS_TRANSLATION_ORIGIN,
    ModuleSettings.RESTORE_COLLAPSED_CARD_HEIGHT,
    ModuleSettings.CONTROL_CENTER_CLOCK_DATE_UP, ModuleSettings.CONTROL_CENTER_BUTTONS_UP,
    ModuleSettings.ANIMATED_MUTE_SLASH,
    ModuleSettings.OPTIMIZE_2X1_TEXT, ModuleSettings.WIFI_LABEL,
    ModuleSettings.SPLIT_NETWORK_CARD,
    ModuleSettings.FOLD_IDLE_MEDIA,
    ModuleSettings.CIRCLE_SMALL_TILES,
    ModuleSettings.COMBINED_COLLAPSE_FIX, ModuleSettings.COMBINED_PULL_ANIMATION, ModuleSettings.LIMIT_AOD_MOVEMENT,
    ModuleSettings.INSTALLER_SKIP_WARNINGS,
    ModuleSettings.WEATHER_DARK_BACKGROUND,
    ModuleSettings.HIDE_LUNAR, ModuleSettings.SCALE, ModuleSettings.LIGHT, ModuleSettings.DARKEN, ModuleSettings.WHITE_ACTIVE,
    ModuleSettings.HEADS_UP_WIDTH, ModuleSettings.NOTIFICATION_CORNERS, ModuleSettings.ORIGINAL_NOTIFICATION_ICONS,
    ModuleSettings.MONOCHROME_NOTIFICATION_ACTIONS, ModuleSettings.MERGE_DUAL_SIGNAL,
    ModuleSettings.SEPARATE_NETWORK_TYPE, ModuleSettings.BLUR_ENABLED, ModuleSettings.WALLPAPER_STARTUP_FIX,
    ModuleSettings.STACKED_RECENTS, ModuleSettings.RECENTS_SWIPE_UP_KILL, ModuleSettings.RECENTS_HIDE_NOT_RUNNING,
    ModuleSettings.FOLDER_CLOSE_TARGET, ModuleSettings.FOLDER_PAGING, ModuleSettings.FOLDER_CENTER, ModuleSettings.FOLDER_RESTORE_COLOR, ModuleSettings.FOLDER_RADIUS_ENABLED,
    ModuleSettings.STORE_EMPTY_HOME,
    ModuleSettings.STORE_HIDE_MINE_RECOMMENDATIONS,
    ModuleSettings.STORE_HIDE_DETAIL_RECOMMENDATIONS,
    ModuleSettings.STORE_HIDE_DOWNLOAD_PAGE_RECOMMENDATIONS,
    ModuleSettings.STORE_HIDE_UPDATE_PAGE_RECOMMENDATIONS,
    ModuleSettings.STORE_CLEAN_SEARCH,
    ModuleSettings.STORE_HIDE_FEATURED, ModuleSettings.STORE_HIDE_GAMES, ModuleSettings.BLOCK_WEATHER_ADS, ModuleSettings.SLIDER_ACTIVE_CORNERS, ModuleSettings.HIDE_DISABLED_APPS, ModuleSettings.BLOCK_STORE_SPLASH, ModuleSettings.BLOCK_WEATHER_RECOMMENDATIONS,
) + ModuleSettings.MEDIA_FOLDERS.map { it[1] }

@Composable
internal fun HomeModuleControls() {
    val context = LocalContext.current
    val preferences = remember(context) { ModuleSettings.preferences(context) }
    val scope = rememberCoroutineScope()
    var version by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> version++ }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    // System animation/long-press values may be customized before installing
    // the module. They must not make a fresh module appear already enabled.
    val checked = remember(version) {
        ModuleSettings.controlCenterStyle(preferences) != 0 ||
        ModuleSettings.networkSplitStyle(preferences) != 0 ||
            preferences.getInt(ModuleSettings.VOLUME_FIRST_FOUR, 0) != 0 ||
            preferences.getInt(ModuleSettings.LOCK_CLOCK_FONT, 0) != 0 ||
            preferences.getInt(ModuleSettings.AOD_CLOCK_FONT, 0) != 0 ||
            preferences.getInt(ModuleSettings.STATUS_BAR_CLOCK_FONT, 0) != 0 ||
            preferences.getInt(ModuleSettings.CONTROL_CENTER_CLOCK_FONT, 0) != 0 ||
            (preferences.getBoolean(ModuleSettings.HIDDEN_LAUNCHER_APPS_ENABLED, true) &&
                !preferences.getStringSet(ModuleSettings.HIDDEN_LAUNCHER_APPS, emptySet()).isNullOrEmpty()) ||
            MODULE_SWITCHES.any { preferences.getBoolean(it, false) }
    }
    FluixMasterToggle(
        checked = checked,
        title = if (checked) "启用模块" else "一键启用",
        subtitle = if (checked) null else "信任开发者，一键启用全部",
        onCheckedChange = { enabled ->
            if (!busy) {
                busy = true
                scope.launch {
                    try {
                        val success = withContext(Dispatchers.IO) {
                            val saved = preferences.edit().also { editor ->
                                MODULE_SWITCHES.forEach { editor.putBoolean(it, enabled) }
                                editor.putInt(ModuleSettings.CONTROL_CENTER_STYLE, if (enabled) 1 else 0)
                                editor.putBoolean(ModuleSettings.HIDDEN_LAUNCHER_APPS_ENABLED, enabled)
                                editor.putInt(ModuleSettings.VOLUME_FIRST_FOUR, 0)
                                editor.putInt(ModuleSettings.LOCK_CLOCK_FONT, if (enabled) 2 else 0)
                                editor.putInt(ModuleSettings.AOD_CLOCK_FONT, if (enabled) 2 else 0)
                                editor.putInt(ModuleSettings.STATUS_BAR_CLOCK_FONT, if (enabled) 2 else 0)
                                editor.putInt(ModuleSettings.CONTROL_CENTER_CLOCK_FONT, if (enabled) 2 else 0)
                                editor.putInt(ModuleSettings.CLOCK_FONT_WEIGHT, if (enabled) 300 else 0)
                                editor.putBoolean(ModuleSettings.LOCK_CLOCK_MONOSPACE, enabled)
                                editor.putBoolean(ModuleSettings.AOD_CLOCK_MONOSPACE, enabled)
                                editor.putBoolean(ModuleSettings.LOCK_CLOCK_SPACING_ENABLED, enabled)
                                editor.putBoolean(ModuleSettings.AOD_CLOCK_SPACING_ENABLED, enabled)
                                // Spacing is stored in hundredths of an em.
                                editor.putInt(ModuleSettings.LOCK_CLOCK_SPACING, if (enabled) -4 else 0)
                                editor.putInt(ModuleSettings.AOD_CLOCK_SPACING, if (enabled) -4 else 0)
                                editor.putBoolean(ModuleSettings.STATUS_BAR_CLOCK_MONOSPACE, false)
                                editor.putBoolean(ModuleSettings.CONTROL_CENTER_CLOCK_MONOSPACE, false)
                                editor.putBoolean(ModuleSettings.STATUS_BAR_CLOCK_SPACING_ENABLED, false)
                                editor.putBoolean(ModuleSettings.CONTROL_CENTER_CLOCK_SPACING_ENABLED, false)
                                if (!enabled) editor.putInt(ModuleSettings.BLUR_RADIUS, ModuleSettings.BLUR_DEFAULT)
                            }.commit()
                            ModuleSettings.MEDIA_FOLDERS.forEach { updateMediaFolder(it[0], enabled) }
                            val rootResults = SystemSetting.entries.map { it.apply(enabled) }
                            saved && rootResults.all { it }
                        }
                        context.contentResolver.notifyChange(ModuleSettings.URI, null)
                        version++
                        if (!success) Toast.makeText(context, "部分设置修改失败，请检查 root 授权", Toast.LENGTH_LONG).show()
                    } finally {
                        busy = false
                    }
                }
            }
        },
        aboveContent = { HomeDeviceBanner() },
        belowContent = {
            FluixActionPairRow(
                leftTitle = "启动 KernelSU",
                onLeftClick = {
                    scope.launch {
                        val success = withContext(Dispatchers.IO) {
                            runRoot("am start -n me.weishu.kernelsu/.ui.MainActivity") != null
                        }
                        if (!success) Toast.makeText(context, "启动失败，请检查 KernelSU 安装及 root 授权", Toast.LENGTH_SHORT).show()
                    }
                },
                rightTitle = "启动 LSPosed",
                onRightClick = {
                    scope.launch {
                        val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                            "android.telephony.action.SECRET_CODE" else "android.provider.Telephony.SECRET_CODE"
                        val success = withContext(Dispatchers.IO) {
                            runRoot("am broadcast -a $action -d android_secret_code://5776733 android") != null
                        }
                        if (!success) Toast.makeText(context, "启动失败，请检查 LSPosed 安装及 root 授权", Toast.LENGTH_SHORT).show()
                    }
                },
            )
        },
    )
}
