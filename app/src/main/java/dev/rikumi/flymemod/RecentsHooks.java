package dev.rikumi.flymemod;

import android.content.Context;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.BooleanSupplier;
import io.github.libxposed.api.XposedInterface;

/** Flyme 12.6 launcher recent-task actions. */
final class RecentsHooks {
    interface Installer {
        void hook(String name, String method, XposedInterface.Hooker hooker, Class<?>... parameters);
    }

    private static final String SYSTEM_UI_PROXY = "com.android.quickstep.SystemUiProxy";
    private static final String TASK_KILL_ALGORITHM = "com.meizu.flyme.launcher.quickstep.TaskKillAlgorithm";
    private final Installer installer;
    private final BooleanSupplier hideNotRunning;
    private final BooleanSupplier swipeUpKill;
    private final BooleanSupplier stackedRecents;
    private final BiConsumer<String, Throwable> log;
    private final Map<Object, Boolean> filteredLists = new WeakHashMap<>();

    RecentsHooks(Installer installer, BooleanSupplier hideNotRunning, BooleanSupplier swipeUpKill,
            BooleanSupplier stackedRecents, BiConsumer<String, Throwable> log) {
        this.installer = installer;
        this.hideNotRunning = hideNotRunning;
        this.swipeUpKill = swipeUpKill;
        this.stackedRecents = stackedRecents;
        this.log = log;
    }

    void install(ClassLoader loader, Consumer<Method> deoptimize) throws ReflectiveOperationException {
        Class<?> recentTasksList = loader.loadClass("com.android.quickstep.RecentTasksList");
        Method invalidate = recentTasksList.getDeclaredMethod("invalidateLoadedTasks");
        invalidate.setAccessible(true);
        installer.hook(recentTasksList.getName(), "isTaskListValid", chain -> {
            boolean filter = hideNotRunning.getAsBoolean();
            synchronized (filteredLists) {
                Boolean previous = filteredLists.get(chain.getThisObject());
                // A preference transition invalidates the old projection once.
                // Once loaded, use the native generation check. Returning false
                // throughout a gesture made every reloadIfNeeded rebuild all cards.
                if ((filter && !stackedRecents.getAsBoolean())
                        || filter != Boolean.TRUE.equals(previous)) return false;
            }
            return chain.proceed();
        }, int.class);
        // Native UI/background caches outlive an overview session and preference
        // changes. Refresh through the native loader, including when switched off.
        installer.hook(recentTasksList.getName(), "getTasks", chain -> {
            boolean filter = hideNotRunning.getAsBoolean();
            Object owner = chain.getThisObject();
            boolean wasFiltered;
            synchronized (filteredLists) {
                wasFiltered = Boolean.TRUE.equals(filteredLists.put(owner, filter));
            }
            if (filter || wasFiltered) invalidate.invoke(owner);
            return chain.proceed();
        }, boolean.class, Consumer.class, Predicate.class,
                loader.loadClass("com.meizu.flyme.launcher.quickstep.util.TaskLoadState"));
        // getRecentTasks is small enough to be inlined in the background loader.
        for (Method method : recentTasksList.getDeclaredMethods()) {
            if (method.getName().equals("loadTasksInBackground")
                    || method.getName().equals("getTasks")
                    || method.getName().equals("lambda$getTasks$7")) deoptimize.accept(method);
        }
        deoptimize.accept(loader.loadClass("com.android.quickstep.RecentsModel")
                .getDeclaredMethod("isTaskListValid", int.class));
        deoptimize.accept(loader.loadClass("com.android.quickstep.views.RecentsView")
                .getDeclaredMethod("reloadIfNeeded"));
        // Verified in the Flyme 12.6.0.0A MEIZU 21 Pro launcher reference APK.
        installer.hook(SYSTEM_UI_PROXY, "getRecentTasks", chain -> {
            Object result = chain.proceed();
            if (!(result instanceof List<?> tasks) || !hideNotRunning.getAsBoolean()) return result;
            try {
                ArrayList<?> filtered = new ArrayList<>(tasks);
                Iterator<?> iterator = filtered.iterator();
                while (iterator.hasNext()) {
                    if (!containsRunningTask(iterator.next())) iterator.remove();
                }
                return filtered;
            } catch (ReflectiveOperationException | RuntimeException e) {
                log.accept("Cannot filter non-running recent tasks", e);
            }
            return result;
        }, int.class, int.class);

        installer.hook(TASK_KILL_ALGORITHM, "removeTaskById", chain -> {
            if (swipeUpKill.getAsBoolean()) return chain.proceed();
            // Keep the task-card dismissal while bypassing Flyme's process-kill policy.
            chain.getThisObject().getClass().getMethod("removeTaskNotKillProcess", int.class)
                    .invoke(chain.getThisObject(), (Integer) chain.getArg(1));
            return null;
        }, Context.class, int.class, String.class);
    }

    private boolean containsRunningTask(Object groupedTask) throws ReflectiveOperationException {
        Method getTaskInfoList = groupedTask.getClass().getMethod("getTaskInfoList");
        Object infos = getTaskInfoList.invoke(groupedTask);
        if (!(infos instanceof Iterable<?> iterable)) return true;
        for (Object info : iterable) {
            Field isRunning = info.getClass().getField("isRunning");
            if (isRunning.getBoolean(info)) return true;
        }
        return false;
    }
}
