package dev.rikumi.flymemod

import android.content.SharedPreferences
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
    val checked = remember(version) { OneClickPreset.isEnabled(preferences) }
    FluixMasterToggle(
        checked = checked,
        title = if (checked) "启用模块" else "一键启用",
        subtitle = if (checked) null else "应用开发者的默认设置",
        onCheckedChange = { enabled ->
            if (!busy) {
                busy = true
                scope.launch {
                    try {
                        val success = withContext(Dispatchers.IO) {
                            val saved = preferences.edit().also {
                                OneClickPreset.apply(it, enabled)
                            }.commit()
                            if (saved) ModuleSettings.MEDIA_FOLDERS.forEach { (folder, key) ->
                                updateMediaFolder(folder, preferences.getBoolean(key, false))
                            }
                            saved
                        }
                        context.contentResolver.notifyChange(ModuleSettings.URI, null)
                        version++
                        if (!success) Toast.makeText(context, "设置保存失败", Toast.LENGTH_LONG).show()
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
                        launchLsposed(context)
                    }
                },
            )
        },
    )
}
