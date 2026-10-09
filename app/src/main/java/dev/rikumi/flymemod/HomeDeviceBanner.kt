package dev.rikumi.flymemod

import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 与 ColorOS Mod 一样，横幅位于首页主开关卡片顶部，显示系统保存的设备名和版本。 */
@Composable
internal fun HomeDeviceBanner() {
    val context = LocalContext.current
    val model = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        ?.takeIf { it.isNotBlank() } ?: Build.MODEL
    // Flyme 设置首页的版本信息同样使用 Build.DISPLAY，无需 root 或阻塞式属性查询。
    val system = Build.DISPLAY.trim().let {
        if (it.contains("flyme", ignoreCase = true)) it else "Flyme $it"
    }
    val shadow = Shadow(Color(0x66000000), Offset(0f, 1f), 10f)
    val textColor = if (isSystemInDarkTheme()) Color.White else MiuixTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .clip(RoundedCornerShape(topStart = FLUIX_CARD_CORNER, topEnd = FLUIX_CARD_CORNER)),
    ) {
        Image(
            painter = painterResource(R.drawable.flyme_home_banner),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        // 与 ColorOS Mod 一致：设备名居中并上移 16dp，系统信息距底边 24dp。
        Box(
            modifier = Modifier.matchParentSize().offset(y = (-16).dp).padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = model,
                style = MiuixTheme.textStyles.title1.copy(
                    color = textColor, fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center, shadow = shadow,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier = Modifier.matchParentSize().padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            BasicText(
                text = system,
                style = MiuixTheme.textStyles.body1.copy(
                    fontSize = 16.sp, fontWeight = FontWeight.Medium,
                    color = textColor.copy(alpha = 0.9f),
                    textAlign = TextAlign.Center, shadow = shadow,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
