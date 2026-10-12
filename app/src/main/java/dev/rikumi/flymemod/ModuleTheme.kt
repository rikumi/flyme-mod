package dev.rikumi.flymemod

import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import top.yukonga.miuix.kmp.theme.Colors

internal data class ModuleThemeSettings(val accent: Color?, val blackBackground: Boolean)

/** Local UI observes the same setting without requiring an injected module process. */
@Composable
internal fun rememberModuleThemeSettings(): ModuleThemeSettings {
    val context = LocalContext.current
    val preferences = remember(context) { ModuleSettings.preferences(context) }
    fun read(): ModuleThemeSettings {
        val accent = if (preferences.getBoolean(ModuleSettings.SYSTEM_THEME_COLOR_ENABLED, false)) {
            Color(preferences.getInt(ModuleSettings.SYSTEM_THEME_COLOR, ModuleSettings.SYSTEM_THEME_COLOR_DEFAULT)).copy(alpha = 1f)
        } else null
        return ModuleThemeSettings(accent, preferences.getBoolean(ModuleSettings.DARK_SYSTEM_APP_BLACK_BACKGROUND, false))
    }
    var settings by remember(preferences) { mutableStateOf(read()) }
    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == ModuleSettings.SYSTEM_THEME_COLOR_ENABLED || key == ModuleSettings.SYSTEM_THEME_COLOR
                || key == ModuleSettings.DARK_SYSTEM_APP_BLACK_BACKGROUND) settings = read()
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        settings = read()
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return settings
}

internal fun moduleAccentColors(base: Colors, accent: Color?): Colors {
    if (accent == null) return base
    val foreground = if (accent.luminance() > 0.179f) Color.Black else Color.White
    val variant = lerp(accent, base.surface, 0.12f)
    val variantForeground = if (variant.luminance() > 0.179f) Color.Black else Color.White
    val disabled = lerp(accent, base.surface, 0.65f)
    return base.copy(
        primary = accent,
        onPrimary = foreground,
        primaryVariant = variant,
        onPrimaryVariant = variantForeground,
        primaryContainer = accent,
        onPrimaryContainer = foreground,
        disabledPrimary = disabled,
        disabledOnPrimary = lerp(foreground, base.surface, 0.65f),
        disabledPrimaryButton = disabled,
        disabledOnPrimaryButton = lerp(foreground, base.surface, 0.65f),
        disabledPrimarySlider = disabled,
    )
}
