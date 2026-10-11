package dev.rikumi.flymemod;

import android.content.SharedPreferences;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/** Change only Flyme's automatic eviction of older window-mode tasks. */
final class SmallWindowLimitHooks {
    private static final String WM = "com.android.server.wm.";
    private final SharedPreferences preferences;
    private final BiConsumer<String, Throwable> log;
    private final ThreadLocal<Trim> trimming = new ThreadLocal<>();
    private Field displayArea;
    private Method childCount, childAt, asTask, mode, home, deferBack, activityCount, topWindow, rootTask, parent, activityTask;
    private boolean errorLogged;
    private record Trim(Object controller, Object incoming, boolean privatePath, boolean floatingDestination) {}

    SmallWindowLimitHooks(SharedPreferences preferences, BiConsumer<String, Throwable> log) {
        this.preferences = preferences; this.log = log;
    }

    void install(ClassLoader loader, SignalHooks.Installer installer, Consumer<Method> deoptimize)
            throws ReflectiveOperationException {
        Class<?> task = loader.loadClass(WM + "Task");
        Class<?> container = loader.loadClass(WM + "WindowContainer");
        Class<?> base = loader.loadClass(WM + "FlymeWindowControllerBase");
        Class<?> mini = loader.loadClass(WM + "FlymeMiniWindowControllerImpl");
        displayArea = base.getDeclaredField("mTaskDisplayArea"); displayArea.setAccessible(true);
        childCount = container.getMethod("getChildCount");
        childAt = container.getMethod("getChildAt", int.class);
        asTask = container.getMethod("asTask");
        parent = container.getMethod("getParent");
        rootTask = task.getMethod("getRootTask");
        Class<?> activity = loader.loadClass(WM + "ActivityRecord");
        activityTask = activity.getMethod("getTask");
        mode = task.getMethod("getWindowingMode");
        home = task.getMethod("isActivityTypeHome");
        activityCount = task.getMethod("getNonFinishingActivityCount");
        deferBack = loader.loadClass(WM + "FlymeTaskExt").getDeclaredMethod("isDeferBack", task);
        deferBack.setAccessible(true);
        topWindow = base.getDeclaredMethod("getTopWindowModeRootTask"); topWindow.setAccessible(true);
        for (Method method : new Method[]{childCount, childAt, asTask, parent, rootTask,
                activityTask, mode, home, activityCount}) method.setAccessible(true);
        String[] predicates = {"FlymeWindowControllerBase", "FlymeMiniWindowControllerImpl",
                "FlymeFreeFormWindowControllerImpl", "FlymeWeltWindowControllerImpl"};
        for (String name : predicates) loader.loadClass(WM + name).getDeclaredMethod("needPositionTaskAtBottom", task, task);
        Method move = base.getDeclaredMethod("moveTasksToOtherWindowInner", task, int.class, int.class, boolean.class, boolean.class);
        Method under = base.getDeclaredMethod("moveUnderWindowTaskToBack", task);
        Method miniUnder = mini.getDeclaredMethod("moveUnderTaskToBack", task);
        mini.getDeclaredMethod("positionWindowTaskAtBottom", task, boolean.class);
        Method shouldBack = mini.getDeclaredMethod("shouldToBack", task, task);
        Method front = loader.loadClass(WM + "FlymeFreeFormWindowControllerImpl")
                .getDeclaredMethod("onTaskMoveToFront", String.class, task);
        Method switchFront = mini.getDeclaredMethod("postSwitchTasksByNewFrontTask", task, task,
                int.class, boolean.class, int.class, boolean.class);
        Class<?> factory = loader.loadClass(WM + "FlymeFeatureFactory");
        Method invisible = factory.getDeclaredMethod("isWindowContainerWillInvisible", container, container);
        Class<?> resultOne = loader.loadClass("flyme.hook.Inject$ResultOne");
        Field visibilityResult = resultOne.getField("arg1");
        Class<?> inject = loader.loadClass(WM + "FlymeWindowManagerInjectImpl");
        Method injectInvisible = inject.getDeclaredMethod("isWindowContainerWillInvisible", resultOne, container, container);
        Method pausing = mini.getDeclaredMethod("onTaskStartPausing", task, activity);
        installer.hook(factory.getName(), "isWindowContainerWillInvisible", chain -> {
            if (enabled() && retainedPair(chain.getArg(0), chain.getArg(1))) return false;
            return chain.proceed();
        }, container, container);
        // TaskFragment seeds the result from matchParentBounds(). For Flyme mini windows
        // that remains true even when their Surface is scaled, and the native injection
        // skips the controller/factory entirely. Correct the seeded result at this entry.
        installer.hook(inject.getName(), "isWindowContainerWillInvisible", chain -> {
            Object result = chain.proceed();
            if (enabled() && retainedPair(chain.getArg(1), chain.getArg(2)))
                visibilityResult.set(chain.getArg(0), false);
            return result;
        }, resultOne, container, container);
        installer.hook(mini.getName(), "onTaskStartPausing", chain -> {
            Object resuming = chain.getArg(1);
            if (enabled() && resuming != null && retainedPair(chain.getArg(0), activityTask.invoke(resuming))) {
                // Allow normal activity pausing, but do not schedule automatic window minimization.
                return false;
            }
            return chain.proceed();
        }, task, activity);
        installer.hook(mini.getName(), "shouldToBack", chain -> {
            Object result = chain.proceed();
            Object incoming = chain.getArg(0), old = chain.getArg(1);
            if (Boolean.TRUE.equals(result) && enabled() && isFloating(incoming) && isFloating(old)
                    && !Boolean.TRUE.equals(deferBack.invoke(null, old))
                    && ((Number) activityCount.invoke(old)).intValue() > 0
                    && keep(chain.getThisObject(), incoming, old)) return false;
            return result;
        }, task, task);
        // FreeForm automatically minimizes the previous window before the generic trim runs.
        installer.hook(front.getDeclaringClass().getName(), "onTaskMoveToFront", chain -> {
            Object incoming = chain.getArg(1);
            if (enabled() && isFloating(incoming)) {
                Object old = topWindow.invoke(chain.getThisObject());
                if (old != null && old != incoming && keep(chain.getThisObject(), incoming, old)) return false;
            }
            return chain.proceed();
        }, String.class, task);
        installer.hook(mini.getName(), "postSwitchTasksByNewFrontTask", chain -> {
            Object incoming = chain.getArg(0), old = chain.getArg(1);
            if (enabled() && !Boolean.TRUE.equals(chain.getArg(5))
                    && ((Number) chain.getArg(2)).intValue() == 12 && old != null && old != incoming
                    && keep(chain.getThisObject(), incoming, old)) {
                // Native cancellation also restores handle/leash flags, without scheduling minimization.
                return chain.proceed(new Object[]{incoming, old, chain.getArg(2), chain.getArg(3), chain.getArg(4), true});
            }
            return chain.proceed();
        }, task, task, int.class, boolean.class, int.class, boolean.class);
        for (String name : predicates) {
            installer.hook(WM + name, "needPositionTaskAtBottom", chain -> {
                Object result = chain.proceed();
                Trim trim = trimming.get();
                if (!Boolean.TRUE.equals(result) || trim == null || !trim.floatingDestination
                        || trim.controller != chain.getThisObject() || trim.incoming != chain.getArg(0) || !enabled()) return result;
                return keep(trim.controller, trim.incoming, chain.getArg(1)) ? false : result;
            }, task, task);
        }
        installer.hook(base.getName(), "moveTasksToOtherWindowInner", chain -> {
            Trim previous = trimming.get();
            trimming.set(new Trim(chain.getThisObject(), chain.getArg(0), false,
                    floatingMode(((Number) chain.getArg(1)).intValue())));
            try { return chain.proceed(); }
            finally { if (previous == null) trimming.remove(); else trimming.set(previous); }
        }, task, int.class, int.class, boolean.class, boolean.class);
        installer.hook(base.getName(), "moveUnderWindowTaskToBack", chain -> {
            Trim previous = trimming.get();
            if (previous == null) {
                Object incoming = chain.getArg(0);
                trimming.set(new Trim(chain.getThisObject(), incoming, false,
                        incoming != null && floatingMode(((Number) mode.invoke(incoming)).intValue())));
            }
            try { return chain.proceed(); }
            finally { if (previous == null) trimming.remove(); else trimming.set(previous); }
        }, task);
        installer.hook(mini.getName(), "moveUnderTaskToBack", chain -> {
            Trim previous = trimming.get();
            trimming.set(new Trim(chain.getThisObject(), chain.getArg(0), true, true));
            try { return chain.proceed(); }
            finally { if (previous == null) trimming.remove(); else trimming.set(previous); }
        }, task);
        installer.hook(mini.getName(), "positionWindowTaskAtBottom", chain -> {
            Trim trim = trimming.get();
            if (trim != null && trim.privatePath && trim.controller == chain.getThisObject() && enabled()
                    && keep(trim.controller, trim.incoming, chain.getArg(0))) return false;
            return chain.proceed();
        }, task, boolean.class);
        deoptimize.accept(move); deoptimize.accept(under); deoptimize.accept(miniUnder);
        deoptimize.accept(shouldBack); deoptimize.accept(front); deoptimize.accept(switchFront);
        deoptimize.accept(invisible); deoptimize.accept(injectInvisible); deoptimize.accept(pausing);
        deoptimize.accept(loader.loadClass(WM + "TaskFragment").getDeclaredMethod("getVisibility", activity));
        for (Method method : loader.loadClass(WM + "FlymeFreeFormWindowControllerImpl").getDeclaredMethods())
            if (method.getName().equals("onTaskStartPausing")) deoptimize.accept(method);
        deoptimize.accept(mini.getDeclaredMethod("enterMiniWindowWithoutWindowMode", task, boolean.class));
        deoptimize.accept(mini.getDeclaredMethod("moveWindowTaskToBackIfNeed"));
        deoptimize.accept(loader.loadClass(WM + "FlymeWeltWindowControllerImpl")
                .getDeclaredMethod("moveWindowTaskToBackIfNeed"));
        log.accept("Flyme small-window limit hooks registered: concurrent visibility and automatic close/minimize paths covered", null);
    }

