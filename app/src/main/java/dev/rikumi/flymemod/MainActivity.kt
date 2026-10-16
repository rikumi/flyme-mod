package dev.rikumi.flymemod

import android.os.Bundle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

private const val PAGE_TRANSITION_MS = 240
private const val DISABLED_APPS_ID = "disabled_apps"

private data class Category(
    val id: String,
    val title: String,
    val icon: Int,
    val iconForeground: Color,
)

private object SettingsHomeColors {
    // Flyme Settings 的 fd_sys_color_primary_* 主题色。
    val blueForeground = Color(0xFF206CFF)
    val cyanForeground = Color(0xFF02BEBF)
    val greenForeground = Color(0xFF35C44F)
    // Extracted from Flyme Settings res/drawable/mz_function_list_ic_display.xml.
    val yellowForeground = Color(0xFFFFBE0A)
    val orangeForeground = Color(0xFFFF7024)
    val redForeground = Color(0xFFE42D22)
}

private val CATEGORY_GROUPS: List<List<Category>> = listOf(
    listOf(
        Category("desktop", "桌面", R.drawable.ic_flyme_home_apps, SettingsHomeColors.blueForeground),
        Category("quick_settings", "控制中心", R.drawable.ic_flyme_home_wireless, SettingsHomeColors.cyanForeground),
        Category("notification", "通知中心与状态栏", R.drawable.ic_flyme_home_notifications, SettingsHomeColors.cyanForeground),
        Category("lockscreen", "锁屏", R.drawable.ic_flyme_home_security, SettingsHomeColors.greenForeground),
    ),
    listOf(
        Category("hidden_apps", "隐藏应用", R.drawable.ic_flyme_home_apps, SettingsHomeColors.yellowForeground),
        Category(DISABLED_APPS_ID, "停用应用", R.drawable.ic_flyme_home_disabled, SettingsHomeColors.yellowForeground),
        Category("navigation", "导航与手势", R.drawable.ic_flyme_home_gestures, SettingsHomeColors.yellowForeground),
    ),
    listOf(
        Category("system_cleanup", "系统净化", R.drawable.ic_flyme_home_cleaner, SettingsHomeColors.orangeForeground),
        Category("camera", "相机与杂项", R.drawable.ic_flyme_home_install, SettingsHomeColors.orangeForeground),
        Category("storage", "存储管理", R.drawable.ic_flyme_home_storage, SettingsHomeColors.redForeground),
    ),
)

private val CATEGORIES = CATEGORY_GROUPS.flatten()

class MainActivity : ComponentActivity() {
    private var requestedPage by mutableStateOf<String?>(null)
    private var pageRequest by mutableIntStateOf(0)
    private var resumeRequest by mutableIntStateOf(0)
    private var launcherEntry by mutableStateOf(false)

    private fun readPageRequest() {
        requestedPage = intent.getStringExtra(ModuleSettings.SETTINGS_PAGE_EXTRA)
        launcherEntry = requestedPage == "hidden_apps" &&
            intent.getBooleanExtra(ModuleSettings.LAUNCHER_ENTRY_EXTRA, false)
        if (launcherEntry) overridePendingTransition(0, 0)
    }

