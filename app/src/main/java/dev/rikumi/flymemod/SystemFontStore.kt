package dev.rikumi.flymemod

import android.content.Context
import android.graphics.fonts.Font
import android.net.Uri
import android.os.ParcelFileDescriptor
import dev.rikumi.flymemod.SystemFontHooks
import io.github.libxposed.service.XposedService
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred

internal object SystemFontStore {
    private val changes = Executors.newSingleThreadExecutor { task ->
        Thread(task, "font-settings-sync").also { it.isDaemon = true }
    }
    private var changeFailure: Throwable? = null
    private val userRevision = AtomicLong()
    @Volatile private var preparedRevision = 0L

    // Enqueue from the UI callback, before launching a cancellable UI coroutine.
    fun enqueue(change: () -> Unit): Deferred<Unit> = enqueueOperation(userChange = true, change)

    // 服务连接/模块更新后的同步不属于用户修改，不应阻塞 Zygote 重启。
    fun enqueueSync(change: () -> Unit): Deferred<Unit> = enqueueOperation(userChange = false, change)

    private fun enqueueOperation(userChange: Boolean, change: () -> Unit): Deferred<Unit> {
        if (userChange) userRevision.incrementAndGet()
        val result = CompletableDeferred<Unit>()
        changes.execute {
            try {
                change()
                if (userChange) changeFailure = null
                result.complete(Unit)
            } catch (error: Throwable) {
                if (error is FontOperation.Cancelled) {
                    if (userChange) changeFailure = null
                    result.completeExceptionally(error)
                    return@execute
                }
                if (userChange) changeFailure = error
                FontFailureLog.record("font settings operation failed", error)
                result.completeExceptionally(error)
            }
        }
        return result
    }

    fun whenReady(context: Context, action: () -> Unit, onFailure: (Throwable) -> Unit) {
        val app = context.applicationContext
        val revision = userRevision.get()
        if (revision == preparedRevision) {
            // 不排在自动字体同步后面，也不主动重放已有挂载。
            Thread(action, "zygote-restart").start()
            return
        }
        changes.execute {
            try {
                changeFailure?.let { throw IllegalStateException("Previous font change failed", it) }
                SystemFontMounts.awaitPending(app)
                preparedRevision = revision
            } catch (error: Throwable) {
                onFailure(error)
                return@execute
            }
            action()
        }
    }

    class NotMonospaceFontException : IllegalArgumentException()
    class NotColorEmojiFontException : IllegalArgumentException()
    private val slots = listOf(SystemFontHooks.ZH, SystemFontHooks.LATIN, SystemFontHooks.MONO, SystemFontHooks.EMOJI)
    fun isFont(key: String) = key in slots
    fun customIndex(key: String) = when (key) {
        SystemFontHooks.ZH -> 2
        SystemFontHooks.LATIN -> 3
        SystemFontHooks.EMOJI -> SystemFontHooks.CUSTOM_EMOJI
        else -> 4
    }
    private fun directory(context: Context) = File(context.createDeviceProtectedStorageContext().filesDir, "system_fonts")
        .also { check(it.isDirectory || it.mkdirs()) }
    private fun cache(context: Context, key: String) = File(directory(context), "$key.font")
    private fun service(context: Context) = (context.applicationContext as CameraSettingsBridge).xposedServices().firstOrNull()
        ?: error("Xposed service unavailable")

    fun migrateChoices(context: Context) {
        val prefs = ModuleSettings.preferences(context)
        if (prefs.getInt(SystemFontHooks.ZH, 0) == 1) {
            check(prefs.edit().putInt(SystemFontHooks.ZH, 0).commit())
        }
        if (prefs.getInt(SystemFontHooks.EMOJI, SystemFontHooks.DEFAULT_EMOJI) in 5..6) {
            check(prefs.edit().putInt(SystemFontHooks.EMOJI, SystemFontHooks.DEFAULT_EMOJI).commit())
        }
    }

    @JvmStatic
    fun enqueueSync(context: Context, service: XposedService) {
        enqueueSync { sync(context.applicationContext, service) }
    }

    @Synchronized
    fun sync(context: Context, service: XposedService) {
        migrateChoices(context)
        val local = ModuleSettings.preferences(context)
        val remote = service.getRemotePreferences(SystemFontHooks.PREFS)
        val editor = remote.edit()
        for (key in slots) {
            val choice = local.getInt(key, SystemFontHooks.defaultChoice(key))
            if (key == SystemFontHooks.EMOJI && choice == 0) {
                editor.remove(key + "_file").putInt(key, choice)
                continue
            }
            val file = if (key == SystemFontHooks.EMOJI && choice > 0 && choice != customIndex(key)) EmojiFontStore.obtain(context, choice) else cache(context, key)
            if (file.isFile) editor.putString(key + "_file", publish(service, key, file))
            editor.putInt(key, choice)
        }
        val manifest = NativeFontBridge.prepare(context, service)
        editor.putString("native_manifest", manifest)
        check(editor.commit()) { "Cannot save font settings" }
        SystemFontMounts.schedule(context, service, manifest)
    }

