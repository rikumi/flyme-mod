package dev.rikumi.flymemod;

import android.app.Application;
import android.content.SharedPreferences;
import android.util.Log;
import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/** Publish camera and miscellaneous switches independent of package visibility. */
public final class CameraSettingsBridge extends Application {
    static final String REMOTE_FILE = "camera_settings";
    private final Set<XposedService> services = new CopyOnWriteArraySet<>();
    private final Set<Runnable> serviceObservers = new CopyOnWriteArraySet<>();
    public java.util.List<XposedService> xposedServices() { return new java.util.ArrayList<>(services); }
    public void addServiceObserver(Runnable observer) { serviceObservers.add(observer); }
    public void removeServiceObserver(Runnable observer) { serviceObservers.remove(observer); }
    private void notifyServicesChanged() { serviceObservers.forEach(Runnable::run); }
    private SharedPreferences local;
    private final SharedPreferences.OnSharedPreferenceChangeListener changes = (prefs, key) -> {
        if (key == null
                || ModuleSettings.FIX_MEDIA_STARTUP.equals(key)
                || ModuleSettings.ANDROID_VERSION_KERNEL.equals(key)
                || ModuleSettings.STATUS_BATTERY_CAPACITY.equals(key)
                || ModuleSettings.AUTO_BRIGHTNESS_OFFSET_ENABLED.equals(key)
                || ModuleSettings.AUTO_BRIGHTNESS_OFFSET.equals(key)
                || ModuleSettings.EARPHONE_NOISE_CONTROL.equals(key)
                || ModuleSettings.HEYMELODY_NOISE_CONTROL.equals(key)
                || ModuleSettings.STATUS_BAR_LYRIC_AVOID.equals(key)
                || ModuleSettings.TICKER_LYRIC_AVOID.equals(key)
                || ModuleSettings.SYSTEM_ICON_PACK.equals(key)
                || ModuleSettings.DARK_SYSTEM_APP_BLACK_BACKGROUND.equals(key)
                || ModuleSettings.WIRELESS_DEBUG_THEME_FIX.equals(key)
                || ModuleSettings.FIX_ZYGOTE_RESTART_UNLOCK.equals(key)
                || ModuleSettings.FINGERPRINT_SUCCESS_HAPTIC.equals(key)
                || ModuleSettings.REMOVE_FINGERPRINT_ANIMATION.equals(key)
                || ModuleSettings.LOCK_CLOCK_FONT.equals(key)
                || ModuleSettings.LOCK_CLOCK_SPACING_ENABLED.equals(key)
                || ModuleSettings.LOCK_CLOCK_SPACING.equals(key)
                || ModuleSettings.CLOCK_FONT_WEIGHT.equals(key)
                || ModuleSettings.SETTINGS_HOME_FORCE_GOOGLE.equals(key)
                || ModuleSettings.PHOTO_PREVIEW_SYSTEM_ROTATION.equals(key)
                || ModuleSettings.BACK_GESTURE_REMOVE_SMART.equals(key)
                || ModuleSettings.BACK_GESTURE_WIDTH_ENABLED.equals(key)
                || ModuleSettings.BACK_GESTURE_WIDTH_DP.equals(key)
                || ModuleSettings.CAMERA_DISABLE_AUTO_NIGHT.equals(key)
                || ModuleSettings.CAMERA_NIGHT_EXPOSURE_ENABLED.equals(key)
                || ModuleSettings.CAMERA_NIGHT_EXPOSURE_MS.equals(key)
                || ModuleSettings.CAMERA_LIVE_PREROLL_ENABLED.equals(key)
                || ModuleSettings.CAMERA_LIVE_PREROLL_MS.equals(key)
                || ModuleSettings.CAMERA_KEEP_FILTER.equals(key)
                || ModuleSettings.LAUNCHER_EXTRA_ROW.equals(key)
                || ModuleSettings.APP_IMMERSIVE_NAVIGATION.equals(key)
                || ModuleSettings.APP_IMMERSIVE_NAVIGATION_PACKAGES.equals(key)
                || ModuleSettings.LANDSCAPE_GAME_PACKAGES.equals(key)
                || ModuleSettings.LANDSCAPE_GAME_ENABLED.equals(key)
                || (key != null && key.startsWith(ModuleSettings.LANDSCAPE_GAME_MODE_PREFIX))
                || ModuleSettings.SMALL_WINDOW_LIMIT_ENABLED.equals(key)
                || ModuleSettings.SMALL_WINDOW_LIMIT.equals(key)
                || ModuleSettings.DARK_APP_WHITELIST_MODE.equals(key)
                || ModuleSettings.DARK_APP_SPECIAL_PACKAGES.equals(key)) publish();
    };

