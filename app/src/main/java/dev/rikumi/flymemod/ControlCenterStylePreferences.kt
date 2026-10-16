package dev.rikumi.flymemod

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ControlCenterStylePreference() {
    val context = LocalContext.current
    val preferences = LocalModulePreferences.current
    var mode by remember { mutableIntStateOf(ModuleSettings.controlCenterStyle(preferences)) }
    var savedColor by remember {
        mutableIntStateOf(preferences.getInt(ModuleSettings.CONTROL_CENTER_ACTIVE_COLOR,
            ModuleSettings.CONTROL_CENTER_ACTIVE_COLOR_DEFAULT))
    }
    var expanded by remember { mutableStateOf(false) }
    FluixDropdownPreference(
        title = "控制中心 ColorOS 样式",
        options = listOf("不修改", "全新焕彩", "自选颜色"),
        selected = mode,
        onSelected = {
            mode = it
            expanded = it == 2
            preferences.edit().putInt(ModuleSettings.CONTROL_CENTER_STYLE, it)
                .putBoolean(ModuleSettings.WHITE_ACTIVE, it != 0).apply()
            context.contentResolver.notifyChange(ModuleSettings.URI, null)
        },
    )
    if (mode == 2) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = FLUIX_ROW_MIN_HEIGHT)
                .clickable { expanded = !expanded }
                .padding(horizontal = FLUIX_ROW_HPADDING, vertical = FLUIX_ROW_VPADDING),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText("激活态颜色", modifier = Modifier.weight(1f),
                style = MiuixTheme.textStyles.body1.copy(color = MiuixTheme.colorScheme.onSurface))
            Box(Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(Color(savedColor)))
        }
        AnimatedVisibility(visible = expanded) {
            ColorPickerEditor(savedColor, onDismiss = { expanded = false }) {
                savedColor = it
                preferences.edit().putInt(ModuleSettings.CONTROL_CENTER_ACTIVE_COLOR, it).apply()
                context.contentResolver.notifyChange(ModuleSettings.URI, null)
            }
        }
    }
}
