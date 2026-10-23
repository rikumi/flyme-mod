package dev.rikumi.flymemod

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun AppNavigationPreference(onSelectApplications: () -> Unit) {
    val context = LocalContext.current
    val preferences = LocalModulePreferences.current
    var enabled by remember { mutableStateOf(preferences.getBoolean(ModuleSettings.APP_IMMERSIVE_NAVIGATION, false)) }
    FluixSwitchPreference(
        title = "应用导航栏强制沉浸",
        subtitle = "由 SystemUI 实现，无需勾选应用作用域",
        checked = enabled,
        onCheckedChange = {
            preferences.edit().putBoolean(ModuleSettings.APP_IMMERSIVE_NAVIGATION, it).apply()
            enabled = it
            context.contentResolver.notifyChange(ModuleSettings.URI, null)
        },
    )
    if (enabled) {
        FluixItemDivider()
        Row(Modifier.fillMaxWidth().heightIn(min = FLUIX_ROW_MIN_HEIGHT)
            .clickable(onClick = onSelectApplications)
            .padding(horizontal = FLUIX_ROW_HPADDING, vertical = FLUIX_ROW_VPADDING),
            verticalAlignment = Alignment.CenterVertically) {
            BasicText("强制沉浸应用列表", style = MiuixTheme.textStyles.body1.copy(color = MiuixTheme.colorScheme.onSurface),
                modifier = Modifier.weight(1f))
            BasicText("${ModuleSettings.navigationApplications(preferences).size} 个应用",
                style = MiuixTheme.textStyles.body2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
        }
    }
}

