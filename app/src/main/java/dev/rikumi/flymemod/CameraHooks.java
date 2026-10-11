package dev.rikumi.flymemod;

import android.content.Context;
import android.os.Bundle;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.Settings;
import java.lang.reflect.Method;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Traced against Camera 12.7.2 on the connected Flyme device. */
final class CameraHooks {
    private final BiConsumer<String, Throwable> log;
    private volatile boolean systemRotation;
    private boolean readErrorLogged, settingsRead, rotationLogged;
    private final SharedPreferences preferences;
    private final SharedPreferences.OnSharedPreferenceChangeListener changes = (prefs, key) -> read();
    private boolean observing;

    CameraHooks(SharedPreferences preferences, BiConsumer<String, Throwable> log) {
        this.preferences = preferences;
        this.log = log;
    }

    void installCamera(ClassLoader loader, SignalHooks.Installer installer, Consumer<Method> deoptimize)
            throws ReflectiveOperationException {
        String application = "com.meizu.media.camera.app.CameraApp";
        installer.hook(application, "onCreate", chain -> {
            observe((Context) chain.getThisObject());
            return chain.proceed();
        });
        for (String activity : new String[]{"com.meizu.media.camera.CameraActivity",
                "com.meizu.media.camera.simplify.CameraSimplifyActivity"}) {
            try {
                Method create = loader.loadClass(activity).getDeclaredMethod("onCreate", Bundle.class);
                installer.hook(activity, "onCreate", chain -> {
                    observe((Context) chain.getThisObject());
                    log.accept("Camera lifecycle: " + chain.getThisObject().getClass().getName(), null);
                    return chain.proceed();
                }, Bundle.class);
                deoptimize.accept(create);
            } catch (ReflectiveOperationException error) {
                log.accept("Cannot resolve camera lifecycle " + activity, error);
            }
        }
        try { installRotation(loader, installer, deoptimize); }
        catch (ReflectiveOperationException | LinkageError error) {
            log.accept("Cannot resolve camera preview rotation", error);
        }
        try {
            new CameraFilterHooks(preferences, log).install(loader, installer, deoptimize);
        } catch (ReflectiveOperationException | LinkageError error) {
            log.accept("Cannot resolve camera filter retention", error);
        }
        log.accept("Camera hook registration completed", null);
    }

    private void installRotation(ClassLoader loader, SignalHooks.Installer installer, Consumer<Method> deoptimize)
            throws ReflectiveOperationException {
        Class<?> camera = loader.loadClass("com.meizu.media.camera.CameraActivity");
        camera.getDeclaredMethod("getMzGalleryIntent", Uri.class);
        installer.hook(camera.getName(), "getMzGalleryIntent", chain -> {
            Intent intent = (Intent) chain.proceed();
            Context context = (Context) chain.getThisObject();
            if (!settingsRead) observe(context);
            boolean locked = Settings.System.getInt(context.getContentResolver(),
                    Settings.System.ACCELEROMETER_ROTATION, 0) == 0;
            if (!rotationLogged) {
                rotationLogged = true;
                log.accept("Camera preview entry: enabled=" + systemRotation + ", locked=" + locked, null);
            }
            if (systemRotation && intent != null && locked) {
                intent.putExtra("Rotation", 0);
                // Preserve CAMERA_VIEW, secure flags, album selection, URI and
                // thumbnail transition. Gallery scope handles its delayed SENSOR request.
            }
            return intent;
        }, Uri.class);
        deoptimize.accept(camera.getDeclaredMethod("gotoGallery", Uri.class, android.graphics.Rect.class));
    }

    private void observe(Context context) {
        read();
        if (observing) return;
        preferences.registerOnSharedPreferenceChangeListener(changes);
        observing = true;
    }

    private void read() {
        try {
            boolean rotation = preferences.getBoolean(ModuleSettings.PHOTO_PREVIEW_SYSTEM_ROTATION, false);
            if (!settingsRead || systemRotation != rotation) {
                log.accept("Camera remote settings: rotation=" + rotation, null);
                rotationLogged = false;
            }
            systemRotation = rotation;
            settingsRead = true;
        } catch (RuntimeException error) {
            if (!readErrorLogged) { readErrorLogged = true; log.accept("Cannot read camera remote settings", error); }
        }
    }
}
