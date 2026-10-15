package dev.rikumi.flymemod;

import io.github.libxposed.api.XposedModule;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.ApplicationInfo;
import android.app.Notification;
import android.graphics.drawable.Icon;
import android.service.notification.StatusBarNotification;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.database.Cursor;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.content.res.ColorStateList;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.util.Log;
import android.provider.Settings;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Button;
import android.view.ViewGroup;
import java.lang.reflect.Method;
import java.util.Map;
import java.lang.reflect.Field;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import io.github.libxposed.api.XposedInterface;

/** Hooks verified against MEIZU 21 Pro / Flyme 12.6.0.0A, SystemUI 16260625. */
public final class XposedInit extends XposedModule {
    private static final String PHONE = "com.flyme.systemui.controlcenter.phone.";
    private static final int DARK_BACKGROUND = 0x73000000;
    private static final float OPERATION_SCALE = 1.06f;
    private final Map<View, Integer> scaledNotificationWidths = new WeakHashMap<>();
    private final Map<View, OperationArea> operationAreas = new WeakHashMap<>();
    private final Set<View> notificationWidthStacks = Collections.newSetFromMap(new WeakHashMap<>());
    private static final int ACTIVE_FOREGROUND = 0x99000000;
    // ColorOS QsColorfulConfigUtil's native radiant palette; keep its opaque tints.
    private static final int ACTIVE_BLUE_COLOR = 0xFF0066FF;
    private static final int ACTIVE_MOBILE_COLOR = 0xFF00990F;
    private static final int ACTIVE_YELLOW_COLOR = 0xFFEBAB22;
    private static final int ACTIVE_PURPLE_COLOR = 0xFF5A47FF;
    private static final int ACTIVE_RED_COLOR = 0xFFDB382C;
    private static final int ACTIVE_VOLUME_COLOR = 0xFF333333;
    private volatile boolean settingsLoaded;
    private boolean scaleEnabled;
    private boolean lightEnabled;
    private boolean tileBackgroundColorEnabled;
    private boolean notificationBackgroundColorEnabled;
    private int notificationBackgroundSaturation = ModuleSettings.TILE_BACKGROUND_SATURATION_DEFAULT;
    private int tileBackgroundSaturation = ModuleSettings.TILE_BACKGROUND_SATURATION_DEFAULT;
    private TileCornerHooks activeTileCornerHooks;
    private boolean tileCornersEnabled;
    private int tileCornerPercent = ModuleSettings.TILE_CORNER_PERCENT_DEFAULT;
    private TileBackdropHooks activeTileBackdropHooks;
    private int lightBackgroundOpacity = ModuleSettings.LIGHT_OPACITY_DEFAULT;
    private final Map<View, Object[]> lightBackgroundWrappers = new WeakHashMap<>();
    private final Set<View> lightBackgroundTiles = Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<GradientDrawable> lightSliderBackgrounds = Collections.newSetFromMap(new WeakHashMap<>());
    private boolean darkenEnabled;
    private final ThreadLocal<Object> blurDimOwner = new ThreadLocal<>();
    private boolean surfaceDarkBackground;
    private RowRevealHooks activeRowRevealHooks;
    private SeparateShadeSwipeHooks activeSeparateShadeSwipeHooks;
    private boolean whiteActiveEnabled;
    private int controlCenterStyle;
    private int customActiveColor = ModuleSettings.CONTROL_CENTER_ACTIVE_COLOR_DEFAULT;
    private int whiteActiveOpacity = 90;
    private final Set<View> whiteActiveTileViews = Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<ImageView> whiteActiveTintedIcons = Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<View> whiteActiveCircleViews = Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<GradientDrawable> whiteActiveProgress = Collections.newSetFromMap(new WeakHashMap<>());
    private boolean sliderActiveCornersEnabled;
    private boolean notificationLightEnabled, notificationCustomCornersEnabled;
    private int notificationLightOpacity = ModuleSettings.LIGHT_OPACITY_DEFAULT;
    private int notificationCornerPercent = ModuleSettings.TILE_CORNER_PERCENT_DEFAULT;
    private NotificationAppearanceHooks activeNotificationAppearanceHooks;
    private NotificationRemovalLayerHooks activeNotificationRemovalLayerHooks;
    private boolean notificationReadLayerFixEnabled;
    private boolean nativeNotificationExpansionEnabled;
    private boolean headsUpWidthEnabled;
    private HeadsUpWidthHooks activeHeadsUpWidthHooks;
    private boolean originalNotificationIconsEnabled;
    private volatile boolean earphoneNoiseControlEnabled;
    private EarphoneNoiseHooks activeEarphoneNoiseHooks;
    private boolean monochromeNotificationActionsEnabled;
    private boolean removeNotificationActionBackgroundEnabled;
    private NotificationActionBackgroundHooks activeNotificationActionBackgroundHooks;
    private volatile boolean disableGoogleNotificationSuggestionsEnabled;
    private volatile boolean limitAodMovementEnabled;
    private int lockClockFont, aodClockFont, statusBarClockFont, controlCenterClockFont;
    private int statusBarClockWeight, controlCenterClockWeight;
    private boolean lockClockMonospace, aodClockMonospace, statusBarClockMonospace, controlCenterClockMonospace;
    private int clockFontWeight, lockClockSpacing, aodClockSpacing, statusBarClockSpacing;
    private boolean lockClockSpacingEnabled, aodClockSpacingEnabled;
    private boolean statusBarClockSpacingEnabled;
    private boolean controlCenterClockDateUpEnabled;
    private int controlCenterClockDateUpDistance = ModuleSettings.CONTROL_CENTER_CLOCK_DATE_UP_DISTANCE_DEFAULT;
    private boolean controlCenterButtonsUpEnabled;
    private int controlCenterButtonsUpDistance = 8;
    private int volumeFirstFourMode;
    private boolean combinedCollapseFixEnabled;
    private boolean combinedPullAnimationEnabled;
    private boolean qsTranslationOriginEnabled;
    private boolean separateShadeSwipeEnabled;
    private boolean secondaryExpansionEnabled;
    private boolean combinedEmptyShadeKeepOpenEnabled;
    private boolean combinedFilterSettingsEnabled;
    private boolean combinedNotificationWidthEnabled;
    private CombinedNotificationWidthHooks activeCombinedNotificationWidthHooks;
    private boolean homeIconMinimumSizeEnabled;
    private CombinedSettingsButtonHooks activeCombinedSettingsButtonHooks;
    private boolean restoreCollapsedCardHeightEnabled;
    private SecondaryExpansionHooks secondaryExpansionHooks;
    private boolean mergeDualSignalEnabled;
    private boolean separateNetworkTypeEnabled;
    private final Map<Object, Map<String, Boolean>> requestedStatusIconVisibility =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<String, String> STATUS_ICON_SETTINGS = createStatusIconSettings();
    private boolean hideLunarEnabled;
    private boolean wifiLabelEnabled;
    private boolean optimize2x1TextEnabled;
    private boolean splitNetworkCardEnabled;
    private int networkSplitStyle;
    private boolean animatedMuteSlashEnabled;
    private MuteSlashHooks activeMuteSlashHooks;
    private CardIconLayoutHooks activeCardIconLayoutHooks;
    private boolean foldIdleMediaEnabled;
    private boolean fixMediaSwipeEnabled;
    private MediaSwipeHooks activeMediaSwipeHooks;
    private boolean notificationSwipeSegmentsEnabled;
    private NotificationSwipeHooks activeNotificationSwipeHooks;
    private FoldIdleMediaHooks activeFoldIdleMediaHooks;
    private boolean circleSmallTilesEnabled;
    private final Map<Notification, Icon> originalNotificationIcons = java.util.Collections.synchronizedMap(new WeakHashMap<>());
    private boolean blurRadiusEnabled;
    private boolean backgroundScaleEnabled;
    private int backgroundScale = ModuleSettings.BACKGROUND_SCALE_DEFAULT;
    private boolean wallpaperStartupFixEnabled;
    private boolean wallpaperStartupReadLogged;
    private volatile Set<String> hiddenLauncherPackages = Collections.emptySet();
    private Set<String> appliedLauncherHiddenPackages = Collections.emptySet();
    private volatile boolean stackedRecentsEnabled;
    private volatile boolean recentsCornerEnabled;
    private volatile int recentsCornerDp = RecentsCornerGeometry.MIN_DP;
    private RecentsCornerHooks activeRecentsCornerHooks;
    private volatile boolean recentsHideNotRunningEnabled, recentsSwipeUpKillEnabled;
    private volatile boolean folderPagingEnabled;
    private volatile boolean folderCenterEnabled;
    private volatile boolean folderCloseTargetEnabled;
    private volatile boolean editAppIconNameEnabled;
    private volatile boolean launcherHideAppMenuEnabled, launcherSpreadHiddenAppsEnabled;
    private volatile boolean lockWidgetStartupFixEnabled;
    private volatile boolean blockLockscreenLongPressEditEnabled;
    private volatile boolean folderRestoreColorEnabled;
    private volatile boolean widgetContourEnabled, menuContourEnabled;
    private volatile boolean iconContourEnabled, folderMenuAlignmentEnabled;
    private volatile boolean fixIconPackPreview = true;
    private volatile boolean folderRadiusEnabled;
    private volatile int folderRadiusDp = ModuleSettings.FOLDER_RADIUS_DEFAULT;
    private volatile Context launcherContext;
    private ContentObserver launcherSettingsObserver;
    private ContentObserver moduleSettingsObserver;
    private SettingsHooks activeSettingsHooks;
    private StoreLayoutHooks activeStoreLayoutHooks;
    private WeatherRecommendationHooks activeWeatherRecommendationHooks;
    private WeatherBackgroundHooks activeWeatherBackgroundHooks;
    private ClockFontHooks activeClockFontHooks;
    private HeaderDateHooks activeHeaderDateHooks;
    private StatusBarLyricHooks activeStatusBarLyricHooks;
    private boolean statusBarLyricAvoidEnabled = ModuleSettings.STATUS_BAR_LYRIC_AVOID_DEFAULT;
    private ChargingInfoHooks activeChargingInfoHooks;
    private int statusBarChargingInfoMode = ModuleSettings.STATUS_BAR_CHARGING_INFO_DEFAULT;
    private CarrierVisibilityHooks activeCarrierVisibilityHooks;
    private boolean hideCarrierEnabled;
    private SimpleQsTextHooks activeSimpleQsTextHooks;
    private SplitNetworkCardHooks activeSplitNetworkCardHooks;
    private CircleTileHooks activeCircleTileHooks;
    private boolean colorOsContourEnabled;
    private ColorOsMaterialHooks activeColorOsMaterialHooks;
    private boolean notificationContourEnabled, mbackSystemTimeoutEnabled, mbackMissingAssistantHomeEnabled;
    private MBackRippleHooks activeMBackRippleHooks;
    private GestureBarWidthHooks activeGestureBarWidthHooks;
    private boolean gestureBarWidthEnabled = true;
    private int gestureBarWidthDp=ModuleSettings.GESTURE_BAR_WIDTH_DEFAULT;
    private boolean backGestureArrowEnabled;
    private boolean backGestureDarkBackgroundEnabled = true;
    private int backGestureDarkOpacity = ModuleSettings.BACK_GESTURE_DARK_OPACITY_DEFAULT;
    private BackGestureBackgroundHooks activeBackGestureBackgroundHooks;
    private boolean backGestureWidthEnabled;
    private boolean backGestureRemoveSmart = true;
    private BackGestureSmartHooks activeBackGestureSmartHooks;
    private BackGestureWidthHooks activeBackGestureWidthHooks;
    private int backGestureWidthDp = ModuleSettings.BACK_GESTURE_WIDTH_DEFAULT;
    private int blurRadius = ModuleSettings.BLUR_DEFAULT;
    private boolean settingsErrorLogged;
    private final Map<View, Drawable> originalCenterBackgrounds = new WeakHashMap<>();
    private final Map<ImageView, Integer> sliderIconColors = new WeakHashMap<>();
    private final Set<ImageView> volumeSliderIcons = Collections.newSetFromMap(new WeakHashMap<>());
    private final Map<View, int[]> notificationContentInsets = new WeakHashMap<>();
    private final Set<View> notificationPaddingHeightChanges = Collections.newSetFromMap(new WeakHashMap<>());

    private ThemeColorHooks themeColorHooks;
    private final ImageDrawHookChain imageDrawHooks = new ImageDrawHookChain();

    private boolean nightBlacklistInstalled;

