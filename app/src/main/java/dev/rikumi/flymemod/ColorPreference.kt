package dev.rikumi.flymemod

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.HsvHueSlider
import top.yukonga.miuix.kmp.basic.HsvSaturationSlider
import top.yukonga.miuix.kmp.basic.HsvValueSlider
import top.yukonga.miuix.kmp.basic.HsvAlphaSlider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField

/** Shared palette, hexadecimal input and explicit apply/cancel for all color settings. */
@Composable
internal fun ColorPickerEditor(
    savedColor: Int,
    opaque: Boolean = false,
    onDismiss: () -> Unit,
    onApply: (Int) -> Unit,
) {
    val context = LocalContext.current
    var selected by remember(savedColor) { mutableStateOf(Color(savedColor)) }
    var hex by remember(savedColor) {
        mutableStateOf(savedColor.toUInt().toString(16).padStart(8, '0').uppercase().let {
            if (opaque) it.takeLast(6) else it
        })
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = FLUIX_ROW_HPADDING, vertical = FLUIX_ROW_VPADDING)) {
        SmoothColorPicker(color = selected, opaque = opaque, onColorChanged = {
            selected = if (opaque) it.copy(alpha = 1f) else it
            hex = selected.toArgb().toUInt().toString(16).padStart(8, '0').uppercase().let { value ->
                if (opaque) value.takeLast(6) else value
            }
        })
        TextField(
            value = hex,
            onValueChange = { value ->
                hex = value.removePrefix("#").filter { it in "0123456789abcdefABCDEF" }
                    .take(if (opaque) 6 else 8).uppercase()
                if (hex.length == 6 || (!opaque && hex.length == 8)) {
                    selected = Color((hex.toLong(16) or (if (hex.length == 6) 0xff000000L else 0L)).toInt())
                }
            },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            label = if (opaque) "RGB" else "ARGB / RGB",
            singleLine = true,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(text = "取消", modifier = Modifier.weight(1f), onClick = onDismiss)
            TextButton(text = "确认", modifier = Modifier.weight(1f), onClick = {
                if (hex.length == 6 || (!opaque && hex.length == 8)) {
                    onApply(selected.toArgb())
                    onDismiss()
                } else Toast.makeText(context, if (opaque) "请输入六位十六进制颜色值" else "请输入六位或八位十六进制颜色值", Toast.LENGTH_SHORT).show()
            })
        }
    }
}

/** Float HSV state preserves continuous dragging, including hue selection for gray colors. */
@Composable
private fun SmoothColorPicker(color: Color, opaque: Boolean, onColorChanged: (Color) -> Unit) {
    val initial = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(color.toArgb(), it) } }
    var hue by remember { mutableFloatStateOf(initial[0]) }
    var saturation by remember { mutableFloatStateOf(initial[1]) }
    var value by remember { mutableFloatStateOf(initial[2]) }
    var alpha by remember { mutableFloatStateOf(if (opaque) 1f else color.alpha) }
    var lastEmitted by remember { mutableIntStateOf(color.toArgb()) }
    LaunchedEffect(color) {
        // Hexadecimal edits synchronize the sliders; our own events retain float precision.
        val argb = color.toArgb()
        if (argb != lastEmitted) {
            val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(argb, it) }
            if (hsv[1] > 0f) hue = hsv[0]
            saturation = hsv[1]
            value = hsv[2]
            alpha = if (opaque) 1f else color.alpha
            lastEmitted = argb
        }
    }
    fun emit() {
        val selected = Color.hsv(hue, saturation, value, if (opaque) 1f else alpha)
        lastEmitted = selected.toArgb()
        onColorChanged(selected)
    }
    Box(Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(8.dp)).background(color))
    Column(Modifier.padding(top = 12.dp)) {
        val haptic = SliderDefaults.SliderHapticEffect.None
        HsvHueSlider(currentHue = hue, onHueChanged = { hue = (it * 360f).coerceIn(0f, 360f); emit() }, hapticEffect = haptic)
        HsvSaturationSlider(currentHue = hue, currentSaturation = saturation,
            onSaturationChanged = { saturation = it; emit() }, hapticEffect = haptic)
        HsvValueSlider(currentHue = hue, currentSaturation = saturation, currentValue = value,
            onValueChanged = { value = it; emit() }, hapticEffect = haptic)
        if (!opaque) HsvAlphaSlider(currentHue = hue, currentSaturation = saturation,
            currentValue = value, currentAlpha = alpha,
            onAlphaChanged = { alpha = it; emit() }, hapticEffect = haptic)
    }
}

@Composable
internal fun ColorSettingRow(item: ColorItem) {
    val context = LocalContext.current
    val preferences = remember(context) { ModuleSettings.preferences(context) }
    var enabled by remember(item.key) { mutableStateOf(preferences.getBoolean(item.key, false)) }
    var color by remember(item.colorKey) { mutableIntStateOf(preferences.getInt(item.colorKey, item.defaultColor)) }
    var expanded by remember(item.key) { mutableStateOf(false) }
    FluixSwitchPreference(
        title = item.title,
        checked = enabled,
        onCheckedChange = {
            enabled = it
            expanded = it
            val editor = preferences.edit().putBoolean(item.key, it)
            editor.apply()
            context.contentResolver.notifyChange(ModuleSettings.URI, null)
        },
        onTitleClick = if (enabled) ({ expanded = !expanded }) else null,
        leftTrailingContent = {
            if (enabled) Box(Modifier.padding(start = 12.dp).size(22.dp)
                .clip(RoundedCornerShape(6.dp)).background(Color(color)))
        },
        showDivider = enabled && expanded,
    )
    AnimatedVisibility(visible = enabled && expanded) {
        ColorPickerEditor(color, opaque = true, onDismiss = { expanded = false }) {
            color = it or 0xff000000.toInt()
            val editor = preferences.edit().putInt(item.colorKey, color)
            editor.apply()
            context.contentResolver.notifyChange(ModuleSettings.URI, null)
        }
    }
}
