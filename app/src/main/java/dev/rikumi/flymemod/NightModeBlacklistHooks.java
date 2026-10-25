package dev.rikumi.flymemod;

import android.app.Application;
import android.database.ContentObserver;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.net.Uri;
import android.provider.Settings;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

final class NightModeBlacklistHooks {
    private static final String TYPE = "flyme.widget.FlymeDarkModeInjectImpl";
    private final Set<Object> registered = Collections.newSetFromMap(new WeakHashMap<>());

    private SharedPreferences.OnSharedPreferenceChangeListener changes;
    private final SharedPreferences preferences;
    NightModeBlacklistHooks(SharedPreferences preferences) { this.preferences = preferences; }
    private boolean whitelist() { return preferences.getBoolean(ModuleSettings.DARK_APP_WHITELIST_MODE, false); }
    private boolean enabled() { return preferences.getBoolean(ModuleSettings.DARK_APP_SPECIAL_PACKAGES, false); }

    void install(ClassLoader loader, SignalHooks.Installer installer, Consumer<Method> deoptimize,
                 BiConsumer<String, Throwable> log) throws ReflectiveOperationException {
        Class<?> type = Class.forName(TYPE, false, loader);
        Field application = type.getDeclaredField("mApplication"); application.setAccessible(true);
        Method refresh = type.getDeclaredMethod("onBlackListChanged");
        Field mode = type.getDeclaredField("mMzNightModeUseOf"); mode.setAccessible(true);
        Field initialized = type.getDeclaredField("mHaveInit"); initialized.setAccessible(true);
        Field black = type.getDeclaredField("mAppBlackList"); black.setAccessible(true);
        installer.hook(TYPE, "filterBlackList", chain -> {
            if (!enabled() && !whitelist()) return chain.proceed();
            Application app = (Application) application.get(chain.getThisObject());
            if (app != null && whitelist()) {
                String selected = Settings.Global.getString(app.getContentResolver(), NightModeWhitelistPolicy.KEY);
                mode.setInt(chain.getThisObject(), NightModeWhitelistPolicy.contains(selected, app.getPackageName()) ? 3 : 2);
                initialized.setBoolean(chain.getThisObject(), true);
                return true;
            }
            if (app != null) black.set(chain.getThisObject(), Settings.Global.getString(
                    app.getContentResolver(), "flyme_night_mode_black_list"));
            // The original ROM branch disables conversion and retains its other exclusion rules.
            return chain.proceed();
        });
        installer.hook(TYPE, "listenNightModeSwitch", chain -> {
            Object result = chain.proceed();
            if (!enabled() && !whitelist()) return result;
            Object instance = chain.getThisObject();
            Application app = (Application) application.get(instance);
            if (app == null) return result;
            synchronized (registered) {
                if (registered.contains(instance)) return result;
                try {
                    app.getContentResolver().registerContentObserver(Settings.Global.getUriFor(
                            "flyme_night_mode_black_list"), false, new ContentObserver(new Handler(Looper.getMainLooper())) {
                        @Override public void onChange(boolean selfChange, Uri uri) {
                            if (!enabled() && !whitelist()) return;
                            try { refresh.invoke(instance); }
                            catch (ReflectiveOperationException | RuntimeException error) {
                                log.accept("Cannot refresh native dark-mode blacklist", error);
                            }
                        }
                    });
                    app.getContentResolver().registerContentObserver(Settings.Global.getUriFor(NightModeWhitelistPolicy.KEY),
                            false, new ContentObserver(new Handler(Looper.getMainLooper())) {
                        @Override public void onChange(boolean selfChange, Uri uri) {
                            if (!whitelist()) return;
                            try { refresh.invoke(instance); }
                            catch (ReflectiveOperationException | RuntimeException error) {
                                log.accept("Cannot refresh forced dark whitelist", error);
                            }
                        }
                    });
                    registered.add(instance);
                } catch (RuntimeException error) {
                    log.accept("Cannot observe native dark-mode blacklist", error);
                }
            }
            return result;
        });
        changes = (prefs, key) -> {
            if (key != null && !ModuleSettings.DARK_APP_WHITELIST_MODE.equals(key)
                    && !ModuleSettings.DARK_APP_SPECIAL_PACKAGES.equals(key)) return;
            new Handler(Looper.getMainLooper()).post(() -> {
                Object[] active;
                synchronized (registered) { active = registered.toArray(); }
                for (Object instance : active) try { refresh.invoke(instance); }
                catch (ReflectiveOperationException | RuntimeException error) {
                    log.accept("Cannot refresh forced dark policy after setting change", error);
                }
            });
        };
        preferences.registerOnSharedPreferenceChangeListener(changes);
        deoptimize.accept(type.getDeclaredMethod("initNightMode"));
        deoptimize.accept(type.getDeclaredMethod("attachApplication", Application.class));
    }
}
