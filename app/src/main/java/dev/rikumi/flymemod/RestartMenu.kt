package dev.rikumi.flymemod

import android.widget.Toast
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Refresh

internal object RestartUiState {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var running = false
    var showLoading by mutableStateOf(false)
        private set

    fun execute(context: Context, command: String, blackout: Boolean = false, scopeRestart: Boolean = false, waitForFonts: Boolean = false) {
        if (running) return
        running = true
        showLoading = blackout
        val applicationContext = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        val preparing = AtomicBoolean(waitForFonts)
        val hint = Runnable {
            if (preparing.get()) Toast.makeText(applicationContext, R.string.ui_font_restart_preparing, Toast.LENGTH_SHORT).show()
        }
        if (waitForFonts) main.postDelayed(hint, 500)
        scope.launch {
            try {
                val success: Boolean? = withContext(Dispatchers.IO) {
                    fun restart(): Boolean = runCatching {
                        val resolvedCommand = if (scopeRestart) ScopeRestartPlan.command(applicationContext) else command
                        val process = ProcessBuilder("su", "-c", resolvedCommand)
                            .redirectErrorStream(true)
                            .start()
                        process.inputStream.bufferedReader().use { reader ->
                            while (reader.readLine() != null) { /* Drain command output. */ }
                        }
                        process.waitFor() == 0
                    }.getOrDefault(false)
                    if (waitForFonts) {
                        val completion = CompletableFuture<Boolean?>()
                        SystemFontStore.whenReady(applicationContext, action = {
                            preparing.set(false)
                            main.removeCallbacks(hint)
                            completion.complete(restart())
                        }, onFailure = { error ->
                            preparing.set(false)
                            main.removeCallbacks(hint)
                            android.util.Log.e("FlymeMod", "Zygote restart cancelled: fonts not ready", error)
                            completion.complete(null)
                        })
                        completion.get()
                    } else restart()
                }
                if (success == null) {
                    Toast.makeText(applicationContext, R.string.ui_font_restart_failed, Toast.LENGTH_LONG).show()
                } else if (!success) {
                    Toast.makeText(applicationContext, "重启失败，请检查 root 授权", Toast.LENGTH_SHORT).show()
                }
            } finally {
                preparing.set(false)
                main.removeCallbacks(hint)
                running = false
                showLoading = false
            }
        }
    }
}

@Composable
internal fun RestartMenu() {
    val context = LocalContext.current

    fun restartScope() {
        RestartUiState.execute(context, "", blackout = true, scopeRestart = true)
    }

    FluixActionMenu(
        icon = {
            Icon(
                painter = rememberVectorPainter(MiuixIcons.Refresh),
                contentDescription = "重启",
            )
        },
        items = listOf(
            ActionMenuItem("重启作用域", { restartScope() }),
            ActionMenuItem("重启 Zygote", { RestartUiState.execute(context,"setprop ctl.restart zygote", waitForFonts = true) }),
        ),
    )
}
