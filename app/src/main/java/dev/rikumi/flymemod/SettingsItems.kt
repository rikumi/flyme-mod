package dev.rikumi.flymemod

import android.content.SharedPreferences
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.theme.MiuixTheme

// Initialize/migrate preferences once per Activity, not while each lazy row is composed.
internal val LocalModulePreferences = staticCompositionLocalOf<SharedPreferences> {
    error("Module preferences must be provided by MainActivity")
}

internal sealed interface SettingsItem {
    val key: String
}

internal data class SettingsGroup(val title: String, val items: List<SettingsItem>)

internal data class SliderSpec(
    val key: String,
    val default: Int,
    val min: Int,
    val max: Int,
    val unit: String = "dp",
    // 所有普通滑条即时保存；部分设置仅在松手时通知 hook。
    val dividerOnlyWhenExpanded: Boolean = false,
    val format: (Int) -> String = { "$it$unit" },
    val step: Int = 1,
) {
    fun snap(value: Int): Int =
        (min + ((value.coerceIn(min, max) - min).toFloat() / step).roundToInt() * step)
            .coerceIn(min, max)
}

internal data class SwitchItem(
    override val key: String,
    val title: String,
    val subtitle: String? = null,
    val slider: SliderSpec? = null,
    val notifyChange: Boolean = true,
    val defaultEnabled: Boolean = false,
    val children: List<SettingsItem> = emptyList(),
    val enabledSubtitle: String? = null,
) : SettingsItem

internal data class SelectItem(
    override val key: String,
    val title: String,
    val options: List<String>,
    val values: List<Int> = options.indices.toList(),
    val defaultValue: Int = values.first(),
) : SettingsItem

internal data class ColorItem(
    override val key: String,
    val title: String,
    val colorKey: String,
    val defaultColor: Int,
) : SettingsItem

internal data class FolderBlockItem(override val key: String, val folder: String) : SettingsItem
internal data class SystemItem(val setting: SystemSetting) : SettingsItem {
    override val key = "system/${setting.name}"
}

internal data class SettingsActions(
    val onSelectNavigationApplications: () -> Unit = {},
    val onSelectGameApplications: () -> Unit = {},
)

internal data class ActionItem(
    override val key: String,
    val title: String,
    val onClick: (SettingsActions) -> Unit,
) : SettingsItem

// 仅用于具有独立状态或预览的专用交互；普通设置使用以上声明。
internal data class CustomItem(
    override val key: String,
    val content: @Composable (SettingsActions) -> Unit,
) : SettingsItem

@Composable
internal fun SettingsCard(items: List<SettingsItem>, actions: SettingsActions = SettingsActions()) {
    if (items.isEmpty()) return
    FluixCard {
        items.forEachIndexed { index, item ->
            key(item.key) {
                if (index > 0) FluixItemDivider()
                SettingsItemRow(item, actions)
            }
        }
    }
}

@Composable
internal fun SettingsItemRow(item: SettingsItem, actions: SettingsActions = SettingsActions()) {
    key(item.key) {
        when (item) {
            is SwitchItem -> SwitchSettingRow(item, actions)
            is SelectItem -> SelectSettingRow(item)
            is ColorItem -> ColorSettingRow(item)
            is FolderBlockItem -> {
                val scope = rememberCoroutineScope()
                SwitchSettingRow(SwitchItem(item.key, item.folder)) { blocked ->
                    scope.launch(Dispatchers.IO) { updateMediaFolder(item.folder, blocked) }
                }
            }
            is SystemItem -> SystemSettingRow(item.setting)
            is ActionItem -> Row(
                Modifier.fillMaxWidth().heightIn(min = FLUIX_ROW_MIN_HEIGHT)
                    .clickable { item.onClick(actions) }
                    .padding(horizontal = FLUIX_ROW_HPADDING, vertical = FLUIX_ROW_VPADDING),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(item.title, style = MiuixTheme.textStyles.body1.copy(color = MiuixTheme.colorScheme.onSurface))
            }
            is CustomItem -> item.content(actions)
        }
    }
}

@Composable
private fun SwitchSettingRow(
    item: SwitchItem,
    actions: SettingsActions = SettingsActions(),
    onChanged: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val preferences = LocalModulePreferences.current
    var checked by remember(preferences, item.key) { mutableStateOf(preferences.getBoolean(item.key, item.defaultEnabled)) }
    var expanded by rememberSaveable(item.key) { mutableStateOf(false) }
    val slider = item.slider
    var value by remember(preferences, slider?.key) {
        mutableStateOf(if (slider == null) 0 else
            slider.snap(preferences.getInt(slider.key, slider.default)))
    }
    FluixSwitchPreference(
        title = item.title,
        subtitle = if (checked) item.enabledSubtitle ?: item.subtitle else item.subtitle,
        checked = checked,
        onCheckedChange = {
            checked = it
            expanded = it
            preferences.edit().putBoolean(item.key, it).apply()
            if (item.notifyChange) context.contentResolver.notifyChange(ModuleSettings.URI, null)
            if (item.key == ModuleSettings.LAUNCHER_EXTRA_ROW) {
                android.widget.Toast.makeText(context, "重启桌面后生效，两套桌面布局分别保存", android.widget.Toast.LENGTH_LONG).show()
            }
            onChanged(it)
        },
        onTitleClick = if (checked && slider != null) ({ expanded = !expanded }) else null,
        leftTrailingContent = {
            if (checked && slider != null) BasicText(
                slider.format(value),
                style = MiuixTheme.textStyles.body2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary),
                modifier = Modifier.padding(start = 12.dp),
            )
        },
        showDivider = checked && slider != null && (!slider.dividerOnlyWhenExpanded || expanded),
    )
    if (slider != null) {
        AnimatedVisibility(visible = checked && expanded, enter = expandVertically(), exit = shrinkVertically()) {
            FluixSlider(
                value = (value - slider.min).toFloat() / (slider.max - slider.min),
                onValueChange = {
                    value = slider.snap((slider.min + it * (slider.max - slider.min)).roundToInt())
                },
                onValueChangeFinished = {
                    preferences.edit().putInt(slider.key, value).apply()
                    context.contentResolver.notifyChange(ModuleSettings.URI, null)
                },
                modifier = Modifier.padding(horizontal = 26.dp, vertical = 18.dp),
            )
        }
    }
    if (checked) {
        item.children.forEach { child ->
            key(child.key) {
                FluixItemDivider()
                SettingsItemRow(child, actions)
            }
        }
    }
}

@Composable
private fun SelectSettingRow(item: SelectItem) {
    val context = LocalContext.current
    val preferences = LocalModulePreferences.current
    var selected by remember(preferences, item.key) {
        val value = preferences.getInt(item.key, item.defaultValue)
        // 顺序型选项沿用原来的边界截断；字重等非连续取值回退到首项。
        mutableStateOf(if (item.values == item.options.indices.toList()) value.coerceIn(item.options.indices)
            else item.values.indexOf(value).coerceAtLeast(0))
    }
    FluixDropdownPreference(
        title = item.title,
        options = item.options,
        selected = selected,
        onSelected = {
            selected = it
            preferences.edit().putInt(item.key, item.values[it]).apply()
            context.contentResolver.notifyChange(ModuleSettings.URI, null)
        },
    )
}
