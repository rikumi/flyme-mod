package com.rikumi.flymemod

import android.content.SharedPreferences
import android.os.Build
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
    ModuleSettings.SECONDARY_EXPANSION_FIX, ModuleSettings.QS_TRANSLATION_ORIGIN,
    ModuleSettings.RESTORE_COLLAPSED_CARD_HEIGHT,
    ModuleSettings.ANIMATED_MUTE_SLASH,
    ModuleSettings.OPTIMIZE_2X1_TEXT, ModuleSettings.WIFI_LABEL,
    ModuleSettings.SPLIT_NETWORK_CARD, ModuleSettings.SOLID_2X1_CARDS,
    ModuleSettings.FOLD_IDLE_MEDIA,
    ModuleSettings.CIRCLE_SMALL_TILES,
    ModuleSettings.COMBINED_COLLAPSE_FIX, ModuleSettings.COMBINED_PULL_ANIMATION, ModuleSettings.LIMIT_AOD_MOVEMENT,
    ModuleSettings.INSTALLER_SKIP_WARNINGS,
    ModuleSettings.STORE_DETAIL_HIDE_REVIEWS, ModuleSettings.STORE_HIDE_SEARCH_RECOMMENDATIONS,
    ModuleSettings.STORE_DETAIL_HIDE_SAME_MODEL, ModuleSettings.STORE_DETAIL_HIDE_TOPICS,
    ModuleSettings.STORE_HIDE_SEARCH_HOT, ModuleSettings.STORE_EMPTY_APPLICATION_PAGE,
    ModuleSettings.WEATHER_DARK_BACKGROUND,
    ModuleSettings.HIDE_LUNAR, ModuleSettings.SCALE, ModuleSettings.LIGHT, ModuleSettings.DARKEN, ModuleSettings.WHITE_ACTIVE,
    ModuleSettings.HEADS_UP_WIDTH, ModuleSettings.NOTIFICATION_CORNERS, ModuleSettings.ORIGINAL_NOTIFICATION_ICONS,
    ModuleSettings.MONOCHROME_NOTIFICATION_ACTIONS, ModuleSettings.MERGE_DUAL_SIGNAL,
    ModuleSettings.SEPARATE_NETWORK_TYPE, ModuleSettings.BLUR_ENABLED, ModuleSettings.WALLPAPER_STARTUP_FIX,
    ModuleSettings.RECENTS_SWIPE_UP_KILL, ModuleSettings.RECENTS_HIDE_NOT_RUNNING,
    ModuleSettings.FOLDER_PAGING, ModuleSettings.FOLDER_CENTER, ModuleSettings.FOLDER_RESTORE_COLOR, ModuleSettings.FOLDER_RADIUS_ENABLED,
    ModuleSettings.STORE_HIDE_FEATURED, ModuleSettings.STORE_HIDE_GAMES, ModuleSettings.STORE_HIDE_POPULAR, ModuleSettings.STORE_HIDE_COMMUNITY, ModuleSettings.STORE_HIDE_DAILY,
    ModuleSettings.BLOCK_WEATHER_ADS, ModuleSettings.SLIDER_ACTIVE_CORNERS, ModuleSettings.HIDE_DISABLED_APPS, ModuleSettings.BLOCK_STORE_SPLASH, ModuleSettings.BLOCK_WEATHER_RECOMMENDATIONS,
) + ModuleSettings.MEDIA_FOLDERS.map { it[1] }

@Composable
internal fun HomeModuleControls() {
    val context = LocalContext.current
    val preferences = remember(context) { ModuleSettings.preferences(context) }
    val scope = rememberCoroutineScope()
    var version by remember { mutableStateOf(0) }
    var systemEnabled by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> version++ }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    LaunchedEffect(context) {
        systemEnabled = withContext(Dispatchers.IO) { SystemSetting.entries.any { it.read(context).first } }
    }
    val checked = remember(version, systemEnabled) {
        systemEnabled || ModuleSettings.networkSplitStyle(preferences) != 0 ||
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
        subtitle = if (checked) null else "信任开发者，一键启用全部推荐功能",
        onCheckedChange = { enabled ->
            if (!busy) {
                busy = true
                scope.launch {
                    try {
                        val success = withContext(Dispatchers.IO) {
                            val saved = preferences.edit().also { editor ->
                                MODULE_SWITCHES.forEach { editor.putBoolean(it, enabled) }
                                editor.putBoolean(ModuleSettings.HIDDEN_LAUNCHER_APPS_ENABLED, enabled)
                                editor.putInt(ModuleSettings.VOLUME_FIRST_FOUR, if (enabled) 2 else 0)
                                editor.putInt(ModuleSettings.LOCK_CLOCK_FONT, if (enabled) 1 else 0)
                                editor.putInt(ModuleSettings.AOD_CLOCK_FONT, if (enabled) 1 else 0)
                                editor.putInt(ModuleSettings.STATUS_BAR_CLOCK_FONT, if (enabled) 1 else 0)
                                editor.putInt(ModuleSettings.CONTROL_CENTER_CLOCK_FONT, if (enabled) 1 else 0)
                                if (!enabled) editor.putInt(ModuleSettings.CLOCK_FONT_WEIGHT, 0)
                                if (!enabled) editor.putInt(ModuleSettings.BLUR_RADIUS, ModuleSettings.BLUR_DEFAULT)
                            }.commit()
                            ModuleSettings.MEDIA_FOLDERS.forEach { updateMediaFolder(it[0], enabled) }
                            val rootResults = SystemSetting.entries.map { it.apply(enabled) }
                            saved && rootResults.all { it }
                        }
                        context.contentResolver.notifyChange(ModuleSettings.URI, null)
                        systemEnabled = withContext(Dispatchers.IO) { SystemSetting.entries.any { it.read(context).first } }
                        version++
                        if (!success) Toast.makeText(context, "部分设置修改失败，请检查 root 授权", Toast.LENGTH_LONG).show()
                    } finally {
                        busy = false
                    }
                }
            }
        },
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
