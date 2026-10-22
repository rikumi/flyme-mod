package dev.rikumi.flymemod

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun SystemFontPreference(key: String, title: String, options: List<String>) {
    val context = LocalContext.current
    val preferences = LocalModulePreferences.current
    val scope = rememberCoroutineScope()
    var selected by remember(key) { mutableIntStateOf(preferences.getInt(key, 0).coerceIn(options.indices)) }
    var busy by remember(key) { mutableStateOf(false) }
    fun save(action: () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { action() }
                selected = preferences.getInt(key, 0).coerceIn(options.indices)
            } catch (error: Exception) {
                val message = when (error) {
                    is SystemFontStore.NotMonospaceFontException -> "请选择包含等宽字形的字体"
                    else -> "字体保存失败，请检查字体文件及 LSPosed 服务连接"
                }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            } finally { busy = false }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) save { SystemFontStore.import(context, key, uri) }
    }
    FluixDropdownPreference(
        title = title,
        options = options,
        selected = selected,
        onSelected = { value ->
            if (!busy) {
                if (value == SystemFontStore.customIndex(key)) picker.launch(arrayOf(
                    "font/*", "application/x-font-ttf", "application/x-font-opentype",
                    "application/vnd.ms-opentype", "application/octet-stream", "*/*",
                ))
                else save { SystemFontStore.select(context, key, value) }
            }
        },
    )
}
