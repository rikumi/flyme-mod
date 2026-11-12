package dev.rikumi.flymemod
import android.content.Context
internal fun hiddenNotificationChannelIds(entry: HiddenNotificationEntry): List<String> = emptyList()
internal fun hiddenNotificationFallbackTitle(context: Context, entry: HiddenNotificationEntry): String = entry.channelName
