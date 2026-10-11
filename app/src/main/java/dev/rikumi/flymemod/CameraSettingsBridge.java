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
        for (XposedService service : services) {
            try {
                service.getRemotePreferences(REMOTE_FILE).edit()
                        .putBoolean(ModuleSettings.PHOTO_PREVIEW_SYSTEM_ROTATION,
                                local.getBoolean(ModuleSettings.PHOTO_PREVIEW_SYSTEM_ROTATION, false))
                        .remove("camera_disable_color_enhancement")
                        .putBoolean(ModuleSettings.CAMERA_KEEP_FILTER,
                                local.getBoolean(ModuleSettings.CAMERA_KEEP_FILTER, false))
                        .putBoolean(ModuleSettings.DARK_APP_SPECIAL_PACKAGES,
                                local.getBoolean(ModuleSettings.DARK_APP_SPECIAL_PACKAGES, false))
                        .commit();
            } catch (RuntimeException error) {
                Log.e("FlymeMod", "Cannot publish camera settings", error);
            }
        }
    }
}