    @Override public void onPackageReady(PackageReadyParam param) {
        if (!nightBlacklistInstalled) {
            try {
                new NightModeBlacklistHooks(getRemotePreferences(CameraSettingsBridge.REMOTE_FILE)).install(param.getClassLoader(),
                        (name, method, hooker, parameters) -> install(param.getClassLoader(), name, method, hooker, parameters),
                        this::deoptimize, (message, error) -> log(error == null ? Log.INFO : Log.ERROR, "FlymeMod", message, error));
                nightBlacklistInstalled = true;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                log(Log.ERROR, "FlymeMod", "Cannot install native dark blacklist listener", error);
            }
        }
        if (themeColorHooks == null && DarkBackgroundTargets.contains(param.getPackageName())) {
            try {
                themeColorHooks = new ThemeColorHooks(param.getPackageName(), getRemotePreferences(CameraSettingsBridge.REMOTE_FILE),
                        (message, error) -> log(error == null ? Log.INFO : Log.ERROR, "FlymeMod", message, error));
                themeColorHooks.install(param.getClassLoader(), this::installDrawingHook, this::deoptimize);
            } catch (RuntimeException | LinkageError error) {
                log(Log.ERROR, "FlymeMod", "Cannot initialize appearance background hooks", error);
            }
        }
        try {
            if (!"dev.rikumi.flymemod".equals(param.getPackageName())) {
                SystemIconPackHooks icons = new SystemIconPackHooks(getRemotePreferences(CameraSettingsBridge.REMOTE_FILE),
                        (message, error) -> log(Log.ERROR, "FlymeMod", message, error));
                ClassLoader iconLoader = param.getClassLoader();
                SignalHooks.Installer iconInstaller = (name, method, hooker, parameters) ->
                        install(iconLoader, name, method, hooker, parameters);
                icons.install(iconLoader, iconInstaller, this::deoptimize);
                if ("com.android.systemui".equals(param.getPackageName()))
                    icons.installSystemUi(iconLoader, iconInstaller);
                if ("com.android.settings".equals(param.getPackageName()))
                    icons.installSettings(iconLoader, iconInstaller, this::deoptimize);
                if ("com.meizu.flyme.launcher".equals(param.getPackageName()))
                    new SystemIconPackLauncherHooks(getRemotePreferences(CameraSettingsBridge.REMOTE_FILE),
                            (message, error) -> log(Log.ERROR, "FlymeMod", message, error))
                            .install(iconLoader, iconInstaller, this::deoptimize);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log(Log.ERROR, "FlymeMod", "Cannot initialize scoped system application icon pack", error);
        }
        installPackageHooks(param.getPackageName(), param.getClassLoader());
    }

    @Override public void onSystemServerStarting(SystemServerStartingParam param) {
        ClassLoader loader = param.getClassLoader();
        try {
            AutoBrightnessOffsetHooks.install(loader, getRemotePreferences(CameraSettingsBridge.REMOTE_FILE),
                    (name, method, hooker, parameters) -> install(loader, name, method, hooker, parameters),
                    this::deoptimize);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log(Log.ERROR, "FlymeMod", "Cannot resolve automatic brightness offset", error);
        }
        try {
            ZygoteRestartBootHooks.install(loader,
                    (name, method, hooker, parameters) -> install(loader, name, method, hooker, parameters),
                    () -> getRemotePreferences(CameraSettingsBridge.REMOTE_FILE)
                            .getBoolean(ModuleSettings.FIX_ZYGOTE_RESTART_UNLOCK, false),
                    this::deoptimize,
                    (message, error) -> log(error == null ? Log.INFO : Log.ERROR, "FlymeMod", message, error));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log(Log.ERROR, "FlymeMod", "Cannot resolve Zygote restart APEX boot notification", error);
        }
        try {
            new AnrDialogHooks((message, error) -> log(error == null ? Log.INFO : Log.ERROR, "FlymeMod", message, error))
                    .install(loader, this::installDrawingHook);
        } catch (RuntimeException | LinkageError error) {
            log(Log.ERROR, "FlymeMod", "Cannot initialize native ANR theme hooks", error);
        }
        try {
            new StatusBarOverlayServiceHooks(getRemotePreferences(CameraSettingsBridge.REMOTE_FILE),
                    (message, error) -> log(error == null ? Log.INFO : Log.ERROR, "FlymeMod", message, error))
                    .install(loader, (name, method, hooker, parameters) ->
                            install(loader, name, method, hooker, parameters), this::deoptimize);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log(Log.ERROR, "FlymeMod", "Cannot resolve status-bar overlay lifecycle", error);
        }
        try {
            FingerprintSuccessHapticHooks.install(loader, getRemotePreferences(CameraSettingsBridge.REMOTE_FILE),
                    (name,method,hooker,parameters) -> install(loader,name,method,hooker,parameters), this::deoptimize,
                    (message,error) -> log(error==null?Log.INFO:Log.ERROR,"FlymeMod",message,error));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log(Log.ERROR,"FlymeMod","Cannot resolve fingerprint success feedback",error);
        }
        try {
            BackGestureServiceHooks.install(loader, getRemotePreferences(CameraSettingsBridge.REMOTE_FILE),
                    (name, method, hooker, parameters) -> install(loader, name, method, hooker, parameters), this::deoptimize,
                    (message,error) -> log(error==null?Log.INFO:Log.ERROR,"FlymeMod",message,error));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log(Log.ERROR, "FlymeMod", "Cannot resolve Flyme side gesture width", error);
        }
        try {
            new SmallWindowLimitHooks(getRemotePreferences(CameraSettingsBridge.REMOTE_FILE),
                    (message, error) -> log(error == null ? Log.INFO : Log.ERROR, "FlymeMod", message, error))
                    .install(loader, (name, method, hooker, parameters) ->
                            install(loader, name, method, hooker, parameters), this::deoptimize);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log(Log.ERROR, "FlymeMod", "Cannot resolve Flyme small-window limit hooks", error);
        }
        try {
            new SmallWindowInputHooks(getRemotePreferences(CameraSettingsBridge.REMOTE_FILE),
                    (message, error) -> log(error == null ? Log.INFO : Log.ERROR, "FlymeMod", message, error))
                    .install(loader, (name, method, hooker, parameters) ->
                            install(loader, name, method, hooker, parameters), this::deoptimize);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log(Log.ERROR, "FlymeMod", "Cannot resolve Flyme small-window input hooks", error);
        }
        try {
            new GameWidthHooks(getRemotePreferences(CameraSettingsBridge.REMOTE_FILE),
                    (message, error) -> log(error == null ? Log.INFO : Log.ERROR, "FlymeMod", message, error))
                    .install(loader, (name, method, hooker, parameters) ->
                            install(loader, name, method, hooker, parameters), this::deoptimize);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log(Log.ERROR, "FlymeMod", "Cannot resolve Flyme landscape game width hooks", error);
        }
    }

    private void installPackageHooks(String packageName, ClassLoader loader) {
        if ("com.android.settings".equals(packageName)) {
            new DeviceInfoExtrasHooks(getRemotePreferences(CameraSettingsBridge.REMOTE_FILE),
                    (message, error) -> log(Log.ERROR, "FlymeMod", message, error))
                    .install(loader, this::installDrawingHook);
        }
        if ("com.android.settings".equals(packageName)) {
            try {
                FingerprintAnimationHooks.installSettings(loader,
                        (name, method, hooker, parameters) -> install(loader, name, method, hooker, parameters),
                        this::deoptimize,
                        () -> getRemotePreferences(CameraSettingsBridge.REMOTE_FILE)
                                .getBoolean(ModuleSettings.REMOVE_FINGERPRINT_ANIMATION, false));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                log(Log.ERROR, "FlymeMod", "Cannot resolve fingerprint animation settings", error);
            }
        }
        if ("com.meizu.flyme.sdkstage".equals(packageName)) {
            try {
                new NightModeAppHooks(getRemotePreferences(CameraSettingsBridge.REMOTE_FILE),
                        (message, error) -> log(error == null ? Log.INFO : Log.ERROR, "FlymeMod", message, error))
                        .install(loader, (name, method, hooker, parameters) ->
                                install(loader, name, method, hooker, parameters), this::deoptimize);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                log(Log.ERROR, "FlymeMod", "Cannot resolve SDKStage dark app manager", error);
            }
            return;
        }
        if ("com.meizu.media.gallery".equals(packageName)) {
            try {
                new GalleryPreviewHooks(getRemotePreferences(CameraSettingsBridge.REMOTE_FILE),
                        (message, error) -> log(error == null ? Log.INFO : Log.ERROR, "FlymeMod", message, error))
                        .install(loader, (name, method, hooker, parameters) ->
                                install(loader, name, method, hooker, parameters));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                log(Log.ERROR, "FlymeMod", "Cannot resolve camera gallery preview hooks", error);
            }
            return;
        }
        if ("com.meizu.media.camera".equals(packageName)) {
            try {
                CameraHooks hooks = new CameraHooks(getRemotePreferences(CameraSettingsBridge.REMOTE_FILE), (message, error) -> log(error == null ? Log.INFO : Log.ERROR, "FlymeMod", message, error));
                SignalHooks.Installer installer = (name, method, hooker, parameters) -> install(loader, name, method, hooker, parameters);
                hooks.installCamera(loader, installer, this::deoptimize);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                log(Log.ERROR, "FlymeMod", "Cannot resolve Flyme camera hooks", error);
            }
            return;
        }
        if ("com.android.packageinstaller".equals(packageName)) {
            try {
                new InstallerHooks((message, error) -> log(Log.ERROR, "FlymeMod", message, error)).install(loader,
                        (name, method, hooker, parameters) -> install(loader, name, method, hooker, parameters));
            } catch (ReflectiveOperationException e) {
                log(Log.ERROR, "FlymeMod", "Cannot resolve Flyme installer classes", e);
            }
            return;
        }
        if ("com.meizu.flyme.weather".equals(packageName)) {
            try {
                activeWeatherRecommendationHooks = new WeatherRecommendationHooks((message, error) -> log(Log.ERROR, "FlymeMod", message, error));
                activeWeatherRecommendationHooks.install(loader,
                        (name, method, hooker, parameters) -> install(loader, name, method, hooker, parameters));
            } catch (ReflectiveOperationException e) {
                log(Log.ERROR, "FlymeMod", "Cannot resolve weather recommendation classes", e);
            }
            try {
                activeWeatherBackgroundHooks = new WeatherBackgroundHooks((message, error) -> log(Log.ERROR, "FlymeMod", message, error));
                activeWeatherBackgroundHooks.install(
                        (name, method, hooker, parameters) -> install(loader, name, method, hooker, parameters));
            } catch (ReflectiveOperationException e) {
                log(Log.ERROR, "FlymeMod", "Cannot resolve weather background classes", e);
            }
            return;
        }
        if ("com.meizu.mstore".equals(packageName)) {
            try {
                activeStoreLayoutHooks = new StoreLayoutHooks((message, error) -> log(Log.ERROR, "FlymeMod", message, error));
                activeStoreLayoutHooks.install(loader,
                        (name, method, hooker, parameters) -> install(loader, name, method, hooker, parameters));
            } catch (ReflectiveOperationException e) {
                log(Log.ERROR, "FlymeMod", "Cannot resolve store layout classes", e);
            }
            try {
                new StoreAdHooks((message, error) -> log(Log.ERROR, "FlymeMod", message, error)).install(loader,
                        (name, method, hooker, parameters) -> install(loader, name, method, hooker, parameters));
            } catch (ReflectiveOperationException e) {
                log(Log.ERROR, "FlymeMod", "Cannot resolve store splash classes", e);
            }
            return;
        }
        if ("com.android.settings".equals(packageName)) {
            try {
                activeSettingsHooks = new SettingsHooks((message, error) -> log(Log.ERROR, "FlymeMod", message, error));
                activeSettingsHooks.install(loader,
                        (name, method, hooker, parameters) -> install(loader, name, method, hooker, parameters));
            } catch (ReflectiveOperationException e) {
                log(Log.ERROR, "FlymeMod", "Cannot resolve Flyme Settings classes", e);
            }
            return;
        }
        if ("com.meizu.flyme.launcher".equals(packageName)) {
            installLauncherIconHiding(loader);
            try {
                new RecentsGestureJankHooks(loader, () -> stackedRecentsEnabled).install(
                        (name, method, hooker, parameters) -> install(loader, name, method, hooker, parameters),
                        this::deoptimize);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                log(Log.ERROR, "FlymeMod", "Cannot resolve stacked-recents data loading fix", error);
            }
            try {
                new HomeIconMinimumSizeHooks(loader, this::loadSettings, () -> homeIconMinimumSizeEnabled).install(
                        (name, method, hooker, parameters) -> install(loader, name, method, hooker, parameters),
                        this::deoptimize);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                log(Log.ERROR, "FlymeMod", "Cannot resolve home-animation minimum icon size", error);
            }
            try {
                new LauncherHiddenAppsHooks(this::loadSettings, () -> launcherHideAppMenuEnabled,
                        () -> launcherSpreadHiddenAppsEnabled,
                        (message, error) -> log(error == null ? Log.INFO : Log.ERROR, "FlymeMod", message, error))
                        .install(loader, (name, method, hooker, parameters) ->
                                install(loader, name, method, hooker, parameters), this::deoptimize);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                log(Log.ERROR, "FlymeMod", "Cannot resolve launcher hidden application shortcuts and gesture", error);
            }
            try {
                new LauncherIconEditHooks(loader, this::loadSettings, () -> editAppIconNameEnabled,
                        () -> iconContourEnabled, () -> menuContourEnabled,
                        (message, error) -> log(error == null ? Log.INFO : Log.ERROR, "FlymeMod", message, error)).install(
                        (name, method, hooker, parameters) -> install(loader, name, method, hooker, parameters), this::deoptimize);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                log(Log.ERROR, "FlymeMod", "Cannot resolve launcher icon editing", error);
            }
            try {
                new FolderCloseTargetHooks(loader, this::loadSettings, () -> folderCloseTargetEnabled,
                        (message, error) -> log(Log.ERROR, "FlymeMod", message, error)).install(
                        (name, method, hooker, parameters) -> install(loader, name, method, hooker, parameters));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                log(Log.ERROR, "FlymeMod", "Cannot resolve folder app-close animation target", error);
            }
            try {
                new StackedRecentsHooks(loader, this::loadSettings, () -> stackedRecentsEnabled,
                        (message, error) -> log(error == null ? Log.INFO : Log.ERROR, "FlymeMod", message, error)).install(
                        (name, method, hooker, parameters) -> install(loader, name, method, hooker, parameters),
                        loader, this::deoptimize);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                log(Log.ERROR, "FlymeMod", "Cannot resolve Flyme stacked recent-task classes", error);
            }
            try {
                new RecentsHooks(
                        (name, method, hooker, parameters) -> install(loader, name, method, hooker, parameters),
                        () -> recentsHideNotRunningEnabled, () -> recentsSwipeUpKillEnabled, () -> stackedRecentsEnabled,
                        (message, error) -> log(Log.ERROR, "FlymeMod", message, error)).install(loader, this::deoptimize);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                log(Log.ERROR, "FlymeMod", "Cannot resolve Flyme recent-task classes", e);
            }
            try {
                activeRecentsCornerHooks = new RecentsCornerHooks(loader, this::loadSettings,
                        () -> recentsCornerEnabled, () -> recentsCornerDp,
                        (message, error) -> log(Log.ERROR, "FlymeMod", message, error));
                activeRecentsCornerHooks.install(
                        (name, method, hooker, parameters) -> install(loader, name, method, hooker, parameters), this::deoptimize);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                log(Log.ERROR, "FlymeMod", "Cannot resolve Flyme recent-task corner geometry", error);
            }
            try {
                new RecentsThumbnailBorderHooks(loader,getRemotePreferences(CameraSettingsBridge.REMOTE_FILE)).install(
                        (name,method,hooker,parameters) -> install(loader,name,method,hooker,parameters),this::deoptimize);
            } catch(ReflectiveOperationException | RuntimeException | LinkageError error) {
                log(Log.ERROR,"FlymeMod","Cannot resolve recent-task thumbnail border",error);
            }
            try {
                new FolderHooks(loader,
                        (name, method, hooker, parameters) -> install(loader, name, method, hooker, parameters),
                        () -> folderPagingEnabled, () -> folderCenterEnabled).install();
            } catch (ReflectiveOperationException e) {
                log(Log.ERROR, "FlymeMod", "Cannot resolve Flyme folder classes", e);
            }
            try {
                new LauncherIconPackPreviewHooks(loader, this::loadSettings, () -> fixIconPackPreview,
                        (message,error) -> log(error==null?Log.INFO:Log.ERROR,"FlymeMod",message,error)).install(
                        (name,method,hooker,parameters) -> install(loader,name,method,hooker,parameters),this::deoptimize);
                new LauncherPreviewContourHooks(loader, this::loadSettings, () -> iconContourEnabled,
                        (message,error) -> log(error==null?Log.INFO:Log.ERROR,"FlymeMod",message,error)).install(
                        (name,method,hooker,parameters) -> install(loader,name,method,hooker,parameters),this::deoptimize);
                new LauncherEditContourHooks(loader,this::loadSettings,() -> iconContourEnabled,
                        (message,error) -> log(error==null?Log.INFO:Log.ERROR,"FlymeMod",message,error)).install(
                        (name,method,hooker,parameters) -> install(loader,name,method,hooker,parameters),this::deoptimize);
                new LauncherSettingsDialogHooks(loader).install(
                        (name,method,hooker,parameters) -> install(loader,name,method,hooker,parameters),this::deoptimize);
            } catch(ReflectiveOperationException | RuntimeException | LinkageError error) {
                log(Log.ERROR,"FlymeMod","Cannot resolve launcher icon previews or accent controls",error);
            }
            try {
                new LauncherContourHooks(loader, this::loadSettings, () -> iconContourEnabled,
                        () -> widgetContourEnabled, () -> menuContourEnabled, () -> folderRadiusEnabled,
                        () -> iconContourEnabled, () -> folderMenuAlignmentEnabled, () -> folderRadiusDp, (message, error) -> log(Log.ERROR, "FlymeMod", message, error))
                        .install(loader, (name, method, hooker, parameters) -> install(loader, name, method, hooker, parameters), this::deoptimize);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                log(Log.ERROR, "FlymeMod", "Cannot resolve launcher background contours", error);
            }
            try {
                new FolderAppearanceHooks(loader, this::loadSettings,
                        () -> folderRestoreColorEnabled, () -> folderRadiusEnabled, () -> folderRadiusDp,
                        (message, error) -> log(Log.ERROR, "FlymeMod", message, error)).install(
                        (name, method, hooker, parameters) -> install(loader, name, method, hooker, parameters));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                log(Log.ERROR, "FlymeMod", "Cannot resolve Flyme folder appearance", e);
            }
            return;
        }
        if("com.flyme.systemuieditor".equals(packageName)) {
            try {
                new ClockPreviewFontHooks(loader,getRemotePreferences(CameraSettingsBridge.REMOTE_FILE),
                        (message,error) -> log(Log.ERROR,"FlymeMod",message,error)).install(
                        (name,method,hooker,parameters) -> install(loader,name,method,hooker,parameters),this::deoptimize);
            } catch(ReflectiveOperationException | RuntimeException | LinkageError error) {
                log(Log.ERROR,"FlymeMod","Cannot resolve clock editor preview classes",error);
            }
        }
        if (!"com.android.systemui".equals(packageName)) return;
        try {
            NetworkTileHooks networkTiles = new NetworkTileHooks(loader,
                    (message, error) -> log(Log.ERROR, "FlymeMod", message, error));
            Class<?> controller = loader.loadClass("com.flyme.systemui.statusbar.policy.MobileDataControllerImpl");
            for (java.lang.reflect.Constructor<?> constructor : controller.getDeclaredConstructors()) {
                hook(constructor).intercept(chain -> {
                    Object result = chain.proceed();
                    networkTiles.capture(chain.getThisObject());
                    return result;
                });
            }
            networkTiles.install((name, method, hooker, parameters) -> install(loader, name, method, hooker, parameters));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log(Log.ERROR, "FlymeMod", "Cannot resolve network tile bridge", error);
        }
        try {
            new GoogleServiceTileHooks((message, error) -> log(Log.ERROR, "FlymeMod", message, error))
                    .install(loader, (name, method, hooker, parameters) ->
                            install(loader, name, method, hooker, parameters));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log(Log.ERROR, "FlymeMod", "Cannot resolve Google services tile bridge", error);
        }
        try {
            new SplashScreenBlackHooks(getRemotePreferences(CameraSettingsBridge.REMOTE_FILE)).install(loader,
                    (name,method,hooker,parameters) -> install(loader,name,method,hooker,parameters),this::deoptimize);
        } catch(ReflectiveOperationException | RuntimeException | LinkageError error) {
            log(Log.ERROR,"FlymeMod","Cannot resolve system splash background classes",error);
        }
        try {
            new AppNavigationHooks(getRemotePreferences(CameraSettingsBridge.REMOTE_FILE),
                    (message, error) -> log(error == null ? Log.INFO : Log.ERROR, "FlymeMod", message, error))
                    .install(loader, (name, method, hooker, parameters) ->
                            install(loader, name, method, hooker, parameters));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log(Log.ERROR, "FlymeMod", "Cannot resolve SystemUI application navigation hooks", error);
        }

        try {
            new LockWidgetStartupHooks(loader, this::loadSettings, () -> lockWidgetStartupFixEnabled).install(
                    (name, method, hooker, parameters) -> install(loader, name, method, hooker, parameters));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log(Log.ERROR, "FlymeMod", "Cannot resolve lockscreen widget startup hooks", error);
        }

        try {
            new LockscreenLongPressHooks(loader, this::loadSettings, () -> blockLockscreenLongPressEditEnabled).install(
                    (name, method, hooker, parameters) -> install(loader, name, method, hooker, parameters));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log(Log.ERROR, "FlymeMod", "Cannot resolve lockscreen long press edit hooks", error);
        }
        try {
            FingerprintAnimationHooks.installSystemUi(loader,
                    (name, method, hooker, parameters) -> install(loader, name, method, hooker, parameters),
                    this::deoptimize,
                    () -> getRemotePreferences(CameraSettingsBridge.REMOTE_FILE)
                            .getBoolean(ModuleSettings.REMOVE_FINGERPRINT_ANIMATION, false));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log(Log.ERROR, "FlymeMod", "Cannot resolve fingerprint animation effects", error);
        }

        install(loader, "com.android.systemui.statusbar.phone.ui.StatusBarIconControllerImpl",
                "setIconVisibility", chain -> {
                    Object controller = chain.getThisObject();
                    List<Object> args = chain.getArgs();
                    if (args == null || args.size() < 2 || !(args.get(0) instanceof String slot)
                            || !(args.get(1) instanceof Boolean requested)) return chain.proceed();
                    try {
                        Context context = (Context) controller.getClass().getField("mContext").get(controller);
                        loadSettings(context);
                    } catch (ReflectiveOperationException ignored) { }
                    requestedStatusIconVisibility.computeIfAbsent(controller, ignored -> new java.util.HashMap<>())
                            .put(slot, requested);
                    String key = STATUS_ICON_SETTINGS.get(slot);
                    boolean visible = key != null && isStatusIconHidden(key) ? false : requested;
                    List<Object> hookedArgs = new ArrayList<>(args);
                    hookedArgs.set(1, visible);
                    return chain.proceed(hookedArgs.toArray());
                }, String.class, boolean.class);

        try {
            activeStatusBarLyricHooks = new StatusBarLyricHooks(this::loadSettings, () -> statusBarLyricAvoidEnabled);
            activeStatusBarLyricHooks.install(loader, (name, method, hooker, parameters) ->
                    install(loader, name, method, hooker, parameters), this::deoptimize);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log(Log.ERROR, "FlymeMod", "Cannot resolve status-bar lyric clock hooks", error);
        }
        try {
            activeClockFontHooks = new ClockFontHooks(loader, this::loadSettings, () -> lockClockFont, () -> lockClockFont,
                    () -> statusBarClockFont, () -> controlCenterClockFont,
                    () -> statusBarClockWeight, () -> controlCenterClockWeight,
                    () -> lockClockMonospace, () -> lockClockMonospace,
                    () -> statusBarClockMonospace, () -> controlCenterClockMonospace,
                    () -> clockFontWeight, () -> lockClockSpacing, () -> lockClockSpacing,
                    () -> statusBarClockSpacing,
                    () -> lockClockSpacingEnabled, () -> lockClockSpacingEnabled,
                    () -> statusBarClockSpacingEnabled,
                    () -> controlCenterClockDateUpEnabled,
                    () -> controlCenterClockDateUpDistance, () -> controlCenterButtonsUpEnabled,
                    () -> controlCenterButtonsUpDistance, () -> hideLunarEnabled,
                    (message, error) -> log(Log.ERROR, "FlymeMod", message, error));
            activeClockFontHooks.install(
                    (name, method, hooker, parameters) -> install(loader, name, method, hooker, parameters));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log(Log.ERROR, "FlymeMod", "Cannot resolve Flyme clock font classes", error);
        }
        try {
            new CombinedShadeRefreshRateHooks(loader,
                    (message, error) -> log(Log.ERROR, "FlymeMod", message, error)).install(
                    (name, method, hooker, parameters) -> install(loader, name, method, hooker, parameters));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log(Log.ERROR, "FlymeMod", "Cannot resolve merged shade refresh rate policy", error);
        }
        try {
            activeCarrierVisibilityHooks = new CarrierVisibilityHooks(this::loadSettings, () -> hideCarrierEnabled);
            activeCarrierVisibilityHooks.install(loader, (name, method, hooker, parameters) ->
                    install(loader, name, method, hooker, parameters), this::deoptimize);
        } catch (ReflectiveOperationException | RuntimeException error) {
            log(Log.ERROR, "FlymeMod", "Cannot resolve carrier label visibility hooks", error);
        }
        activeHeaderDateHooks = new HeaderDateHooks(this::loadSettings, () -> hideLunarEnabled,
                (message, error) -> log(Log.ERROR, "FlymeMod", message, error));
        activeHeaderDateHooks.install(
                (name, method, hooker, parameters) -> install(loader, name, method, hooker, parameters));
        try {
            activeCombinedSettingsButtonHooks = new CombinedSettingsButtonHooks(loader, this::loadSettings,
                    () -> combinedFilterSettingsEnabled,
                    (message, error) -> log(Log.ERROR, "FlymeMod", message, error));
            activeCombinedSettingsButtonHooks.install(
                    (name, method, hooker, parameters) -> install(loader, name, method, hooker, parameters),
                    this::deoptimize);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log(Log.ERROR, "FlymeMod", "Cannot resolve combined shade Settings button hooks", error);
        }
        try {
            activeSimpleQsTextHooks = new SimpleQsTextHooks(loader, this::loadSettings,
                    () -> optimize2x1TextEnabled, () -> networkSplitStyle,
                    (message, error) -> log(Log.ERROR, "FlymeMod", message, error));
            activeSimpleQsTextHooks.install(loader,
                    (name, method, hooker, parameters) -> install(loader, name, method, hooker, parameters));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log(Log.ERROR, "FlymeMod", "Cannot resolve simplified control center text", error);
        }
        try {
            BluetoothConnectionLabelHooks.install(loader,
                    (name, method, hooker, parameters) -> install(loader, name, method, hooker, parameters),
                    this::deoptimize, this::loadSettings, () -> optimize2x1TextEnabled);
            BluetoothDeviceCountHooks.install(loader,
                    (name, method, hooker, parameters) -> install(loader, name, method, hooker, parameters),
                    this::deoptimize);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log(Log.ERROR, "FlymeMod", "Cannot resolve Bluetooth device count labels", error);
        }
        try {
            new AodMovementHooks(loader, this::loadSettings, () -> limitAodMovementEnabled,
                    message -> log(Log.INFO, "FlymeMod", message)).install(
                    (name, method, hooker, parameters) -> install(loader, name, method, hooker, parameters),
                    loader, this::deoptimize);
        } catch (ReflectiveOperationException e) {
            log(Log.ERROR, "FlymeMod", "Cannot resolve Flyme AOD movement classes", e);
        }
        try {
            new VolumeStepHooks(loader, this::loadSettings, () -> volumeFirstFourMode).install(
                    (name, method, hooker, parameters) -> install(loader, name, method, hooker, parameters));
        } catch (ReflectiveOperationException e) {
            log(Log.ERROR, "FlymeMod", "Cannot resolve Flyme physical volume key classes", e);
        }
        installWallpaperStartupFix(loader);
        installNotificationAppearanceLayout(loader);
        try {
            activeCombinedNotificationWidthHooks = new CombinedNotificationWidthHooks(loader,this::loadSettings,
                    () -> combinedNotificationWidthEnabled,
                    (message,error) -> log(Log.ERROR,"FlymeMod",message,error));
            activeCombinedNotificationWidthHooks.install(
                    (name,method,hooker,parameters) -> install(loader,name,method,hooker,parameters));
        } catch (ReflectiveOperationException|RuntimeException error) {
            log(Log.ERROR,"FlymeMod","Cannot resolve merged notification width",error);
        }
        try {
            activeNotificationRemovalLayerHooks = new NotificationRemovalLayerHooks(loader, this::loadSettings,
                    () -> notificationReadLayerFixEnabled,
                    (message, error) -> log(Log.ERROR, "FlymeMod", message, error));
            activeNotificationRemovalLayerHooks.install(
                    (name, method, hooker, parameters) -> install(loader, name, method, hooker, parameters));
        } catch (ReflectiveOperationException | RuntimeException error) {
            log(Log.ERROR, "FlymeMod", "Cannot resolve notification removal layers", error);
        }
        try {
            new NotificationSuggestionHooks(loader, this::loadSettings,
                    () -> disableGoogleNotificationSuggestionsEnabled).install(
                    (name, method, hooker, parameters) -> install(loader, name, method, hooker, parameters));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log(Log.ERROR, "FlymeMod", "Cannot resolve notification assistant suggestion hooks", error);
        }
        try {
            activeBackGestureBackgroundHooks = new BackGestureBackgroundHooks(loader, this::loadSettings,
                    () -> backGestureDarkBackgroundEnabled, () -> backGestureDarkOpacity, () -> backGestureArrowEnabled);
            activeBackGestureBackgroundHooks.install(
                    (name, method, hooker, parameters) -> install(loader, name, method, hooker, parameters));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log(Log.ERROR, "FlymeMod", "Cannot resolve back gesture background", error);
        }
        try {
            activeBackGestureWidthHooks = new BackGestureWidthHooks(loader, this::loadSettings,
                    () -> backGestureWidthEnabled, () -> backGestureWidthDp, () -> backGestureRemoveSmart,
                    (message,error) -> log(error==null?Log.INFO:Log.ERROR,"FlymeMod",message,error));
            activeBackGestureWidthHooks.install(
                    (name,method,hooker,parameters) -> install(loader,name,method,hooker,parameters), this::deoptimize);
        } catch (ReflectiveOperationException error) {
            log(Log.ERROR,"FlymeMod","Cannot resolve back gesture width",error);
        }
        try {
            activeBackGestureSmartHooks = new BackGestureSmartHooks(loader, this::loadSettings, () -> backGestureRemoveSmart,
                    (message,error) -> log(error==null?Log.INFO:Log.ERROR,"FlymeMod",message,error));
            activeBackGestureSmartHooks.install(
                    (name, method, hooker, parameters) -> install(loader, name, method, hooker, parameters), this::deoptimize);
        } catch (ReflectiveOperationException error) {
            log(Log.ERROR, "FlymeMod", "Cannot resolve back gesture direction", error);
        }
        try {
            activeMBackRippleHooks=new MBackRippleHooks(loader, () -> gestureBarWidthEnabled, () -> gestureBarWidthDp,
                    (message, error) -> log(Log.ERROR, "FlymeMod", message, error));
            activeMBackRippleHooks.install(
                    (name, method, hooker, parameters) -> install(loader, name, method, hooker, parameters),this::deoptimize);
        } catch (ReflectiveOperationException error) {
            log(Log.ERROR, "FlymeMod", "Cannot resolve mBack pressed ripple bounds", error);
        }
        try {
            activeGestureBarWidthHooks=new GestureBarWidthHooks(loader,this::loadSettings,
                    () -> gestureBarWidthEnabled, () -> gestureBarWidthDp);
            activeGestureBarWidthHooks.install(
                    (name, method, hooker, parameters) -> install(loader,name,method,hooker,parameters));
        } catch(ReflectiveOperationException error) {
            log(Log.ERROR,"FlymeMod","Cannot resolve gesture bar width",error);
        }
        try {
            new MBackAssistantHooks(loader, this::loadSettings, () -> mbackMissingAssistantHomeEnabled).install(
                    (name, method, hooker, parameters) -> install(loader, name, method, hooker, parameters), this::deoptimize);
        } catch (ReflectiveOperationException error) {
            log(Log.ERROR, "FlymeMod", "Cannot resolve mBack assistant fallback", error);
        }
        try {
            new MBackTimeoutHooks(loader, this::loadSettings, () -> mbackSystemTimeoutEnabled,
                    (message, error) -> log(error == null ? Log.INFO : Log.ERROR, "FlymeMod", message, error)).install(
                    (name, method, hooker, parameters) -> install(loader, name, method, hooker, parameters), this::deoptimize);
        } catch (ReflectiveOperationException error) {
            log(Log.ERROR, "FlymeMod", "Cannot resolve non-pressure mBack timeout", error);
        }
        try {
            new NotificationExpansionHooks(loader, this::loadSettings, () -> nativeNotificationExpansionEnabled,
                    (message, error) -> log(Log.ERROR, "FlymeMod", message, error)).install(
                    (name, method, hooker, parameters) -> install(loader, name, method, hooker, parameters));
        } catch (ReflectiveOperationException error) {
            log(Log.ERROR, "FlymeMod", "Cannot resolve native notification expansion hooks", error);
        }
        try {
            activeHeadsUpWidthHooks = new HeadsUpWidthHooks(loader, this::loadSettings, () -> headsUpWidthEnabled);
            activeHeadsUpWidthHooks.install(
                    (name, method, hooker, parameters) -> install(loader, name, method, hooker, parameters), this::deoptimize);
        } catch (ReflectiveOperationException e) {
            log(Log.ERROR, "FlymeMod", "Cannot resolve heads-up width classes", e);
        }
        try {
            activeTileCornerHooks = new TileCornerHooks(loader, this::loadSettings,
                    () -> tileCornersEnabled, () -> tileCornerPercent, () -> networkSplitStyle, () -> circleSmallTilesEnabled,
                    (message, error) -> log(Log.ERROR, "FlymeMod", message, error));
        } catch (ReflectiveOperationException | RuntimeException error) {
            log(Log.ERROR, "FlymeMod", "Cannot resolve tile corner shapes", error);
        }
        try {
            if (activeTileCornerHooks != null) {
                activeNotificationAppearanceHooks = new NotificationAppearanceHooks(loader, this::loadSettings,
                        () -> notificationLightEnabled, () -> notificationLightOpacity,
                        () -> notificationCustomCornersEnabled, () -> notificationCornerPercent, activeTileCornerHooks,
                        (message, error) -> log(Log.ERROR, "FlymeMod", message, error));
                deoptimize(loader.loadClass("com.android.systemui.statusbar.notification.row.ExpandableNotificationRow")
                        .getDeclaredMethod("dispatchDraw",Canvas.class));
                activeNotificationAppearanceHooks.install(
                        (name, method, hooker, parameters) -> install(loader, name, method, hooker, parameters));
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            log(Log.ERROR, "FlymeMod", "Cannot resolve notification appearance classes", error);
        }
        try {
            activeColorOsMaterialHooks = new ColorOsMaterialHooks(loader, this::loadSettings,
                    () -> colorOsContourEnabled, () -> networkSplitStyle != 0, () -> notificationContourEnabled,
                    () -> notificationLightOpacity, activeTileCornerHooks,
                    view -> notificationCustomCornersEnabled && activeNotificationAppearanceHooks != null
                            ? activeNotificationAppearanceHooks.radius(view) : Double.NaN,
                    (message, error) -> log(Log.ERROR, "FlymeMod", message, error));
            activeColorOsMaterialHooks.install(loader,
                    (name, method, hooker, parameters) -> install(loader, name, method, hooker, parameters));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log(Log.ERROR, "FlymeMod", "Cannot resolve ColorOS control center materials", error);
        }
        installOriginalNotificationIcons(loader);
        installMonochromeNotificationActions(loader);
        try {
            new NotificationBackgroundFadeHooks(loader).install(
                    (name,method,hooker,parameters) -> install(loader,name,method,hooker,parameters));
        } catch (ReflectiveOperationException | RuntimeException error) {
            log(Log.ERROR,"FlymeMod","Cannot resolve merged notification background fade",error);
        }
        try {
            activeNotificationActionBackgroundHooks=new NotificationActionBackgroundHooks(loader,this::loadSettings,
                    () -> removeNotificationActionBackgroundEnabled);
            activeNotificationActionBackgroundHooks.install(
                    (name,method,hooker,parameters) -> install(loader,name,method,hooker,parameters));
        } catch (ReflectiveOperationException | RuntimeException error) {
            log(Log.ERROR,"FlymeMod","Cannot resolve notification action bar background",error);
        }
        try {
            activeChargingInfoHooks = new ChargingInfoHooks(loader, this::loadSettings,
                    () -> statusBarChargingInfoMode, (view, reference) -> {
                        if (activeClockFontHooks != null) activeClockFontHooks.applyStatusBarFont(view, reference);
                        else {
                            view.setTypeface(reference.getTypeface());
                            view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, reference.getTextSize());
                            view.setIncludeFontPadding(reference.getIncludeFontPadding());
                        }
                    }, (message, error) -> log(Log.ERROR, "FlymeMod", message, error),
                    message -> log(Log.INFO, "FlymeMod", message));
            activeChargingInfoHooks.install((name, method, hooker, parameters) -> install(loader, name, method, hooker, parameters));
        } catch (ReflectiveOperationException | RuntimeException error) {
            log(Log.ERROR, "FlymeMod", "Cannot resolve Flyme charging information view", error);
        }
        try {
            new SignalHooks(loader,
                    (name, method, hooker, parameters) -> install(loader, name, method, hooker, parameters),
                    this::loadSettings, view -> {
                        if (activeClockFontHooks != null) activeClockFontHooks.applyStatusBarFont(view);
                    },
                    () -> mergeDualSignalEnabled, () -> separateNetworkTypeEnabled,
                    (message, error) -> log(Log.ERROR, "FlymeMod", message, error)).install();
        } catch (ReflectiveOperationException e) {
            log(Log.ERROR, "FlymeMod", "Cannot resolve Flyme mobile signal classes", e);
        }
        try {
            secondaryExpansionHooks = new SecondaryExpansionHooks(loader, this::loadSettings,
                    () -> secondaryExpansionEnabled, (message, error) -> log(Log.ERROR, "FlymeMod", message, error));
            secondaryExpansionHooks.setSlowRebound(() -> combinedPullAnimationEnabled);
            secondaryExpansionHooks.install((name, method, hooker, parameters) -> install(loader, name, method, hooker, parameters));
        } catch (ReflectiveOperationException e) {
            log(Log.ERROR, "FlymeMod", "Cannot resolve secondary QS expansion hooks", e);
        }
        try {
            new CombinedEmptyShadeHooks(loader, this::loadSettings, () -> combinedEmptyShadeKeepOpenEnabled,
                    (message, error) -> log(Log.ERROR, "FlymeMod", message, error)).install(
                    (name, method, hooker, parameters) -> install(loader, name, method, hooker, parameters));
        } catch (ReflectiveOperationException e) {
            log(Log.ERROR, "FlymeMod", "Cannot resolve combined empty-shade hooks", e);
        }
        try {
            activeRowRevealHooks = new RowRevealHooks(loader, this::loadSettings, () -> combinedPullAnimationEnabled,
                    (message, error) -> log(Log.ERROR, "FlymeMod", message, error));
            activeRowRevealHooks.install(
                    (name, method, hooker, parameters) -> install(loader, name, method, hooker, parameters), this::deoptimize);
            ReboundTimingHooks rebound = new ReboundTimingHooks(() -> combinedPullAnimationEnabled);
            rebound.install(
                    (name, method, hooker, parameters) -> install(loader, name, method, hooker, parameters));
            rebound.installRowStretch(loader,
                    (name, method, hooker, parameters) -> install(loader, name, method, hooker, parameters));
            BlurRevealTimingHooks blurTiming = new BlurRevealTimingHooks(loader, () -> combinedPullAnimationEnabled,
                    (message, error) -> log(Log.ERROR, "FlymeMod", message, error));
            activeRowRevealHooks.addVisibilityListener(blurTiming::onVisibilityChanged);
            activeRowRevealHooks.addProgressListener(blurTiming::onRevealProgress);
            blurTiming.deoptimizeWriters(loader, this::deoptimize);
            blurTiming.install(loader,
                    (name, method, hooker, parameters) -> install(loader, name, method, hooker, parameters));
            CombinedShadeAnimationHooks animations = new CombinedShadeAnimationHooks(loader, this::loadSettings,
                    () -> combinedPullAnimationEnabled, () -> combinedCollapseFixEnabled,
                    () -> qsTranslationOriginEnabled,
                    (message, error) -> log(Log.ERROR, "FlymeMod", message, error));
            activeRowRevealHooks.addVisibilityListener(animations::onVisibilityChanged);
            activeRowRevealHooks.addProgressListener(animations::onRevealProgress);
            animations.setVisibleContent(activeRowRevealHooks::hasVisibleContent);
            if (secondaryExpansionHooks != null) {
                animations.setSecondaryOwner(secondaryExpansionHooks::ownsGesture);
                animations.setSecondaryTouchOwner(secondaryExpansionHooks::allowsTouch);
                secondaryExpansionHooks.setVisualOwner(animations::releaseForSecondary);
            }
            animations.install(
                    (name, method, hooker, parameters) -> install(loader, name, method, hooker, parameters));
        } catch (ReflectiveOperationException e) {
            log(Log.ERROR, "FlymeMod", "Cannot resolve combined shade animation classes", e);
        }
        try {
            SeparateShadeOriginHooks separate = new SeparateShadeOriginHooks(loader, this::loadSettings, () -> qsTranslationOriginEnabled,
                    (message, error) -> log(Log.ERROR, "FlymeMod", message, error));
            separate.setSlowRebound(() -> combinedPullAnimationEnabled);
            if (activeRowRevealHooks != null) activeRowRevealHooks.addVisibilityListener(separate::onVisibilityChanged);
            separate.install(
                    (name, method, hooker, parameters) -> install(loader, name, method, hooker, parameters));
        } catch (ReflectiveOperationException e) {
            log(Log.ERROR, "FlymeMod", "Cannot resolve separate QS translation origin classes", e);
        }
        try {
            CollapsedCardHeightHooks heights = new CollapsedCardHeightHooks(loader, this::loadSettings,
                    () -> restoreCollapsedCardHeightEnabled, (message, error) -> log(Log.ERROR, "FlymeMod", message, error));
            heights.setGeometryEnabled(() -> secondaryExpansionEnabled);
            heights.install((name, method, hooker, parameters) -> install(loader, name, method, hooker, parameters));
        } catch (ReflectiveOperationException e) {
            log(Log.ERROR, "FlymeMod", "Cannot resolve collapsed QS card height classes", e);
        }
        try {
            new WifiLabelHooks(loader, this::loadSettings, () -> wifiLabelEnabled).install(
                    (name, method, hooker, parameters) -> install(loader, name, method, hooker, parameters));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log(Log.ERROR, "FlymeMod", "Cannot resolve control center Wi-Fi labels", error);
        }
        installDarkBackground(loader);
        installBlurRadius(loader);
        installBackgroundScale(loader);
        try {
            SeparateShadeSwipeHooks swipe = new SeparateShadeSwipeHooks(loader, this::loadSettings, () -> separateShadeSwipeEnabled,
                    (message, error) -> log(Log.ERROR, "FlymeMod", message, error));
            activeSeparateShadeSwipeHooks = swipe;
            swipe.setBlurRadiusOverride(() -> blurRadiusEnabled ? blurRadius * ModuleSettings.BLUR_SCALE : -1);
            if (activeRowRevealHooks != null) swipe.setContentPose(activeRowRevealHooks::setHorizontalPose);
            swipe.install(
                    (name, method, hooker, parameters) -> install(loader, name, method, hooker, parameters), this::deoptimize);
        } catch (ReflectiveOperationException error) {
            log(Log.ERROR, "FlymeMod", "Cannot resolve separate shade swipe classes", error);
        }
        try {
            activeMediaSwipeHooks = new MediaSwipeHooks(loader, this::loadSettings, () -> fixMediaSwipeEnabled,
                    (message, error) -> log(Log.ERROR, "FlymeMod", message, error));
            MediaSwipeHooks mediaSwipe = activeMediaSwipeHooks;
            for (java.lang.reflect.Constructor<?> constructor : loader.loadClass(
                    "com.android.systemui.media.controls.ui.view.MediaCarouselScrollHandler").getDeclaredConstructors()) {
                hook(constructor).intercept(chain -> {
                    Object result = chain.proceed();
                    mediaSwipe.onCreated(chain.getThisObject());
                    return result;
                });
            }
            activeMediaSwipeHooks.install(loader,
                    (name, method, hooker, parameters) -> install(loader, name, method, hooker, parameters), this::deoptimize);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log(Log.ERROR, "FlymeMod", "Cannot resolve media swipe behavior", error);
        }
        try {
            activeNotificationSwipeHooks = new NotificationSwipeHooks(loader, this::loadSettings,
                    () -> notificationSwipeSegmentsEnabled,
                    (message, error) -> log(Log.ERROR, "FlymeMod", message, error));
            activeNotificationSwipeHooks.install(
                    (name, method, hooker, parameters) -> install(loader, name, method, hooker, parameters), this::deoptimize);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log(Log.ERROR, "FlymeMod", "Cannot resolve notification swipe segments", error);
        }
        try {
            activeCircleTileHooks = new CircleTileHooks(loader, this::loadSettings, () -> circleSmallTilesEnabled,
                    (message, error) -> log(Log.ERROR, "FlymeMod", message, error));
            activeCircleTileHooks.install(
                    (name, method, hooker, parameters) -> install(loader, name, method, hooker, parameters));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log(Log.ERROR, "FlymeMod", "Cannot resolve circular small tiles", error);
        }
        try {
            activeSplitNetworkCardHooks = new SplitNetworkCardHooks(loader, this::loadSettings,
                    () -> networkSplitStyle, () -> optimize2x1TextEnabled, this::applyIconColor,
                    (message, error) -> log(Log.ERROR, "FlymeMod", message, error));
            activeSplitNetworkCardHooks.setLightSurfacePolicy(this::shouldUseLightTileSurface);
            if (activeColorOsMaterialHooks != null)
                activeSplitNetworkCardHooks.setContourListener(card -> {
                    activeColorOsMaterialHooks.updateNetworkRows(card);
                    if (activeTileCornerHooks != null) activeTileCornerHooks.observeNetwork(card);
                });
            activeSplitNetworkCardHooks.install(
                    (name, method, hooker, parameters) -> install(loader, name, method, hooker, parameters));
            activeFoldIdleMediaHooks = new FoldIdleMediaHooks(loader, this::loadSettings,
                    () -> splitNetworkCardEnabled && foldIdleMediaEnabled, activeSplitNetworkCardHooks,
                    (message, error) -> log(error == null ? Log.INFO : Log.ERROR, "FlymeMod", message, error));
            activeFoldIdleMediaHooks.install(
                    (name, method, hooker, parameters) -> install(loader, name, method, hooker, parameters));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log(Log.ERROR, "FlymeMod", "Cannot resolve split network card", error);
        }
        try {
            activeCardIconLayoutHooks = new CardIconLayoutHooks(loader, this::loadSettings, () -> optimize2x1TextEnabled,
                    (message, error) -> log(Log.ERROR, "FlymeMod", message, error));
            activeCardIconLayoutHooks.install(
                    (name, method, hooker, parameters) -> install(loader, name, method, hooker, parameters));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log(Log.ERROR, "FlymeMod", "Cannot resolve 2x1 card icon layout", error);
        }
        try {
            activeMuteSlashHooks = new MuteSlashHooks(loader, this::loadSettings, () -> animatedMuteSlashEnabled,
                    icon -> whiteActiveEnabled ? sliderIconColors.getOrDefault(icon, Color.WHITE) | 0xFF000000
                            : icon.getImageTintList() == null ? Color.WHITE
                            : icon.getImageTintList().getColorForState(icon.getDrawableState(), Color.WHITE),
                    (message, error) -> log(Log.ERROR, "FlymeMod", message, error));
            activeMuteSlashHooks.install(
                    (name, method, hooker, parameters) -> install(loader, name, method, hooker, parameters));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log(Log.ERROR, "FlymeMod", "Cannot resolve animated mute slash", error);
        }
        try {
            new NetworkCardAnimationHooks(loader, this::loadSettings, () -> scaleEnabled).install(
                    (name, method, hooker, parameters) -> install(loader, name, method, hooker, parameters), this::deoptimize);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log(Log.ERROR, "FlymeMod", "Cannot resolve network card animation geometry", error);
        }
        try {
            new ShadeRestPositionHooks(loader, this::loadSettings,
                    () -> combinedPullAnimationEnabled || qsTranslationOriginEnabled).install(
                    (name, method, hooker, parameters) -> install(loader, name, method, hooker, parameters));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log(Log.ERROR, "FlymeMod", "Cannot resolve control-center resting alignment", error);
        }
        install(loader, PHONE + "ControlCenterPanelView", "onLayout", chain -> {
            Object result = chain.proceed();
            View root = (View) chain.getThisObject();
            loadSettings(root.getContext());
            if (scaleEnabled) {
                View container = (View) root.getClass().getField("mQSContainer").get(root);
                applyScale(container);
            }
            return result;
        }, boolean.class, int.class, int.class, int.class, int.class);
        install(loader, PHONE + "MzQSContainerImpl", "setPadding", chain -> {
            Object result = chain.proceed();
            View container = (View) chain.getThisObject();
            loadSettings(container.getContext());
            if (scaleEnabled) applyScale(container);
            return result;
        }, int.class);
        install(loader, PHONE + "MzQSContainerImpl", "onFinishInflate", chain -> {
            Object result = chain.proceed();
            View container = (View) chain.getThisObject();
            // Both panel modes share this container. Its width is zero during inflation,
            // so establish the center pivot after layout, including later size changes.
            container.addOnLayoutChangeListener((view, left, top, right, bottom,
                    oldLeft, oldTop, oldRight, oldBottom) -> {
                loadSettings(view.getContext());
                if (scaleEnabled) applyScale(view);
            });
            return result;
        });
        install(loader, "com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout",
                "updateSidePadding", chain -> {
                    Object result = chain.proceed();
                    View stack = (View) chain.getThisObject();
                    loadSettings(stack.getContext());
                    notificationWidthStacks.add(stack);
                    if (activeCombinedNotificationWidthHooks != null
                            && activeCombinedNotificationWidthHooks.applyWidth(stack,(Integer)chain.getArg(0))) return result;
                    if (!scaleEnabled) return result;
                    Object interactor = stack.getClass().getField("mShadeInteractor").get(stack);
                    Object mode = interactor.getClass().getMethod("isClassicsMode").invoke(interactor);
                    boolean combined = Boolean.TRUE.equals(mode.getClass().getMethod("getValue").invoke(mode));
                    int width = (Integer) chain.getArg(0);
                    if (combined && width > 0) {
                        notificationWidthStacks.add(stack);
                        View container = findQsContainer(stack);
                        if (container != null) {
                            int target = notificationTargetWidth(container);
                            if (target > 0) {
                                // Use the measured mini-panel content, not a screen-width
                                // assumption or a resource from a different density context.
                                target = Math.min(width, target);
                                stack.getClass().getField("mSidePaddings").setInt(stack,
                                        Math.max(0, Math.round((width - target) / 2f)));
                            }
                        }
                    }
                    return result;
                }, int.class);
        install(loader, "com.android.systemui.shade.QuickSettingsControllerImpl",
                "calculateNotificationsTopPadding", chain -> {
                    float original = (Float) chain.proceed();
                    Object controller = chain.getThisObject();
                    // Clear the previous mode's spacer before any early return (split shade,
                    // landscape or an unbound QS), so later setting changes cannot subtract it.
                    if (activeCombinedNotificationWidthHooks != null) activeCombinedNotificationWidthHooks.reset(controller);
                    Object qs = controller.getClass().getField("mQs").get(controller);
                    if (qs == null) return original;
                    View container = (View) qs.getClass().getMethod("getContainer").invoke(qs);
                    if (container == null || !container.getClass().getName()
                            .equals(PHONE + "MzQSContainerImpl")) return original;
                    loadSettings(container.getContext());
                    if (container.getResources().getConfiguration().orientation
                            != Configuration.ORIENTATION_PORTRAIT) return original;
                    Object interactor = controller.getClass().getField("mShadeInteractor").get(controller);
                    Object mode = interactor.getClass().getMethod("isClassicsMode").invoke(interactor);
                    if (!Boolean.TRUE.equals(mode.getClass().getMethod("getValue").invoke(mode))) return original;
                    // Compensate only the operating area below the fixed scale pivot.
                    // Recalculate from the original QS height on each frame, without accumulation.
                    float operatingHeight = Math.max(0f,
                            original - container.getY() - container.getPaddingTop());
                    float adjusted = scaleEnabled ? original + operatingHeight * (operationScale(container) - 1f) : original;
                    return activeCombinedNotificationWidthHooks == null ? adjusted
                            : activeCombinedNotificationWidthHooks.padding(controller,container,adjusted);
                }, boolean.class, int.class, float.class);

        try {
            activeTileBackdropHooks = new TileBackdropHooks(loader, () -> tileBackgroundColorEnabled,
                    () -> tileBackgroundSaturation / 100.0, () -> notificationBackgroundColorEnabled,
                    () -> notificationBackgroundSaturation / 100.0, () -> networkSplitStyle, activeTileCornerHooks,
                    (message, error) -> log(Log.ERROR, "FlymeMod", message, error),
                    message -> log(Log.INFO, "FlymeMod", message));
            activeTileBackdropHooks.install((name, method, hooker, parameters) -> install(loader, name, method, hooker, parameters));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log(Log.ERROR, "FlymeMod", "Cannot resolve live tile backdrop shader", error);
        }
        try {
            new TileDialogHooks(loader, () -> lightEnabled, this::lightBackgroundColor, this::loadSettings,
                    view -> { if (activeColorOsMaterialHooks != null) activeColorOsMaterialHooks.observeDialog(view); },
                    view -> { if (activeTileBackdropHooks != null) activeTileBackdropHooks.observeDialog(view); },
                    (message, error) -> log(Log.ERROR, "FlymeMod", message, error), () -> darkenEnabled)
                    .install((name, method, hooker, parameters) -> install(loader, name, method, hooker, parameters));
        } catch (ReflectiveOperationException | RuntimeException error) {
            log(Log.ERROR, "FlymeMod", "Cannot resolve control center detail dialog surfaces", error);
        }
        try {
            MediaOutputSurfaceHooks.install(loader,
                    (name, method, hooker, parameters) -> install(loader, name, method, hooker, parameters),
                    this::loadSettings, () -> lightEnabled, () -> tileBackgroundColorEnabled,
                    this::lightBackgroundColor,
                    view -> { if (activeColorOsMaterialHooks != null) activeColorOsMaterialHooks.observeDialog(view); },
                    view -> { if (activeTileBackdropHooks != null) activeTileBackdropHooks.observeDialog(view); },
                    () -> darkenEnabled, () -> DARK_BACKGROUND);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log(Log.ERROR, "FlymeMod", "Cannot resolve media output dialog surface", error);
        }
        XposedInterface.Hooker background = chain -> {
            View tile = (View) chain.getThisObject();
            lightBackgroundTiles.add(tile);
            loadSettings(tile.getContext());
            if (activeTileBackdropHooks != null) activeTileBackdropHooks.observe(tile);
            boolean policyDisabled = chain.getArgs().size() > 1 && Boolean.TRUE.equals(chain.getArg(1));
            // Connectivity activation belongs to its icon circles, not the card surface.
            if (whiteActiveEnabled && !hasCircularCardActivation((View) chain.getThisObject())
                    && (Integer) chain.getArg(0) == 2 && !policyDisabled) {
                whiteActiveTileViews.add((View) chain.getThisObject());
                return activeTileBackgroundColor();
            }
            return lightEnabled ? lightBackgroundColor() : chain.proceed();
        };
        install(loader, "com.android.systemui.qs.tileimpl.QSTileViewImpl",
                "getBackgroundColorForState", background, int.class, boolean.class);
        // Connectivity, media, device controls and other large cards inherit this method.
        install(loader, "com.flyme.systemui.qs.tileimpl.FlymeCustomQSTileView",
                "getBackgroundColorForState", background, int.class);
        install(loader, PHONE + "MzQQSPanel", "initialize", chain -> {
            Object result = chain.proceed();
            if (activeTileBackdropHooks != null) activeTileBackdropHooks.observePanel((View) chain.getThisObject());
            return result;
        });
        install(loader, PHONE + "MzQSPanel", "setWrapperForUiModel", chain -> {
            View panel = (View) chain.getThisObject();
            lightBackgroundWrappers.put(panel, chain.getArgs().toArray());
            loadSettings(panel.getContext());
            Object result;
            if (!lightEnabled) result = chain.proceed();
            else {
                Object[] args = chain.getArgs().toArray();
                args[0] = args[1] = args[2] = lightBackgroundColor();
                result = chain.proceed(args);
            }
            if (activeTileBackdropHooks != null) activeTileBackdropHooks.observePanel(panel);
            return result;
        }, int.class, int.class, int.class, int.class, int.class);
        // Slider state/theme changes have a separate background setter.
        install(loader, "com.android.systemui.settings.brightness.BrightnessSliderView",
                "changeSliderBgColor", chain -> {
                    Object result = chain.proceed();
                    View view = (View) chain.getThisObject();
                    loadSettings(view.getContext());
                    if (lightEnabled || whiteActiveEnabled) {
                        Object slider = view.getClass().getField("mSlider").get(view);
                        if (slider != null) tintSliderDrawable((Drawable) slider.getClass()
                                .getMethod("getProgressDrawable").invoke(slider));
                    }
                    return result;
                }, int.class);
        // ToggleSeekBar is a Meizu VerticalSeekBar, not android.widget.SeekBar.
        // Its attach/configuration/resource refresh paths all replace the layered drawable here.
        install(loader, "com.meizu.common.widget.ProgressBar", "setProgressDrawable", chain -> {
            View slider = (View) chain.getThisObject();
            if (slider.getClass().getName().equals("com.android.systemui.settings.brightness.ToggleSeekBar")) {
                loadSettings(slider.getContext());
                Drawable drawable = (Drawable) chain.getArg(0);
                if (sliderActiveCornersEnabled && drawable instanceof LayerDrawable layers) {
                    layers.mutate();
                    Drawable progress = layers.findDrawableByLayerId(android.R.id.progress);
                    if (progress instanceof android.graphics.drawable.ClipDrawable clip
                            && !(progress instanceof RoundedSliderProgress)) {
                        RoundedSliderProgress rounded = new RoundedSliderProgress(clip.getDrawable(),
                                2f * slider.getResources().getDisplayMetrics().density);
                        rounded.setBounds(clip.getBounds());
                        rounded.setState(clip.getState());
                        rounded.setLevel(clip.getLevel());
                        rounded.setVisible(clip.isVisible(), false);
                        rounded.setLayoutDirection(clip.getLayoutDirection());
                        layers.setDrawableByLayerId(android.R.id.progress, rounded);
                    }
                }
                if (lightEnabled || whiteActiveEnabled) tintSliderDrawable(drawable);
            }
            Object result = chain.proceed();
            if (activeTileBackdropHooks != null && isToggleSlider(slider)) activeTileBackdropHooks.observe(slider);
            if (whiteActiveEnabled && isToggleSlider(slider)) updateSliderIcon(slider, false);
            return result;
        }, Drawable.class);
        installWhiteActive(loader);
        try {
            activeEarphoneNoiseHooks=new EarphoneNoiseHooks(loader,()->earphoneNoiseControlEnabled,
                    (message,error)->log(error==null?Log.INFO:Log.ERROR,"FlymeMod",message,error));
            activeEarphoneNoiseHooks.install((className, methodName, interceptor, parameterTypes) ->
                    install(loader, className, methodName, interceptor, parameterTypes),loader,this::deoptimize);
        } catch (ReflectiveOperationException | RuntimeException error) {
            log(Log.ERROR,"FlymeMod","Cannot hook earphone noise controls",error);
        }
    }

    private void tintSliderDrawable(Drawable drawable) {
        if (lightEnabled && drawable instanceof LayerDrawable layers
                && layers.findDrawableByLayerId(android.R.id.background) instanceof GradientDrawable bg) {
            bg.mutate();
            lightSliderBackgrounds.add(bg);
            bg.setColor(lightBackgroundColor());
        }
        if (whiteActiveEnabled && drawable instanceof LayerDrawable layers) {
            tintProgress(layers.findDrawableByLayerId(android.R.id.progress));
        }
    }

    private void installNotificationAppearanceLayout(ClassLoader loader) {
        String row = "com.android.systemui.statusbar.notification.row.";
        // Flyme's redacted base/live public templates bypass getViewHeight():
        // the row returns the inner public view height without the content insets.
        install(loader, row + "ExpandableNotificationRow", "getMinHeight", chain ->
                notificationPublicHeight((View) chain.getThisObject(), (Integer) chain.proceed()));
        install(loader, row + "ExpandableNotificationRow", "onLayout", chain -> {
            Object result = chain.proceed();
            View notification = (View) chain.getThisObject();
            // Padding changes before native onLayout snapshots intrinsic height.
            // Notify after children have their final bounds, including restoration.
            if (notificationPaddingHeightChanges.remove(notification))
                notification.getClass().getMethod("notifyHeightChanged", boolean.class).invoke(notification, false);
            return result;
        }, boolean.class, int.class, int.class, int.class, int.class);
        // The content container includes padding in measured, collapsed, expanded
        // and heads-up heights. Header-only padding cannot enlarge the card.
        install(loader, row + "NotificationContentView", "onMeasure", chain -> {
            View content = (View) chain.getThisObject();
            loadSettings(content.getContext());
            int requestedPadding = notificationCustomCornersEnabled && activeNotificationAppearanceHooks != null
                    ? Math.round(activeNotificationAppearanceHooks.radius(content) * .25f) : 0;
            int previousExtra = notificationAddedVerticalPadding(content);
            applyNotificationContentPadding(content, requestedPadding);
            if (previousExtra != notificationAddedVerticalPadding(content)) {
                View notification = (View) content.getClass().getField("mContainingNotification").get(content);
                if (notification != null) notificationPaddingHeightChanges.add(notification);
            }
            // measureChildWithMargins subtracts outer padding from both expanded and
            // heads-up caps. Contracted measurement already adds it before measuring.
            java.lang.reflect.Field maxHeight = content.getClass().getField("mNotificationMaxHeight");
            java.lang.reflect.Field headsHeight = content.getClass().getField("mHeadsUpHeight");
            int originalMaxHeight = maxHeight.getInt(content);
            int originalHeadsHeight = headsHeight.getInt(content);
            int extra = notificationAddedVerticalPadding(content);
            android.view.ViewGroup.LayoutParams[] fixedParams = new android.view.ViewGroup.LayoutParams[2];
            int[] fixedHeights = new int[2];
            if (extra > 0) {
                String[] children = {"mExpandedChild", "mHeadsUpChild"};
                for (int index = 0; index < children.length; index++) {
                    View child = (View) content.getClass().getField(children[index]).get(content);
                    if (child != null && child.getLayoutParams().height >= 0) {
                        fixedParams[index] = child.getLayoutParams();
                        fixedHeights[index] = fixedParams[index].height;
                        // Fixed remote-view heights are another cap before padding subtraction.
                        fixedParams[index].height += extra;
                    }
                }
                maxHeight.setInt(content, originalMaxHeight + extra);
                headsHeight.setInt(content, originalHeadsHeight + extra);
            }
            try {
                Object result = chain.proceed();
                if (extra > 0) {
                    View expanded = (View) content.getClass().getField("mExpandedChild").get(content);
                    View contracted = (View) content.getClass().getField("mContractedChild").get(content);
                    // Native onMeasure remeasures a shorter expanded child using the
                    // contracted child's height as a parent spec. That fallback subtracts
                    // our outer padding again, unlike the initial expanded measurement.
                    // Restore only our deduction before layout/stack expansion reads it.
                    if (expanded != null && contracted != null
                            && expanded.getMeasuredHeight() < contracted.getMeasuredHeight()) {
                        int height = Math.min(contracted.getMeasuredHeight(), expanded.getMeasuredHeight() + extra);
                        expanded.measure(View.MeasureSpec.makeMeasureSpec(expanded.getMeasuredWidth(), View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                    }
                }
                return result;
            } finally {
                if (extra > 0) {
                    maxHeight.setInt(content, originalMaxHeight);
                    headsHeight.setInt(content, originalHeadsHeight);
                    for (int index = 0; index < fixedParams.length; index++)
                        if (fixedParams[index] != null) fixedParams[index].height = fixedHeights[index];
                }
            }
        }, int.class, int.class);
        // Flyme folds grouped notifications into a separate summary card. Its
        // FrameLayout content root measures padding into getContentHeight(), while
        // the sibling background continues to cover the full card.
        install(loader, "com.flyme.notification.view.NotificationGroupCollapseContainer", "onMeasure", chain -> {
            View group = (View) chain.getThisObject();
            loadSettings(group.getContext());
            View content = (View) group.getClass().getField("mRootContainer").get(group);
            if (content != null) {
                int requestedPadding = notificationCustomCornersEnabled && activeNotificationAppearanceHooks != null
                        ? Math.round(activeNotificationAppearanceHooks.radius(group) * .25f) : 0;
                applyNotificationContentPadding(content, requestedPadding);
            }
            return chain.proceed();
        }, int.class, int.class);
        install(loader, row + "wrapper.NotificationTemplateViewWrapper", "updateActionOffset", chain -> {
            Object result = chain.proceed();
            Object wrapper = chain.getThisObject();
            View template = (View) wrapper.getClass().getField("mView").get(wrapper);
            loadSettings(template.getContext());
            if (!notificationCustomCornersEnabled || !(template.getParent() instanceof View content)
                    || !content.getClass().getName().equals(row + "NotificationContentView")) return result;
            if (content.getClass().getField("mIsHeadsUp").getBoolean(content)
                    || content.getClass().getField("mExpandedChild").get(content) != template) return result;
            View actions = (View) wrapper.getClass().getField("mActionsContainer").get(wrapper);
            if (actions != null) {
                // Wrapper heights include the outer padding; actions belong to the
                // inner template and must not consume that padding as extra offset.
                int height = wrapper.getClass().getField("mContentHeight").getInt(wrapper);
                int hint = wrapper.getClass().getField("mMinHeightHint").getInt(wrapper);
                int header = (Integer) wrapper.getClass().getMethod("getHeaderTranslation", boolean.class).invoke(wrapper, false);
                float offset = Math.max(0, Math.max(height, hint) - template.getHeight() - header
                        - notificationAddedVerticalPadding(content));
                actions.setTranslationY(offset);
            }
            return result;
        });
        install(loader, row + "wrapper.NotificationHeaderViewWrapper", "resolveHeaderViews", chain -> {
            Object result = chain.proceed();
            Object wrapper = chain.getThisObject();
            View content = (View) wrapper.getClass().getField("mView").get(wrapper);
            loadSettings(content.getContext());
            if (activeNotificationAppearanceHooks != null) activeNotificationAppearanceHooks.configureHeader(wrapper,content);
            return result;
        });
        install(loader, "androidx.constraintlayout.utils.widget.ImageFilterView", "setRound", chain -> {
            View image = (View) chain.getThisObject();
            loadSettings(image.getContext());
            int id = image.getResources().getIdentifier("media_mask_bg", "id", "com.android.systemui");
            if (id != 0 && image.getId() == id) {
                if (activeNotificationAppearanceHooks != null)
                    return chain.proceed(new Object[]{activeNotificationAppearanceHooks.imageRadius(image,(Float)chain.getArg(0))});
            }
            return chain.proceed();
        }, float.class);
        install(loader, "com.android.systemui.media.controls.ui.controller.MediaControlPanel",
                "setMaskDrawable", chain -> {
            Object result = chain.proceed();
            Object panel = chain.getThisObject();
            Object holder = panel.getClass().getField("mMediaViewHolder").get(panel);
            if (holder != null) {
                View mask = (View) holder.getClass().getMethod("getViewMask").invoke(holder);
                loadSettings(mask.getContext());
                if (activeNotificationAppearanceHooks != null) activeNotificationAppearanceHooks.configureMediaMask(mask);
            }
            return result;
        }, int.class);
    }

    private void tintProgress(Drawable drawable) {
        if (drawable instanceof DrawableWrapper wrapper) tintProgress(wrapper.getDrawable());
        else if (drawable instanceof GradientDrawable gradient) {
            gradient.mutate();
            whiteActiveProgress.add(gradient);
            gradient.setColor(activeBackgroundColor());
        } else if (drawable instanceof LayerDrawable layers) {
            for (int i = 0; i < layers.getNumberOfLayers(); i++) tintProgress(layers.getDrawable(i));
        }
    }

    private int lightBackgroundColor() {
        return Color.argb(Math.round(lightBackgroundOpacity * 255f / 100f), 255, 255, 255);
    }

    private void refreshLightBackgroundOpacity() {
        for (View panel : new ArrayList<>(lightBackgroundWrappers.keySet())) {
            if (panel == null) continue;
            Object[] original = lightBackgroundWrappers.get(panel);
            if (original == null) continue;
            try { panel.getClass().getMethod("setWrapperForUiModel", int.class, int.class, int.class,
                    int.class, int.class).invoke(panel, original); }
            catch (ReflectiveOperationException | RuntimeException error) {
                log(Log.ERROR, "FlymeMod", "Cannot refresh light card wrapper", error);
            }
        }
        for (View tile : new ArrayList<>(lightBackgroundTiles)) {
            if (tile == null) continue;
            try { tile.getClass().getMethod("updateResources").invoke(tile); }
            catch (ReflectiveOperationException | RuntimeException error) {
                log(Log.ERROR, "FlymeMod", "Cannot refresh light tile background", error);
            }
        }
        for (GradientDrawable background : new ArrayList<>(lightSliderBackgrounds))
            if (background != null) background.setColor(lightBackgroundColor());
    }

    private int activeTileBackgroundColor() {
        return controlCenterStyle == 2 ? customActiveColor : activeBackgroundColor();
    }

    private int activeTileForegroundColor() {
        return controlCenterStyle == 2 ? Color.WHITE : ACTIVE_FOREGROUND;
    }

    private int activeBackgroundColor() {
        return Color.argb(Math.round(whiteActiveOpacity * 255f / 100f), 255, 255, 255);
    }

    private void refreshWhiteActiveOpacity() {
        java.util.Set<View> tiles = new java.util.HashSet<>(lightBackgroundTiles);
        tiles.addAll(whiteActiveTileViews);
        for (View tile : new ArrayList<>(tiles)) {
            if (tile == null) continue;
            try { tile.getClass().getMethod("updateResources").invoke(tile); }
            catch (ReflectiveOperationException | RuntimeException error) {
                log(Log.ERROR, "FlymeMod", "Cannot refresh active tile opacity", error);
            }
        }
        for (View icon : new ArrayList<>(whiteActiveCircleViews)) {
            if (icon == null) continue;
            try {
                Object state = icon.getClass().getField("mIconState").get(icon);
                if (state != null && state.getClass().getField("state").getInt(state) == 2) {
                    icon.getClass().getMethod("setCircleIconBg", int.class).invoke(icon, icon.getClass().getMethod("getCircleIconBgColor", String.class, int.class)
                                    .invoke(icon, state.getClass().getField("spec").get(state), 2));
                }
            } catch (ReflectiveOperationException | RuntimeException error) {
                log(Log.ERROR, "FlymeMod", "Cannot refresh active circle opacity", error);
            }
        }
        if (whiteActiveEnabled) for (GradientDrawable progress : new ArrayList<>(whiteActiveProgress)) {
            if (progress != null) progress.setColor(activeBackgroundColor());
        }
    }

    private boolean isToggleSlider(View view) {
        return view.getClass().getName().equals("com.android.systemui.settings.brightness.ToggleSeekBar");
    }

    private View sliderOwner(View view) {
        for (View current = view; current != null;
                current = current.getParent() instanceof View parent ? parent : null) {
            if (current.getClass().getName().equals("com.android.systemui.settings.brightness.BrightnessSliderView")) return current;
        }
        return null;
    }

    private void updateSliderIcon(View slider, boolean force) throws ReflectiveOperationException {
        View owner = sliderOwner(slider);
        if (owner == null) return;
        ImageView icon = (ImageView) owner.getClass().getField("mIconView").get(owner);
        if (icon == null) return;
        int value = (Integer) slider.getClass().getMethod("getProgress").invoke(slider);
        int max = (Integer) slider.getClass().getMethod("getMax").invoke(slider);
        int color = volumeSliderIcons.contains(icon)
                ? (!whiteActiveEnabled ? Color.WHITE
                    : max > 0 && (long)value*100 > (long)max*15 ? 0xCC555555 : 0xCCDADADA)
                : (max > 0 && (long) value * 100 > (long) max * 15
                    ? activeTileIconColor("brightness") : Color.WHITE);
        if (!force && Integer.valueOf(color).equals(sliderIconColors.get(icon))) return;
        // The brightness circle has overlapping fill and stroke. Tint each path
        // opaquely, then apply alpha once to the composited icon to avoid a darker rim.
        applyIconColor(icon, color | 0xFF000000);
        icon.setLayerType(Color.alpha(color) < 255 ? View.LAYER_TYPE_HARDWARE : View.LAYER_TYPE_NONE, null);
        icon.setAlpha(Color.alpha(color) / 255f);
        sliderIconColors.put(icon, color);
    }

    private void applyIconColor(ImageView icon, int color) throws ReflectiveOperationException {
        // LottieDrawable ignores ImageView's ordinary color filter, so tint its paths.
        ClassLoader loader = icon.getClass().getClassLoader();
        Class<?> keyPath = Class.forName("com.airbnb.lottie.model.KeyPath", false, loader);
        Class<?> callback = Class.forName("com.airbnb.lottie.value.LottieValueCallback", false, loader);
        Object path = keyPath.getConstructor(String[].class).newInstance((Object) new String[]{"**"});
        Object property = Class.forName("com.airbnb.lottie.LottieProperty", false, loader)
                .getField("COLOR_FILTER").get(null);
        Object filter = callback.getConstructor(Object.class)
                .newInstance(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
        icon.getClass().getMethod("addValueCallback", keyPath, Object.class, callback).invoke(icon, path, property, filter);
        if (icon.getDrawable() != null && !icon.getDrawable().getClass().getName().equals("com.airbnb.lottie.LottieDrawable")) {
            icon.setImageTintList(ColorStateList.valueOf(color));
        }
        icon.invalidate();
    }

    private void clearLottieTint(ImageView icon) throws ReflectiveOperationException {
        ClassLoader loader = icon.getClass().getClassLoader();
        Class<?> keyPath = Class.forName("com.airbnb.lottie.model.KeyPath", false, loader);
        Class<?> callback = Class.forName("com.airbnb.lottie.value.LottieValueCallback", false, loader);
        Object path = keyPath.getConstructor(String[].class).newInstance((Object) new String[]{"**"});
        Object property = Class.forName("com.airbnb.lottie.LottieProperty", false, loader)
                .getField("COLOR_FILTER").get(null);
        icon.getClass().getMethod("addValueCallback", keyPath, Object.class, callback)
                .invoke(icon, path, property, null);
    }

    private void refreshActiveIconTheme() {
        for (View icon : new ArrayList<>(whiteActiveCircleViews)) {
            if (icon == null) continue;
            try {
                icon.getClass().getMethod("setIconForUiModelChange").invoke(icon);
                Object state = icon.getClass().getField("mIconState").get(icon);
                if (state == null) continue;
                int value = state.getClass().getField("state").getInt(state);
                String spec = (String) state.getClass().getField("spec").get(state);
                int color = (Integer) icon.getClass().getMethod("getCircleIconBgColor", String.class, int.class)
                        .invoke(icon, spec, value);
                icon.getClass().getMethod("setCircleIconBg", int.class).invoke(icon, color);
            } catch (ReflectiveOperationException | RuntimeException error) {
                log(Log.ERROR, "FlymeMod", "Cannot restore active icon theme", error);
            }
        }
    }

    private void installWhiteActive(ClassLoader loader) {
        install(loader,"com.flyme.systemui.volume.QSVolumeControllerImpl","changeIconResource",chain -> {
            Object result=chain.proceed();
            ImageView icon=(ImageView)chain.getThisObject().getClass().getField("mIcon").get(chain.getThisObject());
            if(icon!=null) { volumeSliderIcons.add(icon); sliderIconColors.remove(icon); }
            return result;
        },int.class);
        install(loader, "android.widget.ImageView", "onDraw", chain -> {
            ImageView icon = (ImageView) chain.getThisObject();
            if (volumeSliderIcons.contains(icon)) {
                View owner = sliderOwner(icon);
                if (owner != null) {
                    View slider = (View) owner.getClass().getField("mSlider").get(owner);
                    if (slider != null) {
                        loadSettings(icon.getContext());
                        updateSliderIcon(slider, false);
                    }
                }
            }
            return chain.proceed();
        }, Canvas.class);
        Class<?> tileStateClass;
        try {
            tileStateClass = Class.forName("com.android.systemui.plugins.qs.QSTile$State", false, loader);
        } catch (ClassNotFoundException e) {
            log(Log.ERROR, "FlymeMod", "Cannot resolve active tile state", e);
            return;
        }
        try {
            String helperName = "com.meizu.common.animator.MzPressAnimationHelper";
            Class<?> helper = loader.loadClass(helperName);
            Class<?> spring = loader.loadClass("androidx.dynamicanimation.animation.SpringAnimation");
            java.lang.reflect.Field hardware = helper.getField("mUseHardwareLayer");
            install(loader, helperName, "useHardwareLayer", chain -> {
                View target = (View) chain.getArg(0);
                return isColorOsCircleIcon(target) ? false : chain.proceed();
            }, View.class);
            // Helpers already created before a style change retain their hardware flag.
            // Suppress only the per-press layer promotion, preserving the native spring.
            install(loader, helperName, "doScale", chain -> {
                View target = (View) chain.getArg(0);
                if (!isColorOsCircleIcon(target)) return chain.proceed();
                Object owner = chain.getThisObject();
                boolean previous = hardware.getBoolean(owner);
                hardware.setBoolean(owner, false);
                try { return chain.proceed(); }
                finally { hardware.setBoolean(owner, previous); }
            }, View.class, android.view.MotionEvent.class, spring);
            deoptimize(helper.getDeclaredMethod("addTargetView", View.class, boolean.class));
            deoptimize(helper.getDeclaredMethod("handleCustomTouch", View.class, android.view.MotionEvent.class));
            deoptimize(loader.loadClass(helperName + "$1").getDeclaredMethod("onTouch", View.class, android.view.MotionEvent.class));
        } catch (ReflectiveOperationException error) {
            log(Log.ERROR, "FlymeMod", "Cannot resolve circular icon press layers", error);
        }
        install(loader, "com.android.systemui.qs.tileimpl.QSIconViewImpl", "setIcon", chain -> {
            Object result = chain.proceed();
            ImageView icon = (ImageView) chain.getArg(0);
            Object tileState = chain.getArg(1);
            loadSettings(icon.getContext());
            if (whiteActiveEnabled && tileState.getClass().getField("state").getInt(tileState) == 2) {
                String spec = (String) tileState.getClass().getField("spec").get(tileState);
                whiteActiveTintedIcons.add(icon);
                applyIconColor(icon, activeTileIconColor(icon, spec));
            }
            return result;
        }, ImageView.class, tileStateClass, boolean.class);
        install(loader, "com.android.systemui.qs.tileimpl.QSIconViewImpl", "getCircleIconBgColor", chain -> {
            loadSettings(((View) chain.getThisObject()).getContext());
            whiteActiveCircleViews.add((View) chain.getThisObject());
            if (whiteActiveEnabled && (Integer) chain.getArg(1) == 2) {
                return activeTileBackgroundColor();
            }
            return chain.proceed();
        }, String.class, int.class);
        install(loader, "com.android.systemui.qs.tileimpl.QSIconViewImpl", "getIconColorForState", chain -> {
            View icon = (View) chain.getThisObject();
            loadSettings(icon.getContext());
            whiteActiveCircleViews.add(icon);
            if (!whiteActiveEnabled || (Integer) chain.getArg(0) != 2) return chain.proceed();
            Object state = icon.getClass().getField("mIconState").get(icon);
            String spec = state == null ? null : (String) state.getClass().getField("spec").get(state);
            return activeTileIconColor(icon, spec);
        }, int.class);
        install(loader, "com.meizu.common.widget.AbsSeekBar", "onProgressRefresh", chain -> {
            Object result = chain.proceed();
            View slider = (View) chain.getThisObject();
            if (isToggleSlider(slider)) {
                loadSettings(slider.getContext());
                if (whiteActiveEnabled) updateSliderIcon(slider, false);
            }
            return result;
        }, float.class, boolean.class);
        install(loader, "com.android.systemui.settings.brightness.BrightnessSliderView", "initBrightnessViewComponents", chain -> {
            Object result = chain.proceed();
            View owner = (View) chain.getThisObject();
            loadSettings(owner.getContext());
            if (whiteActiveEnabled) {
                View slider = (View) owner.getClass().getField("mSlider").get(owner);
                if (slider != null) updateSliderIcon(slider, true);
            }
            return result;
        });
        try {
            Class<?> composition = Class.forName("com.airbnb.lottie.LottieComposition", false, loader);
            install(loader, "com.airbnb.lottie.LottieAnimationView", "setComposition", chain -> {
                Object result = chain.proceed();
                View icon = (View) chain.getThisObject();
                View owner = sliderOwner(icon);
                if (owner != null) {
                    loadSettings(icon.getContext());
                    if (whiteActiveEnabled || volumeSliderIcons.contains(icon)) {
                        View slider = (View) owner.getClass().getField("mSlider").get(owner);
                        if (slider != null) updateSliderIcon(slider, true);
                    }
                }
                return result;
            }, composition);
            Class<?> animationView = Class.forName("com.airbnb.lottie.LottieAnimationView", false, loader);
            install(loader, animationView.getName(), "setAnimation", chain -> {
                Object result = chain.proceed();
                if ("volume.json".equals(chain.getArg(0))) {
                    ImageView icon = (ImageView) chain.getThisObject();
                    volumeSliderIcons.add(icon);
                    sliderIconColors.remove(icon);
                    loadSettings(icon.getContext());
                    if (whiteActiveEnabled) {
                        int color = ACTIVE_FOREGROUND;
                        applyIconColor(icon, color | 0xFF000000);
                        icon.setLayerType(View.LAYER_TYPE_HARDWARE, null);
                        icon.setAlpha(Color.alpha(color) / 255f);
                        sliderIconColors.put(icon, color);
                    }
                }
                return result;
            }, String.class);
            install(loader, "com.android.systemui.qs.tileimpl.QSIconViewImpl", "tintLottie", chain -> {
                View owner = (View) chain.getThisObject();
                ImageView glyph = (ImageView) chain.getArg(1);
                loadSettings(owner.getContext());
                boolean active = whiteActiveEnabled
                        && chain.getArg(0).getClass().getField("state").getInt(chain.getArg(0)) == 2;
                // The ROM skips tintLottie for *_on compositions. Remove our callback
                // first so disabling white activation restores their original colors.
                if (!active && whiteActiveTintedIcons.remove(glyph)) clearLottieTint(glyph);
                Object result = chain.proceed();
                if (active) {
                    String spec = (String) chain.getArg(0).getClass().getField("spec").get(chain.getArg(0));
                    whiteActiveTintedIcons.add(glyph);
                    applyIconColor(glyph, activeTileIconColor(owner, spec));
                }
                return result;
            }, tileStateClass, animationView, int.class);
        } catch (ClassNotFoundException e) {
            log(Log.ERROR, "FlymeMod", "Cannot resolve active icon classes", e);
        }
    }

    private boolean isColorOsCircleIcon(View view) throws ReflectiveOperationException {
        if (!view.getClass().getName().equals("com.android.systemui.qs.tileimpl.QSIconViewImpl")) return false;
        loadSettings(view.getContext());
        return whiteActiveEnabled && view.getClass().getField("mAddCircleIconBg").getBoolean(view);
    }

    private int activeTileIconColor(View icon, String spec) {
        return activeTileIconColor(spec);
    }

    private int activeTileIconColor(String spec) {
        if (controlCenterStyle == 2 && !("brightness".equals(spec) || "volume".equals(spec) || "sound".equals(spec)))
            return Color.WHITE;
        if (spec == null) return ACTIVE_BLUE_COLOR;
        String normalized = spec.toLowerCase(java.util.Locale.ROOT);
        if (normalized.equals("mobile") || normalized.equals("mobile_data") || normalized.equals("cellular")) {
            return ACTIVE_MOBILE_COLOR;
        }
        if (normalized.equals("brightness") || normalized.equals("battery")
                || normalized.equals("battery_saver") || normalized.equals("flashlight")) {
            return ACTIVE_YELLOW_COLOR;
        }
        if (normalized.equals("screenrecord") || normalized.equals("screen_record")) {
            return ACTIVE_RED_COLOR;
        }
        if (normalized.equals("dnd")) return ACTIVE_PURPLE_COLOR;
        if (normalized.equals("volume") || normalized.equals("sound")) return ACTIVE_VOLUME_COLOR;
        return ACTIVE_BLUE_COLOR;
    }

    private boolean shouldDarken(Context context) {
        loadSettings(context);
        return darkenEnabled;
    }

    private void installBlurRadius(ClassLoader loader) {
        // Both panel modes reach this final surface-blur entry point. Keep the
        // original progress/zoom, and change only the radius sent to the surface.
        try {
            Class<?> viewRoot = Class.forName("android.view.ViewRootImpl", false, loader);
            install(loader, "com.android.systemui.statusbar.BlurUtils", "applyBlurMZ", chain -> {
                Object root = chain.getArg(0);
                if (root == null) return chain.proceed();
                View view = (View) root.getClass().getMethod("getView").invoke(root);
                if (view == null || !view.getClass().getName().equals("com.android.systemui.shade.NotificationShadeWindowView")) return chain.proceed();
                loadSettings(view.getContext());
                if (!blurRadiusEnabled) return chain.proceed();
                int original = (Integer) chain.getArg(1);
                float max = chain.getThisObject().getClass().getField("maxBlurRadius").getFloat(chain.getThisObject());
                if (max <= 0 || original <= 0) return chain.proceed();
                float progress = Math.max(0f, Math.min(1f, original / max));
                Object[] args = chain.getArgs().toArray();
                args[1] = Math.round(progress * blurRadius * ModuleSettings.BLUR_SCALE);
                if ((Float) args[3] <= 0f) args[3] = progress;
                return chain.proceed(args);
            }, viewRoot, int.class, boolean.class, float.class);
        } catch (ClassNotFoundException e) {
            log(Log.ERROR, "FlymeMod", "Cannot resolve ViewRootImpl for blur radius", e);
        }
        // The root wallpaper render effect is a second, independent blur path.
        install(loader, "com.flyme.systemui.utils.MzBlurUtils", "setFlymeBlurEffect", chain -> {
            View view = (View) chain.getArg(0);
            loadSettings(view.getContext());
            int id = view.getResources().getIdentifier("mz_root_container", "id", "com.android.systemui");
            if (!blurRadiusEnabled || id == 0 || view.getId() != id || !Boolean.TRUE.equals(chain.getArg(1))) return chain.proceed();
            Object[] args = chain.getArgs().toArray();
            // ControlCenterBlurInteractor.createBlurState uses 300 as its maximum.
            args[2] = Math.max(0f, Math.min(1f, (Float) args[2] / 300f))
                    * blurRadius * ModuleSettings.BLUR_SCALE;
            if (blurRadius == 0) args[1] = false;
            return chain.proceed(args);
        }, View.class, boolean.class, float.class);
    }

    private void installBackgroundScale(ClassLoader loader) {
        // Flyme sends its animated background shrink as a pixel inset, independent
        // of the blur radius. Scope the framework builder hook to this shade call.
        ThreadLocal<Float> shrinkRatio = new ThreadLocal<>();
        try {
            Class<?> viewRoot = Class.forName("android.view.ViewRootImpl", false, loader);
            install(loader, "android.view.SyncRtSurfaceTransactionApplier$SurfaceParams$Builder",
                    "withBackgroundBlur", chain -> {
                        Float ratio = shrinkRatio.get();
                        if (ratio == null || ratio == 1f) return chain.proceed();
                        Object[] args = chain.getArgs().toArray();
                        args[1] = Math.round((Integer) args[1] * ratio);
                        return chain.proceed(args);
                    }, int.class, int.class);
            install(loader, "com.android.systemui.statusbar.BlurUtils", "applyBlurMZ", chain -> {
                Object root = chain.getArg(0);
                if (root == null) return chain.proceed();
                View view = (View) root.getClass().getMethod("getView").invoke(root);
                if (view == null || !view.getClass().getName().equals("com.android.systemui.shade.NotificationShadeWindowView"))
                    return chain.proceed();
                loadSettings(view.getContext());
                if (!backgroundScaleEnabled) return chain.proceed();
                Float previous = shrinkRatio.get();
                shrinkRatio.set(backgroundScale / 100f);
                try {
                    // Scaling the final inset also handles 0%: passing a zero
                    // progress to applyBlurMZ would fall back to the blur radius.
                    return chain.proceed();
                } finally {
                    if (previous == null) shrinkRatio.remove();
                    else shrinkRatio.set(previous);
                }
            }, viewRoot, int.class, boolean.class, float.class);
            deoptimize(Class.forName("com.android.systemui.statusbar.BlurUtils", false, loader)
                    .getDeclaredMethod("applyBlurMZ", viewRoot, int.class, boolean.class, float.class));
        } catch (ReflectiveOperationException error) {
            log(Log.ERROR, "FlymeMod", "Cannot resolve background scale methods", error);
        }
    }

    private boolean shouldUseLightTileSurface(Context context) {
        return shouldDarken(context) && (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK)!=Configuration.UI_MODE_NIGHT_YES;
    }

    private void installDarkBackground(ClassLoader loader) {
        try {
            new LightTileSurfaceHooks(loader,this::shouldUseLightTileSurface,()->networkSplitStyle!=0).install(
                    (name,method,hooker,parameters) -> install(loader,name,method,hooker,parameters));
        } catch (ReflectiveOperationException error) {
            log(Log.ERROR,"FlymeMod","Cannot resolve light control center tile surfaces",error);
        }
        try {
            new NotificationForegroundHooks(loader,this::shouldDarken).install(
                    (name,method,hooker,parameters) -> install(loader,name,method,hooker,parameters));
        } catch (ReflectiveOperationException error) {
            log(Log.ERROR,"FlymeMod","Cannot resolve dark shade notification foreground",error);
        }
        Map<Object, ShadeDimFrame> dimFrames = new WeakHashMap<>();
        // Extend only the native dim-layer call to apart mode. The normal blur
        // eligibility checks (including height/spring and lockscreen paths) stay intact.
        install(loader, "com.android.systemui.shade.NotificationPanelViewController", "allowSetShadeBlur", chain ->
                blurDimOwner.get() == chain.getThisObject() ? true : chain.proceed());
        install(loader, "com.android.systemui.shade.NotificationPanelViewController", "showBackgroundBlurDimLayer", chain -> {
            Object controller = chain.getThisObject();
            View view = (View) controller.getClass().getField("mView").get(controller);
            // Re-read the theme's native color before applying the optional black backdrop.
            controller.getClass().getMethod("updateDimColor").invoke(controller);
            Object keyguard = controller.getClass().getField("mKeyguardStateController").get(controller);
            boolean locked = (Boolean) Class.forName("com.android.systemui.statusbar.policy.KeyguardStateController", false, loader)
                    .getMethod("isShowing").invoke(keyguard)
                    || ((Integer) controller.getClass().getMethod("getBarState").invoke(controller)) != 0;
            boolean active = shouldDarken(view.getContext())
                    && !locked;
            if (active) {
                controller.getClass().getField("mDimColor").setInt(controller, DARK_BACKGROUND);
            }
            Object previous = blurDimOwner.get();
            if (active) blurDimOwner.set(controller); else blurDimOwner.remove();
            try {
                // The flow also emits for blur-scale spring frames. An unchanged
                // radius/color on the same surface needs no new Surface transaction.
                Field surfaceField = controller.getClass().getField("mBackgroundSurface");
                Object surface = surfaceField.get(controller);
                float fraction = locked ? 0f : (Float) chain.getArg(0);
                if (active && activeRowRevealHooks != null && combinedPullAnimationEnabled) {
                    Object shade = controller.getClass().getField("mShadeInteractor").get(controller);
                    Object mode = Class.forName("com.android.systemui.shade.domain.interactor.ShadeInteractor", false, loader)
                            .getMethod("isExpandToQsEnabled").invoke(shade);
                    boolean combined = Boolean.TRUE.equals(Class.forName("kotlinx.coroutines.flow.StateFlow", false, loader)
                            .getMethod("getValue").invoke(mode));
                    Object owner = combined ? controller
                            : controller.getClass().getField("mCenterController").get(controller);
                    fraction = ShadeDimFrame.effectiveFraction(fraction,
                            activeRowRevealHooks.backdropProgress(owner));
                }
                int color = controller.getClass().getField("mDimColor").getInt(controller);
                ShadeDimFrame frame = dimFrames.computeIfAbsent(controller, ignored -> new ShadeDimFrame());
                Object result = null;
                if (!active || !frame.matches(surface, fraction, color)) {
                    if (fraction <= 0f && (active || locked || surfaceDarkBackground)) {
                        // Native removal uses applyTransactionOnDraw. The window can
                        // stop drawing when ACTION_UP hides it, leaving a color layer
                        // over the app. Removal must not depend on another shade frame.
                        ShadeDimFrame.removeImmediately(controller, surfaceField);
                    } else {
                        result = chain.proceed(new Object[]{fraction});
                    }
                    surface = surfaceField.get(controller);
                    frame.record(active ? surface : null, fraction, color);
                }
                surfaceDarkBackground = active
                        && view.isAttachedToWindow()
                        && (surfaceDarkBackground
                            || surface != null);
                return result;
            } finally {
                if (previous == null) blurDimOwner.remove(); else blurDimOwner.set(previous);
            }
        }, float.class);
        try {
            // Its small eligibility getter may have been inlined by the ROM compiler.
            deoptimize(Class.forName("com.android.systemui.shade.NotificationPanelViewController", false, loader)
                    .getDeclaredMethod("showBackgroundBlurDimLayer", float.class));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log(Log.ERROR, "FlymeMod", "Cannot deoptimize control center blur dim layer", error);
        }
        install(loader, "com.android.systemui.scrim.ScrimView", "onDraw", chain -> {
            View scrim = (View) chain.getThisObject();
            int id = scrim.getResources().getIdentifier("center_behind", "id", "com.android.systemui");
            // Keep native alpha values/animators intact, but draw the dark backdrop once.
            // Keep ownership after the surface fades to zero: center_behind has its
            // own animator and can still be nontransparent at that moment. Restoring
            // its draw here would flash the old dim layer on the final closing frame.
            // Theme/lockscreen changes release ownership in the dim-layer hook.
            if (surfaceDarkBackground && id != 0 && scrim.getId() == id
                    && shouldDarken(scrim.getContext())
                    && (activeSeparateShadeSwipeHooks == null || !activeSeparateShadeSwipeHooks.drawsCenterBackdrop(scrim))) return null;
            return chain.proceed();
        }, Canvas.class);
        // The reference ROM uses a separate vector scrim over its blurred surface.
        // Replace that drawable, leaving blur radius, scaling and fade animation intact.
        install(loader, PHONE + "CenterController", "setBehindViewColor", chain -> {
            Object result = chain.proceed();
            Object controller = chain.getThisObject();
            Context context = (Context) controller.getClass().getField("mContext").get(controller);
            View scrim = (View) controller.getClass().getField("mBehindView").get(controller);
            // The original method has just installed the current theme's drawable.
            if (scrim != null) originalCenterBackgrounds.remove(scrim);
            if (shouldDarken(context)) {
                updateCenterBackground(scrim, true);
            }
            return result;
        });
        // Ui-mode animation also writes a tint on every frame; keep the black scrim.
        install(loader, PHONE + "CenterController", "setBehindViewColor", chain -> {
            Object controller = chain.getThisObject();
            Context context = (Context) controller.getClass().getField("mContext").get(controller);
            boolean active = shouldDarken(context);
            View scrim = (View) controller.getClass().getField("mBehindView").get(controller);
            updateCenterBackground(scrim, active);
            return active ? null : chain.proceed();
        }, Integer.class);

        // Header callbacks propagate the same color to footer/edit/device-center text.
        try {
            deoptimize(loader.loadClass("com.flyme.systemui.statusbar.phone.StatusBarHeaderView")
                    .getDeclaredMethod("updateViewColor",int.class));
            deoptimize(loader.loadClass("com.flyme.systemui.controlcenter.qs.QSStatusBarController")
                    .getDeclaredMethod("updateViewColor",int.class));
        } catch (ReflectiveOperationException | RuntimeException error) {
            log(Log.ERROR,"FlymeMod","Cannot deoptimize dark control center header colors",error);
        }
        install(loader,"com.android.systemui.statusbar.policy.Clock","onDarkChanged",chain -> {
            TextView clock=(TextView)chain.getThisObject();
            Object result=chain.proceed();
            for(View parent=clock; parent!=null; parent=parent.getParent() instanceof View next?next:null) {
                if(parent.getClass().getName().equals("com.flyme.systemui.controlcenter.qs.QSStatusBar")) {
                    if(shouldDarken(clock.getContext()))clock.setTextColor(Color.WHITE);
                    break;
                }
            }
            return result;
        },ArrayList.class,float.class,int.class);
        install(loader,"com.flyme.systemui.controlcenter.qs.QSStatusBarController","onDarkChanged",chain -> {
            Object controller=chain.getThisObject();
            View view=(View)controller.getClass().getField("mView").get(controller);
            Object result=chain.proceed();
            if(shouldDarken(view.getContext())) {
                ((TextView)controller.getClass().getField("mClockView").get(controller)).setTextColor(Color.WHITE);
                ((TextView)controller.getClass().getField("mDate").get(controller)).setTextColor(Color.WHITE);
                controller.getClass().getMethod("updateViewColor",int.class).invoke(controller,Color.WHITE);
            }
            return result;
        },ArrayList.class,float.class,int.class);
        install(loader, "com.flyme.systemui.statusbar.phone.StatusBarHeaderView", "updateViewColor", chain -> {
            View header = (View) chain.getThisObject();
            return shouldDarken(header.getContext())
                    ? chain.proceed(new Object[]{Color.WHITE}) : chain.proceed();
        }, int.class);
        install(loader, "com.flyme.systemui.controlcenter.qs.QSStatusBarController", "updateViewColor", chain -> {
            Object controller = chain.getThisObject();
            View view = (View) controller.getClass().getField("mView").get(controller);
            return shouldDarken(view.getContext())
                    ? chain.proceed(new Object[]{Color.WHITE}) : chain.proceed();
        }, int.class);

        XposedInterface.Hooker foreground = chain -> {
            View view = (View) chain.getThisObject();
            loadSettings(view.getContext());
            if(shouldUseLightTileSurface(view.getContext()) && isControlCenterView(view))return Color.BLACK;
            if (whiteActiveEnabled && !hasCircularCardActivation(view) && (Integer) chain.getArg(0) == 2
                    && !(chain.getArgs().size() > 1 && Boolean.TRUE.equals(chain.getArg(1)))) return activeTileForegroundColor();
            return shouldDarken(view.getContext()) && isControlCenterView(view)
                    ? Color.WHITE : chain.proceed();
        };
        for (String method : new String[]{"getLabelColorForState", "getSecondaryLabelColorForState"}) {
            install(loader, "com.android.systemui.qs.tileimpl.QSTileViewImpl", method,
                    foreground, int.class, boolean.class);
            install(loader, "com.flyme.systemui.qs.tileimpl.FlymeCustomQSTileView", method,
                    foreground, int.class);
        }
        try {
            Class<?> state = Class.forName("com.android.systemui.plugins.qs.QSTile$State", false, loader);
            install(loader, "com.android.systemui.qs.tileimpl.QSIconViewImpl", "getColor", chain -> {
                View view = (View) chain.getThisObject();
                loadSettings(view.getContext());
                if(shouldUseLightTileSurface(view.getContext()) && isControlCenterView(view))return Color.BLACK;
                int tileState = chain.getArg(0).getClass().getField("state").getInt(chain.getArg(0));
                if (whiteActiveEnabled && tileState == 2) return activeTileForegroundColor();
                return shouldDarken(view.getContext()) && isControlCenterView(view) ? Color.WHITE : chain.proceed();
            }, state);
        } catch (ClassNotFoundException e) {
            log(Log.ERROR, "FlymeMod", "Cannot resolve QSTile.State", e);
        }
    }

    private boolean hasCircularCardActivation(View view) {
        if (isConnectivityCard(view)) return true;
        if (view.getClass().getName().equals(
                "com.flyme.systemui.qs.tileimpl.DeviceCenterQSTileViewImpl")) return true;
        try {
            String spec = (String) view.getClass().getField("tileSpec").get(view);
            // The first state update sets tileSpec only after choosing colors.
            Object icon = view.getClass().getMethod("getIcon").invoke(view);
            Object state = icon.getClass().getField("mIconState").get(icon);
            if (state != null) spec = (String) state.getClass().getField("spec").get(state);
            return "dnd".equals(spec) || "controls".equals(spec);
        } catch (ReflectiveOperationException | RuntimeException ignored) { return false; }
    }

    private boolean isConnectivityCard(View view) {
        return view.getClass().getName().equals("com.flyme.systemui.qs.tileimpl.ConnectivityQSTileViewImpl");
    }

    private void updateCenterBackground(View scrim, boolean active) throws ReflectiveOperationException {
        if (scrim == null) return;
        if (active && !originalCenterBackgrounds.containsKey(scrim)) {
            Drawable original = (Drawable) scrim.getClass().getMethod("getDrawableMZ").invoke(scrim);
            originalCenterBackgrounds.put(scrim, original);
            scrim.getClass().getMethod("setDrawableMz", Drawable.class)
                    .invoke(scrim, new ColorDrawable(DARK_BACKGROUND));
        } else if (!active && originalCenterBackgrounds.containsKey(scrim)) {
            // Restore before letting the original day-mode tint animation proceed.
            Drawable original = originalCenterBackgrounds.remove(scrim);
            if (original != null) scrim.getClass().getMethod("setDrawableMz", Drawable.class)
                    .invoke(scrim, original);
        }
    }

    private boolean isControlCenterView(View view) {
        for (View current = view; current != null;
                current = current.getParent() instanceof View parent ? parent : null) {
            if (current.getClass().getName().equals(PHONE + "ControlCenterPanelView")) return true;
            if (current.getClass().getName().equals(PHONE + "MzQSContainerImpl")) return true;
        }
        return false;
    }

    private void applyScale(View container) {
        if (container == null || container.getWidth() == 0) return;
        container.setPivotX(container.getWidth() / 2f);
        // The full-screen container reserves the header space as top padding.
        container.setPivotY(container.getPaddingTop());
        float scale = computeOperationScale(container);
        container.setScaleX(scale);
        container.setScaleY(scale);
        int target = notificationTargetWidth(container);
        Integer previous = scaledNotificationWidths.put(container, target);
        if ((previous == null || previous != target) && !combinedNotificationWidthEnabled) {
            // The stack may have measured before QS; refresh once after QS dimensions settle.
            for (View stack : new ArrayList<>(notificationWidthStacks)) {
                if (stack != null && stack.getRootView() == container.getRootView()) stack.requestLayout();
            }
        }
    }

    private View findQsContainer(View view) {
        if (view.getClass().getName().equals(PHONE + "MzQSContainerImpl")) return view;
        int id = view.getResources().getIdentifier("mz_quick_settings_container", "id", "com.android.systemui");
        return id == 0 ? null : view.getRootView().findViewById(id);
    }

    private OperationArea operationArea(View container) {
        OperationArea area = operationAreas.get(container);
        if (area != null && area.mini.get() != null && area.pager.get() != null) return area;
        int miniId = container.getResources().getIdentifier("mz_quick_settings_panel_mini", "id", "com.android.systemui");
        int pagerId = container.getResources().getIdentifier("paged_unified_tile_layout", "id", "com.android.systemui");
        View mini = miniId == 0 ? null : container.findViewById(miniId);
        View pager = pagerId == 0 ? null : container.findViewById(pagerId);
        area = new OperationArea(mini, pager);
        if (mini != null && pager != null) {
            operationAreas.put(container, area);
            java.lang.ref.WeakReference<View> owner = new java.lang.ref.WeakReference<>(container);
            View.OnLayoutChangeListener layout = (view, l, t, r, b, ol, ot, or, ob) -> {
                View target = owner.get();
                if (target != null && scaleEnabled) applyScale(target);
            };
            mini.addOnLayoutChangeListener(layout);
            pager.addOnLayoutChangeListener(layout);
        }
        return area;
    }

    private int miniPanelContentWidth(View container) {
        View mini = operationArea(container).mini.get();
        return mini == null ? 0 : Math.max(0, mini.getMeasuredWidth() - mini.getPaddingLeft() - mini.getPaddingRight());
    }

    private int expandedPanelContentWidth(View container) {
        OperationArea area = operationArea(container);
        View pager = area.pager.get();
        if (pager == null || area.contentWidth == null) return 0;
        try { return ((Number) area.contentWidth.invoke(pager)).intValue(); }
        catch (ReflectiveOperationException | RuntimeException error) { return 0; }
    }

    private static final class OperationArea {
        final java.lang.ref.WeakReference<View> mini, pager;
        final java.lang.reflect.Method contentWidth;
        OperationArea(View mini, View pager) {
            this.mini = new java.lang.ref.WeakReference<>(mini);
            this.pager = new java.lang.ref.WeakReference<>(pager);
            java.lang.reflect.Method method = null;
            try { if (pager != null) method = pager.getClass().getMethod("getContentWidth"); }
            catch (ReflectiveOperationException ignored) { }
            contentWidth = method;
        }
    }

    private float operationScale(View container) {
        // Height compensation runs per animation frame; width is resolved on layout.
        return scaledNotificationWidths.containsKey(container) ? container.getScaleX() : computeOperationScale(container);
    }

    private float computeOperationScale(View container) {
        int contentWidth = Math.max(miniPanelContentWidth(container), expandedPanelContentWidth(container));
        if (contentWidth <= 0) return 1f;
        int screenWidth = container.getRootView().getMeasuredWidth();
        if (screenWidth <= 0) screenWidth = container.getResources().getDisplayMetrics().widthPixels;
        float maximum = Math.max(1f, screenWidth - 40f * container.getResources().getDisplayMetrics().density);
        return Math.min(OPERATION_SCALE, maximum / contentWidth);
    }

    private int notificationTargetWidth(View container) {
        int contentWidth = miniPanelContentWidth(container);
        return contentWidth > 0 ? Math.round(contentWidth * operationScale(container)) : 0;
    }

    private void installLauncherIconHiding(ClassLoader loader) {
        install(loader, "com.meizu.flyme.launcher.LauncherApplication", "onCreate", chain -> {
            launcherContext = ((Context) chain.getThisObject()).getApplicationContext();
            // Read before the original method starts LauncherPreLoading; the
            // first model load must already have the hidden-component setting.
            loadSettings(launcherContext);
            if (launcherSettingsObserver == null) {
                appliedLauncherHiddenPackages = hiddenLauncherPackages;
                launcherSettingsObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
                    @Override public void onChange(boolean selfChange) {
                        Set<String> previous;
                        synchronized (XposedInit.this) {
                            previous = appliedLauncherHiddenPackages;
                            settingsLoaded = false;
                            loadSettings(launcherContext);
                            appliedLauncherHiddenPackages = hiddenLauncherPackages;
                        }
                        if (previous.equals(hiddenLauncherPackages)) return;
                        try {
                            Object appState = Class.forName("com.android.launcher3.LauncherAppState", false, loader)
                                    .getMethod("getInstance", Context.class).invoke(null, launcherContext);
                            Object model = appState.getClass().getMethod("getModel").invoke(appState);
                            model.getClass().getMethod("forceReload").invoke(model);
                        } catch (ReflectiveOperationException e) {
                            log(Log.ERROR, "FlymeMod", "Cannot refresh launcher hidden icons", e);
                        }
                    }
                };
                launcherContext.getContentResolver().registerContentObserver(
                        ModuleSettings.URI, false, launcherSettingsObserver);
            }
            return chain.proceed();
        });
        install(loader, "android.content.pm.LauncherApps", "getActivityList", chain -> {
            if (launcherContext != null) loadSettings(launcherContext);
            Object result = chain.proceed();
            if (hiddenLauncherPackages.isEmpty() || !(result instanceof List<?> activities)) return result;
            List<Object> visible = new ArrayList<>(activities.size());
            for (Object activity : activities) {
                if (!(activity instanceof LauncherActivityInfo info)
                        || !hiddenLauncherPackages.contains(info.getComponentName().getPackageName())) visible.add(activity);
            }
            return visible;
        }, String.class, UserHandle.class);
        install(loader, "com.android.launcher3.AppFilter", "shouldShowApp", chain -> {
            if (launcherContext != null) loadSettings(launcherContext);
            ComponentName component = (ComponentName) chain.getArg(0);
            return component != null && hiddenLauncherPackages.contains(component.getPackageName()) ? false : chain.proceed();
        }, ComponentName.class);
        install(loader, "com.android.launcher3.model.WorkspaceItemProcessor", "processItem", chain -> {
            if (launcherContext != null) loadSettings(launcherContext);
            if (!hiddenLauncherPackages.isEmpty()) {
                var cursorField = chain.getThisObject().getClass().getDeclaredField("c");
                cursorField.setAccessible(true);
                Object cursor = cursorField.get(chain.getThisObject());
                if (cursor.getClass().getField("itemType").getInt(cursor) == 0) {
                    Intent intent = (Intent) cursor.getClass().getMethod("parseIntent").invoke(cursor);
                    // Skip binding desktop/folder icons, leaving the saved row
                    // intact so disabling the switch restores their positions.
                    if (intent != null && intent.getComponent() != null
                            && hiddenLauncherPackages.contains(intent.getComponent().getPackageName())) return null;
                }
            }
            return chain.proceed();
        });
    }

    private void installWallpaperStartupFix(ClassLoader loader) {
        install(loader, "com.flyme.keyguard.settings.SettingsUpdateMonitor",
                "isAdjustWallpaperDarkEnabled", chain -> {
            Object monitor = chain.getThisObject();
            Context context = (Context) monitor.getClass().getField("mContext").get(monitor);
            loadSettings(context);
            if (!wallpaperStartupFixEnabled) return chain.proceed();
            // ImageWallpaper snapshots this getter before the monitor may have
            // run initSetings(). Read the persisted preference instead of its
            // initial true value; preserve the ROM's default and later callbacks.
            boolean actual = Settings.Global.getInt(context.getContentResolver(),
                    "flyme_dark_mode_adjust_wallpaper", 1) == 1;
            if (!wallpaperStartupReadLogged) {
                wallpaperStartupReadLogged = true;
                boolean cached = monitor.getClass().getField("mAdjustWallpaperEnable").getBoolean(monitor);
                log(Log.INFO, "FlymeMod", "Wallpaper startup preference: cached=" + cached
                        + ", persisted=" + actual);
            }
            return actual;
        });
    }

    private void applyNotificationContentPadding(View content, int requestedPadding) {
        int[] last = notificationContentInsets.get(content);
        if (requestedPadding > 0 || last != null) {
            int[] padding = {content.getPaddingStart(), content.getPaddingTop(), content.getPaddingEnd(), content.getPaddingBottom()};
            for (int index=0; index<4; index++) {
                // Remove our previous addition only while native code has retained it.
                if (last != null && padding[index] == last[index]) padding[index] -= last[4];
                padding[index] += requestedPadding;
            }
            if (padding[0] != content.getPaddingStart() || padding[1] != content.getPaddingTop()
                    || padding[2] != content.getPaddingEnd() || padding[3] != content.getPaddingBottom())
                content.setPaddingRelative(padding[0], padding[1], padding[2], padding[3]);
            if (requestedPadding == 0) notificationContentInsets.remove(content);
            else notificationContentInsets.put(content, new int[]{padding[0],padding[1],padding[2],padding[3],requestedPadding});
        }
    }

    private int notificationAddedVerticalPadding(View content) {
        int[] insets = notificationContentInsets.get(content);
        return insets == null ? 0 : 2 * insets[4];
    }

    private int notificationPublicHeight(View notification, int nativeHeight) throws ReflectiveOperationException {
        if (!notification.getClass().getField("mShowingPublic").getBoolean(notification)) return nativeHeight;
        View content = (View) notification.getClass().getMethod("getShowingLayout").invoke(notification);
        if (content == null) return nativeHeight;
        int extra = notificationAddedVerticalPadding(content);
        if (extra == 0) return nativeHeight;
        Object wrapper = content.getClass().getMethod("getNotificationViewWrapper").invoke(content);
        if (wrapper == null) return nativeHeight;
        // Match only the two direct getHeight() branches of Flyme getMinHeight().
        // Other publicVersion templates already include padding via getViewHeight().
        boolean live = (Boolean) notification.getClass().getMethod("isLiveNotification").invoke(notification);
        View publicView = live ? (View) wrapper.getClass().getMethod("getLivePublicView").invoke(wrapper) : null;
        if (publicView == null) publicView = (View) wrapper.getClass().getMethod("getBasePublicView").invoke(wrapper);
        return publicView == null ? nativeHeight : nativeHeight + extra;
    }

    private void installMonochromeNotificationActions(ClassLoader loader) {
        String row = "com.android.systemui.statusbar.notification.row.";
        try {
            Class<?> entry = Class.forName("com.android.systemui.statusbar.notification.collection.NotificationEntry", false, loader);
            install(loader, row + "NotificationContentView", "onNotificationUpdated", chain -> {
                Object result = chain.proceed();
                recolorNotificationActions((View) chain.getThisObject(), false);
                return result;
            }, entry);
            Class<?> notificationRow = Class.forName(row + "ExpandableNotificationRow", false, loader);
            install(loader, row + "wrapper.NotificationTemplateViewWrapper", "onContentUpdated", chain -> {
                Object result = chain.proceed();
                recolorNotificationActions((View) chain.getArg(0), false);
                return result;
            }, notificationRow);
        } catch (ClassNotFoundException e) {
            log(Log.ERROR, "FlymeMod", "Cannot resolve notification action classes", e);
        }
        install(loader, row + "ExpandableNotificationRow", "onConfigurationChanged", chain -> {
            Object result = chain.proceed();
            recolorNotificationActions((View) chain.getThisObject(), false);
            return result;
        }, Configuration.class);
        install(loader, row + "wrapper.NotificationTemplateViewWrapper", "disableActionView", chain -> {
            Object result = chain.proceed();
            setNotificationActionColor((TextView) chain.getArg(0));
            return result;
        }, Button.class);
        install(loader, "com.android.systemui.statusbar.policy.SmartReplyView", "setButtonColors", chain -> {
            Object result = chain.proceed();
            TextView button = (TextView) chain.getArg(0);
            if (isSmartAction(button)) setNotificationActionColor(button);
            return result;
        }, Button.class);
    }

    private boolean isSmartAction(View view) throws ReflectiveOperationException {
        Object params = view.getLayoutParams();
        if (params == null || !params.getClass().getName().equals("com.android.systemui.statusbar.policy.SmartReplyView$LayoutParams")) return false;
        java.lang.reflect.Field field = params.getClass().getDeclaredField("mButtonType");
        field.setAccessible(true);
        return "ACTION".equals(String.valueOf(field.get(params)));
    }

    private void recolorNotificationActions(View view, boolean inActions) throws ReflectiveOperationException {
        loadSettings(view.getContext());
        if (!monochromeNotificationActionsEnabled) return;
        boolean actions = inActions || view.getClass().getName().equals("com.android.internal.widget.NotificationActionListLayout");
        if (view instanceof TextView text && (actions || isSmartAction(view))) setNotificationActionColor(text);
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) recolorNotificationActions(group.getChildAt(i), actions);
        }
    }

    private void setNotificationActionColor(TextView button) {
        loadSettings(button.getContext());
        if (!monochromeNotificationActionsEnabled) return;
        boolean dark = (button.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int color = dark ? Color.WHITE : Color.BLACK;
        int id = button.getResources().getIdentifier("notification_action_disabled_alpha", "dimen", "android");
        float disabledAlpha = id != 0 ? button.getResources().getFloat(id) : 0.5f;
        int disabled = (Math.round(255 * disabledAlpha) << 24) | (color & 0x00FFFFFF);
        button.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[0]},
                new int[]{disabled, color}));
    }

    private void installOriginalNotificationIcons(ClassLoader loader) {
        install(loader, "com.flyme.notification.utils.FlymeNotificationIconUtils", "resetNotificationSmallIconIfNeed", chain -> {
            Context context = (Context) chain.getThisObject().getClass().getField("mContext").get(chain.getThisObject());
            loadSettings(context);
            StatusBarNotification sbn = (StatusBarNotification) chain.getArg(0);
            if (originalNotificationIconsEnabled && sbn != null) {
                Notification notification = sbn.getNotification();
                Icon icon = notification.getSmallIcon();
                // Capture before Flyme replaces the Notification's icon in place.
                if (icon != null && !originalNotificationIcons.containsKey(notification)) {
                    try {
                        Context packageContext = (Context) sbn.getClass().getMethod("getPackageContext", Context.class)
                                .invoke(sbn, context);
                        ApplicationInfo info = packageContext != null ? packageContext.getApplicationInfo() : null;
                        if (info != null && (info.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0) {
                            originalNotificationIcons.put(notification, icon);
                        }
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        log(Log.ERROR, "FlymeMod", "Cannot identify notification source " + sbn.getPackageName(), e);
                    }
                }
            }
            return chain.proceed();
        }, StatusBarNotification.class);
        try {
            Class<?> controller = loader.loadClass("com.flyme.aod.presenter.AODNotificationController");
            Class<?> entry = loader.loadClass("com.android.systemui.statusbar.notification.collection.NotificationEntry");
            controller.getDeclaredMethod("insertNotificationData", entry);
            java.lang.reflect.Field contextField = controller.getField("mContext");
            java.lang.reflect.Method getSbn = entry.getMethod("getSbn");
            java.lang.reflect.Field iconField = loader.loadClass("com.flyme.aod.presenter.AODNotificationController$NotificationData")
                    .getField("notificationDrawable");
            java.lang.reflect.Field[] maps = new java.lang.reflect.Field[4];
            String[] names = {"mNotificationData", "mThirdAppNotificationData", "mOngoingNotificationData", "mFilterNotificationData"};
            for (int i = 0; i < names.length; i++) maps[i] = controller.getField(names[i]);
            install(loader, controller.getName(), "insertNotificationData", chain -> {
                Object result = chain.proceed();
                Context context = (Context) contextField.get(chain.getThisObject());
                loadSettings(context);
                if (!originalNotificationIconsEnabled) return result;
                StatusBarNotification sbn = (StatusBarNotification) getSbn.invoke(chain.getArg(0));
                Icon original = originalNotificationIcons.get(sbn.getNotification());
                if (original == null) return result;
                try {
                    Drawable drawable = original.loadDrawable(context);
                    if (drawable == null) return result;
                    String packageName = (String) sbn.getClass().getMethod("getOrigPackageName").invoke(sbn);
                    for (java.lang.reflect.Field mapField : maps) {
                        Object data = ((Map<?, ?>) mapField.get(chain.getThisObject())).get(packageName);
                        if (data != null) iconField.set(data, new OriginalNotificationIcon(drawable));
                    }
                } catch (ReflectiveOperationException | RuntimeException error) {
                    log(Log.ERROR, "FlymeMod", "Cannot load original lockscreen notification icon " + sbn.getPackageName(), error);
                }
                return result;
            }, entry);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log(Log.ERROR, "FlymeMod", "Cannot hook original lockscreen notification icons", error);
        }
        try {
            Class<?> ticker = loader.loadClass("com.flyme.systemui.statusbar.ticker.MarqueeTicker");
            Class<?> segment = loader.loadClass("com.flyme.systemui.statusbar.ticker.MarqueeTicker$Segment");
            java.lang.reflect.Field contextField = ticker.getField("mContext");
            hook(segment.getDeclaredConstructor(ticker, StatusBarNotification.class, Drawable.class, CharSequence.class))
                    .intercept(chain -> {
                        Context context = (Context) contextField.get(chain.getArg(0));
                        loadSettings(context);
                        StatusBarNotification sbn = (StatusBarNotification) chain.getArg(1);
                        Icon original = !originalNotificationIconsEnabled || sbn == null ? null
                                : originalNotificationIcons.get(sbn.getNotification());
                        if (original == null) return chain.proceed();
                        Drawable drawable;
                        try {
                            drawable = original.loadDrawable(context);
                        } catch (RuntimeException error) {
                            log(Log.ERROR, "FlymeMod", "Cannot load original ticker icon " + sbn.getPackageName(), error);
                            return chain.proceed();
                        }
                        if (drawable == null) return chain.proceed();
                        // Replace only this queued ticker segment, without changing the shared Notification.
                        return chain.proceed(new Object[]{chain.getArg(0), sbn,
                                new OriginalNotificationIcon(drawable), chain.getArg(3)});
                    });
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log(Log.ERROR, "FlymeMod", "Cannot hook original ticker notification icons", error);
        }
        try {
            Class<?> statusBarIcon = Class.forName("com.android.internal.statusbar.StatusBarIcon", false, loader);
            install(loader, "com.android.systemui.statusbar.StatusBarIconView", "set", chain -> {
                View view = (View) chain.getThisObject();
                loadSettings(view.getContext());
                if (!originalNotificationIconsEnabled) return chain.proceed();
                StatusBarNotification sbn = (StatusBarNotification) view.getClass().getField("mNotification").get(view);
                Icon original = sbn == null ? null : originalNotificationIcons.get(sbn.getNotification());
                if (original == null) return chain.proceed();
                // Do not mutate shared descriptors used by the notification pipeline.
                Object descriptor = statusBarIcon.getMethod("clone").invoke(chain.getArg(0));
                statusBarIcon.getField("icon").set(descriptor, original);
                statusBarIcon.getField("preloadedIcon").set(descriptor, null);
                return chain.proceed(new Object[]{descriptor});
            }, statusBarIcon);
            install(loader, "com.android.systemui.statusbar.StatusBarIconView", "onDraw", chain -> {
                View view = (View) chain.getThisObject();
                if (!originalNotificationIconsEnabled) return chain.proceed();
                StatusBarNotification sbn = (StatusBarNotification) view.getClass().getField("mNotification").get(view);
                if (sbn == null || !originalNotificationIcons.containsKey(sbn.getNotification())) return chain.proceed();
                // Flyme scales only the icon around the view center; the overflow
                // dot and layout spacing stay unchanged. Restore after each draw.
                java.lang.reflect.Field scale = view.getClass().getField("mIconScale");
                float originalScale = scale.getFloat(view);
                scale.setFloat(view, originalScale * 0.65f);
                try {
                    return chain.proceed();
                } finally {
                    scale.setFloat(view, originalScale);
                }
            }, Canvas.class);
        } catch (ClassNotFoundException e) {
            log(Log.ERROR, "FlymeMod", "Cannot resolve notification icon descriptor", e);
        }
    }

    private static final class OriginalNotificationIcon extends DrawableWrapper {
        OriginalNotificationIcon(Drawable drawable) {
            super(drawable);
        }

        @Override public void draw(Canvas canvas) {
            int saved = canvas.save();
            try {
                // Scale only the artwork; preserve native layout, intrinsic size and tinting.
                canvas.scale(.65f, .65f, getBounds().exactCenterX(), getBounds().exactCenterY());
                super.draw(canvas);
            } finally {
                canvas.restoreToCount(saved);
            }
        }
    }

    private synchronized void loadSettings(Context context) {
        if (moduleSettingsObserver == null) {
            Context appContext = context.getApplicationContext();
            moduleSettingsObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override public void onChange(boolean selfChange) {
                    boolean previousBackSmart = backGestureRemoveSmart;
                    boolean previousWhiteActive = whiteActiveEnabled;
                    int previousOpacity = whiteActiveOpacity;
                    int previousStyle = controlCenterStyle;
                    int previousActiveColor = customActiveColor;
                    int previousLightOpacity = lightBackgroundOpacity;
                    synchronized (XposedInit.this) { settingsLoaded = false; }
                    loadSettings(appContext);
                    if (activeBackGestureSmartHooks != null && previousBackSmart != backGestureRemoveSmart)
                        activeBackGestureSmartHooks.refresh();
                    if(activeMBackRippleHooks!=null)activeMBackRippleHooks.refresh();
                    if(activeGestureBarWidthHooks!=null)activeGestureBarWidthHooks.refresh();
                    if (activeBackGestureWidthHooks != null) activeBackGestureWidthHooks.refresh();
                    if (activeBackGestureBackgroundHooks != null) activeBackGestureBackgroundHooks.refresh();
                    reapplyStatusIconVisibility();
                    if (activeHeadsUpWidthHooks != null) activeHeadsUpWidthHooks.refresh();
                    if (activeHeaderDateHooks != null) activeHeaderDateHooks.refresh();
                    if (activeClockFontHooks != null) activeClockFontHooks.refresh();
                    if (activeChargingInfoHooks != null) activeChargingInfoHooks.refresh();
                    if (activeStatusBarLyricHooks != null) activeStatusBarLyricHooks.refresh();
                    if (activeRecentsCornerHooks != null) activeRecentsCornerHooks.refresh();
                    if (activeCarrierVisibilityHooks != null) activeCarrierVisibilityHooks.refresh();
                    if (activeSimpleQsTextHooks != null) activeSimpleQsTextHooks.refresh();
                    if (activeCombinedSettingsButtonHooks != null) activeCombinedSettingsButtonHooks.refresh();
                    if (activeCombinedNotificationWidthHooks != null) activeCombinedNotificationWidthHooks.refresh();
                    for (View stack : new ArrayList<>(notificationWidthStacks)) if (stack != null) stack.requestLayout();
                    if (activeEarphoneNoiseHooks != null) activeEarphoneNoiseHooks.refresh();
                    if (activeSplitNetworkCardHooks != null) activeSplitNetworkCardHooks.refresh();
                    if (activeCardIconLayoutHooks != null) activeCardIconLayoutHooks.refresh();
                    if (activeMuteSlashHooks != null) activeMuteSlashHooks.refresh();
                    if (activeFoldIdleMediaHooks != null) activeFoldIdleMediaHooks.refresh();
                    if (activeMediaSwipeHooks != null) activeMediaSwipeHooks.refresh();
                    if (activeNotificationSwipeHooks != null) activeNotificationSwipeHooks.refresh();
                    // Restore custom square corners before forcing circles; restore circles before applying custom corners.
                    if (activeTileCornerHooks != null && circleSmallTilesEnabled) activeTileCornerHooks.refresh();
                    if (activeCircleTileHooks != null) activeCircleTileHooks.refresh();
                    if (activeTileCornerHooks != null && !circleSmallTilesEnabled) activeTileCornerHooks.refresh();
                    if (activeNotificationAppearanceHooks != null) activeNotificationAppearanceHooks.refresh();
                    if (activeNotificationActionBackgroundHooks != null) activeNotificationActionBackgroundHooks.refresh();
                    if (activeNotificationRemovalLayerHooks != null) activeNotificationRemovalLayerHooks.refresh();
                    for (View content : new ArrayList<>(notificationContentInsets.keySet()))
                        if (content != null) content.requestLayout();
                    if (activeColorOsMaterialHooks != null) activeColorOsMaterialHooks.refresh();
                    boolean activeThemeChanged = previousWhiteActive != whiteActiveEnabled
                            || previousStyle != controlCenterStyle || previousActiveColor != customActiveColor;
                    if (activeThemeChanged) refreshActiveIconTheme();
                    if (activeThemeChanged || (whiteActiveEnabled && previousOpacity != whiteActiveOpacity))
                        refreshWhiteActiveOpacity();
                    if (lightEnabled && previousLightOpacity != lightBackgroundOpacity) refreshLightBackgroundOpacity();
                    if (activeTileBackdropHooks != null) activeTileBackdropHooks.refresh();
                }
            };
            appContext.getContentResolver().registerContentObserver(
                    ModuleSettings.URI, false, moduleSettingsObserver);
        }
        if (settingsLoaded) return;
        try (Cursor cursor = context.getContentResolver().query(ModuleSettings.URI, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                hiddenStatusIcons.clear();
                for (String key : ModuleSettings.HIDE_STATUS_ICON_KEYS) {
                    int column = cursor.getColumnIndex(key);
                    if (column >= 0 && cursor.getInt(column) != 0) hiddenStatusIcons.add(key);
                }
                scaleEnabled = cursor.getInt(0) != 0;
                lightEnabled = cursor.getInt(1) != 0;
                int tileCornersColumn = cursor.getColumnIndex(ModuleSettings.TILE_CORNERS);
                tileCornersEnabled = tileCornersColumn >= 0 && cursor.getInt(tileCornersColumn) != 0;
                int tileCornerPercentColumn = cursor.getColumnIndex(ModuleSettings.TILE_CORNER_PERCENT);
                tileCornerPercent = tileCornerPercentColumn < 0 ? ModuleSettings.TILE_CORNER_PERCENT_DEFAULT
                        : Math.max(ModuleSettings.TILE_CORNER_PERCENT_MIN, Math.min(ModuleSettings.TILE_CORNER_PERCENT_MAX, cursor.getInt(tileCornerPercentColumn)));
                int tileColorColumn = cursor.getColumnIndex(ModuleSettings.TILE_BACKGROUND_COLOR);
                tileBackgroundColorEnabled = tileColorColumn >= 0 && cursor.getInt(tileColorColumn) != 0;
                int tileSaturationColumn = cursor.getColumnIndex(ModuleSettings.TILE_BACKGROUND_SATURATION);
                tileBackgroundSaturation = tileSaturationColumn < 0 ? ModuleSettings.TILE_BACKGROUND_SATURATION_DEFAULT
                        : Math.max(ModuleSettings.TILE_BACKGROUND_SATURATION_MIN,
                                Math.min(ModuleSettings.TILE_BACKGROUND_SATURATION_MAX, cursor.getInt(tileSaturationColumn)));
                int notificationContourColumn = cursor.getColumnIndex(ModuleSettings.NOTIFICATION_CONTOUR);
                int notificationColorColumn = cursor.getColumnIndex(ModuleSettings.NOTIFICATION_BACKGROUND_COLOR);
                notificationBackgroundColorEnabled = notificationColorColumn >= 0 && cursor.getInt(notificationColorColumn) != 0;
                int notificationSaturationColumn = cursor.getColumnIndex(ModuleSettings.NOTIFICATION_BACKGROUND_SATURATION);
                notificationBackgroundSaturation = notificationSaturationColumn < 0 ? ModuleSettings.TILE_BACKGROUND_SATURATION_DEFAULT
                        : Math.max(ModuleSettings.TILE_BACKGROUND_SATURATION_MIN,
                                Math.min(ModuleSettings.TILE_BACKGROUND_SATURATION_MAX, cursor.getInt(notificationSaturationColumn)));
                notificationContourEnabled = notificationContourColumn >= 0 && cursor.getInt(notificationContourColumn) != 0;
                int backArrowColumn = cursor.getColumnIndex(ModuleSettings.BACK_GESTURE_ARROW);
                backGestureArrowEnabled = backArrowColumn >= 0 && cursor.getInt(backArrowColumn) != 0;
                int backDarkColumn = cursor.getColumnIndex(ModuleSettings.BACK_GESTURE_DARK_BACKGROUND);
                backGestureDarkBackgroundEnabled = backDarkColumn < 0 || cursor.getInt(backDarkColumn) != 0;
                int backDarkOpacityColumn = cursor.getColumnIndex(ModuleSettings.BACK_GESTURE_DARK_OPACITY);
                backGestureDarkOpacity = ModuleSettings.backGestureDarkOpacity(backDarkOpacityColumn < 0
                        ? ModuleSettings.BACK_GESTURE_DARK_OPACITY_DEFAULT : cursor.getInt(backDarkOpacityColumn));
                int backSmartColumn = cursor.getColumnIndex(ModuleSettings.BACK_GESTURE_REMOVE_SMART);
                backGestureRemoveSmart = backSmartColumn < 0 || cursor.getInt(backSmartColumn) != 0;
                int backWidthColumn = cursor.getColumnIndex(ModuleSettings.BACK_GESTURE_WIDTH_ENABLED);
                backGestureWidthEnabled = backWidthColumn >= 0 && cursor.getInt(backWidthColumn) != 0;
                int backDpColumn = cursor.getColumnIndex(ModuleSettings.BACK_GESTURE_WIDTH_DP);
                backGestureWidthDp = backDpColumn < 0 ? ModuleSettings.BACK_GESTURE_WIDTH_DEFAULT
                        : Math.max(0, cursor.getInt(backDpColumn));
                int mbackColumn = cursor.getColumnIndex(ModuleSettings.MBACK_SYSTEM_TIMEOUT);
                int gestureBarColumn=cursor.getColumnIndex(ModuleSettings.GESTURE_BAR_WIDTH_ENABLED);
                gestureBarWidthEnabled=gestureBarColumn<0 || cursor.getInt(gestureBarColumn)!=0;
                int gestureBarDpColumn=cursor.getColumnIndex(ModuleSettings.GESTURE_BAR_WIDTH_DP);
                gestureBarWidthDp=ModuleSettings.gestureBarWidth(gestureBarDpColumn<0?
                        ModuleSettings.GESTURE_BAR_WIDTH_DEFAULT:cursor.getInt(gestureBarDpColumn));
                mbackSystemTimeoutEnabled = mbackColumn >= 0 && cursor.getInt(mbackColumn) != 0;
                int mbackAssistantColumn = cursor.getColumnIndex(ModuleSettings.MBACK_MISSING_ASSISTANT_HOME);
                mbackMissingAssistantHomeEnabled = mbackAssistantColumn >= 0 && cursor.getInt(mbackAssistantColumn) != 0;
                int contourColumn = cursor.getColumnIndex(ModuleSettings.TILES_CONTOUR);
                colorOsContourEnabled = contourColumn >= 0 && cursor.getInt(contourColumn) != 0;
                int lightOpacityColumn = cursor.getColumnIndex(ModuleSettings.LIGHT_OPACITY);
                lightBackgroundOpacity = lightOpacityColumn < 0 ? ModuleSettings.LIGHT_OPACITY_DEFAULT
                        : Math.max(0, Math.min(ModuleSettings.LIGHT_OPACITY_MAX, cursor.getInt(lightOpacityColumn)));
                int darkenColumn = cursor.getColumnIndex(ModuleSettings.DARKEN);
                darkenEnabled = darkenColumn >= 0 && cursor.getInt(darkenColumn) != 0;
                int activeColumn = cursor.getColumnIndex(ModuleSettings.WHITE_ACTIVE);
                int styleColumn = cursor.getColumnIndex(ModuleSettings.CONTROL_CENTER_STYLE);
                controlCenterStyle = styleColumn >= 0 ? Math.max(0, Math.min(2, cursor.getInt(styleColumn)))
                        : activeColumn >= 0 && cursor.getInt(activeColumn) != 0 ? 1 : 0;
                whiteActiveEnabled = controlCenterStyle != 0;
                int activeColorColumn = cursor.getColumnIndex(ModuleSettings.CONTROL_CENTER_ACTIVE_COLOR);
                customActiveColor = activeColorColumn >= 0 ? cursor.getInt(activeColorColumn)
                        : ModuleSettings.CONTROL_CENTER_ACTIVE_COLOR_DEFAULT;
                int opacityColumn = cursor.getColumnIndex(ModuleSettings.WHITE_ACTIVE_OPACITY);
                whiteActiveOpacity = opacityColumn < 0 ? 90 : Math.max(50, Math.min(100, cursor.getInt(opacityColumn)));
                int sliderCornersColumn = cursor.getColumnIndex(ModuleSettings.SLIDER_ACTIVE_CORNERS);
                sliderActiveCornersEnabled = sliderCornersColumn >= 0 && cursor.getInt(sliderCornersColumn) != 0;
                int recentsCornerColumn = cursor.getColumnIndex(ModuleSettings.RECENTS_CORNER_ENABLED);
                recentsCornerEnabled = recentsCornerColumn >= 0 && cursor.getInt(recentsCornerColumn) != 0;
                int recentsCornerDpColumn = cursor.getColumnIndex(ModuleSettings.RECENTS_CORNER_DP);
                recentsCornerDp = recentsCornerDpColumn < 0 ? RecentsCornerGeometry.MIN_DP
                        : RecentsCornerGeometry.clamp(cursor.getInt(recentsCornerDpColumn));
                int stackedRecentsColumn = cursor.getColumnIndex(ModuleSettings.STACKED_RECENTS);
                stackedRecentsEnabled = stackedRecentsColumn >= 0 && cursor.getInt(stackedRecentsColumn) != 0;
                // LauncherApplication.onCreate loads this snapshot before preloading
                // tasks. Recents hooks must never query the provider on gesture frames.
                int recentsHideColumn = cursor.getColumnIndex(ModuleSettings.RECENTS_HIDE_NOT_RUNNING);
                recentsHideNotRunningEnabled = recentsHideColumn >= 0 && cursor.getInt(recentsHideColumn) != 0;
                int recentsKillColumn = cursor.getColumnIndex(ModuleSettings.RECENTS_SWIPE_UP_KILL);
                recentsSwipeUpKillEnabled = recentsKillColumn >= 0 && cursor.getInt(recentsKillColumn) != 0;
                int headsUpWidthColumn = cursor.getColumnIndex(ModuleSettings.HEADS_UP_WIDTH);
                int combinedWidthColumn = cursor.getColumnIndex(ModuleSettings.COMBINED_NOTIFICATION_WIDTH);
                combinedNotificationWidthEnabled = combinedWidthColumn >= 0 && cursor.getInt(combinedWidthColumn) != 0;
                headsUpWidthEnabled = headsUpWidthColumn >= 0 && cursor.getInt(headsUpWidthColumn) != 0;
                int notificationLightColumn = cursor.getColumnIndex(ModuleSettings.NOTIFICATION_LIGHT);
                notificationLightEnabled = notificationLightColumn >= 0 && cursor.getInt(notificationLightColumn) != 0;
                int notificationLightOpacityColumn = cursor.getColumnIndex(ModuleSettings.NOTIFICATION_LIGHT_OPACITY);
                notificationLightOpacity = notificationLightOpacityColumn < 0 ? ModuleSettings.LIGHT_OPACITY_DEFAULT
                        : Math.max(0, Math.min(ModuleSettings.LIGHT_OPACITY_MAX, cursor.getInt(notificationLightOpacityColumn)));
                int notificationCustomCornersColumn = cursor.getColumnIndex(ModuleSettings.NOTIFICATION_CUSTOM_CORNERS);
                int notificationReadLayerFixColumn = cursor.getColumnIndex(ModuleSettings.NOTIFICATION_READ_LAYER_FIX);
                notificationReadLayerFixEnabled = notificationReadLayerFixColumn >= 0 && cursor.getInt(notificationReadLayerFixColumn) != 0;
                notificationCustomCornersEnabled = notificationCustomCornersColumn >= 0 && cursor.getInt(notificationCustomCornersColumn) != 0;
                int notificationCornerPercentColumn = cursor.getColumnIndex(ModuleSettings.NOTIFICATION_CORNER_PERCENT);
                notificationCornerPercent = notificationCornerPercentColumn < 0 ? ModuleSettings.TILE_CORNER_PERCENT_DEFAULT
                        : Math.max(ModuleSettings.TILE_CORNER_PERCENT_MIN, Math.min(ModuleSettings.TILE_CORNER_PERCENT_MAX, cursor.getInt(notificationCornerPercentColumn)));
                int nativeExpansionColumn = cursor.getColumnIndex(ModuleSettings.NATIVE_NOTIFICATION_EXPANSION);
                nativeNotificationExpansionEnabled = nativeExpansionColumn >= 0 && cursor.getInt(nativeExpansionColumn) != 0;
                int originalIconsColumn = cursor.getColumnIndex(ModuleSettings.ORIGINAL_NOTIFICATION_ICONS);
                originalNotificationIconsEnabled = originalIconsColumn >= 0 && cursor.getInt(originalIconsColumn) != 0;
                int actionColorsColumn = cursor.getColumnIndex(ModuleSettings.MONOCHROME_NOTIFICATION_ACTIONS);
                monochromeNotificationActionsEnabled = actionColorsColumn >= 0 && cursor.getInt(actionColorsColumn) != 0;
                int actionBackgroundColumn=cursor.getColumnIndex(ModuleSettings.REMOVE_NOTIFICATION_ACTION_BACKGROUND);
                removeNotificationActionBackgroundEnabled=actionBackgroundColumn>=0 && cursor.getInt(actionBackgroundColumn)!=0;
                int suggestionsColumn = cursor.getColumnIndex(ModuleSettings.DISABLE_GOOGLE_NOTIFICATION_SUGGESTIONS);
                disableGoogleNotificationSuggestionsEnabled = suggestionsColumn >= 0 && cursor.getInt(suggestionsColumn) != 0;
                int collapsedHeightColumn = cursor.getColumnIndex(ModuleSettings.RESTORE_COLLAPSED_CARD_HEIGHT);
                restoreCollapsedCardHeightEnabled = collapsedHeightColumn >= 0 && cursor.getInt(collapsedHeightColumn) != 0;
                int noiseColumn=cursor.getColumnIndex(ModuleSettings.EARPHONE_NOISE_CONTROL);
                earphoneNoiseControlEnabled=noiseColumn>=0&&cursor.getInt(noiseColumn)!=0;
                int volumeStepsColumn = cursor.getColumnIndex(ModuleSettings.VOLUME_FIRST_FOUR);
                volumeFirstFourMode = volumeStepsColumn < 0 ? 0 : Math.max(0, Math.min(2, cursor.getInt(volumeStepsColumn)));
                int aodMovementColumn = cursor.getColumnIndex(ModuleSettings.LIMIT_AOD_MOVEMENT);
                limitAodMovementEnabled = aodMovementColumn >= 0 && cursor.getInt(aodMovementColumn) != 0;
                int lockFontColumn = cursor.getColumnIndex(ModuleSettings.LOCK_CLOCK_FONT);
                lockClockFont = lockFontColumn < 0 ? 0 : Math.max(0, Math.min(ModuleSettings.CLOCK_FONT_NAMES.length - 1, cursor.getInt(lockFontColumn)));
                int aodFontColumn = cursor.getColumnIndex(ModuleSettings.AOD_CLOCK_FONT);
                aodClockFont = aodFontColumn < 0 ? 0 : Math.max(0, Math.min(ModuleSettings.CLOCK_FONT_NAMES.length - 1, cursor.getInt(aodFontColumn)));
                int statusFontColumn = cursor.getColumnIndex(ModuleSettings.STATUS_BAR_CLOCK_FONT);
                int lyricAvoidColumn = cursor.getColumnIndex(ModuleSettings.STATUS_BAR_LYRIC_AVOID);
                statusBarLyricAvoidEnabled = lyricAvoidColumn < 0 ? ModuleSettings.STATUS_BAR_LYRIC_AVOID_DEFAULT : cursor.getInt(lyricAvoidColumn) != 0;
                int chargingInfoColumn = cursor.getColumnIndex(ModuleSettings.STATUS_BAR_CHARGING_INFO);
                statusBarChargingInfoMode = chargingInfoColumn < 0 ? ModuleSettings.STATUS_BAR_CHARGING_INFO_DEFAULT : Math.max(0, Math.min(2, cursor.getInt(chargingInfoColumn)));
                statusBarClockFont = statusFontColumn < 0 ? 0 : Math.max(0, Math.min(ModuleSettings.CLOCK_FONT_NAMES.length - 1, cursor.getInt(statusFontColumn)));
                int controlCenterFontColumn = cursor.getColumnIndex(ModuleSettings.CONTROL_CENTER_CLOCK_FONT);
                controlCenterClockFont = controlCenterFontColumn < 0 ? 0 : Math.max(0, Math.min(ModuleSettings.CLOCK_FONT_NAMES.length - 1, cursor.getInt(controlCenterFontColumn)));
                int lockMonospaceColumn = cursor.getColumnIndex(ModuleSettings.LOCK_CLOCK_MONOSPACE);
                lockClockMonospace = lockMonospaceColumn >= 0 && cursor.getInt(lockMonospaceColumn) != 0;
                int aodMonospaceColumn = cursor.getColumnIndex(ModuleSettings.AOD_CLOCK_MONOSPACE);
                aodClockMonospace = aodMonospaceColumn >= 0 && cursor.getInt(aodMonospaceColumn) != 0;
                int statusMonospaceColumn = cursor.getColumnIndex(ModuleSettings.STATUS_BAR_CLOCK_MONOSPACE);
                statusBarClockMonospace = statusMonospaceColumn >= 0 && cursor.getInt(statusMonospaceColumn) != 0;
                int controlCenterMonospaceColumn = cursor.getColumnIndex(ModuleSettings.CONTROL_CENTER_CLOCK_MONOSPACE);
                controlCenterClockMonospace = controlCenterMonospaceColumn >= 0 && cursor.getInt(controlCenterMonospaceColumn) != 0;
                int fontWeightColumn = cursor.getColumnIndex(ModuleSettings.CLOCK_FONT_WEIGHT);
                clockFontWeight = fontWeightColumn < 0 ? 0 : cursor.getInt(fontWeightColumn);
                int statusFontWeightColumn = cursor.getColumnIndex(ModuleSettings.STATUS_BAR_CLOCK_WEIGHT);
                statusBarClockWeight = statusFontWeightColumn < 0 ? 0 : cursor.getInt(statusFontWeightColumn);
                int controlCenterFontWeightColumn = cursor.getColumnIndex(ModuleSettings.CONTROL_CENTER_CLOCK_WEIGHT);
                controlCenterClockWeight = controlCenterFontWeightColumn < 0 ? 0 : cursor.getInt(controlCenterFontWeightColumn);
                int controlCenterDateUpColumn = cursor.getColumnIndex(ModuleSettings.CONTROL_CENTER_CLOCK_DATE_UP);
                controlCenterClockDateUpEnabled = controlCenterDateUpColumn >= 0 && cursor.getInt(controlCenterDateUpColumn) != 0;
                int controlCenterDateUpDistanceColumn = cursor.getColumnIndex(ModuleSettings.CONTROL_CENTER_CLOCK_DATE_UP_DISTANCE);
                controlCenterClockDateUpDistance = controlCenterDateUpDistanceColumn < 0 ? ModuleSettings.CONTROL_CENTER_CLOCK_DATE_UP_DISTANCE_DEFAULT
                        : Math.max(0, Math.min(40, cursor.getInt(controlCenterDateUpDistanceColumn)));
                int buttonsUpColumn = cursor.getColumnIndex(ModuleSettings.CONTROL_CENTER_BUTTONS_UP);
                controlCenterButtonsUpEnabled = buttonsUpColumn >= 0 && cursor.getInt(buttonsUpColumn) != 0;
                int buttonsDistanceColumn = cursor.getColumnIndex(ModuleSettings.CONTROL_CENTER_BUTTONS_UP_DISTANCE);
                controlCenterButtonsUpDistance = buttonsDistanceColumn < 0 ? 8
                        : Math.max(0, Math.min(40, cursor.getInt(buttonsDistanceColumn)));
                int lockSpacingColumn = cursor.getColumnIndex(ModuleSettings.LOCK_CLOCK_SPACING);
                lockClockSpacing = lockSpacingColumn < 0 ? 0 : Math.max(-10, Math.min(10, cursor.getInt(lockSpacingColumn)));
                int aodSpacingColumn = cursor.getColumnIndex(ModuleSettings.AOD_CLOCK_SPACING);
                aodClockSpacing = aodSpacingColumn < 0 ? 0 : Math.max(-10, Math.min(10, cursor.getInt(aodSpacingColumn)));
                int statusSpacingColumn = cursor.getColumnIndex(ModuleSettings.STATUS_BAR_CLOCK_SPACING);
                statusBarClockSpacing = statusSpacingColumn < 0 ? 0 : Math.max(-10, Math.min(10, cursor.getInt(statusSpacingColumn)));
                int lockSpacingEnabledColumn = cursor.getColumnIndex(ModuleSettings.LOCK_CLOCK_SPACING_ENABLED);
                lockClockSpacingEnabled = lockSpacingEnabledColumn >= 0 && cursor.getInt(lockSpacingEnabledColumn) != 0;
                int aodSpacingEnabledColumn = cursor.getColumnIndex(ModuleSettings.AOD_CLOCK_SPACING_ENABLED);
                aodClockSpacingEnabled = aodSpacingEnabledColumn >= 0 && cursor.getInt(aodSpacingEnabledColumn) != 0;
                int statusSpacingEnabledColumn = cursor.getColumnIndex(ModuleSettings.STATUS_BAR_CLOCK_SPACING_ENABLED);
                statusBarClockSpacingEnabled = statusSpacingEnabledColumn >= 0 && cursor.getInt(statusSpacingEnabledColumn) != 0;
                int collapseFixColumn = cursor.getColumnIndex(ModuleSettings.COMBINED_COLLAPSE_FIX);
                combinedCollapseFixEnabled = collapseFixColumn >= 0 && cursor.getInt(collapseFixColumn) != 0;
                int secondaryColumn = cursor.getColumnIndex(ModuleSettings.SECONDARY_EXPANSION_FIX);
                secondaryExpansionEnabled = secondaryColumn >= 0 && cursor.getInt(secondaryColumn) != 0;
                int emptyKeepOpenColumn = cursor.getColumnIndex(ModuleSettings.COMBINED_EMPTY_SHADE_KEEP_OPEN);
                combinedEmptyShadeKeepOpenEnabled = emptyKeepOpenColumn >= 0 && cursor.getInt(emptyKeepOpenColumn) != 0;
                int filterSettingsColumn = cursor.getColumnIndex(ModuleSettings.COMBINED_FILTER_SETTINGS);
                combinedFilterSettingsEnabled = filterSettingsColumn >= 0 && cursor.getInt(filterSettingsColumn) != 0;
                int homeIconMinimumColumn = cursor.getColumnIndex(ModuleSettings.HOME_ICON_MINIMUM_SIZE);
                homeIconMinimumSizeEnabled = homeIconMinimumColumn >= 0 && cursor.getInt(homeIconMinimumColumn) != 0;
                int translationOriginColumn = cursor.getColumnIndex(ModuleSettings.QS_TRANSLATION_ORIGIN);
                qsTranslationOriginEnabled = translationOriginColumn >= 0 && cursor.getInt(translationOriginColumn) != 0;
                int separateSwipeColumn = cursor.getColumnIndex(ModuleSettings.SEPARATE_SHADE_SWIPE);
                separateShadeSwipeEnabled = separateSwipeColumn >= 0 && cursor.getInt(separateSwipeColumn) != 0;
                int pullAnimationColumn = cursor.getColumnIndex(ModuleSettings.COMBINED_PULL_ANIMATION);
                combinedPullAnimationEnabled = pullAnimationColumn >= 0 && cursor.getInt(pullAnimationColumn) != 0;
                int mergeSignalColumn = cursor.getColumnIndex(ModuleSettings.MERGE_DUAL_SIGNAL);
                mergeDualSignalEnabled = mergeSignalColumn >= 0 && cursor.getInt(mergeSignalColumn) != 0;
                int lunarColumn = cursor.getColumnIndex(ModuleSettings.HIDE_LUNAR);
                hideLunarEnabled = lunarColumn >= 0 && cursor.getInt(lunarColumn) != 0;
                int carrierColumn = cursor.getColumnIndex(ModuleSettings.HIDE_CARRIER);
                hideCarrierEnabled = carrierColumn >= 0 && cursor.getInt(carrierColumn) != 0;
                int wifiLabelColumn = cursor.getColumnIndex(ModuleSettings.WIFI_LABEL);
                wifiLabelEnabled = wifiLabelColumn >= 0 && cursor.getInt(wifiLabelColumn) != 0;
                int optimizeTextColumn = cursor.getColumnIndex(ModuleSettings.OPTIMIZE_2X1_TEXT);
                optimize2x1TextEnabled = optimizeTextColumn >= 0 && cursor.getInt(optimizeTextColumn) != 0;
                int foldIdleMediaColumn = cursor.getColumnIndex(ModuleSettings.FOLD_IDLE_MEDIA);
                foldIdleMediaEnabled = foldIdleMediaColumn >= 0 && cursor.getInt(foldIdleMediaColumn) != 0;
                int fixMediaSwipeColumn = cursor.getColumnIndex(ModuleSettings.FIX_MEDIA_SWIPE);
                fixMediaSwipeEnabled = fixMediaSwipeColumn >= 0 && cursor.getInt(fixMediaSwipeColumn) != 0;
                int notificationSwipeColumn = cursor.getColumnIndex(ModuleSettings.NOTIFICATION_SWIPE_SEGMENTS);
                notificationSwipeSegmentsEnabled = notificationSwipeColumn >= 0 && cursor.getInt(notificationSwipeColumn) != 0;
                int splitNetworkCardColumn = cursor.getColumnIndex(ModuleSettings.SPLIT_NETWORK_CARD);
                int muteSlashColumn = cursor.getColumnIndex(ModuleSettings.ANIMATED_MUTE_SLASH);
                animatedMuteSlashEnabled = muteSlashColumn >= 0 && cursor.getInt(muteSlashColumn) != 0;
                int splitStyleColumn = cursor.getColumnIndex(ModuleSettings.NETWORK_SPLIT_STYLE);
                networkSplitStyle = splitStyleColumn >= 0 ? Math.max(0, Math.min(1, cursor.getInt(splitStyleColumn)))
                        : (splitNetworkCardColumn >= 0 && cursor.getInt(splitNetworkCardColumn) != 0 ? 1 : 0);
                splitNetworkCardEnabled = networkSplitStyle != 0;
                int circleTilesColumn = cursor.getColumnIndex(ModuleSettings.CIRCLE_SMALL_TILES);
                circleSmallTilesEnabled = circleTilesColumn >= 0 && cursor.getInt(circleTilesColumn) != 0;
                int networkTypeColumn = cursor.getColumnIndex(ModuleSettings.SEPARATE_NETWORK_TYPE);
                separateNetworkTypeEnabled = networkTypeColumn >= 0 && cursor.getInt(networkTypeColumn) != 0;
                int blurEnabledColumn = cursor.getColumnIndex(ModuleSettings.BLUR_ENABLED);
                blurRadiusEnabled = blurEnabledColumn >= 0 && cursor.getInt(blurEnabledColumn) != 0;
                int blurColumn = cursor.getColumnIndex(ModuleSettings.BLUR_RADIUS);
                blurRadius = Math.max(0, Math.min(ModuleSettings.BLUR_MAX,
                        blurColumn >= 0 ? cursor.getInt(blurColumn) : ModuleSettings.BLUR_DEFAULT));
                int backgroundScaleEnabledColumn = cursor.getColumnIndex(ModuleSettings.BACKGROUND_SCALE_ENABLED);
                backgroundScaleEnabled = backgroundScaleEnabledColumn >= 0 && cursor.getInt(backgroundScaleEnabledColumn) != 0;
                int backgroundScaleColumn = cursor.getColumnIndex(ModuleSettings.BACKGROUND_SCALE);
                backgroundScale = Math.max(0, Math.min(ModuleSettings.BACKGROUND_SCALE_MAX,
                        backgroundScaleColumn >= 0 ? cursor.getInt(backgroundScaleColumn) : ModuleSettings.BACKGROUND_SCALE_DEFAULT));
                int wallpaperFixColumn = cursor.getColumnIndex(ModuleSettings.WALLPAPER_STARTUP_FIX);
                wallpaperStartupFixEnabled = wallpaperFixColumn >= 0 && cursor.getInt(wallpaperFixColumn) != 0;
                int hiddenColumn = cursor.getColumnIndex(ModuleSettings.HIDDEN_LAUNCHER_APPS);
                Set<String> hidden = new java.util.HashSet<>();
                if (hiddenColumn >= 0 && cursor.getString(hiddenColumn) != null) {
                    for (String pkg : cursor.getString(hiddenColumn).split("\n")) {
                        if (!pkg.isEmpty()) hidden.add(pkg);
                    }
                }
                hiddenLauncherPackages = Collections.unmodifiableSet(hidden);
                int folderPagingColumn = cursor.getColumnIndex(ModuleSettings.FOLDER_PAGING);
                folderPagingEnabled = folderPagingColumn >= 0 && cursor.getInt(folderPagingColumn) != 0;
                int folderCenterColumn = cursor.getColumnIndex(ModuleSettings.FOLDER_CENTER);
                folderCenterEnabled = folderCenterColumn >= 0 && cursor.getInt(folderCenterColumn) != 0;
                int folderCloseTargetColumn = cursor.getColumnIndex(ModuleSettings.FOLDER_CLOSE_TARGET);
                folderCloseTargetEnabled = folderCloseTargetColumn >= 0 && cursor.getInt(folderCloseTargetColumn) != 0;
                int iconEditColumn = cursor.getColumnIndex(ModuleSettings.EDIT_APP_ICON_NAME);
                editAppIconNameEnabled = iconEditColumn >= 0 && cursor.getInt(iconEditColumn) != 0;
                int hideMenuColumn = cursor.getColumnIndex(ModuleSettings.LAUNCHER_HIDE_APP_MENU);
                launcherHideAppMenuEnabled = hideMenuColumn >= 0 && cursor.getInt(hideMenuColumn) != 0;
                int spreadHiddenColumn = cursor.getColumnIndex(ModuleSettings.LAUNCHER_SPREAD_HIDDEN_APPS);
                launcherSpreadHiddenAppsEnabled = spreadHiddenColumn >= 0 && cursor.getInt(spreadHiddenColumn) != 0;
                int lockWidgetColumn = cursor.getColumnIndex(ModuleSettings.LOCK_WIDGET_STARTUP_FIX);
                lockWidgetStartupFixEnabled = lockWidgetColumn >= 0 && cursor.getInt(lockWidgetColumn) != 0;
                int lockLongPressColumn = cursor.getColumnIndex(ModuleSettings.BLOCK_LOCKSCREEN_LONG_PRESS_EDIT);
                blockLockscreenLongPressEditEnabled = lockLongPressColumn >= 0 && cursor.getInt(lockLongPressColumn) != 0;
                int fixPackColumn = cursor.getColumnIndex(ModuleSettings.FIX_ICON_PACK_PREVIEW);
                fixIconPackPreview = fixPackColumn < 0 || cursor.getInt(fixPackColumn) != 0;
                int iconContourColumn = cursor.getColumnIndex(ModuleSettings.ICON_CONTOUR);
                iconContourEnabled = iconContourColumn >= 0 && cursor.getInt(iconContourColumn) != 0;
                int folderMenuAlignmentColumn = cursor.getColumnIndex(ModuleSettings.FOLDER_MENU_ALIGNMENT);
                folderMenuAlignmentEnabled = folderMenuAlignmentColumn >= 0 && cursor.getInt(folderMenuAlignmentColumn) != 0;
                int widgetContourColumn = cursor.getColumnIndex(ModuleSettings.WIDGET_CONTOUR);
                widgetContourEnabled = widgetContourColumn >= 0 && cursor.getInt(widgetContourColumn) != 0;
                int menuContourColumn = cursor.getColumnIndex(ModuleSettings.MENU_CONTOUR);
                menuContourEnabled = menuContourColumn >= 0 && cursor.getInt(menuContourColumn) != 0;
                int folderColorColumn = cursor.getColumnIndex(ModuleSettings.FOLDER_RESTORE_COLOR);
                folderRestoreColorEnabled = folderColorColumn >= 0 && cursor.getInt(folderColorColumn) != 0;
                int folderRadiusEnabledColumn = cursor.getColumnIndex(ModuleSettings.FOLDER_RADIUS_ENABLED);
                folderRadiusEnabled = folderRadiusEnabledColumn >= 0 && cursor.getInt(folderRadiusEnabledColumn) != 0;
                int folderRadiusColumn = cursor.getColumnIndex(ModuleSettings.FOLDER_RADIUS);
                folderRadiusDp = folderRadiusColumn < 0 ? ModuleSettings.FOLDER_RADIUS_DEFAULT
                        : Math.max(0, Math.min(ModuleSettings.FOLDER_RADIUS_MAX, cursor.getInt(folderRadiusColumn)));
                settingsLoaded = true;
                log(Log.INFO, "FlymeMod", "Control center settings loaded: scale=" + scaleEnabled
                        + ", light=" + lightEnabled + ", darken=" + darkenEnabled
                        + ", headsUpWidth=" + headsUpWidthEnabled + ", whiteActive=" + whiteActiveEnabled + ", notificationCorners=" + notificationCustomCornersEnabled
                        + ", originalNotificationIcons=" + originalNotificationIconsEnabled
                        + ", statusBarChargingInfo=" + statusBarChargingInfoMode
                        + ", monochromeNotificationActions=" + monochromeNotificationActionsEnabled
                        + ", volumeFirstFour=" + volumeFirstFourMode
                        + ", earphoneNoiseControl=" + earphoneNoiseControlEnabled
                        + ", limitAodMovement=" + limitAodMovementEnabled
                        + ", combinedCollapseFix=" + combinedCollapseFixEnabled
                        + ", combinedPullAnimation=" + combinedPullAnimationEnabled
                        + ", qsTranslationOrigin=" + qsTranslationOriginEnabled
                        + ", restoreCollapsedCardHeight=" + restoreCollapsedCardHeightEnabled
                        + ", secondaryExpansion=" + secondaryExpansionEnabled
                        + ", combinedEmptyShadeKeepOpen=" + combinedEmptyShadeKeepOpenEnabled
                        + ", hideLunar=" + hideLunarEnabled
                        + ", mergeDualSignal=" + mergeDualSignalEnabled + ", separateNetworkType=" + separateNetworkTypeEnabled
                        + ", blurRadius=" + (blurRadiusEnabled ? blurRadius : "system")
                        + ", backgroundScale=" + (backgroundScaleEnabled ? backgroundScale : "system")
                        + ", wallpaperStartupFix=" + wallpaperStartupFixEnabled
                        + ", hiddenLauncherApps=" + hiddenLauncherPackages.size()
                        + ", folderPaging=" + folderPagingEnabled + ", folderCenter=" + folderCenterEnabled);
            }
        } catch (Exception e) {
                if (!settingsErrorLogged) {
                settingsErrorLogged = true;
                log(Log.ERROR, "FlymeMod", "Cannot read control center settings", e);
            }
        }
    }

    private static Map<String, String> createStatusIconSettings() {
        Map<String, String> settings = new java.util.HashMap<>();
        settings.put("zen", ModuleSettings.HIDE_STATUS_ICON_KEYS[0]);
        settings.put("sleep", ModuleSettings.HIDE_STATUS_ICON_KEYS[0]);
        settings.put("vpn", ModuleSettings.HIDE_STATUS_ICON_KEYS[1]);
        settings.put("location", ModuleSettings.HIDE_STATUS_ICON_KEYS[2]);
        settings.put("cast", ModuleSettings.HIDE_STATUS_ICON_KEYS[3]);
        settings.put("connected_display", ModuleSettings.HIDE_STATUS_ICON_KEYS[3]);
        settings.put("hotspot", ModuleSettings.HIDE_STATUS_ICON_KEYS[4]);
        settings.put("screen_record", ModuleSettings.HIDE_STATUS_ICON_KEYS[5]);
        settings.put("camera", ModuleSettings.HIDE_STATUS_ICON_KEYS[6]);
        settings.put("microphone", ModuleSettings.HIDE_STATUS_ICON_KEYS[7]);
        return settings;
    }

    private boolean isStatusIconHidden(String key) {
        try {
            if (settingsLoaded) {
                // Settings are represented in the loaded cursor-backed fields below.
                return hiddenStatusIcons.contains(key);
            }
        } catch (RuntimeException ignored) { }
        return false;
    }

    private final Set<String> hiddenStatusIcons = new java.util.HashSet<>();

    private void reapplyStatusIconVisibility() {
        synchronized (requestedStatusIconVisibility) {
            for (Map.Entry<Object, Map<String, Boolean>> controllerEntry : requestedStatusIconVisibility.entrySet()) {
                Object controller = controllerEntry.getKey();
                if (controller == null) continue;
                for (Map.Entry<String, Boolean> iconEntry : new java.util.HashMap<>(controllerEntry.getValue()).entrySet()) {
                    try {
                        controller.getClass().getMethod("setIconVisibility", String.class, boolean.class)
                                .invoke(controller, iconEntry.getKey(), iconEntry.getValue());
                    } catch (ReflectiveOperationException ignored) { }
                }
            }
        }
    }

    private void install(ClassLoader loader, String name, String method, XposedInterface.Hooker hooker,
                         Class<?>... parameters) {
        try {
            Method target = Class.forName(name, false, loader).getDeclaredMethod(method, parameters);
            installDrawingHook(target, hooker);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            log(Log.ERROR, "FlymeMod", "Cannot hook " + name + "#" + method, e);
        }
    }

    private void installDrawingHook(java.lang.reflect.Executable target, XposedInterface.Hooker hooker) {
        if (target.getDeclaringClass() == ImageView.class && target.getName().equals("onDraw")
                && java.util.Arrays.equals(target.getParameterTypes(), new Class<?>[]{android.graphics.Canvas.class})) {
            if (imageDrawHooks.add(hooker)) hook(target).intercept(imageDrawHooks);
        } else hook(target).intercept(hooker);
    }
}
