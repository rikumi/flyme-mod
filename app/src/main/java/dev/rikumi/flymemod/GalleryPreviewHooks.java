package dev.rikumi.flymemod;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.provider.Settings;
import java.util.function.BiConsumer;

/** Gallery 12.7.3: retain the native CAMERA_VIEW route and its album/security policy. */
final class GalleryPreviewHooks {
    private final SharedPreferences preferences;
    private final BiConsumer<String, Throwable> log;
    private boolean reported;

    GalleryPreviewHooks(SharedPreferences preferences, BiConsumer<String, Throwable> log) {
        this.preferences = preferences;
        this.log = log;
    }

    void install(ClassLoader loader, SignalHooks.Installer installer) throws ReflectiveOperationException {
        Class<?> preview = loader.loadClass("com.meizu.media.gallery.external.ExternalPhotoActivity");
        preview.getDeclaredMethod("onCreate", Bundle.class);
        Activity.class.getDeclaredMethod("setRequestedOrientation", int.class);
        installer.hook(Activity.class.getName(), "setRequestedOrientation", chain -> {
            Activity activity = (Activity) chain.getThisObject();
            if (!preview.isInstance(activity)) return chain.proceed();
            Intent intent = activity.getIntent();
            if (intent == null || !"com.meizu.gallery.action.CAMERA_VIEW".equals(intent.getAction()))
                return chain.proceed();
            boolean enabled;
            try {
                enabled = preferences.getBoolean(ModuleSettings.PHOTO_PREVIEW_SYSTEM_ROTATION, false);
            } catch (RuntimeException error) {
                if (!reported) { reported = true; log.accept("Cannot read gallery preview settings", error); }
                return chain.proceed();
            }
            if (!enabled || Settings.System.getInt(activity.getContentResolver(),
                    Settings.System.ACCELEROMETER_ROTATION, 0) != 0) return chain.proceed();
            if (!reported) {
                reported = true;
                log.accept("Camera gallery preview follows system rotation lock", null);
            }
            // Covers initial landscape selection and PhotoPagerFragment.K9's
            // delayed SENSOR request. USER respects the actual system rotation lock.
            return chain.proceed(new Object[]{ActivityInfo.SCREEN_ORIENTATION_USER});
        }, int.class);
    }
}
