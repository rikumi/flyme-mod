package dev.rikumi.flymemod

import android.content.res.Resources
import android.os.Build
import android.provider.Settings
import android.widget.ImageView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 采用 Flyme Settings 首页账号头像行的尺寸和排版，保留模块的设备信息来源。 */
@Composable
internal fun HomeDeviceBanner() {
    val context = LocalContext.current
    val model = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        ?.takeIf { it.isNotBlank() } ?: Build.MODEL
    // Flyme 设置首页的版本信息同样使用 Build.DISPLAY，无需 root 或阻塞式属性查询。
    val system = Build.DISPLAY.trim().let {
        if (it.contains("flyme", ignoreCase = true)) it else "Flyme $it"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = FLUIX_ROW_HPADDING, end = FLUIX_ROW_HPADDING, top = 24.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HomeDeviceAvatar()
        Column(modifier = Modifier.weight(1f).padding(start = FLUIX_ROW_HPADDING)) {
            BasicText(
                text = model,
                modifier = Modifier.fillMaxWidth(),
                style = MiuixTheme.textStyles.body1.copy(
                    fontSize = 17.sp, fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Start,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            BasicText(
                text = system,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                style = MiuixTheme.textStyles.body2.copy(
                    fontSize = 14.sp, fontWeight = FontWeight.Normal,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    textAlign = TextAlign.Start,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun HomeDeviceAvatar() {
    val context = LocalContext.current
    val logo = remember(context) {
        try {
            ContextCompat.getDrawable(context, R.drawable.home_device_flyme_logo)?.mutate()
        } catch (_: Resources.NotFoundException) {
            null
        }
    }
    // 视口紧贴气球轮廓，宽高比例与参考一致；左缘与普通设置项内容共用边距。
    AndroidView(
        modifier = Modifier.size(width = 40.dp, height = 43.2.dp),
        factory = { viewContext ->
            ImageView(viewContext).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        },
        update = { view ->
            view.setImageDrawable(logo ?: ContextCompat.getDrawable(context, R.drawable.home_device_avatar_fallback))
        },
    )
}
