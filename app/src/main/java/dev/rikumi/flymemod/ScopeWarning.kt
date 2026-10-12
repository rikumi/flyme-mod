package dev.rikumi.flymemod

import android.content.pm.PackageManager
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun rememberMissingScopes(resumeRequest: Int): Boolean {
    val context = LocalContext.current
    val application = context.applicationContext as CameraSettingsBridge
    val scope = rememberCoroutineScope()
    var missing by remember { mutableStateOf(false) }
    var refreshJob by remember { mutableStateOf<Job?>(null) }
    val refresh: () -> Unit = {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            val result = withContext(Dispatchers.IO) {
                val services = application.xposedServices()
                if (services.isEmpty()) return@withContext null
                runCatching {
                    val recommended = ThemeColorTargets.recommended()
                    val installed = recommended.filterTo(mutableSetOf()) { packageName ->
                        if (packageName == "system") false else try {
                            context.packageManager.getApplicationInfo(packageName, 0).enabled
                        } catch (_: PackageManager.NameNotFoundException) { false }
                    }
                    val selected = services.flatMap { it.scope }.toSet()
                    ScopeRequirements.hasMissing(recommended, installed, selected)
                }.getOrNull()
            }
            if (result != null) missing = result
        }
    }
    DisposableEffect(application) {
        val observer = Runnable { scope.launch { refresh() } }
        application.addServiceObserver(observer)
        onDispose {
            application.removeServiceObserver(observer)
            refreshJob?.cancel()
        }
    }
    LaunchedEffect(resumeRequest) { refresh() }
    return missing
}

@Composable
internal fun ScopeWarningCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var opening by remember { mutableStateOf(false) }
    FluixScopeWarningCard {
        if (!opening) {
            opening = true
            scope.launch {
                try {
                    launchLsposed(context)
                } finally {
                    opening = false
                }
            }
        }
    }
}
