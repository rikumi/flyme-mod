package dev.rikumi.flymemod;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** SDKStage 5.0.01: extend its actual app manager, retaining native preference writes. */
final class NightModeAppHooks {
    private static final String MANAGER = "com.meizu.flyme.sdkstage.common.d";
    private final SharedPreferences preferences;
    private final BiConsumer<String, Throwable> log;
    private volatile boolean enabled;
    private boolean readErrorLogged;
    private final Map<Object, Boolean> listModes = Collections.synchronizedMap(new WeakHashMap<>());
    private final SharedPreferences.OnSharedPreferenceChangeListener changes = (prefs, key) -> read();

    NightModeAppHooks(SharedPreferences preferences, BiConsumer<String, Throwable> log) {
        this.preferences = preferences;
        this.log = log;
    }

    void install(ClassLoader loader, SignalHooks.Installer installer, Consumer<Method> deoptimize)
            throws ReflectiveOperationException {
        Class<?> manager = loader.loadClass(MANAGER);
        manager.getDeclaredMethod("g", ApplicationInfo.class, boolean.class);
        // Raw DEX names, not JADX collision aliases (f8500b / f8501c).
        Field context = manager.getDeclaredField("b");
        Field apps = manager.getDeclaredField("c");
        if (context.getType() != Context.class || apps.getType() != List.class)
            throw new NoSuchFieldException("Unexpected SDKStage app-manager field types");
        context.setAccessible(true);
        apps.setAccessible(true);
        Class<?> appInfo = loader.loadClass("com.meizu.flyme.sdkstage.data.model.AppInfo");
        Method packageName = appInfo.getDeclaredMethod("getPackageName");
        appInfo.getDeclaredMethod("setTitle", CharSequence.class);
        read();
        preferences.registerOnSharedPreferenceChangeListener(changes);

        installer.hook(MANAGER, "g", chain -> {
            if (!enabled) return chain.proceed();
            ApplicationInfo info = (ApplicationInfo) chain.getArg(0);
            String pkg = info.packageName;
            if ("com.android.shell".equals(pkg) || "com.android.documentsui".equals(pkg)) return false;
            boolean system = (info.flags & (ApplicationInfo.FLAG_SYSTEM
                    | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
            boolean firstParty = pkg.startsWith("com.meizu.") || pkg.startsWith("com.flyme.");
            // Keep background-only services out of the user-facing list, while
            // allowing Wear and other system/first-party apps with a launcher entry.
            if ((system || firstParty) && ((Context) context.get(chain.getThisObject()))
                    .getPackageManager().getLaunchIntentForPackage(pkg) != null) return false;
            return chain.proceed();
        }, ApplicationInfo.class, boolean.class);

        installer.hook(appInfo.getName(), "setTitle", chain -> {
            if (enabled && "com.android.shell".equals(packageName.invoke(chain.getThisObject())))
                return chain.proceed(new Object[]{"LSPosed"});
            return chain.proceed();
        }, CharSequence.class);

        installer.hook(MANAGER, "e", chain -> {
            read();
            Object instance = chain.getThisObject();
            synchronized (instance) {
                boolean current = enabled;
                Boolean previous = listModes.put(instance, current);
                // Clear only the manager's display cache. Do not reset the native
                // blacklist or any per-app dark-mode choice when the option changes.
                if (previous != null && previous != current) ((List<?>) apps.get(instance)).clear();
                Object result = chain.proceed();
                if ((previous == null || previous != current) && result instanceof List<?> loaded) {
                    boolean shell = false, documents = false, wear = false;
                    for (Object item : loaded) {
                        String pkg = (String) packageName.invoke(item);
                        shell |= "com.android.shell".equals(pkg);
                        documents |= "com.android.documentsui".equals(pkg);
                        wear |= "com.meizu.wear".equals(pkg);
                    }
                    log.accept("Dark app list: enabled=" + current + ", count=" + loaded.size()
                            + ", shell=" + shell + ", documents=" + documents + ", wear=" + wear, null);
                }
                return result;
            }
        });
        String activity = "com.meizu.flyme.sdkstage.activity.AppManagerActivity";
        loader.loadClass(activity).getDeclaredMethod("onCreate", Bundle.class);
        installer.hook(activity, "onCreate", chain -> {
            read();
            log.accept("Extended dark app manager: enabled=" + enabled, null);
            return chain.proceed();
        }, Bundle.class);
        // Both native list loading and package-added loading inline the private
        // filter and AppInfo title setter on this version.
        deoptimize.accept(manager.getDeclaredMethod("e"));
        deoptimize.accept(manager.getDeclaredMethod("b", String.class));
        log.accept("SDKStage dark app manager hooks registered: enabled=" + enabled, null);
    }

    private void read() {
        try {
            enabled = preferences.getBoolean(ModuleSettings.DARK_APP_SPECIAL_PACKAGES, false);
        } catch (RuntimeException error) {
            if (!readErrorLogged) {
                readErrorLogged = true;
                log.accept("Cannot read extended dark app manager setting", error);
            }
        }
    }
}
