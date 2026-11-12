package dev.rikumi.flymemod

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun SystemFontPreference(key: String, title: String, options: List<String>, values: List<Int> = options.indices.toList()) {
    val context = LocalContext.current.applicationContext
    val preferences = LocalModulePreferences.current
    val scope = rememberCoroutineScope()
    var selected by remember(key) { mutableIntStateOf(values.indexOf(preferences.getInt(key, SystemFontHooks.defaultChoice(key))).coerceAtLeast(0)) }
    var busy by remember(key) { mutableStateOf(false) }
    fun save(action: () -> Unit) {
        if (busy) return
        busy = true
        val pending = SystemFontStore.enqueue(action)
        scope.launch {
            try {
                pending.await()
                selected = values.indexOf(preferences.getInt(key, SystemFontHooks.defaultChoice(key))).coerceAtLeast(0)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val message = when (error) {
                    is SystemFontStore.NotMonospaceFontException -> R.string.ui_font_not_monospace
                    is SystemFontStore.NotColorEmojiFontException -> R.string.ui_font_not_color_emoji
                    else -> R.string.ui_font_import_failed
                }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            } finally { busy = false }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) save { SystemFontStore.import(context, key, uri) }
    }
    FluixDropdownPreference(title, options, selected, onSelected = { index ->
        if (!busy) {
            if (values[index] == SystemFontStore.customIndex(key)) picker.launch(arrayOf(
                "font/*", "application/x-font-ttf", "application/x-font-opentype",
                "application/vnd.ms-opentype", "application/octet-stream", "*/*",
            ))
            else save { SystemFontStore.select(context, key, values[index]) }
        }
    })
}
