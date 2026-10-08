package dev.rikumi.flymemod;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageInstaller;
import android.database.Cursor;
import android.os.Bundle;
import android.os.Message;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;

/** Flyme Android 16 installer: preserve the native scan/install lifecycle and normalize its verdict. */
final class InstallerHooks {
    private static final String INSTALLER = "com.android.packageinstaller.FlymePackageInstallerActivity";
    private final Map<Object, Run> runs = Collections.synchronizedMap(new WeakHashMap<>());
    private final BiConsumer<String, Throwable> log;
    private boolean readErrorLogged;

    private static final class Run {
        final boolean enabled;
        boolean started;
        volatile boolean pending;
        volatile boolean cacheDeferred;
        volatile boolean sourceDeferred;
        Run(boolean enabled) { this.enabled = enabled; }
    }

    InstallerHooks(BiConsumer<String, Throwable> log) { this.log = log; }

    void install(ClassLoader loader, SignalHooks.Installer installer) throws ReflectiveOperationException {
        Class<?> activity = Class.forName(INSTALLER, false, loader);
        Method install = method(activity, "doInstallFlyme");
        Method clear = method(activity, "clearCachedApk");
        Method deleteSource = method(activity, "lambda$deleteSourceApkAndClearCacheFinish$22");
        Field task = field(activity, "mInstallingTask");
        Field dismissed = field(activity, "dismissed");
        Field timestamp = field(activity, "lastTimeMillis");
        Field storeInfo = field(activity, "mzStoreAppInfo");
        Class<?> storeInfoClass = loader.loadClass("com.meizu.flyme.openidsdk.com.meizu.mzstore.MzStoreAppInfo");
        Field icpStatus = field(storeInfoClass, "icpStatus");
        Field showConfirm = field(storeInfoClass, "showConfirm");
        Field needToConfirm = field(storeInfoClass, "needToConfirm");
        Class<?> installingTask = Class.forName(INSTALLER + "$InstallingAsyncTask", false, loader);
        Field owner = field(installingTask, "this$0");
        Class<?> mainHandler = Class.forName(INSTALLER + "$MainHandler", false, loader);

        installer.hook(INSTALLER, "onCreate", chain -> {
            Object self = chain.getThisObject();
            runs.put(self, new Run(read((Context) self)));
            return chain.proceed();
        }, Bundle.class);
        installer.hook(mainHandler.getName(), "handleMessage", chain -> {
            Object activityInstance = chain.getArg(0);
            Message message = (Message) chain.getArg(1);
            if (enabled(activityInstance) && message.what == 4) {
                // Preserve Flyme's scanner and continuation lifecycle, but normalize every
                // completed scan result to safe before MainHandler consumes it.
                message.arg1 = 1;
            }
            return chain.proceed();
        }, activity, Message.class);
        installer.hook(INSTALLER, "onQueryPkgInfoFinish", chain -> {
            Object self = chain.getThisObject();
            if (enabled(self)) {
                // The optional Meizu Store lookup can be missing or report an unregistered
                // package even after the first-screen install confirmation. Normalize that
                // advisory verdict so it cannot replace the install flow with a store warning.
                Object info = storeInfo.get(self);
                if (info != null) {
                    icpStatus.setBoolean(info, true);
                    showConfirm.setBoolean(info, false);
                    needToConfirm.setBoolean(info, false);
                }
            }
            return chain.proceed();
        });
        installer.hook(INSTALLER, "doInstallFlyme", chain -> {
            Object self = chain.getThisObject();
            Run run = runs.get(self);
            if (run == null || !run.enabled) return chain.proceed();
            if (run.started) return null;
            run.started = true;
            run.pending = true;
            timestamp.setLong(self, System.currentTimeMillis());
            try {
                return chain.proceed();
            } finally {
                // installExistingPackage and setup failures do not create a staging task.
                if (task.get(self) == null) run.pending = false;
            }
        });
        installer.hook(INSTALLER, "clearCachedApk", chain -> {
            Run run = runs.get(chain.getThisObject());
            if (run != null && run.pending) {
                run.cacheDeferred = true;
                return null;
            }
            return chain.proceed();
        });
        installer.hook(INSTALLER, "lambda$deleteSourceApkAndClearCacheFinish$22", chain -> {
            Run run = runs.get(chain.getThisObject());
            if (run != null && run.pending) {
                run.sourceDeferred = true;
                return null;
            }
            return chain.proceed();
        });
        installer.hook(installingTask.getName(), "onPostExecute", chain -> {
            Object self = owner.get(chain.getThisObject());
            Run run = runs.get(self);
            if (run == null || !run.enabled) return chain.proceed();
            try {
                // The original task commits to PackageInstaller even after Activity destruction.
                return chain.proceed();
            } finally {
                run.pending = false;
                if (run.sourceDeferred) deleteSource.invoke(self);
                if (run.cacheDeferred || dismissed.getBoolean(self) || ((Activity) self).isFinishing()) {
                    clear.invoke(self);
                }
            }
        }, PackageInstaller.Session.class);
    }

    private boolean enabled(Object activity) {
        Run run = runs.get(activity);
        return run != null && run.enabled;
    }

    private boolean read(Context context) {
        try (Cursor cursor = context.getContentResolver().query(ModuleSettings.URI, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(ModuleSettings.INSTALLER_SKIP_WARNINGS);
                return column >= 0 && cursor.getInt(column) != 0;
            }
        } catch (RuntimeException e) {
            if (!readErrorLogged) {
                readErrorLogged = true;
                log.accept("Cannot read installer preferences", e);
            }
        }
        return false;
    }

    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Method method(Class<?> type, String name, Class<?>... arguments) throws ReflectiveOperationException {
        Method method = type.getDeclaredMethod(name, arguments);
        method.setAccessible(true);
        return method;
    }
}