    private boolean enabled() {
        return preferences.getBoolean(ModuleSettings.SMALL_WINDOW_LIMIT_ENABLED, false);
    }

    private boolean isFloating(Object task) throws ReflectiveOperationException {
        return task != null && floatingMode(((Number) mode.invoke(task)).intValue());
    }

    private boolean keep(Object controller, Object incoming, Object candidate) {
        try {
            return keepInArea(displayArea.get(controller), incoming, candidate);
        } catch (ReflectiveOperationException | RuntimeException error) {
            reportCountingError(error);
            return false;
        }
    }

    private boolean retainedPair(Object lower, Object upper) {
        try {
            if (lower == null || upper == null) return false;
            Object lowerTask = asTask.invoke(lower), upperTask = asTask.invoke(upper);
            if (lowerTask == null || upperTask == null) return false;
            Object old = rootTask.invoke(lowerTask), incoming = rootTask.invoke(upperTask);
            if (old == incoming || !isFloating(old) || !isFloating(incoming)
                    || Boolean.TRUE.equals(deferBack.invoke(null, old))) return false;
            Object area = parent.invoke(old);
            return area != null && area == parent.invoke(incoming) && keepInArea(area, incoming, old);
        } catch (ReflectiveOperationException | RuntimeException error) {
            reportCountingError(error);
            return false;
        }
    }

