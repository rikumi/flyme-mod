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
    private SharedPreferences local;
    private final SharedPreferences.OnSharedPreferenceChangeListener changes = (prefs, key) -> {
        if (ModuleSettings.PHOTO_PREVIEW_SYSTEM_ROTATION.equals(key)
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
            }
            @Override public void onServiceDied(XposedService service) { services.remove(service); }
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
                        .putBoolean(ModuleSettings.PHOTO_PREVIEW_SYSTEM_ROTATION,
                                local.getBoolean(ModuleSettings.PHOTO_PREVIEW_SYSTEM_ROTATION, false))
                        .remove("camera_disable_color_enhancement")
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
