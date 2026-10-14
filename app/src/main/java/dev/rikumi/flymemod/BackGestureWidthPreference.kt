package dev.rikumi.flymemod

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun BackGestureWidthPreference() {
    val context = LocalContext.current
    val preferences = remember(context) { ModuleSettings.preferences(context) }
    val maximum = ModuleSettings.BACK_GESTURE_WIDTH_MAX_DEFAULT
    var enabled by remember { mutableStateOf(preferences.getBoolean(ModuleSettings.BACK_GESTURE_WIDTH_ENABLED, false)) }
    var amount by remember { mutableStateOf(preferences.getInt(ModuleSettings.BACK_GESTURE_WIDTH_DP,
        ModuleSettings.BACK_GESTURE_WIDTH_DEFAULT).coerceIn(0, maximum)) }
    var expanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var previewVisible by remember { mutableStateOf(false) }
    var hidePreview by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(enabled, expanded) {
        if (!enabled || !expanded) {
            hidePreview?.cancel()
            previewVisible = false
        }
    }
    BackGesturePreview(visible = enabled && expanded && previewVisible, amount = amount)
    FluixSwitchPreference(
        title = "调整返回手势区宽度",
        checked = enabled,
        onCheckedChange = {
            enabled = it
            expanded = it
            preferences.edit().putBoolean(ModuleSettings.BACK_GESTURE_WIDTH_ENABLED, it).apply()
            context.contentResolver.notifyChange(ModuleSettings.URI, null)
        },
        onTitleClick = if (enabled) ({ expanded = !expanded }) else null,
        leftTrailingContent = {
            if (enabled) BasicText("$amount dp",
                style = MiuixTheme.textStyles.body2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary),
                modifier = Modifier.padding(start = 12.dp))
        },
        showDivider = enabled,
    )
    AnimatedVisibility(visible = enabled && expanded, enter = expandVertically(), exit = shrinkVertically()) {
        FluixSlider(
            value = amount / maximum.toFloat(),
            onInteractionChange = { active ->
                hidePreview?.cancel()
                previewVisible = true
                if (!active) hidePreview = scope.launch {
                    delay(500)
                    previewVisible = false
                }
            },
            onValueChange = {
                val next = (it * maximum).roundToInt()
                    .coerceIn(0, maximum)
                if (next != amount) {
                    amount = next
                }
            },
            onValueChangeFinished = {
                preferences.edit().putInt(ModuleSettings.BACK_GESTURE_WIDTH_DP, amount).apply()
                context.contentResolver.notifyChange(ModuleSettings.URI, null)
            },
            modifier = Modifier.padding(horizontal = 26.dp, vertical = 18.dp),
        )
    }
}
