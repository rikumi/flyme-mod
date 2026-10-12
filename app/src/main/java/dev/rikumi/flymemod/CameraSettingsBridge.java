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
        if (ModuleSettings.DARK_SYSTEM_APP_BLACK_BACKGROUND.equals(key)
                || ModuleSettings.SYSTEM_THEME_COLOR_ENABLED.equals(key)
                || ModuleSettings.SYSTEM_THEME_COLOR.equals(key)
                || ModuleSettings.PHOTO_PREVIEW_SYSTEM_ROTATION.equals(key)
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
                || (key != null && key.startsWith(ModuleSettings.LANDSCAPE_GAME_MODE_PREFIX))
                || ModuleSettings.SMALL_WINDOW_LIMIT_ENABLED.equals(key)
                || ModuleSettings.SMALL_WINDOW_LIMIT.equals(key)
                || ModuleSettings.DARK_APP_SPECIAL_PACKAGES.equals(key)) publish();
    };

    @Override public void onCreate() {
        super.onCreate();
        local = ModuleSettings.preferences(this);
        local.registerOnSharedPreferenceChangeListener(changes);
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

    private void publish() {
        Set<String> gamePackages = new java.util.HashSet<>(local.getStringSet(
                ModuleSettings.LANDSCAPE_GAME_PACKAGES, java.util.Collections.emptySet()));
        String gameModes = gamePackages.stream().sorted().map(pkg -> pkg + "=" + local.getInt(
                ModuleSettings.LANDSCAPE_GAME_MODE_PREFIX + pkg, 0)).collect(java.util.stream.Collectors.joining("\n"));
        for (XposedService service : services) {
            try {
                service.getRemotePreferences(REMOTE_FILE).edit()
                        .putBoolean(ModuleSettings.DARK_SYSTEM_APP_BLACK_BACKGROUND,
                                local.getBoolean(ModuleSettings.DARK_SYSTEM_APP_BLACK_BACKGROUND, false))
                        .putBoolean(ModuleSettings.SYSTEM_THEME_COLOR_ENABLED,
                                local.getBoolean(ModuleSettings.SYSTEM_THEME_COLOR_ENABLED, false))
                        .putInt(ModuleSettings.SYSTEM_THEME_COLOR,
                                local.getInt(ModuleSettings.SYSTEM_THEME_COLOR, ModuleSettings.SYSTEM_THEME_COLOR_DEFAULT))
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
                        .putBoolean(ModuleSettings.DARK_APP_SPECIAL_PACKAGES,
                                local.getBoolean(ModuleSettings.DARK_APP_SPECIAL_PACKAGES, false))
                        .commit();
            } catch (RuntimeException error) {
                Log.e("FlymeMod", "Cannot publish camera settings", error);
            }
        }
    }
}
