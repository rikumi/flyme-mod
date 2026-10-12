package dev.rikumi.flymemod

import android.widget.Toast
import android.content.Context
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

/** Keep an in-flight restart and its loading screen across Activity recreation. */
internal object RestartUiState {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var running = false
    var showLoading by mutableStateOf(false)
        private set

    fun execute(context: Context, command: String, blackout: Boolean = false, scopeRestart: Boolean = false) {
        if (running) return
        running = true
        showLoading = blackout
        val applicationContext = context.applicationContext
        scope.launch {
            try {
                val success = withContext(Dispatchers.IO) {
                    runCatching {
                        val resolvedCommand = if (scopeRestart) ScopeRestartPlan.command(applicationContext) else command
                        val process = ProcessBuilder("su", "-c", resolvedCommand)
                            .redirectErrorStream(true)
                            .start()
                        // 持续读取输出，防止子进程的输出缓冲区阻塞。
                        process.inputStream.bufferedReader().use { reader ->
                            while (reader.readLine() != null) { /* 丢弃命令输出 */ }
                        }
                        process.waitFor() == 0
                    }.getOrDefault(false)
                }
                if (!success) {
                    Toast.makeText(applicationContext, "重启失败，请检查 root 授权", Toast.LENGTH_SHORT).show()
                }
            } finally {
                running = false
                showLoading = false
            }
        }
    }
}

/** 首页和二级页面共用的重启菜单，操作仅在用户点击菜单项时执行。 */
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
            ActionMenuItem("重启 Zygote", { RestartUiState.execute(context,"setprop ctl.restart zygote") }),
        ),
    )
}