    private fun returnToLauncher() {
        startActivity(
            android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_HOME)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION),
            android.app.ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle(),
        )
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readPageRequest()
        pageRequest++
    }
    override fun onResume() {
        super.onResume()
        resumeRequest++
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            launcherEntry = false
            intent.removeExtra(ModuleSettings.LAUNCHER_ENTRY_EXTRA)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readPageRequest()
        setContent {
            val preferences = remember { ModuleSettings.preferences(this) }
            CompositionLocalProvider(LocalModulePreferences provides preferences) {
                val moduleTheme = rememberModuleThemeSettings()
                val darkPage = if (moduleTheme.blackBackground) Color.Black else FLUIX_PAGE_DARK
                MiuixTheme(
                    controller = remember(moduleTheme.blackBackground) {
                        ThemeController(
                            colorSchemeMode = ColorSchemeMode.System,
                            lightColors = lightColorScheme(
                                primary = Color(0xFF206CFF),
                                background = FLUIX_PAGE_LIGHT,
                                surface = FLUIX_PAGE_LIGHT,
                                onSurface = FLUIX_TEXT_LIGHT,
                                onSurfaceVariantSummary = FLUIX_SUMMARY_LIGHT,
                                surfaceContainer = FLUIX_GROUP_LIGHT,
                                dividerLine = Color.Transparent,
                            ),
                            darkColors = darkColorScheme(
                                primary = Color(0xFF1564ED),
                                background = darkPage,
                                surface = darkPage,
                                onSurface = FLUIX_TEXT_DARK,
                                onSurfaceVariantSummary = FLUIX_SUMMARY_DARK,
                                surfaceContainer = FLUIX_GROUP_DARK,
                                dividerLine = Color.Transparent,
                            ),
                        )
                    },
                    textStyles = fluixTextStyles(),
                ) {
                    FluixStatusBar()
                    FluixOverscrollHost { SettingsScreen(requestedPage, pageRequest, resumeRequest, launcherEntry, ::returnToLauncher) }
                    if (RestartUiState.showLoading) RestartLoadingOverlay()
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(requestedPage: String?, pageRequest: Int, resumeRequest: Int, launcherEntry: Boolean, onReturnToLauncher: () -> Unit) {
    var openCategoryId by remember { mutableStateOf(requestedPage?.takeIf { page -> CATEGORIES.any { it.id == page } }) }
    LaunchedEffect(pageRequest) {
        if (requestedPage != null && CATEGORIES.any { it.id == requestedPage }) openCategoryId = requestedPage
    }
    val onPageBack: () -> Unit = {
        if (launcherEntry && openCategoryId == "hidden_apps") onReturnToLauncher()
        else openCategoryId = null
    }
    BackHandler(enabled = openCategoryId != null, onBack = onPageBack)

    AnimatedContent(
        targetState = openCategoryId,
        transitionSpec = {
            if (launcherEntry) {
                androidx.compose.animation.EnterTransition.None.togetherWith(androidx.compose.animation.ExitTransition.None)
            } else {
            val direction = if (targetState != null) 1 else -1
            val spec = tween<IntOffset>(durationMillis = PAGE_TRANSITION_MS)
            (fadeIn(animationSpec = tween(PAGE_TRANSITION_MS)) +
                slideInHorizontally(animationSpec = spec) { direction * it / 5 })
                .togetherWith(
                    fadeOut(animationSpec = tween(PAGE_TRANSITION_MS)) +
                        slideOutHorizontally(animationSpec = spec) { -direction * it / 5 },
                )
            }
        },
        label = "settings_pages",
    ) { pageId ->
        val category = CATEGORIES.firstOrNull { it.id == pageId }
        if (category == null) {
            HomeScreen(onOpenCategory = { openCategoryId = it })
        } else if (category.id == DISABLED_APPS_ID) {
            DisabledAppsScreen(LocalContext.current, onBack = { openCategoryId = null })
        } else if (category.id == "hidden_apps") {
            HiddenAppsScreen(LocalContext.current, onBack = onPageBack)
        } else {
            CategoryScreen(category, onBack = { openCategoryId = null })
        }
    }
}

@Composable
private fun HomeScreen(onOpenCategory: (String) -> Unit) {
    val listState = rememberLazyListState()
    val overscrollOffset = remember { mutableFloatStateOf(0f) }
    Scaffold(
        topBar = {
            FluixLargeTitle(
                title = "Flyme Mod",
                actions = { RestartMenu() },
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .fluixOverscroll(listState, overscrollOffset),
        ) {
            item(key = "module_controls") {
                HomeModuleControls()
            }
            CATEGORY_GROUPS.forEach { group ->
                item(key = "category_group/${group.first().id}") {
                    FluixCard {
                        group.forEachIndexed { index, category ->
                            if (index > 0) FluixItemDivider(startInset = FLUIX_CATEGORY_TEXT_START)
                            FluixCategoryRow(
                                icon = category.icon,
                                iconForeground = category.iconForeground,
                                title = category.title,
                                onClick = { onOpenCategory(category.id) },
                            )
                        }
                    }
                }
            }
            item(key = "home_bottom_space") { Box(Modifier.height(20.dp)) }
        }
    }
}

@Composable
private fun CategoryScreen(category: Category, onBack: () -> Unit) {
    var pickingNavigationApplications by remember { mutableStateOf(false) }
    var pickingGameApplications by remember { mutableStateOf(false) }
    if (pickingGameApplications) {
        BackHandler { pickingGameApplications = false }
        HiddenAppsScreen(LocalContext.current, onBack = { pickingGameApplications = false }, landscapeGames = true)
        return
    }
    if (pickingNavigationApplications) {
        BackHandler { pickingNavigationApplications = false }
        HiddenAppsScreen(LocalContext.current, onBack = { pickingNavigationApplications = false },
            navigationApplications = true)
        return
    }
    val listState = rememberLazyListState()
    val overscrollOffset = remember { mutableFloatStateOf(0f) }
    Scaffold(
        topBar = {
            FluixTopAppBar(
                title = category.title,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = "返回",
                            tint = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.size(FLUIX_BACK_ICON),
                        )
                    }
                },
                actions = { RestartMenu() },
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .fluixOverscroll(listState, overscrollOffset),
        ) {
            SETTINGS_GROUPS[category.id].orEmpty().forEach { group ->
                item(key = "${category.id}/${group.title}/title") {
                    FluixSmallTitle(text = group.title)
                }
                itemsIndexed(
                    items = group.items,
                    key = { _, item -> "${category.id}/${group.title}/${item.key}" },
                    contentType = { _, item -> item.javaClass },
                ) { index, item ->
                    FluixCardRow(first = index == 0, last = index == group.items.lastIndex) {
                        if (index > 0) FluixItemDivider()
                        SettingsItemRow(item, SettingsActions(
                            onSelectNavigationApplications = { pickingNavigationApplications = true },
                            onSelectGameApplications = { pickingGameApplications = true },
                        ))
                    }
                }
            }
            item { Box(Modifier.height(24.dp)) }
        }
    }
}
