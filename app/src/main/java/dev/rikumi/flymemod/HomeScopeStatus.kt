package dev.rikumi.flymemod

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun readScopeStatus(context: Context, bridge: CameraSettingsBridge): String? {
    val services = bridge.xposedServices()
    if (services.isEmpty()) return "未连接到 LSPosed，无法检测作用域"
    return try {
        val input = ScopeRequirements::class.java.classLoader
            ?.getResourceAsStream("META-INF/xposed/scope.list")
            ?: error("Missing module scope declaration")
        val recommended = input.bufferedReader().useLines { lines ->
            lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()
        }
        val installed = recommended.filterTo(linkedSetOf()) { pkg ->
            if (pkg == "system") false else try {
                context.packageManager.getApplicationInfo(pkg, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) { false }
        }
        val selected = services.flatMap { it.scope }.toSet()
        val missing = ScopeRequirements.missing(recommended, installed, selected)
        if (missing.isEmpty()) null else "有作用域未勾选，请前往勾选"
    } catch (error: Exception) {
        Log.w("FlymeMod", "Cannot read module scope", error)
        "作用域检测失败，点击前往 LSPosed 检查"
    }
}

@Composable
internal fun HomeScopeStatus(resumeRequest: Int) {
    val context = LocalContext.current
    val bridge = context.applicationContext as CameraSettingsBridge
    val scope = rememberCoroutineScope()
    var serviceVersion by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }
    DisposableEffect(bridge) {
        val observer = Runnable { scope.launch { serviceVersion++ } }
        bridge.addServiceObserver(observer)
        onDispose { bridge.removeServiceObserver(observer) }
    }
    LaunchedEffect(resumeRequest, serviceVersion) {
        status = withContext(Dispatchers.IO) { readScopeStatus(context, bridge) }
    }
    var opening by remember { mutableStateOf(false) }
    status?.let { message ->
        FluixScopeWarningCard(message) {
            if (!opening) {
                opening = true
                scope.launch {
                    try { launchLsposed(context) }
                    finally { opening = false }
                }
            }
        }
    }
}
