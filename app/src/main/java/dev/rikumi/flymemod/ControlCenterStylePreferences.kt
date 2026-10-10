package dev.rikumi.flymemod

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ColorPalette
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ControlCenterStylePreference() {
    val context = LocalContext.current
    val preferences = remember(context) { ModuleSettings.preferences(context) }
    var mode by remember { mutableIntStateOf(ModuleSettings.controlCenterStyle(preferences)) }
    var savedColor by remember {
        mutableIntStateOf(preferences.getInt(ModuleSettings.CONTROL_CENTER_ACTIVE_COLOR,
            ModuleSettings.CONTROL_CENTER_ACTIVE_COLOR_DEFAULT))
    }
    var selected by remember { mutableStateOf(Color(savedColor)) }
    var hex by remember { mutableStateOf(savedColor.toUInt().toString(16).padStart(8, '0').uppercase()) }
    var expanded by remember { mutableStateOf(false) }
    fun reset() {
        selected = Color(savedColor)
        hex = savedColor.toUInt().toString(16).padStart(8, '0').uppercase()
    }
    FluixDropdownPreference(
        title = "控制中心 ColorOS 样式",
        options = listOf("不修改", "全新焕彩", "自选颜色"),
        selected = mode,
        onSelected = {
            mode = it
            reset()
            expanded = it == 2
            preferences.edit().putInt(ModuleSettings.CONTROL_CENTER_STYLE, it)
                .putBoolean(ModuleSettings.WHITE_ACTIVE, it != 0).apply()
            context.contentResolver.notifyChange(ModuleSettings.URI, null)
        },
    )
    if (mode == 2) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = FLUIX_ROW_MIN_HEIGHT)
                .clickable { reset(); expanded = !expanded }
                .padding(horizontal = FLUIX_ROW_HPADDING, vertical = FLUIX_ROW_VPADDING),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText("激活态颜色", modifier = Modifier.weight(1f),
                style = MiuixTheme.textStyles.body1.copy(color = MiuixTheme.colorScheme.onSurface))
            Box(Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(Color(savedColor)))
        }
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.fillMaxWidth().padding(horizontal = FLUIX_ROW_HPADDING, vertical = FLUIX_ROW_VPADDING)) {
                // Same palette, hex entry and explicit apply/cancel behavior as ColorOS Mod.
                ColorPalette(color = selected, onColorChanged = {
                    selected = it
                    hex = it.toArgb().toUInt().toString(16).padStart(8, '0').uppercase()
                })
                TextField(
                    value = hex,
                    onValueChange = { value ->
                        hex = value.removePrefix("#").filter { it in "0123456789abcdefABCDEF" }.take(8).uppercase()
                        if (hex.length == 6 || hex.length == 8) {
                            val argb = hex.toLong(16) or (if (hex.length == 6) 0xff000000L else 0L)
                            selected = Color(argb.toInt())
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    label = "ARGB / RGB",
                    singleLine = true,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(text = "取消", modifier = Modifier.weight(1f), onClick = { reset(); expanded = false })
                    TextButton(text = "确认", modifier = Modifier.weight(1f), onClick = {
                        if (hex.length == 6 || hex.length == 8) {
                            savedColor = selected.toArgb()
                            preferences.edit().putInt(ModuleSettings.CONTROL_CENTER_ACTIVE_COLOR, savedColor).apply()
                            context.contentResolver.notifyChange(ModuleSettings.URI, null)
                            expanded = false
                        } else Toast.makeText(context, "请输入六位或八位十六进制颜色值", Toast.LENGTH_SHORT).show()
                    })
                }
            }
        }
    }
}
