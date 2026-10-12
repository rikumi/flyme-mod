package dev.rikumi.flymemod

// Match coloros-mod: remove empty folders only, and restore their media_rw ownership.
internal fun updateMediaFolder(folder: String, blocked: Boolean) {
    if (ModuleSettings.MEDIA_FOLDERS.none { it[0] == folder }) return
    if (blocked) {
        runRoot("rmdir /data/media/0/$folder 2>/dev/null; rmdir /storage/emulated/0/$folder 2>/dev/null")
    } else {
        runRoot("mkdir -p /data/media/0/$folder 2>/dev/null; chown 1023:1023 /data/media/0/$folder 2>/dev/null; chmod 0771 /data/media/0/$folder 2>/dev/null")
    }
}
