package dev.rikumi.flymemod;

import android.appwidget.AppWidgetHost;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.DisplayMetrics;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.BiConsumer;
import io.github.libxposed.api.XposedInterface;

/** A process-lifetime storage namespace: toggles take effect on the next launcher start. */
final class LauncherExtraRowHooks {
    private static final String PACKAGE = "com.meizu.flyme.launcher";
    private static final String IDP = "com.android.launcher3.InvariantDeviceProfile";
    private final SharedPreferences preferences;
    private final BiConsumer<String, Throwable> log;
    private boolean decided, preparing, installed;
    private volatile boolean active;
    private Context application;
    private final ThreadLocal<Integer> desiredRows = new ThreadLocal<>();

    LauncherExtraRowHooks(SharedPreferences preferences, BiConsumer<String, Throwable> log) {
        this.preferences = preferences;
        this.log = log;
    }

    private synchronized boolean enabled(Context context) {
        if (!installed) return false;
        if (!PACKAGE.equals(context.getPackageName())) return false;
        if (decided || preparing) return active;
        preparing = true;
        try {
            if (preferences.getBoolean(ModuleSettings.LAUNCHER_EXTRA_ROW, false)
                    && LauncherExtraRowDevice.isSupported(context)
                    && context.getPackageManager().getPackageInfo(PACKAGE, 0).getLongVersionCode() == 13000000) {
                LauncherExtraRowStorage.prepare(context, log);
                Context device = context.createDeviceProtectedStorageContext();
                if (!device.getDataDir().equals(context.getDataDir())) LauncherExtraRowStorage.prepare(device, log);
                application = context;
                active = true;
                log.accept("Launcher extra row: isolated database and preferences selected for this process", null);
            }
        } catch (Exception error) {
            // Never expand the native database if creating the isolated snapshot failed.
            log.accept("Cannot initialize isolated launcher layout; keeping native layout", error);
        } finally {
            preparing = false;
            decided = true;
        }
        return active;
    }

