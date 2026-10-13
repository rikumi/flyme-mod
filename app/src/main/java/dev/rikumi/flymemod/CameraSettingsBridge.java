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
        if (ModuleSettings.AUTO_BRIGHTNESS_OFFSET_ENABLED.equals(key)
                || ModuleSettings.AUTO_BRIGHTNESS_OFFSET.equals(key)
                || ModuleSettings.STATUS_BAR_LYRIC_AVOID.equals(key)
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
        if (!local.getBoolean(ModuleSettings.LANDSCAPE_GAME_ENABLED, OneClickPreset.defaultSwitch(ModuleSettings.LANDSCAPE_GAME_ENABLED))) gamePackages.clear();
        String gameModes = gamePackages.stream().sorted().map(pkg -> pkg + "=" + local.getInt(
                ModuleSettings.LANDSCAPE_GAME_MODE_PREFIX + pkg, 0)).collect(java.util.stream.Collectors.joining("\n"));
        for (XposedService service : services) {
            try {
                service.getRemotePreferences(REMOTE_FILE).edit()
                        .putBoolean(ModuleSettings.AUTO_BRIGHTNESS_OFFSET_ENABLED,
                                local.getBoolean(ModuleSettings.AUTO_BRIGHTNESS_OFFSET_ENABLED, OneClickPreset.defaultSwitch(ModuleSettings.AUTO_BRIGHTNESS_OFFSET_ENABLED)))
                        .putInt(ModuleSettings.AUTO_BRIGHTNESS_OFFSET,
                                local.getInt(ModuleSettings.AUTO_BRIGHTNESS_OFFSET, ModuleSettings.AUTO_BRIGHTNESS_OFFSET_DEFAULT))
                        .putBoolean(ModuleSettings.STATUS_BAR_LYRIC_AVOID,
                                local.getBoolean(ModuleSettings.STATUS_BAR_LYRIC_AVOID, OneClickPreset.defaultSwitch(ModuleSettings.STATUS_BAR_LYRIC_AVOID)))
                        .putBoolean(ModuleSettings.SYSTEM_ICON_PACK,local.getBoolean(ModuleSettings.SYSTEM_ICON_PACK, OneClickPreset.defaultSwitch(ModuleSettings.SYSTEM_ICON_PACK)))
                        .putBoolean(ModuleSettings.DARK_SYSTEM_APP_BLACK_BACKGROUND,
                                local.getBoolean(ModuleSettings.DARK_SYSTEM_APP_BLACK_BACKGROUND, OneClickPreset.defaultSwitch(ModuleSettings.DARK_SYSTEM_APP_BLACK_BACKGROUND)))
                        .putBoolean(ModuleSettings.WIRELESS_DEBUG_THEME_FIX,
                                local.getBoolean(ModuleSettings.WIRELESS_DEBUG_THEME_FIX, OneClickPreset.defaultSwitch(ModuleSettings.WIRELESS_DEBUG_THEME_FIX)))
                        .putBoolean(ModuleSettings.FIX_ZYGOTE_RESTART_UNLOCK,
                                local.getBoolean(ModuleSettings.FIX_ZYGOTE_RESTART_UNLOCK, OneClickPreset.defaultSwitch(ModuleSettings.FIX_ZYGOTE_RESTART_UNLOCK)))
                        .putBoolean(ModuleSettings.FINGERPRINT_SUCCESS_HAPTIC,
                                local.getBoolean(ModuleSettings.FINGERPRINT_SUCCESS_HAPTIC, OneClickPreset.defaultSwitch(ModuleSettings.FINGERPRINT_SUCCESS_HAPTIC)))
                        .putBoolean(ModuleSettings.REMOVE_FINGERPRINT_ANIMATION,
                                local.getBoolean(ModuleSettings.REMOVE_FINGERPRINT_ANIMATION, OneClickPreset.defaultSwitch(ModuleSettings.REMOVE_FINGERPRINT_ANIMATION)))
                        .putInt(ModuleSettings.LOCK_CLOCK_FONT,local.getInt(ModuleSettings.LOCK_CLOCK_FONT,0))
                        .putBoolean(ModuleSettings.LOCK_CLOCK_SPACING_ENABLED,local.getBoolean(ModuleSettings.LOCK_CLOCK_SPACING_ENABLED, OneClickPreset.defaultSwitch(ModuleSettings.LOCK_CLOCK_SPACING_ENABLED)))
                        .putInt(ModuleSettings.LOCK_CLOCK_SPACING,local.getInt(ModuleSettings.LOCK_CLOCK_SPACING,0))
                        .putInt(ModuleSettings.CLOCK_FONT_WEIGHT,local.getInt(ModuleSettings.CLOCK_FONT_WEIGHT,0))
                        .putBoolean(ModuleSettings.SETTINGS_HOME_FORCE_GOOGLE,
                                local.getBoolean(ModuleSettings.SETTINGS_HOME_FORCE_GOOGLE, OneClickPreset.defaultSwitch(ModuleSettings.SETTINGS_HOME_FORCE_GOOGLE)))
                        .putBoolean(ModuleSettings.BACK_GESTURE_REMOVE_SMART,
                                local.getBoolean(ModuleSettings.BACK_GESTURE_REMOVE_SMART, OneClickPreset.defaultSwitch(ModuleSettings.BACK_GESTURE_REMOVE_SMART)))
                        .putBoolean(ModuleSettings.BACK_GESTURE_WIDTH_ENABLED,
                                local.getBoolean(ModuleSettings.BACK_GESTURE_WIDTH_ENABLED, OneClickPreset.defaultSwitch(ModuleSettings.BACK_GESTURE_WIDTH_ENABLED)))
                        .putInt(ModuleSettings.BACK_GESTURE_WIDTH_DP,
                                local.getInt(ModuleSettings.BACK_GESTURE_WIDTH_DP, ModuleSettings.BACK_GESTURE_WIDTH_DEFAULT))
                        .putBoolean(ModuleSettings.PHOTO_PREVIEW_SYSTEM_ROTATION,
                                local.getBoolean(ModuleSettings.PHOTO_PREVIEW_SYSTEM_ROTATION, OneClickPreset.defaultSwitch(ModuleSettings.PHOTO_PREVIEW_SYSTEM_ROTATION)))
                        .putBoolean(ModuleSettings.CAMERA_DISABLE_AUTO_NIGHT,
                                local.getBoolean(ModuleSettings.CAMERA_DISABLE_AUTO_NIGHT, OneClickPreset.defaultSwitch(ModuleSettings.CAMERA_DISABLE_AUTO_NIGHT)))
                        .putBoolean(ModuleSettings.CAMERA_NIGHT_EXPOSURE_ENABLED,
                                local.getBoolean(ModuleSettings.CAMERA_NIGHT_EXPOSURE_ENABLED, OneClickPreset.defaultSwitch(ModuleSettings.CAMERA_NIGHT_EXPOSURE_ENABLED)))
                        .putInt(ModuleSettings.CAMERA_NIGHT_EXPOSURE_MS,
                                local.getInt(ModuleSettings.CAMERA_NIGHT_EXPOSURE_MS, ModuleSettings.CAMERA_NIGHT_EXPOSURE_DEFAULT))
                        .remove("camera_disable_color_enhancement")
                        .putBoolean(ModuleSettings.CAMERA_LIVE_PREROLL_ENABLED,
                                local.getBoolean(ModuleSettings.CAMERA_LIVE_PREROLL_ENABLED, OneClickPreset.defaultSwitch(ModuleSettings.CAMERA_LIVE_PREROLL_ENABLED)))
                        .putInt(ModuleSettings.CAMERA_LIVE_PREROLL_MS,
                                local.getInt(ModuleSettings.CAMERA_LIVE_PREROLL_MS, ModuleSettings.CAMERA_LIVE_PREROLL_DEFAULT))
                        .putBoolean(ModuleSettings.CAMERA_KEEP_FILTER,
                                local.getBoolean(ModuleSettings.CAMERA_KEEP_FILTER, OneClickPreset.defaultSwitch(ModuleSettings.CAMERA_KEEP_FILTER)))
                        .putBoolean(ModuleSettings.APP_IMMERSIVE_NAVIGATION,
                                local.getBoolean(ModuleSettings.APP_IMMERSIVE_NAVIGATION, OneClickPreset.defaultSwitch(ModuleSettings.APP_IMMERSIVE_NAVIGATION)))
                        .putStringSet(ModuleSettings.APP_IMMERSIVE_NAVIGATION_PACKAGES,
                                ModuleSettings.navigationApplications(local))
                        .putString(ModuleSettings.LANDSCAPE_GAME_MODES, gameModes)
                        .putBoolean(ModuleSettings.SMALL_WINDOW_LIMIT_ENABLED,
                                local.getBoolean(ModuleSettings.SMALL_WINDOW_LIMIT_ENABLED, OneClickPreset.defaultSwitch(ModuleSettings.SMALL_WINDOW_LIMIT_ENABLED)))
                        .putInt(ModuleSettings.SMALL_WINDOW_LIMIT,
                                local.getInt(ModuleSettings.SMALL_WINDOW_LIMIT, ModuleSettings.SMALL_WINDOW_LIMIT_DEFAULT))
                        .putBoolean(ModuleSettings.DARK_APP_WHITELIST_MODE, local.getBoolean(ModuleSettings.DARK_APP_WHITELIST_MODE, OneClickPreset.defaultSwitch(ModuleSettings.DARK_APP_WHITELIST_MODE)))
                        .putBoolean(ModuleSettings.DARK_APP_SPECIAL_PACKAGES,
                                local.getBoolean(ModuleSettings.DARK_APP_SPECIAL_PACKAGES, OneClickPreset.defaultSwitch(ModuleSettings.DARK_APP_SPECIAL_PACKAGES)))
                        .commit();
            } catch (RuntimeException error) {
                Log.e("FlymeMod", "Cannot publish camera settings", error);
            }
        }
    }
}
