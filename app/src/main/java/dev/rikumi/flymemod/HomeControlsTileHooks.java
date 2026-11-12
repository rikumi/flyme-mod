package dev.rikumi.flymemod;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.Executor;
import android.content.SharedPreferences;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.BiConsumer;

/** Opens Flyme 12.6's native Controls UI from the module-owned custom tile. */
final class HomeControlsTileHooks {
    private final SharedPreferences preferences;
    private final BiConsumer<String, Throwable> logger;

    HomeControlsTileHooks(SharedPreferences preferences, BiConsumer<String, Throwable> logger) {
        this.preferences = preferences;
        this.logger = logger;
    }

    void install(ClassLoader loader, SignalHooks.Installer installer) throws ReflectiveOperationException {
        Class<?> expandable = Class.forName("com.android.systemui.animation.Expandable", false, loader);
        Class.forName("com.android.systemui.qs.external.CustomTile", false, loader)
                .getDeclaredMethod("handleClick", expandable);
        installer.hook("com.android.systemui.qs.external.CustomTile", "handleClick", chain -> {
            if (preferences.getBoolean(HomeControlsTileSettings.KEY, false) && handleClick(chain.getThisObject())) return null;
            return chain.proceed();
        }, expandable);
    }

    private boolean handleClick(Object tile) {
        try {
            ComponentName component = (ComponentName) field(tile, "mComponent");
            if (!"dev.rikumi.flymemod".equals(component.getPackageName())
                    || !HomeControlsTileSettings.SERVICE.equals(component.getClassName())) return false;
            Context context = (Context) field(tile, "mContext");
            Object graph = field(context.getApplicationContext(), "mSysUIComponent");
            Object controls = call(field(graph, "controlsComponentProvider"), "get");
            Optional<?> ui = (Optional<?>) call(controls, "getControlsUiController");
            Optional<?> listing = (Optional<?>) call(controls, "getControlsListingController");
            if (ui.isEmpty() || listing.isEmpty()) return false;
            Object starter = field(tile, "mActivityStarter");
            Handler main = (Handler) field(tile, "mUiHandler");
            Executor background = (Executor) field(listing.get(), "backgroundExecutor");
            background.execute(() -> {
                try {
                    call(listing.get(), "forceReload");
                    main.post(() -> launch(context, controls, ui.get(), starter));
                } catch (ReflectiveOperationException | RuntimeException error) {
                    logger.accept("Module home controls discovery failed", error);
                    main.post(() -> launchApp(context, starter));
                }
            });
            return true;
        } catch (ReflectiveOperationException | RuntimeException error) {
            logger.accept("Module home controls launch failed", error);
        }
        // Let the TileService launch Google Home or Mi Home if the native UI is unavailable.
        return false;
    }

    private void launch(Context context, Object controls, Object ui, Object starter) {
        try {
            Class<?> destination = (Class<?>) call(ui, "resolveActivity");
            Intent intent = new Intent().setComponent(new ComponentName(context, destination))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra("extra_animate", true);
            boolean active = "AVAILABLE".equals(call(controls, "getVisibility").toString());
            Optional<?> controller = (Optional<?>) call(controls, "getControlsController");
            if (active && controller.isPresent()) {
                Object selection = call(controller.get(), "getPreferredSelection");
                if (selection.getClass().getName().endsWith("SelectedItem$StructureItem")) {
                    Object structure = call(selection, "getStructure");
                    active = !((Collection<?>) call(structure, "getControls")).isEmpty();
                }
            }
            call(starter, "startActivity", intent, true, null, active);
        } catch (ReflectiveOperationException | RuntimeException error) {
            logger.accept("Module home controls activity start failed", error);
            launchApp(context, starter);
        }
    }

    private void launchApp(Context context, Object starter) {
        for (Intent intent : HomeControlsLaunchIntents.apps(context)) {
            try {
                call(starter, "startActivity", intent, true);
                return;
            } catch (ReflectiveOperationException | RuntimeException error) { logger.accept("Module home app start failed", error); }
        }
    }

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    private static Object call(Object target, String name, Object... args) throws ReflectiveOperationException {
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
            Class<?>[] types = method.getParameterTypes();
            boolean matches = true;
            for (int i = 0; i < args.length; i++) {
                if (args[i] == null ? types[i].isPrimitive()
                        : !(types[i].isInstance(args[i]) || types[i] == boolean.class && args[i] instanceof Boolean)) {
                    matches = false;
                    break;
                }
            }
            if (!matches) continue;
            method.setAccessible(true);
            return method.invoke(target, args);
        }
        throw new NoSuchMethodException(name);
    }
}
