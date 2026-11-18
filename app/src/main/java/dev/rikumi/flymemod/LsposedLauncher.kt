package dev.rikumi.flymemod

import android.content.Context
import android.os.Build
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal suspend fun launchLsposed(context: Context) {
    val attempts = listOf(
        // Vector (LSPosed fork) loads its manager into com.android.shell.
        "am start -W -c org.matrix.vector.manager.LAUNCH_MANAGER com.android.shell/.BugreportWarningActivity",
        "am start -W -n org.lsposed.manager/.ui.activity.MainActivity",
    )
    val opened = withContext(Dispatchers.IO) {
        attempts.any { runRoot(it)?.contains("Status: ok") == true }
    }
    if (!opened) {
        val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            "android.telephony.action.SECRET_CODE" else "android.provider.Telephony.SECRET_CODE"
        withContext(Dispatchers.IO) {
            runRoot("am broadcast -a $action -d android_secret_code://5776733 android")
        }
    }
    withContext(Dispatchers.Main) {
        Toast.makeText(
            context,
            if (opened) "已打开 LSPosed 管理器"
            else "已发送意图；若未打开，请在拨号盘输入 *#*#5776733#*#*",
            Toast.LENGTH_SHORT,
        ).show()
    }
}