    @Override public void onCreate() {
        super.onCreate();
        local = ModuleSettings.preferences(this);
        local.registerOnSharedPreferenceChangeListener(changes);
        SystemIconPackStore.get(this);
        XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
            @Override public void onServiceBind(XposedService service) {
                services.add(service);
                new Thread(() -> {
                    try { SystemFontStore.sync(CameraSettingsBridge.this, service); }
                    catch (Exception error) { Log.e("FlymeMod", "Cannot synchronize system fonts", error); }
                }, "FlymeMod-font-sync").start();
                publish();
                notifyServicesChanged();
            }
            @Override public void onServiceDied(XposedService service) {
                services.remove(service);
                notifyServicesChanged();
            }
        });
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration configuration) {
        super.onConfigurationChanged(configuration);
        SystemIconPackStore.get(this).rebuild();
    }

    private void publish() {
        Set<String> gamePackages = new java.util.HashSet<>(local.getStringSet(
                ModuleSettings.LANDSCAPE_GAME_PACKAGES, java.util.Collections.emptySet()));
        if (!local.getBoolean(ModuleSettings.LANDSCAPE_GAME_ENABLED, false)) gamePackages.clear();
        String gameModes = gamePackages.stream().sorted().map(pkg -> pkg + "=" + local.getInt(
                ModuleSettings.LANDSCAPE_GAME_MODE_PREFIX + pkg, 0)).collect(java.util.stream.Collectors.joining("\n"));
        for (XposedService service : services) {
            try {
                service.getRemotePreferences(REMOTE_FILE).edit()
                        .putBoolean(ModuleSettings.FIX_MEDIA_STARTUP, local.getBoolean(ModuleSettings.FIX_MEDIA_STARTUP, false))
                        .putBoolean(ModuleSettings.LAUNCHER_EXTRA_ROW,
                                local.getBoolean(ModuleSettings.LAUNCHER_EXTRA_ROW, false))
                        .putBoolean(ModuleSettings.AUTO_BRIGHTNESS_OFFSET_ENABLED,
                                local.getBoolean(ModuleSettings.AUTO_BRIGHTNESS_OFFSET_ENABLED, false))
                        .putInt(ModuleSettings.AUTO_BRIGHTNESS_OFFSET,
                                local.getInt(ModuleSettings.AUTO_BRIGHTNESS_OFFSET, ModuleSettings.AUTO_BRIGHTNESS_OFFSET_DEFAULT))
                        .putBoolean(ModuleSettings.HEYMELODY_NOISE_CONTROL,
                                local.getBoolean(ModuleSettings.HEYMELODY_NOISE_CONTROL, false))
                        .putBoolean(ModuleSettings.EARPHONE_NOISE_CONTROL,
                                local.getBoolean(ModuleSettings.EARPHONE_NOISE_CONTROL, false))
                        .putBoolean(ModuleSettings.TICKER_LYRIC_AVOID, local.getBoolean(ModuleSettings.TICKER_LYRIC_AVOID, false))
                        .putBoolean(ModuleSettings.STATUS_BAR_LYRIC_AVOID,
                                local.getBoolean(ModuleSettings.STATUS_BAR_LYRIC_AVOID, false))
                        .putBoolean(ModuleSettings.SYSTEM_ICON_PACK,local.getBoolean(ModuleSettings.SYSTEM_ICON_PACK, false))
                        .putBoolean(ModuleSettings.DARK_SYSTEM_APP_BLACK_BACKGROUND,
                                local.getBoolean(ModuleSettings.DARK_SYSTEM_APP_BLACK_BACKGROUND, false))
                        .putBoolean(ModuleSettings.WIRELESS_DEBUG_THEME_FIX,
                                local.getBoolean(ModuleSettings.WIRELESS_DEBUG_THEME_FIX, false))
                        .putBoolean(ModuleSettings.FIX_ZYGOTE_RESTART_UNLOCK,
                                local.getBoolean(ModuleSettings.FIX_ZYGOTE_RESTART_UNLOCK, false))
                        .putBoolean(ModuleSettings.FINGERPRINT_SUCCESS_HAPTIC,
                                local.getBoolean(ModuleSettings.FINGERPRINT_SUCCESS_HAPTIC, false))
                        .putBoolean(ModuleSettings.REMOVE_FINGERPRINT_ANIMATION,
                                local.getBoolean(ModuleSettings.REMOVE_FINGERPRINT_ANIMATION, false))
                        .putInt(ModuleSettings.LOCK_CLOCK_FONT,local.getInt(ModuleSettings.LOCK_CLOCK_FONT,0))
                        .putBoolean(ModuleSettings.LOCK_CLOCK_SPACING_ENABLED,local.getBoolean(ModuleSettings.LOCK_CLOCK_SPACING_ENABLED, false))
                        .putInt(ModuleSettings.LOCK_CLOCK_SPACING,local.getInt(ModuleSettings.LOCK_CLOCK_SPACING,0))
                        .putInt(ModuleSettings.CLOCK_FONT_WEIGHT,local.getInt(ModuleSettings.CLOCK_FONT_WEIGHT,0))
                        .putBoolean(ModuleSettings.ANDROID_VERSION_KERNEL, local.getBoolean(ModuleSettings.ANDROID_VERSION_KERNEL, false))
                        .putBoolean(ModuleSettings.STATUS_BATTERY_CAPACITY, local.getBoolean(ModuleSettings.STATUS_BATTERY_CAPACITY, false))
                        .putBoolean(ModuleSettings.SETTINGS_HOME_FORCE_GOOGLE,
                                local.getBoolean(ModuleSettings.SETTINGS_HOME_FORCE_GOOGLE, false))
                        .putBoolean(ModuleSettings.BACK_GESTURE_REMOVE_SMART,
                                local.getBoolean(ModuleSettings.BACK_GESTURE_REMOVE_SMART, false))
                        .putBoolean(ModuleSettings.BACK_GESTURE_WIDTH_ENABLED,
                                local.getBoolean(ModuleSettings.BACK_GESTURE_WIDTH_ENABLED, false))
                        .putInt(ModuleSettings.BACK_GESTURE_WIDTH_DP,
                                local.getInt(ModuleSettings.BACK_GESTURE_WIDTH_DP, ModuleSettings.BACK_GESTURE_WIDTH_DEFAULT))
                        .putBoolean(ModuleSettings.PHOTO_PREVIEW_SYSTEM_ROTATION,
                                local.getBoolean(ModuleSettings.PHOTO_PREVIEW_SYSTEM_ROTATION, false))
                        .putBoolean(ModuleSettings.CAMERA_DISABLE_AUTO_NIGHT,
                                local.getBoolean(ModuleSettings.CAMERA_DISABLE_AUTO_NIGHT, false))
                        .putBoolean(ModuleSettings.CAMERA_NIGHT_EXPOSURE_ENABLED,
                                local.getBoolean(ModuleSettings.CAMERA_NIGHT_EXPOSURE_ENABLED, false))
                        .putInt(ModuleSettings.CAMERA_NIGHT_EXPOSURE_MS,
                                local.getInt(ModuleSettings.CAMERA_NIGHT_EXPOSURE_MS, ModuleSettings.CAMERA_NIGHT_EXPOSURE_DEFAULT))
                        .remove("camera_disable_color_enhancement")
                        .putBoolean(ModuleSettings.CAMERA_LIVE_PREROLL_ENABLED,
                                local.getBoolean(ModuleSettings.CAMERA_LIVE_PREROLL_ENABLED, false))
                        .putInt(ModuleSettings.CAMERA_LIVE_PREROLL_MS,
                                local.getInt(ModuleSettings.CAMERA_LIVE_PREROLL_MS, ModuleSettings.CAMERA_LIVE_PREROLL_DEFAULT))
                        .putBoolean(ModuleSettings.CAMERA_KEEP_FILTER,
                                local.getBoolean(ModuleSettings.CAMERA_KEEP_FILTER, false))
                        .putBoolean(ModuleSettings.APP_IMMERSIVE_NAVIGATION,
                                local.getBoolean(ModuleSettings.APP_IMMERSIVE_NAVIGATION, false))
                        .putStringSet(ModuleSettings.APP_IMMERSIVE_NAVIGATION_PACKAGES,
                                ModuleSettings.navigationApplications(local))
                        .putString(ModuleSettings.LANDSCAPE_GAME_MODES, gameModes)
                        .putBoolean(ModuleSettings.SMALL_WINDOW_LIMIT_ENABLED,
                                local.getBoolean(ModuleSettings.SMALL_WINDOW_LIMIT_ENABLED, false))
                        .putInt(ModuleSettings.SMALL_WINDOW_LIMIT,
                                local.getInt(ModuleSettings.SMALL_WINDOW_LIMIT, ModuleSettings.SMALL_WINDOW_LIMIT_DEFAULT))
                        .putBoolean(ModuleSettings.DARK_APP_WHITELIST_MODE, local.getBoolean(ModuleSettings.DARK_APP_WHITELIST_MODE, false))
                        .putBoolean(ModuleSettings.DARK_APP_SPECIAL_PACKAGES,
                                local.getBoolean(ModuleSettings.DARK_APP_SPECIAL_PACKAGES, false))
                        .commit();
            } catch (RuntimeException error) {
                Log.e("FlymeMod", "Cannot publish camera settings", error);
            }
        }
    }
}
