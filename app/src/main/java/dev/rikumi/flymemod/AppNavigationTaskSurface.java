package dev.rikumi.flymemod;

import android.app.ActivityManager;
import android.content.res.Configuration;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.Rect;
import android.util.SparseArray;
import android.view.SurfaceControl;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.IntConsumer;
import java.util.function.Consumer;

final class AppNavigationTaskSurface {
    private static final String SHELL = "com.android.wm.shell.ShellTaskOrganizer";
    private final Field lock, tasks, configuration, windowConfiguration, visible, displayId, topActivityInfo,
            focused, activityType, lastFocused;
    private final Method taskInfo, leash, getBounds, getMode, sameSurface, colorLayer, metadata, setColor;
    private final Constructor<?> copySurface;
    private volatile Object organizer;

    AppNavigationTaskSurface(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> shell = loader.loadClass(SHELL);
        Class<?> appeared = loader.loadClass("android.window.TaskAppearedInfo");
        Class<?> info = loader.loadClass("android.app.TaskInfo");
        Class<?> window = loader.loadClass("android.app.WindowConfiguration");
        lock = shell.getField("mLock"); tasks = shell.getField("mTasks");
        taskInfo = appeared.getMethod("getTaskInfo"); leash = appeared.getMethod("getLeash");
        configuration = info.getField("configuration"); visible = info.getField("isVisible");
        displayId = info.getField("displayId"); topActivityInfo = info.getField("topActivityInfo");
        focused = info.getField("isFocused"); activityType = info.getField("topActivityType");
        lastFocused = shell.getField("mLastFocusedTaskInfo");
        windowConfiguration = Configuration.class.getField("windowConfiguration");
        getBounds = window.getMethod("getBounds"); getMode = window.getMethod("getWindowingMode");
        copySurface = SurfaceControl.class.getConstructor(SurfaceControl.class, String.class);
        sameSurface = SurfaceControl.class.getMethod("isSameSurface", SurfaceControl.class);
        colorLayer = SurfaceControl.Builder.class.getMethod("setColorLayer");
        metadata = SurfaceControl.Builder.class.getMethod("setMetadata", int.class, int.class);
        setColor = SurfaceControl.Transaction.class.getMethod("setColor", SurfaceControl.class, float[].class);
        shell.getDeclaredMethod("registerOrganizer");
        shell.getDeclaredMethod("onTaskAppeared", ActivityManager.RunningTaskInfo.class, SurfaceControl.class);
        shell.getDeclaredMethod("onTaskInfoChanged", ActivityManager.RunningTaskInfo.class);
        shell.getDeclaredMethod("onTaskVanished", ActivityManager.RunningTaskInfo.class);
    }

    void install(SignalHooks.Installer installer, IntConsumer vanished,
            Consumer<ActivityManager.RunningTaskInfo> focusChanged) {
        installer.hook(SHELL, "registerOrganizer", chain -> {
            Object result = chain.proceed(); organizer = chain.getThisObject(); return result;
        });
        installer.hook(SHELL, "onTaskAppeared", chain -> {
            Object result = chain.proceed(); organizer = chain.getThisObject();
            ActivityManager.RunningTaskInfo info = (ActivityManager.RunningTaskInfo) chain.getArg(0);
            if (focused.getBoolean(info) && visible.getBoolean(info)) focusChanged.accept(info);
            return result;
        }, ActivityManager.RunningTaskInfo.class, SurfaceControl.class);
        installer.hook(SHELL, "onTaskInfoChanged", chain -> {
            Object result = chain.proceed(); organizer = chain.getThisObject();
            ActivityManager.RunningTaskInfo info = (ActivityManager.RunningTaskInfo) chain.getArg(0);
            if (focused.getBoolean(info) && visible.getBoolean(info)) focusChanged.accept(info);
            return result;
        }, ActivityManager.RunningTaskInfo.class);
        installer.hook(SHELL, "onTaskVanished", chain -> {
            vanished.accept(((ActivityManager.RunningTaskInfo) chain.getArg(0)).taskId);
            return chain.proceed();
        }, ActivityManager.RunningTaskInfo.class);
    }

