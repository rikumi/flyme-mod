package dev.rikumi.flymemod;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

final class ZygoteRestartBootHooks {
    static void install(ClassLoader loader, SignalHooks.Installer installer, BooleanSupplier enabled,
                        Consumer<Method> deoptimize, BiConsumer<String, Throwable> log)
            throws ReflectiveOperationException {
        Class<?> properties = loader.loadClass("android.os.SystemProperties");
        // Capture before ActivityManagerService writes this property at the end of boot.
        boolean alreadyBooted = (Boolean) properties.getMethod("getBoolean", String.class, boolean.class)
                .invoke(null, "sys.boot_completed", false);
        if (!alreadyBooted) return;

        Class<?> staging = loader.loadClass("com.android.server.pm.StagingManager");
        Method notification = staging.getDeclaredMethod("markBootCompleted");
        Field successfulSessions = staging.getDeclaredField("mSuccessfulStagedSessionIds");
        successfulSessions.setAccessible(true);
        deoptimize.accept(staging.getDeclaredMethod("markStagedSessionsAsSuccessful"));
        deoptimize.accept(loader.loadClass("com.android.server.pm.StagingManager$Lifecycle")
                .getDeclaredMethod("onBootPhase", int.class));
        deoptimize.accept(notification);
        installer.hook(staging.getName(), "markBootCompleted", chain -> {
            if (!enabled.getAsBoolean()) return chain.proceed();
            List<?> sessions = (List<?>) successfulSessions.get(chain.getThisObject());
            synchronized (sessions) {
                if (sessions.isEmpty()) {

                    log.accept("Skipped duplicate APEX boot notification after Zygote restart", null);
                    return null;
                }
            }
            return chain.proceed();
        });
        log.accept("Zygote restart APEX boot notification hook registered", null);
    }
}
