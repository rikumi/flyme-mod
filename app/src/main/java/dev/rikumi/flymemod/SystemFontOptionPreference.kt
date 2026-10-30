package dev.rikumi.flymemod

import android.widget.Toast
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun SystemFontOptionPreference(key: String, title: String) {
    val context = LocalContext.current.applicationContext
    val preferences = LocalModulePreferences.current
    val scope = rememberCoroutineScope()
    var checked by remember(key) { mutableStateOf(preferences.getBoolean(key, false)) }
    var busy by remember(key) { mutableStateOf(false) }
    FluixSwitchPreference(title = title, checked = checked, onCheckedChange = { target ->
        if (!busy) {
            busy = true
            val previous = checked
            val operation = SystemFontStore.enqueue {
                try {
                    check(preferences.edit().putBoolean(key, target).commit())
                    val services = (context as CameraSettingsBridge).xposedServices()
                    if (target) {
                        check(services.isNotEmpty()) { "Xposed service unavailable" }
                        services.forEach { SystemFontStore.sync(context, it) }
                    } else SystemFontMounts.scheduleRestore(context)
                } catch (error: Exception) {
                    preferences.edit().putBoolean(key, previous).commit()
                    throw error
                }
            }
            scope.launch {
                try {
                    operation.await()
                    checked = target
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Toast.makeText(context, R.string.ui_font_mount_failed, Toast.LENGTH_LONG).show()
                } finally { busy = false }
            }
        }
    })
}