    ActivityManager.RunningTaskInfo focusedTask() throws ReflectiveOperationException {
        Object owner = organizer;
        if (owner == null) return null;
        synchronized (lock.get(owner)) {
            ActivityManager.RunningTaskInfo info = (ActivityManager.RunningTaskInfo) lastFocused.get(owner);
            return info != null && focused.getBoolean(info) && visible.getBoolean(info) ? info : null;
        }
    }

    boolean isOverviewHost(ActivityManager.RunningTaskInfo info) throws IllegalAccessException {
        int type = activityType.getInt(info);
        return type == 2 || type == 3; // HOME / RECENTS: no live app-row sampling here.
    }

    Target find(ActivityManager.RunningTaskInfo foreground, int display, Rect sample)
            throws ReflectiveOperationException {
        Object owner = organizer;
        if (owner == null || foreground == null || foreground.topActivity == null) return null;
        synchronized (lock.get(owner)) {
            Object appeared = ((SparseArray<?>) tasks.get(owner)).get(foreground.taskId);
            if (appeared == null) return null;
            ActivityManager.RunningTaskInfo info = (ActivityManager.RunningTaskInfo) taskInfo.invoke(appeared);
            if (!visible.getBoolean(info) || displayId.getInt(info) != display
                    || !foreground.topActivity.equals(info.topActivity)) return null;
            Object window = windowConfiguration.get(configuration.get(info));
            // A floating/split task cannot own the display-wide navigation strip.
            if ((Integer) getMode.invoke(window) != 1) return null;
            Rect bounds = new Rect((Rect) getBounds.invoke(window));
            if (!bounds.contains(sample)) return null;
            ActivityInfo activity = (ActivityInfo) topActivityInfo.get(info);
            SurfaceControl surface = (SurfaceControl) leash.invoke(appeared);
            if (activity == null || activity.applicationInfo == null || surface == null || !surface.isValid()) return null;
            return new Target(info.taskId, activity.applicationInfo.uid, bounds, copy(surface));
        }
    }

    SurfaceControl copy(SurfaceControl source) throws ReflectiveOperationException {
        return (SurfaceControl) copySurface.newInstance(source, "FlymeMod application navigation");
    }

    boolean same(Target a, Target b) throws ReflectiveOperationException {
        return a != null && b != null && a.id == b.id && a.uid == b.uid
                && (Boolean) sameSurface.invoke(a.surface, b.surface);
    }

    SurfaceControl createBacking(Target target) throws ReflectiveOperationException {
        SurfaceControl.Builder builder = new SurfaceControl.Builder()
                .setName("FlymeMod application navigation backing").setParent(target.surface);
        colorLayer.invoke(builder);

        metadata.invoke(builder, 1, target.uid); // METADATA_OWNER_UID
        metadata.invoke(builder, 2, 1);          // METADATA_WINDOW_TYPE / TYPE_BASE_APPLICATION
        metadata.invoke(builder, 3, target.id);  // METADATA_TASK_ID
        return builder.build();
    }

    void draw(SurfaceControl backing, Target target, Rect screen, int color)
            throws ReflectiveOperationException {
        Rect local = new Rect(screen); local.offset(-target.bounds.left, -target.bounds.top);
        if (!local.intersect(0, 0, target.bounds.width(), target.bounds.height())) return;
        try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
            setColor.invoke(transaction, backing, new float[]{Color.red(color) / 255f,
                    Color.green(color) / 255f, Color.blue(color) / 255f});
            transaction.setPosition(backing, local.left, local.top)
                    .setCrop(backing, new Rect(0, 0, local.width(), local.height()))
                    .setLayer(backing, Integer.MAX_VALUE).setAlpha(backing, 1f)
                    .setVisibility(backing, true).apply();
        }
    }

    static void remove(SurfaceControl surface) {
        if (surface == null) return;
        try {
            if (surface.isValid()) try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
                transaction.reparent(surface, null).apply();
            }
        } finally { surface.release(); }
    }

    static final class Target implements AutoCloseable {
        final int id, uid;
        final Rect bounds;
        final SurfaceControl surface;
        Target(int id, int uid, Rect bounds, SurfaceControl surface) {
            this.id = id; this.uid = uid; this.bounds = bounds; this.surface = surface;
        }
        @Override public void close() { surface.release(); }
    }
}
