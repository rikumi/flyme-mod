package dev.rikumi.flymemod;

import android.content.Context;
import android.database.Cursor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import io.github.libxposed.api.XposedInterface;

/** Flyme 12.6 launcher recent-task actions. */
final class RecentsHooks {
    interface Installer {
        void hook(String name, String method, XposedInterface.Hooker hooker, Class<?>... parameters);
    }

    private static final String SYSTEM_UI_PROXY = "com.android.quickstep.SystemUiProxy";
    private static final String TASK_KILL_ALGORITHM = "com.meizu.flyme.launcher.quickstep.TaskKillAlgorithm";
    private final Installer installer;
    private final Supplier<Context> context;
    private final BiConsumer<String, Throwable> log;

    RecentsHooks(Installer installer, Supplier<Context> context, BiConsumer<String, Throwable> log) {
        this.installer = installer;
        this.context = context;
        this.log = log;
    }

    void install() throws ReflectiveOperationException {
        // Verified in the Flyme 12.6.0.0A MEIZU 21 Pro launcher reference APK.
        installer.hook(SYSTEM_UI_PROXY, "getRecentTasks", chain -> {
            Object result = chain.proceed();
            if (!(result instanceof List<?> tasks) || !enabled(ModuleSettings.RECENTS_HIDE_NOT_RUNNING)) return result;
            try {
                Iterator<?> iterator = tasks.iterator();
                while (iterator.hasNext()) {
                    if (!containsRunningTask(iterator.next())) iterator.remove();
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                log.accept("Cannot filter non-running recent tasks", e);
            }
            return result;
        }, int.class, int.class);

        installer.hook(TASK_KILL_ALGORITHM, "removeTaskById", chain -> {
            if (enabled(ModuleSettings.RECENTS_SWIPE_UP_KILL)) return chain.proceed();
            // Keep the task-card dismissal while bypassing Flyme's process-kill policy.
            chain.getThisObject().getClass().getMethod("removeTaskNotKillProcess", int.class)
                    .invoke(chain.getThisObject(), (Integer) chain.getArg(1));
            return null;
        }, Context.class, int.class, String.class);
    }

    private boolean enabled(String key) {
        Context app = context.get();
        if (app == null) return false;
        try (Cursor cursor = app.getContentResolver().query(ModuleSettings.URI, null, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) return false;
            int column = cursor.getColumnIndex(key);
            return column >= 0 && cursor.getInt(column) != 0;
        } catch (RuntimeException error) {
            log.accept("Cannot read recent-task preferences", error);
            return false;
        }
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
