package dev.rikumi.flymemod;

import java.lang.reflect.Method;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

final class RecentsGestureJankHooks {
    private static final String RECENTS = "com.android.quickstep.views.RecentsView";
    private static final String TASK = "com.android.quickstep.views.TaskView";
    private static final String UPDATE = "lambda$loadVisibleTaskData$16";
    private final BooleanSupplier enabled;
    private final Class<?> taskType;
    private final Method update, loadVisible, accept;
    private final ThreadLocal<Request> current = new ThreadLocal<>();

    RecentsGestureJankHooks(ClassLoader loader, BooleanSupplier enabled) throws ReflectiveOperationException {
        this.enabled = enabled;
        taskType = loader.loadClass(TASK);
        Class<?> recents = loader.loadClass(RECENTS);
        update = recents.getDeclaredMethod(UPDATE, int.class, int.class, int.class, int.class,
                List.class, int.class, Integer.class, taskType);
        loadVisible = recents.getDeclaredMethod("loadVisibleTaskData", int.class);
        accept = loader.loadClass(RECENTS + "$$ExternalSyntheticLambda46")
                .getDeclaredMethod("accept", Object.class, Object.class);
        taskType.getDeclaredMethod("onTaskListVisibilityChanged", boolean.class, int.class);
    }

    void install(SignalHooks.Installer installer, Consumer<Method> deoptimize) {
        installer.hook(RECENTS, UPDATE, chain -> {
            if (!enabled.getAsBoolean()) return chain.proceed();
            Request previous = current.get();
            Request request;
            if (previous == null) request = new Request();
            else if (previous.task == null) request = previous;
            else {
                if (previous.nested == null) previous.nested = new Request();
                request = previous.nested;
            }
            request.task = chain.getArg(7);
            current.set(request);
            try { return chain.proceed(); }
            finally {
                request.task = null;
                request.completed = false;
                request.running = false;

                current.set(previous == null ? request : previous);
            }
        }, int.class, int.class, int.class, int.class, List.class, int.class, Integer.class, taskType);
        installer.hook(TASK, "onTaskListVisibilityChanged", chain -> {
            Request request = current.get();
            if (request == null || request.task != chain.getThisObject() || request.running)
                return chain.proceed();
            boolean visible = (Boolean) chain.getArg(0);
            int changes = (Integer) chain.getArg(1);

            if (visible && request.completed && request.changes == changes) return null;
            request.completed = false;
            request.running = true;
            try {
                Object result = chain.proceed();
                request.changes = changes;
                request.completed = visible;
                return result;
            } finally { request.running = false; }
        }, boolean.class, int.class);
        deoptimize.accept(update);
        deoptimize.accept(loadVisible);
        deoptimize.accept(accept);
    }

    private static final class Request {
        Object task;
        Request nested;
        int changes;
        boolean completed, running;
    }
}
