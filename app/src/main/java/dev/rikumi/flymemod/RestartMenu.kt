package dev.rikumi.flymemod

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Refresh

/** 首页和二级页面共用的重启菜单，操作仅在用户点击菜单项时执行。 */
@Composable
internal fun RestartMenu() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }

    fun execute(command: String) {
        if (running) return
        running = true
        scope.launch {
            try {
                val success = withContext(Dispatchers.IO) {
                    runCatching {
                        val process = ProcessBuilder("su", "-c", command)
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
                    Toast.makeText(context, "重启失败，请检查 root 授权", Toast.LENGTH_SHORT).show()
                }
            } finally {
                running = false
            }
        }
    }

    fun restartScope() {
        execute("pids=\$(pidof com.android.systemui com.meizu.flyme.launcher com.android.settings com.meizu.mstore com.meizu.flyme.weather com.android.packageinstaller); [ -n \"\$pids\" ] || exit 1; " +
            "for pid in \$pids; do kill -TERM \"\$pid\" || exit 1; sleep 2; done")
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
            ActionMenuItem("重启 Zygote", { execute("setprop ctl.restart zygote") }),
        ),
    )
}
