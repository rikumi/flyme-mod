package com.rikumi.flymemod

import android.content.Context
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Called only from IO tasks; a denied or failed command must not appear successful. */
internal fun runRoot(command: String): String? = runCatching {
    val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    if (process.waitFor() == 0) output else null
}.getOrNull()

internal enum class SystemSetting(
    val title: String, val min: Int, val max: Int, val step: Int, val recommended: Int,
) {
    LONG_PRESS("修改系统长按超时", 100, 600, 50, 300),
    ANIMATION("微调系统动画时长", 0, 20, 1, 15);

    fun read(context: Context): Pair<Boolean, Int> {
        val resolver = context.contentResolver
        return when (this) {
            LONG_PRESS -> {
                val value = Settings.Secure.getInt(resolver, "long_press_timeout", 500)
                (value != 500) to value.coerceIn(min, max)
            }
            ANIMATION -> {
                val scale = Settings.Global.getFloat(resolver, "animator_duration_scale", 1f)
                val enabled = listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")
                    .any { Settings.Global.getFloat(resolver, it, 1f) != 1f }
                enabled to (scale * 20).roundToInt().coerceIn(min, max)
            }
        }
    }

    fun apply(enabled: Boolean, value: Int = recommended): Boolean {
        val command = when (this) {
            LONG_PRESS -> "settings put secure long_press_timeout ${if (enabled) value.coerceIn(min, max) else 500}"
            ANIMATION -> {
                val scale = String.format(Locale.US, "%.2f", if (enabled) value.coerceIn(min, max) * 0.05f else 1f)
                "settings put global window_animation_scale $scale && " +
                    "settings put global transition_animation_scale $scale && " +
                    "settings put global animator_duration_scale $scale"
            }
        }
        return runRoot(command) != null
    }

    fun display(value: Int): String = when (this) {
        LONG_PRESS -> "${value}ms"
        ANIMATION -> String.format(Locale.US, "%.2f倍", value * 0.05f)
    }
}

@Composable
internal fun SystemSettingsPreferences() {
    FluixCard {
        SystemSetting.entries.forEachIndexed { index, setting ->
            key(setting) {
                if (index > 0) FluixItemDivider()
                SystemSettingRow(setting)
            }
        }
    }
}

@Composable
private fun SystemSettingRow(setting: SystemSetting) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checked by remember(setting) { mutableStateOf(false) }
    var value by remember(setting) { mutableStateOf(setting.recommended) }
    var expanded by remember(setting) { mutableStateOf(false) }
    var running by remember(setting) { mutableStateOf(false) }
    LaunchedEffect(setting) {
        val current = withContext(Dispatchers.IO) { setting.read(context) }
        checked = current.first
        value = current.second
    }
    fun apply(enabled: Boolean, target: Int) {
        if (running) return
        running = true
        scope.launch {
            try {
                val success = withContext(Dispatchers.IO) { setting.apply(enabled, target) }
                val current = withContext(Dispatchers.IO) { setting.read(context) }
                checked = current.first
                value = current.second
                if (!success) Toast.makeText(context, "修改失败，请检查 root 授权", Toast.LENGTH_SHORT).show()
            } finally {
                running = false
            }
        }
    }
    FluixSwitchPreference(
        checked = checked,
        onCheckedChange = {
            expanded = it
            apply(it, setting.recommended)
        },
        title = setting.title,
        onTitleClick = if (checked) ({ expanded = !expanded }) else null,
        leftTrailingContent = {
            if (checked) BasicText(
                text = setting.display(value),
                style = MiuixTheme.textStyles.body2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary),
                modifier = Modifier.padding(start = 12.dp),
            )
        },
        showDivider = checked,
    )
    AnimatedVisibility(
        visible = checked && expanded,
        enter = expandVertically(),
        exit = shrinkVertically(),
    ) {
        FluixSlider(
            value = (value - setting.min).toFloat() / (setting.max - setting.min),
            onValueChange = {
                if (!running) value = (setting.min +
                    (it * (setting.max - setting.min) / setting.step).roundToInt() * setting.step)
                    .coerceIn(setting.min, setting.max)
            },
            onValueChangeFinished = { apply(true, value) },
            modifier = Modifier.padding(horizontal = 26.dp, vertical = 18.dp),
        )
    }
}
