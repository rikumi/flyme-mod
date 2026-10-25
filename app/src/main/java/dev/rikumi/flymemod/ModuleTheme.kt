package dev.rikumi.flymemod

import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

internal data class ModuleThemeSettings(val blackBackground: Boolean)

@Composable
internal fun rememberModuleThemeSettings(): ModuleThemeSettings {
    val preferences = LocalModulePreferences.current
    fun read(): ModuleThemeSettings {
        return ModuleThemeSettings(preferences.getBoolean(ModuleSettings.DARK_SYSTEM_APP_BLACK_BACKGROUND, false))
    }
    var settings by remember(preferences) { mutableStateOf(read()) }
    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == ModuleSettings.DARK_SYSTEM_APP_BLACK_BACKGROUND) settings = read()
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        settings = read()
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return settings
}
