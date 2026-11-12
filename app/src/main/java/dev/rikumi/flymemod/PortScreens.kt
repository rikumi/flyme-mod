package dev.rikumi.flymemod

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
private fun PortText(text: String, secondary: Boolean = false, modifier: Modifier = Modifier) {
    BasicText(text, modifier, style = if (secondary) MiuixTheme.textStyles.body2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        else MiuixTheme.textStyles.body1.copy(color = MiuixTheme.colorScheme.onSurface))
}

@Composable
private fun PortPage(title: String, onBack: () -> Unit, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Scaffold(topBar = { FluixTopAppBar(title = title, navigationIcon = {
        IconButton(onClick = onBack) { Icon(MiuixIcons.Back, "返回", tint = MiuixTheme.colorScheme.onSurface, modifier = Modifier.size(FLUIX_BACK_ICON)) }
    }) }) { padding -> LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp), content = content) }
}

@Composable
private fun PortAction(title: String, summary: String = "", enabled: Boolean = true, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = FLUIX_ROW_HPADDING, vertical = FLUIX_ROW_VPADDING)) {
        PortText(title)
        if (summary.isNotEmpty()) PortText(summary, secondary = true)
    }
}

@Composable
internal fun HiddenNotificationsScreen(context: Context, onBack: () -> Unit) {
    val state by HiddenNotificationsService.state.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var access by remember { mutableStateOf(HiddenNotificationsService.hasAccess(context)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) {
            access = HiddenNotificationsService.hasAccess(context)
            HiddenNotificationsService.refresh(context)
        } }
        lifecycle.addObserver(observer)
        HiddenNotificationsService.refresh(context)
        onDispose { lifecycle.removeObserver(observer) }
    }
    PortPage(context.getString(R.string.ui_category_hidden_notifications), onBack) {
        if (!access || !state.connected) item {
            FluixCard { PortAction(context.getString(R.string.hidden_notifications_permission), context.getString(R.string.hidden_notifications_permission_hint)) {
                try { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                catch (_: RuntimeException) { Toast.makeText(context, R.string.hidden_notifications_settings_failed, Toast.LENGTH_LONG).show() }
            } }
        }
        item { FluixCard { PortAction(context.getString(R.string.hidden_notifications_refresh)) { HiddenNotificationsService.refresh(context) } } }
        state.error?.let { message -> item { FluixSmallTitle(context.getString(message)) } }
        listOf(true, false).forEach { hidden ->
            val entries = state.entries.filter { it.hidden == hidden }
            item { FluixSmallTitle(context.getString(if (hidden) R.string.hidden_notifications_hidden else R.string.hidden_notifications_visible)) }
            if (state.loaded && entries.isEmpty()) item { FluixSmallTitle(context.getString(if (hidden) R.string.hidden_notifications_no_hidden else R.string.hidden_notifications_no_visible)) }
            items(entries, key = { it.key }) { entry ->
                FluixCard {
                    HiddenNotificationRow(context, entry, state.busyKey == null,
                        state.busyKey == entry.key) { HiddenNotificationsService.toggle(entry) }
                }
            }
        }
    }
}

@Composable
private fun HiddenNotificationRow(
    ctx: Context,
    entry: HiddenNotificationEntry,
    enabled: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
) {
    val action = ctx.getString(if (entry.hidden) R.string.hidden_notifications_restore else R.string.hidden_notifications_hide)
    val fallbackTitle = remember(ctx, entry.key, entry.channelId, entry.channelName) {
        hiddenNotificationFallbackTitle(ctx, entry)
    }
    val channelName = entry.channelName.ifBlank { fallbackTitle }
        .ifBlank { ctx.getString(R.string.hidden_notifications_unknown_channel) }
    val channelIdLabel = ctx.getString(R.string.hidden_notifications_channel, "", entry.notificationId)
    val appName = entry.appName.takeUnless { it == entry.packageName }.orEmpty()
    val detailStyle = MiuixTheme.textStyles.body2.copy(fontSize = 12.sp, lineHeight = 16.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    Row(modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClickLabel = action, onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        entry.icon?.let {
            Image(it.asImageBitmap(), contentDescription = null,
                modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)))
            Box(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            val title = entry.title.ifBlank { fallbackTitle }.ifBlank {
                if (!entry.posted && entry.text.isBlank())
                    ctx.getString(R.string.hidden_notifications_content_unrecorded)
                else appName.ifBlank { channelName }
            }
            BasicText(title,
                style = MiuixTheme.textStyles.body1.copy(color = MiuixTheme.colorScheme.onSurface),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (entry.text.isNotBlank()) {
                BasicText(entry.text, style = detailStyle, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText(if (appName.isBlank()) channelName else "$appName · $channelName", style = detailStyle,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                BasicText(channelIdLabel, style = detailStyle, maxLines = 1, softWrap = false)
            }
        }
        BasicText(ctx.getString(when {
            busy -> R.string.hidden_notifications_processing
            entry.hidden -> R.string.hidden_notifications_state_hidden
            else -> R.string.hidden_notifications_state_visible
        }),
            style = detailStyle, modifier = Modifier.padding(start = 12.dp))
    }
}
