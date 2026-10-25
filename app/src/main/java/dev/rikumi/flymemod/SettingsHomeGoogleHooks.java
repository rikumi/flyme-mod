package dev.rikumi.flymemod;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Process;
import android.os.UserHandle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

final class SettingsHomeGoogleHooks {
    private static final String HOME = "com.meizu.settings.home.FlymeDashboardSummary";
    private static final String GOOGLE = "com.google.android.gms";
    private static final String LINK = "com.google.android.gms.app.settings.GoogleSettingsIALink";
    private final SharedPreferences preferences;
    private final BiConsumer<String, Throwable> log;
    private final Map<Object, Boolean> pages = Collections.synchronizedMap(new WeakHashMap<>());
    private final AtomicBoolean failure = new AtomicBoolean();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Method getView, reload;
    private final ThreadLocal<ActivityInfo> loadingGoogle = new ThreadLocal<>();
    private final SharedPreferences.OnSharedPreferenceChangeListener changes = (prefs, key) -> {
        if (key == null || ModuleSettings.SETTINGS_HOME_FORCE_GOOGLE.equals(key)) refresh();
    };

    SettingsHomeGoogleHooks(SharedPreferences preferences, BiConsumer<String, Throwable> log) {
        this.preferences = preferences;
        this.log = log;
    }

    void install(ClassLoader loader, ThemeColorHooks.Installer installer, Consumer<Executable> deoptimizer) {
        try {
            Class<?> home = Class.forName(HOME, false, loader);
            Class<?> task = Class.forName(HOME + "$LoadingTask", false, loader);
            Field owner = task.getDeclaredField("this$0"); owner.setAccessible(true);
            Field adapter = home.getField("mAdapter");
            Class<?> adapterType = Class.forName(HOME + "Adapter", false, loader);
            Field list = adapterType.getField("mTileList");
            Method notifyRows = adapterType.getMethod("notifyDataSetChanged");
            getView = home.getMethod("getView");
            Method activity = home.getMethod("getActivity");
            reload = home.getDeclaredMethod("initDashboardTileList", boolean.class);
            Class<?> tile = Class.forName("com.android.settingslib.drawer.Tile", false, loader);
            Field componentPackage = tile.getField("mComponentPackage"), component = tile.getField("mComponentName");
            Field icon = tile.getField("mIcon"), drawable = tile.getField("mDrawable"), immersive = tile.getField("mImmersiveType");
            Method loadTile = Class.forName("com.android.settingslib.drawer.TileUtils", false, loader).getDeclaredMethod(
                    "loadTile", Context.class, UserHandle.class, Map.class, String.class, List.class,
                    Intent.class, Bundle.class, ComponentInfo.class);
            installer.hook(tile.getDeclaredMethod("getTitle", Context.class), chain -> {
                Object result = chain.proceed();
                ActivityInfo info = loadingGoogle.get();
                Object row = chain.getThisObject();

                if (result == null && info != null && GOOGLE.equals(componentPackage.get(row))
                        && LINK.equals(component.get(row))) {
                    CharSequence label = info.loadLabel(((Context) chain.getArg(0)).getPackageManager());
                    if (label != null && label.length() > 0) {
                        tile.getField("mTitle").set(row, label.toString());
                        return label;
                    }
                }
                return result;
            });
            deoptimizer.accept(loadTile);
            installer.hook(task.getDeclaredMethod("doInBackground", Object[].class), chain -> {
                Object page = owner.get(chain.getThisObject()); pages.put(page, Boolean.TRUE);
                Object result = chain.proceed();
                if (!preferences.getBoolean(ModuleSettings.SETTINGS_HOME_FORCE_GOOGLE, false)) return result;
                try {
                    Object targetAdapter = adapter.get(page);
                    Context context = (Activity) activity.invoke(page);
                    if (targetAdapter == null || context == null) return result;
                    @SuppressWarnings("unchecked") List<Object> rows = (List<Object>) list.get(targetAdapter);
                    if (rows == null) return result;
                    for (Object row : rows)
                        if (GOOGLE.equals(componentPackage.get(row)) && LINK.equals(component.get(row))) return result;
                    ActivityInfo info = context.getPackageManager().getActivityInfo(new ComponentName(GOOGLE, LINK), PackageManager.GET_META_DATA);
                    if (!info.enabled || !info.applicationInfo.enabled || info.metaData == null) return result;
                    // The ROM loader validates the real settings category/profile metadata and creates its own Tile.
                    List<Object> candidates = new ArrayList<>();
                    loadingGoogle.set(info);
                    try {
                        loadTile.invoke(null, context, Process.myUserHandle(), new HashMap<>(), null, candidates,
                                new Intent().setComponent(new ComponentName(GOOGLE, LINK)), info.metaData, info);
                    } finally {
                        loadingGoogle.remove();
                    }
                    for (Object row : candidates) {
                        if (!GOOGLE.equals(componentPackage.get(row)) || !LINK.equals(component.get(row))) continue;
                        int id = context.getResources().getIdentifier("google_settings_icon", "drawable", "com.android.settings");
                        if (id == 0) return result;
                        Icon nativeIcon = Icon.createWithResource(context, id);
                        icon.set(row, nativeIcon); drawable.set(row, nativeIcon.loadDrawable(context));
                        // A new final section does not alter the native rows' existing section boundaries.
                        immersive.setInt(row, 3);
                        main.post(() -> {
                            try {
                                if (!preferences.getBoolean(ModuleSettings.SETTINGS_HOME_FORCE_GOOGLE, false)
                                        || adapter.get(page) != targetAdapter || list.get(targetAdapter) != rows) return;
                                for (Object existing : rows)
                                    if (GOOGLE.equals(componentPackage.get(existing)) && LINK.equals(component.get(existing))) return;
                                rows.add(row);
                                notifyRows.invoke(targetAdapter);
                            } catch (ReflectiveOperationException | RuntimeException error) { report(error); }
                        });
                        break;
                    }
                } catch (PackageManager.NameNotFoundException ignored) {
                    // No GMS or no enabled settings link: retain the native home page.
                } catch (ReflectiveOperationException | RuntimeException error) { report(error); }
                return result;
            });
            deoptimizer.accept(task.getDeclaredMethod("doInBackground", Object[].class));
            preferences.registerOnSharedPreferenceChangeListener(changes);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) { report(error); }
    }

    private void refresh() {
        ArrayList<Object> active;
        synchronized (pages) { active = new ArrayList<>(pages.keySet()); }
        for (Object page : active) {
            try {
                View view = (View) getView.invoke(page);
                if (view != null) view.post(() -> {
                    try { reload.invoke(page, true); }
                    catch (ReflectiveOperationException | RuntimeException error) { report(error); }
                });
            } catch (ReflectiveOperationException | RuntimeException error) { report(error); }
        }
    }

    private void report(Throwable error) {
        if (failure.compareAndSet(false, true)) log.accept("Cannot restore registered Google Settings home link", error);
    }
}
