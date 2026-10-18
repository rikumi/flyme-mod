package dev.rikumi.flymemod;

import android.content.SharedPreferences;
import android.content.ComponentName;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/** Retain pin windows while allowing only the selected window to remain expanded. */
final class SmallWindowLimitHooks {
    private static final String WM = "com.android.server.wm.";
    private final SharedPreferences preferences;
    private final BiConsumer<String, Throwable> log;
    private final ThreadLocal<Trim> trimming = new ThreadLocal<>();
    private Field displayArea;
    private Field activityComponent, launchedFromPackage, taskSupervisor;
    private Method rootActivity, removeTask;
    private Method childCount, childAt, asTask, mode, home, deferBack, activityCount, rootTask, parent, moveWindow, position;
    private final ThreadLocal<Boolean> collapsing = new ThreadLocal<>();
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
        rootActivity = task.getDeclaredMethod("getRootActivity");
        rootActivity.setAccessible(true);
        activityComponent = activity.getDeclaredField("mActivityComponent");
        activityComponent.setAccessible(true);
        launchedFromPackage = activity.getDeclaredField("launchedFromPackage");
        launchedFromPackage.setAccessible(true);
        for (Class<?> type = task; type != null; type = type.getSuperclass()) {
            try { taskSupervisor = type.getDeclaredField("mTaskSupervisor"); break; }
            catch (NoSuchFieldException ignored) { }
        }
        if (taskSupervisor == null) throw new NoSuchFieldException("Task.mTaskSupervisor");
        taskSupervisor.setAccessible(true);
        removeTask = loader.loadClass(WM + "ActivityTaskSupervisor")
                .getDeclaredMethod("removeTask", task, boolean.class, boolean.class, String.class);
        removeTask.setAccessible(true);
        mode = task.getMethod("getWindowingMode");
        home = task.getMethod("isActivityTypeHome");
        activityCount = task.getMethod("getNonFinishingActivityCount");
        deferBack = loader.loadClass(WM + "FlymeTaskExt").getDeclaredMethod("isDeferBack", task);
        deferBack.setAccessible(true);
        Class<?> areaClass = loader.loadClass(WM + "TaskDisplayArea");
        position = areaClass.getMethod("positionChildAt", int.class, container, boolean.class);
        position.setAccessible(true);
        Method priority = areaClass.getDeclaredMethod("getPriority", container);
        installer.hook(areaClass.getName(), priority.getName(), chain -> {
            Object result = chain.proceed();
            if (!enabled()) return result;
            Object childTask = asTask.invoke(chain.getArg(0));
            if (childTask == null) return result;
            int windowMode = ((Number) mode.invoke(childTask)).intValue();
            // Native pin priority 2 otherwise prevents an expanded window moving above it.
            if (windowMode == 12 && ((Number) result).intValue() == 2)
                return SmallWindowDragOrder.isActive(childTask) ? 2 : 1;
            if (expandedMode(windowMode) && !Boolean.TRUE.equals(deferBack.invoke(null, childTask))) return 2;
            return result;
        }, container);
        Method topChildren = areaClass.getDeclaredMethod("isAlwaysOnTopChildren", task);
        installer.hook(areaClass.getName(), topChildren.getName(), chain -> {
            Object result = chain.proceed();
            Object childTask = chain.getArg(0);
            if (enabled() && childTask != null
                    && expandedMode(((Number) mode.invoke(childTask)).intValue())
                    && !Boolean.TRUE.equals(deferBack.invoke(null, childTask))) return true;
            return result;
        }, task);
        // Flyme's override calls super before adding defer-back tasks to this group.
        deoptimize.accept(loader.loadClass(WM + "FlymeTaskDisplayArea")
                .getDeclaredMethod("isAlwaysOnTopChildren", task));
        for (String name : new String[]{"findMinPositionForRootTask", "findMaxPositionForRootTask"})
            deoptimize.accept(areaClass.getDeclaredMethod(name, task));
        deoptimize.accept(areaClass.getDeclaredMethod("assignRootTaskOrdering",
                loader.loadClass("android.view.SurfaceControl$Transaction")));
        Method animationLayer = loader.loadClass(WM + "FlymeTaskDisplayArea")
                .getDeclaredMethod("getWinAppAnimationLayer", boolean.class, boolean.class);
        animationLayer.setAccessible(true);
        mini.getDeclaredMethod("getAnimationLeashParent", task);
        installer.hook(mini.getName(), "getAnimationLeashParent", chain -> {
            Object result = chain.proceed();
            Object target = chain.getArg(0);
            if (result != null && enabled() && target != null
                    && expandedMode(((Number) mode.invoke(target)).intValue())
                    && !Boolean.TRUE.equals(deferBack.invoke(null, target)))
                return animationLayer.invoke(displayArea.get(chain.getThisObject()), true, true);
            return result;
        }, task);
        Method taskParentSurface = container.getMethod("getParentSurfaceControl");
        taskParentSurface.setAccessible(true);
        Method subtype = loader.loadClass(WM + "FlymeTaskExt")
                .getDeclaredMethod("getWindowSubtype", container);
        subtype.setAccessible(true);
        task.getDeclaredMethod("getAnimationLeashParent");
        installer.hook(task.getName(), "getAnimationLeashParent", chain -> {
            Object target = chain.getThisObject();
            if (enabled() && ((Number) mode.invoke(target)).intValue() == 12
                    && ((Number) subtype.invoke(null, target)).intValue() == 0) {
                // Press/release scale and fling leashes must share the task's ordering.
                // The native upper animation group would raise idle pins above expanded windows.
                Object surface = taskParentSurface.invoke(target);
                if (surface != null) return surface;
            }
            return chain.proceed();
        });
        for (Method method : loader.loadClass(WM + "SurfaceAnimator").getDeclaredMethods())
            if (method.getName().equals("createAnimationLeash")
                    || method.getName().equals("startAnimation")) deoptimize.accept(method);
        for (Method method : new Method[]{childCount, childAt, asTask, parent, rootTask,
                mode, home, activityCount}) method.setAccessible(true);
        String[] predicates = {"FlymeWindowControllerBase", "FlymeMiniWindowControllerImpl",
                "FlymeFreeFormWindowControllerImpl", "FlymeWeltWindowControllerImpl"};
        for (String name : predicates) loader.loadClass(WM + name).getDeclaredMethod("needPositionTaskAtBottom", task, task);
        Method move = base.getDeclaredMethod("moveTasksToOtherWindowInner", task, int.class, int.class, boolean.class, boolean.class);
        moveWindow = move; moveWindow.setAccessible(true);
        Method under = base.getDeclaredMethod("moveUnderWindowTaskToBack", task);
        Method miniUnder = mini.getDeclaredMethod("moveUnderTaskToBack", task);
        mini.getDeclaredMethod("positionWindowTaskAtBottom", task, boolean.class);
        Method shouldBack = mini.getDeclaredMethod("shouldToBack", task, task);
        Method front = loader.loadClass(WM + "FlymeFreeFormWindowControllerImpl")
                .getDeclaredMethod("onTaskMoveToFront", String.class, task);
        Method switchFront = mini.getDeclaredMethod("postSwitchTasksByNewFrontTask", task, task,
                int.class, boolean.class, int.class, boolean.class);
        Class<?> factory = loader.loadClass(WM + "FlymeFeatureFactory");
        deoptimize.accept(factory.getDeclaredMethod("getAnimationLeashParent", task));
        deoptimize.accept(loader.loadClass(WM + "FlymeFreeFormWindowControllerImpl")
                .getDeclaredMethod("getAnimationLeashParent", task));
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
        installer.hook(mini.getName(), "shouldToBack", chain -> {
            Object result = chain.proceed();
            Object incoming = chain.getArg(0), old = chain.getArg(1);
            if (Boolean.TRUE.equals(result) && enabled() && isFloating(incoming) && isFloating(old)
                    && !Boolean.TRUE.equals(deferBack.invoke(null, old))
                    && ((Number) activityCount.invoke(old)).intValue() > 0
                    && keep(chain.getThisObject(), incoming, old)) return false;
            return result;
        }, task, task);
        // Do not cancel native pausing/front-task switches: they turn an expanded
        // window into a draggable pin window, rather than evicting it.
        for (String name : new String[]{"FlymeMiniWindowControllerImpl", "FlymeFreeFormWindowControllerImpl"}) {
            installer.hook(WM + name, "onTaskMoveToFront", chain -> {
                Object result = chain.proceed();
                collapseOtherExpanded(chain.getThisObject(), chain.getArg(1));
                return result;
            }, String.class, task);
        }
        Method update = mini.getDeclaredMethod("updateWindowModeInfo", task, int.class, boolean.class, boolean.class);
        installer.hook(mini.getName(), update.getName(), chain -> {
            Object result = chain.proceed();
            if (Boolean.TRUE.equals(chain.getArg(2))) collapseOtherExpanded(chain.getThisObject(), chain.getArg(0));
            return result;
        }, task, int.class, boolean.class, boolean.class);
        for (String[] entry : new String[][]{
                {mini.getName(), "enterMiniWindowWithoutWindowMode"},
                {WM + "FlymeFreeFormWindowControllerImpl", "enterFreeWindowWithoutWindowModeChange"}}) {
            Method enter = loader.loadClass(entry[0]).getDeclaredMethod(entry[1], task, boolean.class);
            installer.hook(entry[0], entry[1], chain -> {
                Object result = chain.proceed();
                if (Boolean.TRUE.equals(result)) collapseOtherExpanded(chain.getThisObject(), chain.getArg(0));
                return result;
            }, task, boolean.class);
            deoptimize.accept(enter);
        }
        deoptimize.accept(update);
        deoptimize.accept(mini.getDeclaredMethod("onTaskMoveToFront", String.class, task));
        for (String name : predicates) {
            installer.hook(WM + name, "needPositionTaskAtBottom", chain -> {
                Object result = chain.proceed();
                Trim trim = trimming.get();
                if (trim == null || !trim.floatingDestination
                        || trim.controller != chain.getThisObject() || trim.incoming != chain.getArg(0) || !enabled()) return result;
                Object candidate = chain.getArg(1);
                if (candidate == trim.incoming) return false;
                // Apply one shared quota across raw modes and pin subtypes. Native
                // predicates only evict their own type, allowing mixed modes to exceed it.
                if (isFloating(candidate) && !Boolean.TRUE.equals(home.invoke(candidate)))
                    return !keep(trim.controller, trim.incoming, candidate);
                return result;
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
        installPlacement(loader, installer, deoptimize);
        log.accept("Flyme small-window limit hooks registered: concurrent pin visibility, expanded-window handoff and automatic eviction paths covered", null);
    }

    private void installPlacement(ClassLoader loader, SignalHooks.Installer installer,
                                  Consumer<Method> deoptimize) throws ReflectiveOperationException {
        Class<?> task=loader.loadClass(WM+"Task"), pin=loader.loadClass(WM+"FlymePinWindowControllerImpl");
        Class<?> info=loader.loadClass(WM+"FlymePinWindowInfo"), window=loader.loadClass(WM+"FlymeWindowInfo");
        Method getInfo=loader.loadClass(WM+"FlymeTaskExt").getDeclaredMethod("getWindowInfo",task);
        getInfo.setAccessible(true);
        Method visible=window.getMethod("getVisibleBound");visible.setAccessible(true);
        Method drag=info.getDeclaredMethod("getDragBound");drag.setAccessible(true);
        Method move=info.getDeclaredMethod("updatePosition",int.class,int.class,boolean.class);move.setAccessible(true);
        Method hidden=loader.loadClass(WM+"FlymeFloatWindowInfo").getDeclaredMethod("isHidden");hidden.setAccessible(true);
        Method getDisplay=task.getMethod("getDisplayContent");getDisplay.setAccessible(true);
        Method metrics=loader.loadClass(WM+"DisplayContent").getMethod("getDisplayMetrics");metrics.setAccessible(true);
        ThreadLocal<Object> entering=new ThreadLocal<>();
        installer.hook(pin.getName(),"updateWindowModeInfo",chain->{
            Object previous=entering.get();
            if(enabled() && (Boolean)chain.getArg(2) && (Integer)chain.getArg(1)!=12)
                entering.set(chain.getArg(0));
            try {return chain.proceed();}
            finally {if(previous==null)entering.remove();else entering.set(previous);}
        },task,int.class,boolean.class,boolean.class);
        installer.hook(pin.getName(),"createWindowInfo",chain->{
            Object result=chain.proceed(), incoming=chain.getArg(0);
            if(!enabled() || entering.get()!=incoming || !info.isInstance(result))return result;
            try {
                android.graphics.Rect bounds=(android.graphics.Rect)visible.invoke(result);
                android.graphics.Rect safe=(android.graphics.Rect)drag.invoke(result);
                Object area=displayArea.get(chain.getThisObject());
                for(int i=((Number)childCount.invoke(area)).intValue()-1;i>=0;i--){
                    Object other=asTask.invoke(childAt.invoke(area,i));
                    if(other==null || other==incoming || (Integer)mode.invoke(other)!=12)continue;
                    Object old=getInfo.invoke(null,other);
                    if(!info.isInstance(old) || Boolean.TRUE.equals(hidden.invoke(old)))continue;
                    android.graphics.Rect last=(android.graphics.Rect)visible.invoke(old);
                    if(!android.graphics.Rect.intersects(last,safe))continue;
                    float density=((android.util.DisplayMetrics)metrics.invoke(getDisplay.invoke(incoming))).density;
                    int x=Math.max(safe.left,Math.min(safe.right-bounds.width(),last.left));
                    int y=nextPinTop(last.bottom,bounds.height(),safe.top,safe.bottom,Math.round(8*density));
                    move.invoke(result,x,y,true);
                    break;
                }
            } catch(ReflectiveOperationException|RuntimeException error){reportCountingError(error);}
            return result;
        },task,int.class,int.class,int.class);
        deoptimize.accept(pin.getDeclaredMethod("updateWindowModeInfo",task,int.class,boolean.class,boolean.class));
    }

    static int nextPinTop(int previousBottom,int height,int top,int bottom,int gap){
        int next=Math.max(top,previousBottom+gap);
        return (long)next+height<=bottom?next:top;
    }

    private void collapseOtherExpanded(Object controller, Object incoming) {
        if (!enabled() || Boolean.TRUE.equals(collapsing.get()) || incoming == null) return;
        try {
            incoming = rootTask.invoke(incoming);
            if (incoming == null || !expandedMode(((Number) mode.invoke(incoming)).intValue())) return;
            Object area = displayArea.get(controller);
            if (parent.invoke(incoming) != area) return;
            // Snapshot first: native mode changes can reorder the display area's children.
            List<Object> expanded = new ArrayList<>();
            for (int i = ((Number) childCount.invoke(area)).intValue() - 1; i >= 0; i--) {
                Object candidate = asTask.invoke(childAt.invoke(area, i));
                if (candidate != null && candidate != incoming
                        && (expandedMode(((Number) mode.invoke(candidate)).intValue())
                            || (isFloating(candidate) && isMoreApps(candidate)))
                        && !Boolean.TRUE.equals(deferBack.invoke(null, candidate))
                        && ((Number) activityCount.invoke(candidate)).intValue() > 0)
                    expanded.add(candidate);
            }
            collapsing.set(true);
            try {
                // Native mode 12 owns the pin surface, drag/tap consumer and shrink animation.
                // Invoke directly: postSwitchTasksToOtherWindow removes the previous pending
                // message, so queuing one message per task would only shrink the last window.
                for (Object candidate : expanded) {
                    if (isMoreApps(candidate)) {
                        // This picker is an auxiliary task, not an application to retain.
                        // Finish its activities and remove recents, without killing SystemUITools.
                        removeTask.invoke(taskSupervisor.get(candidate), candidate, false, true,
                                "flyme-mod-more-apps-selected");
                    } else moveWindow.invoke(controller, candidate, 12, 0, true, true);
                }
                // Shrinking can reorder children. Reapply native ordering with the new priorities.
                position.invoke(area, Integer.MAX_VALUE, incoming, false);
            } finally { collapsing.remove(); }
        } catch (ReflectiveOperationException | RuntimeException error) {
            reportCountingError(error);
        }
    }

    static boolean expandedMode(int mode) { return mode == 11 || mode == 1035; }

    private boolean isMoreApps(Object task) throws ReflectiveOperationException {
        Object activity = rootActivity.invoke(task);
        if (activity == null) return false;
        ComponentName component = (ComponentName) activityComponent.get(activity);
        return component != null && isMoreAppsComponent(component.getPackageName(), component.getClassName(),
                (String) launchedFromPackage.get(activity));
    }

    static boolean isMoreAppsComponent(String packageName, String className, String launchedFrom) {
        return "com.flyme.systemuitools".equals(packageName)
                && ("com.flyme.systemuitools.windowmode.views.MoreAppWindow".equals(className)
                    || ("com.flyme.systemuitools".equals(launchedFrom)
                        && "com.flyme.systemuitools.windowmode.views.SlideLaunchAppSettings".equals(className)));
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