    private void reportCountingError(Throwable error) {
        if (!errorLogged) { errorLogged = true; log.accept("Cannot count Flyme window-mode tasks", error); }
    }

    private boolean keepInArea(Object area, Object incoming, Object candidate) throws ReflectiveOperationException {
        int count = ((Number) childCount.invoke(area)).intValue();
        List<Object> ordered = new ArrayList<>(count);
        for (int i = count - 1; i >= 0; i--) {
            Object child = asTask.invoke(childAt.invoke(area, i));
            if (child != null) ordered.add(child);
        }
        int limit = Math.max(1, Math.min(ModuleSettings.SMALL_WINDOW_LIMIT_MAX,
                preferences.getInt(ModuleSettings.SMALL_WINDOW_LIMIT, ModuleSettings.SMALL_WINDOW_LIMIT_DEFAULT)));
        return retain(ordered, incoming, candidate, limit, value -> {
            try { return ((Number) mode.invoke(value)).intValue(); }
            catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        }, value -> {
            try { return (Boolean) home.invoke(value); }
            catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        });
    }

    static boolean floatingMode(int mode) { return mode == 11 || mode == 12 || mode == 1035 || mode == 268; }

    /** Reserve one slot for the incoming task, which can still be fullscreen before its mode changes. */
    static boolean retain(List<Object> ordered, Object incoming, Object candidate, int limit,
                          ToIntFunction<Object> mode, Predicate<Object> home) {
        if (candidate == incoming) return true;
        int rank = 1;
        for (Object task : ordered) {
            if (home.test(task)) break;
            if (task == incoming || !floatingMode(mode.applyAsInt(task))) continue;
            rank++;
            if (task == candidate) return rank <= limit;
        }
        return false;
    }
}