    @Synchronized
    fun select(context: Context, key: String, value: Int) {
        val connectedService = (context.applicationContext as CameraSettingsBridge).xposedServices().firstOrNull()
        // The stock emoji font needs no imported file. Keep the reset locally
        // while disconnected; onServiceBind queues sync on this same executor.
        if (key == SystemFontHooks.EMOJI && value == 0 && connectedService == null) {
            check(ModuleSettings.preferences(context).edit().putInt(key, value).commit())
            return
        }
        val service = connectedService ?: error("Xposed service unavailable")
        val emoji = if (key == SystemFontHooks.EMOJI && value > 0 && value != customIndex(key)) EmojiFontStore.obtain(context, value) else null
        val emojiRemote = emoji?.let { publish(service, key, it) }
        val manifest = NativeFontBridge.prepare(context, service, key, value)
        val remote = service.getRemotePreferences(SystemFontHooks.PREFS)
        val operation = FontOperation.current()
        val editor = remote.edit().putInt(key, value)
            .putString("native_manifest", manifest)
        if (emojiRemote != null) editor.putString(key + "_file", emojiRemote)
        else if (key == SystemFontHooks.EMOJI && value == 0) editor.remove(key + "_file")
        operation?.beginApplying()
        check(editor.commit()) { "Cannot save font settings" }
        check(ModuleSettings.preferences(context).edit().putInt(key, value).commit())
        SystemFontMounts.schedule(context, service, manifest)
        SystemFontMounts.awaitPending(context)
        operation?.complete()
        if (key == SystemFontHooks.EMOJI && value > 0) notifyReady(context)
    }

    @Synchronized
    fun import(context: Context, key: String, uri: Uri) {
        require(isFont(key))
        val service = service(context)
        val temporary = File.createTempFile("import-", ".font", directory(context))
        var backup: File? = null
        var replaced = false
        var committed = false
        try {
            FontOperation.report(FontOperation.Step.READ_IMPORT, uri.toString())
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input)
                FontOperation.track(input)
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(65536)
                    var size = 0L
                    while (true) {
                        FontOperation.check()
                        val count = input.read(buffer)
                        if (count < 0) break
                        size += count
                        val limit = if (key == SystemFontHooks.EMOJI) FontFileInfo.MAX_BYTES else 100L * 1024 * 1024
                        require(size <= limit) { "Font exceeds size limit" }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            FontOperation.check()
            // Validate every TTC member with Android's parser before replacing the previous import.
            FontOperation.report(FontOperation.Step.PARSE_FONT, uri.toString())
            val faces = FontFileInfo.read(temporary)
            if (key == SystemFontHooks.MONO && faces.none { it.monospace }) throw NotMonospaceFontException()
            if (key == SystemFontHooks.EMOJI && faces.none { it.color }) throw NotColorEmojiFontException()
            for (face in faces) Font.Builder(temporary).setTtcIndex(face.index).build()
            val remote = service.getRemotePreferences(SystemFontHooks.PREFS)
            val oldFile = remote.getString(key + "_file", null)
            val name = publish(service, key, temporary)
            val manifest = NativeFontBridge.prepare(context, service, key, customIndex(key), temporary)
            val current = cache(context, key)
            FontOperation.report(FontOperation.Step.SAVE_FONT, current.name)
            if (current.exists()) {
                backup = File.createTempFile("previous-", ".font", directory(context))
                current.copyTo(backup, overwrite = true)
            }
            FontOperation.current()?.beginApplying()
            check(temporary.renameTo(current)) { "Cannot save font" }
            replaced = true
            check(remote.edit().putString(key + "_file", name).putInt(key, customIndex(key))
                .putString("native_manifest", manifest).commit()) {
                "Cannot save font settings"
            }
            committed = true
            check(ModuleSettings.preferences(context).edit().putInt(key, customIndex(key)).commit())
            SystemFontMounts.schedule(context, service, manifest)
            if (oldFile != null && oldFile != name) runCatching { service.deleteRemoteFile(oldFile) }
        } finally {
            if (replaced && !committed) {
                if (backup != null) check(backup.renameTo(cache(context, key))) { "Cannot restore previous font" }
                else cache(context, key).delete()
            }
            backup?.delete()
            temporary.delete()
        }
        notifyReady(context)
        FontOperation.current()?.complete()
    }

    private fun notifyReady(context: Context) {
        SystemFontMounts.awaitPending(context)
        val app = context.applicationContext
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(app, R.string.ui_font_emoji_ready, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun publish(service: XposedService, key: String, file: File): String {
        FontOperation.report(FontOperation.Step.PUBLISH_FONT, file.name)
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(65536)
            while (true) {
                FontOperation.check()
                val count = input.read(bytes)
                if (count < 0) break
                digest.update(bytes, 0, count)
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        val name = "${key}_$hash.font"
        if (name !in service.listRemoteFiles()) {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(service.openRemoteFile(name)).use { output ->
                    output.channel.truncate(0)
                    output.channel.position(0)
                    file.inputStream().use { FontOperation.copy(it, output) }
                    output.fd.sync()
                }
            } catch (error: Exception) {
                runCatching { service.deleteRemoteFile(name) }
                throw error
            }
        }
        return name
    }
}
