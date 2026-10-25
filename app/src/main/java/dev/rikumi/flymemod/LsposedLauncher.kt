package dev.rikumi.flymemod

import android.content.Context
import android.os.Build
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal suspend fun launchLsposed(context: Context) {
    val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        "android.telephony.action.SECRET_CODE" else "android.provider.Telephony.SECRET_CODE"
    val success = withContext(Dispatchers.IO) {
        runRoot("am broadcast -a $action -d android_secret_code://5776733 android") != null
    }
    withContext(Dispatchers.Main) {
        Toast.makeText(
            context,
            if (success) "已发送意图，请等待 LSPosed 处理" else "启动失败，请检查 LSPosed 安装及 root 授权",
            Toast.LENGTH_SHORT,
        ).show()
    }
}
