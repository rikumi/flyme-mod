package dev.rikumi.flymemod;

import android.content.Context;
import android.database.Cursor;
import java.lang.reflect.Field;
import java.util.function.BiConsumer;

/** Application store 11.1.5: skip only the splash redirect, keeping setup and privacy flow. */
final class StoreAdHooks {
    private final BiConsumer<String, Throwable> log;
    private boolean readErrorLogged;

    StoreAdHooks(BiConsumer<String, Throwable> log) { this.log = log; }

    void install(ClassLoader loader, SignalHooks.Installer installer) throws ReflectiveOperationException {
        Class<?> base = Class.forName("com.meizu.cloud.base.app.BaseActivity", false, loader);
        Class<?> callback = Class.forName("com.meizu.cloud.app.firstad.JumpFirstAdInterface", false, loader);
        // Resolve the delegate through its interface so its obfuscated class name may change.
        Class<?> delegate = null;
        for (Field field : base.getDeclaredFields()) {
            if (callback.isAssignableFrom(field.getType()) && field.getType() != callback) {
                delegate = field.getType();
                break;
            }
        }
        if (delegate == null) {
            throw new NoSuchMethodException("Store splash redirect delegate");
        }
        Field contextField = null;
        for (Field field : delegate.getDeclaredFields()) {
            if (Context.class.isAssignableFrom(field.getType())) {
                field.setAccessible(true);
                contextField = field;
                break;
            }
        }
        if (contextField == null) throw new NoSuchFieldException("Store redirect activity");
        Field activity = contextField;
        // Both captured versions return false here to continue normal page initialization.
        String decision = "rd.j".equals(delegate.getName()) ? "b" : "a";
        if (("hc.l".equals(delegate.getName()) || "rd.j".equals(delegate.getName()))
                && delegate.getDeclaredMethod(decision).getReturnType() == boolean.class) {
            installer.hook(delegate.getName(), decision, chain -> {
                Context context = (Context) activity.get(chain.getThisObject());
                int version = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionCode;
                return (version == 11001005 || version == 12205001)
                        && enabled(context) ? false : chain.proceed();
            });
        }
        Class<?> firstAd = Class.forName("com.meizu.cloud.app.request.model.FirstAd", false, loader);
        Class<?> platformAd = Class.forName("com.meizu.advertise.api.AdData", false, loader);
        io.github.libxposed.api.XposedInterface.Hooker skipAd = chain -> {
            Object redirect = chain.getThisObject();
            if (!enabled((Context) activity.get(redirect))) return chain.proceed();
            callback.getMethod("noAd").invoke(redirect);
            return null;
        };
        installer.hook(delegate.getName(), "onGetMStoreAdSuccess", skipAd, firstAd, String.class);
        installer.hook(delegate.getName(), "onGetPlatformAdSuccess", skipAd, platformAd);
    }

    private boolean enabled(Context context) {
        try (Cursor cursor = context.getContentResolver().query(ModuleSettings.URI, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(ModuleSettings.BLOCK_STORE_SPLASH);
                return column >= 0 && cursor.getInt(column) != 0;
            }
        } catch (RuntimeException e) {
            if (!readErrorLogged) {
                readErrorLogged = true;
                log.accept("Cannot read store advertisement preferences", e);
            }
        }
        return false;
    }
}
