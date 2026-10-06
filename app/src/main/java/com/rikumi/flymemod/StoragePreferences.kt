package com.rikumi.flymemod

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
internal fun StoragePreferences() {
    val context = LocalContext.current
    val preferences = remember(context) { ModuleSettings.preferences(context) }
    val scope = rememberCoroutineScope()
    FluixCard {
        ModuleSettings.MEDIA_FOLDERS.forEachIndexed { index, (folder, key) ->
            if (index > 0) FluixItemDivider()
            var checked by remember(key) { mutableStateOf(preferences.getBoolean(key, false)) }
            FluixSwitchPreference(
                checked = checked,
                onCheckedChange = {
                    checked = it
                    preferences.edit().putBoolean(key, it).apply()
                    context.contentResolver.notifyChange(ModuleSettings.URI, null)
                    scope.launch(Dispatchers.IO) { updateMediaFolder(folder, it) }
                },
                title = folder,
            )
        }
    }
}

// Match coloros-mod: remove empty folders only, and restore their media_rw ownership.
internal fun updateMediaFolder(folder: String, blocked: Boolean) {
    if (ModuleSettings.MEDIA_FOLDERS.none { it[0] == folder }) return
    if (blocked) {
        runRoot("rmdir /data/media/0/$folder 2>/dev/null; rmdir /storage/emulated/0/$folder 2>/dev/null")
    } else {
        runRoot("mkdir -p /data/media/0/$folder 2>/dev/null; chown 1023:1023 /data/media/0/$folder 2>/dev/null; chmod 0771 /data/media/0/$folder 2>/dev/null")
    }
}
