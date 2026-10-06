package com.rikumi.flymemod;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.os.Handler;
import android.os.Looper;
import java.util.function.BiConsumer;

/** Flyme Settings: filter only the all-apps filter, keeping the disabled-apps filter intact. */
final class SettingsHooks {
    private volatile boolean hideDisabled;
    private ContentObserver observer;
    private Context observerContext;
    private final BiConsumer<String, Throwable> log;

    SettingsHooks(BiConsumer<String, Throwable> log) { this.log = log; }

    void install(ClassLoader loader, SignalHooks.Installer installer) throws ReflectiveOperationException {
        String stateName = "com.meizu.settings.applications.ApplicationsState";
        Class<?> state = Class.forName(stateName, false, loader);
        Class<?> entry = Class.forName(stateName + "$AppEntry", false, loader);
        Object allApps = state.getField("FILTER_ALL_ENABLED_DISABLED").get(null);
        installer.hook(allApps.getClass().getName(), "filterApp", chain -> {
            Object result = chain.proceed();
            if (chain.getThisObject() != allApps || !hideDisabled || !Boolean.TRUE.equals(result)) return result;
            ApplicationInfo info = (ApplicationInfo) entry.getField("info").get(chain.getArg(0));
            int enabled = ApplicationInfo.class.getField("enabledSetting").getInt(info);
            return info.enabled && enabled != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    && enabled != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                    && enabled != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED;
        }, entry);
        installer.hook("com.meizu.settings.FlymeSettingsApplication", "onCreate", chain -> {
            Object result = chain.proceed();
            Context context = (Context) chain.getThisObject();
            read(context);
            if (observer == null) {
                observerContext = context.getApplicationContext();
                observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
                    @Override public void onChange(boolean selfChange) { read(context); }
                };
                observerContext.getContentResolver().registerContentObserver(ModuleSettings.URI, false, observer);
            }
            return result;
        });
        installer.hook("com.meizu.settings.applications.ManageApplications", "onResume", chain -> {
            Context context = (Context) chain.getThisObject().getClass().getMethod("getActivity").invoke(chain.getThisObject());
            if (context != null) read(context);
            return chain.proceed();
        });
    }

    private void read(Context context) {
        try (Cursor cursor = context.getContentResolver().query(ModuleSettings.URI, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(ModuleSettings.HIDE_DISABLED_APPS);
                hideDisabled = column >= 0 && cursor.getInt(column) != 0;
            }
        } catch (RuntimeException e) {
            log.accept("Cannot read Settings app preferences", e);
        }
    }
}
