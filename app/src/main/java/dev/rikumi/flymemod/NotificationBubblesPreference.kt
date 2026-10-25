package dev.rikumi.flymemod

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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
internal fun NotificationBubblesPreference() {
    val context = LocalContext.current
    val resolver = context.contentResolver
    val key = "notification_bubbles"
    fun disabled() = Settings.Secure.getInt(resolver, key, 1) == 0
    var checked by remember(resolver) { mutableStateOf(disabled()) }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { checked = disabled() }
        }
        resolver.registerContentObserver(Settings.Secure.getUriFor(key), false, observer)
        checked = disabled()
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    FluixSwitchPreference(
        title = "禁用通知气泡",
        checked = checked,
        onCheckedChange = { target ->
            if (!running) {
                running = true
                scope.launch {
                    try {
                        val success = withContext(Dispatchers.IO) {
                            runRoot("settings --user current put secure notification_bubbles ${if (target) 0 else 1}") != null
                        }
                        checked = disabled()
                        if (!success || checked != target) {
                            Toast.makeText(context, "修改失败，请检查 root 授权", Toast.LENGTH_SHORT).show()
                        }
                    } finally {
                        running = false
                    }
                }
            }
        },
    )
}