    @android.annotation.SuppressLint("BlockedPrivateApi") // Runs inside LSPosed, with hidden API access.
    void install(ClassLoader loader, BiConsumer<Executable, XposedInterface.Hooker> hook)
            throws ReflectiveOperationException {
        Class<?> contextImpl = loader.loadClass("android.app.ContextImpl");
        Class<?> idp = loader.loadClass(IDP);
        Class<?> option = loader.loadClass(IDP + "$DisplayOption");
        Class<?> grid = loader.loadClass(IDP + "$GridOption");
        Class<?> info = loader.loadClass("com.android.launcher3.util.DisplayController$Info");
        Class<?> flymeDp = loader.loadClass("com.meizu.flyme.launcher.deviceprofile.FlymeDeviceProfile");
        Field gridField = accessible(option.getDeclaredField("grid"));
        Field gridRows = accessible(grid.getDeclaredField("numRows"));
        Field rows = idp.getField("numRows");
        Field parent = accessible(flymeDp.getDeclaredField("mParentDeviceProfile"));
        Field inv = loader.loadClass("com.android.launcher3.DeviceProfile").getField("inv");
        // Resolve all required entry points before installing any storage or grid interception.
        Method dbPath = contextImpl.getDeclaredMethod("getDatabasePath", String.class);
        Method prefs = contextImpl.getDeclaredMethod("getSharedPreferences", File.class, int.class);
        Method dbList = contextImpl.getDeclaredMethod("databaseList");
        Method filesDir = contextImpl.getDeclaredMethod("getFilesDir");
        Method init = idp.getDeclaredMethod("initGrid", Context.class, info, option);
        Method partner = idp.getDeclaredMethod("applyPartnerDeviceProfileOverrides", Context.class, DisplayMetrics.class);
        Method height = flymeDp.getDeclaredMethod("getWorkspaceGridCellHeight");
        Class<?> dragListener = loader.loadClass("com.meizu.flyme.launcher.dragndrop.GlobalItemDragListener");
        Class<?> dragItem = loader.loadClass("com.meizu.flyme.launcher.dragndrop.LauncherGlobalDragItemInfo");
        Class<?> pendingWidget = loader.loadClass("com.android.launcher3.widget.PendingAppWidgetHostView");
        Field dragSource = accessible(dragListener.getDeclaredField("mSourceDragView"));
        Field currentItem = accessible(dragListener.getDeclaredField("mItem"));
        Field boundWidget = dragItem.getField("mBoundWidget");
        Method launcherSource = dragItem.getDeclaredMethod("isLauncherSource");
        Method dragInfo = dragItem.getDeclaredMethod("getLauncherDragItemInfo");
        Method prepareDrag = dragListener.getDeclaredMethod("processLauncherViewBeforeDragStart");
        Field dragLauncher = accessible(dragListener.getDeclaredField("mLauncher"));
        Method editing = loader.loadClass("com.android.launcher3.Launcher").getMethod("isInEditMode");
        Method showWidgetMenu = loader.loadClass("com.android.launcher3.popup.PopupContainerWithArrow")
                .getDeclaredMethod("showForWidget", loader.loadClass("com.android.launcher3.widget.LauncherAppWidgetHostView"));

        hook.accept(dbPath, chain -> {
            Context context = (Context) chain.getThisObject();
            if (!enabled(context)) return chain.proceed();
            return LauncherExtraRowStorage.path(context, "databases", (String) chain.getArg(0));
        });
        hook.accept(prefs, chain -> {
            Context context = (Context) chain.getThisObject();
            if (!enabled(context)) return chain.proceed();
            Object[] args = chain.getArgs().toArray();
            args[0] = LauncherExtraRowStorage.path(context, "shared_prefs", ((File) args[0]).getName());
            return chain.proceed(args);
        });
        hook.accept(dbList, chain -> {
            Context context = (Context) chain.getThisObject();
            if (!enabled(context)) return chain.proceed();
            String[] names = LauncherExtraRowStorage.path(context, "databases", "unused").getParentFile().list();
            return names == null ? new String[0] : names;
        });
        hook.accept(filesDir, chain -> {
            Context context = (Context) chain.getThisObject();
            return enabled(context) ? LauncherExtraRowStorage.path(context, "files", "unused").getParentFile() : chain.proceed();
        });
        // AppWidgetHost owns resources outside SQLite. A separate host prevents cross-layout deletion.
        for (Constructor<?> constructor : AppWidgetHost.class.getDeclaredConstructors()) {
            Class<?>[] types = constructor.getParameterTypes();
            if (types.length < 2 || types[0] != Context.class || types[1] != int.class) continue;
            hook.accept(constructor, chain -> {
                int nativeHost = (Integer) chain.getArg(1);
                if ((nativeHost != 1024 && nativeHost != 1026) || !enabled((Context) chain.getArg(0))) return chain.proceed();
                Object[] args = chain.getArgs().toArray();
                args[1] = LauncherExtraRowPolicy.WIDGET_HOST + nativeHost - 1024;
                return chain.proceed(args);
            });
        }
        hook.accept(AppWidgetHost.class.getDeclaredMethod("deleteAppWidgetId", int.class), chain -> {
            if (active) {
                int requested = (Integer) chain.getArg(0);
                boolean owned = false;
                for (int id : ((AppWidgetHost) chain.getThisObject()).getAppWidgetIds()) if (id == requested) owned = true;
                if (!owned) return null;
            }
            return chain.proceed();
        });
        hook.accept(init, chain -> {
            if (!enabled((Context) chain.getArg(0))) return chain.proceed();
            Object selectedGrid = gridField.get(chain.getArg(2));
            synchronized (selectedGrid) {
                int nativeRows = gridRows.getInt(selectedGrid);
                Integer previous = desiredRows.get();
                desiredRows.set(nativeRows + 1);
                gridRows.setInt(selectedGrid, nativeRows + 1);
                try { return chain.proceed(); }
                finally {
                    gridRows.setInt(selectedGrid, nativeRows);
                    if (previous == null) desiredRows.remove(); else desiredRows.set(previous);
                }
            }
        });
        hook.accept(partner, chain -> {
            Object result = chain.proceed();
            Integer desired = desiredRows.get();
            if (active && desired != null) rows.setInt(chain.getThisObject(), desired);
            return result;
        });
        hook.accept(height, chain -> {
            int original = (Integer) chain.proceed();
            if (!active) return original;
            int count = rows.getInt(inv.get(parent.get(chain.getThisObject())));
            return count > 1 ? LauncherExtraRowPolicy.rowHeight(original, count) : original;
        });
        hook.accept(prepareDrag, chain -> {
            Object result = chain.proceed();
            if (!active) return result;
            Object source = dragSource.get(chain.getThisObject());
            Object item = currentItem.get(chain.getThisObject());
            // Flyme only detaches/reuses FlymeLauncherAppWidgetHostView. Its pending
            // configuration view is a different subclass: otherwise preload creates
            // a second view for the SAME widget and the old view remains on the page.
            if (item != null && pendingWidget.isInstance(source) && boundWidget.get(item) == null
                    && Boolean.TRUE.equals(launcherSource.invoke(item))) {
                android.view.View view = (android.view.View) source;
                if (view.getTag() != null && dragInfo.invoke(item) == view.getTag()
                        && view.getParent() instanceof android.view.ViewGroup parentView) {
                    Object launcher = dragLauncher.get(chain.getThisObject());
                    if (!Boolean.TRUE.equals(editing.invoke(launcher))) showWidgetMenu.invoke(null, view);
                    parentView.removeView(view);
                    boundWidget.set(item, source);
                }
            }
            return result;
        });
        Class<?> backup = loader.loadClass("com.meizu.flyme.launcher.backup.MzLauncherBackupHelper");
        for (String name : new String[]{"getLocalBackupPath", "getLocalRestorePath"}) {
            hook.accept(backup.getDeclaredMethod(name), chain -> {
                if (!active) return chain.proceed();
                File directory = LauncherExtraRowStorage.path(application, "files", "layout-backup");
                if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Cannot create isolated launcher backup");
                return directory.getAbsolutePath() + File.separator;
            });
        }
        installed = true;
    }

    private static Field accessible(Field field) { field.setAccessible(true); return field; }
}
