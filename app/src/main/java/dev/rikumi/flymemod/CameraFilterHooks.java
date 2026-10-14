package dev.rikumi.flymemod;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Camera 12.7.2 FilterManager: F/J select; A temporarily resets; C restores. */
final class CameraFilterHooks {
    private static final String FILE = "flymemod_filter_selection";
    private static final String NONE = "Mznone";
    private final SharedPreferences settings;
    private final BiConsumer<String, Throwable> log;
    private final Map<Object, State> states = Collections.synchronizedMap(new WeakHashMap<>());
    private Field context, current, pending, ui, controls, classicList, myList;
    private Method isMy, restore, hasEffect, setButtonPressed;
    private final Handler main = new Handler(Looper.getMainLooper());

    private static final class State {
        volatile boolean initialized, previewReady;
    }

    CameraFilterHooks(SharedPreferences settings, BiConsumer<String, Throwable> log) {
        this.settings = settings;
        this.log = log;
    }

    void install(ClassLoader loader, SignalHooks.Installer installer, Consumer<Method> deoptimize)
            throws ReflectiveOperationException {
        Class<?> manager = loader.loadClass("com.meizu.media.camera.filter.h");
        // Field names verified in the original DEX, rather than jadx's renamed fields.
        context = manager.getField("a");
        current = manager.getField("b");
        pending = manager.getField("c");
        ui = manager.getField("e");
        controls = manager.getField("d");
        classicList = manager.getField("l");
        myList = manager.getField("k");
        isMy = manager.getDeclaredMethod("t");
        restore = manager.getDeclaredMethod("C", boolean.class);
        hasEffect = manager.getDeclaredMethod("s");
        setButtonPressed = loader.loadClass("com.meizu.media.camera.MzUIController")
                .getMethod("setFilterBtnPressed", boolean.class);
        for (String method : new String[]{"F", "J"}) {
            manager.getDeclaredMethod(method, int.class);
            installer.hook(manager.getName(), method, chain -> {
                Object result = chain.proceed();
                try { remember(chain.getThisObject()); }
                catch (ReflectiveOperationException | RuntimeException error) {
                    log.accept("Cannot remember camera filter", error);
                }
                return result;
            }, int.class);
        }
        installer.hook(manager.getName(), "C", chain -> {
            try {
                Object target = chain.getThisObject();
                state(target).previewReady = true;
                prepare(target);
            } catch (ReflectiveOperationException | RuntimeException error) {
                log.accept("Cannot restore camera filter", error);
            }
            Object result = chain.proceed();
            syncButton(chain.getThisObject());
            return result;
        }, boolean.class);
        // Filter lists may arrive asynchronously after the first preview frame.
        for (String method : new String[]{"G", "K"}) {
            manager.getDeclaredMethod(method, List.class);
            installer.hook(manager.getName(), method, chain -> {
                Object result = chain.proceed();
                Object target = chain.getThisObject();
                main.post(() -> {
                    try {
                        if (state(target).previewReady && prepare(target)) {
                            restore.invoke(target, true);
                            syncButton(target);
                        }
                    } catch (ReflectiveOperationException | RuntimeException error) {
                        log.accept("Cannot restore loaded camera filter", error);
                    }
                });
                return result;
            }, List.class);
        }
        Class<?> module = loader.loadClass("com.meizu.media.camera.MzCamModule");
        Method getManager = module.getDeclaredMethod("getMFilterHandler");
        module.getDeclaredMethod("onPauseBeforeSuper");
        installer.hook(module.getName(), "onPauseBeforeSuper", chain -> {
            try {
                Object target = getManager.invoke(chain.getThisObject());
                if (target != null) state(target).previewReady = false;
            } catch (ReflectiveOperationException | RuntimeException error) {
                log.accept("Cannot suspend saved camera filter restoration", error);
            }
            return chain.proceed();
        });
        Class<?> commonUi = loader.loadClass("com.meizu.media.camera.ui.MzCommonUI");
        commonUi.getDeclaredMethod("inflateDelay");
        installer.hook(commonUi.getName(), "inflateDelay", chain -> {
            Object result = chain.proceed();
            // The controls can be inflated after the preview/filter was restored.
            synchronized (states) {
                for (Object target : states.keySet()) {
                    try {
                        if (controls.get(target) == chain.getThisObject()) syncButton(target);
                    } catch (IllegalAccessException error) {
                        log.accept("Cannot resolve restored filter controls", error);
                    }
                }
            }
            return result;
        });
        deoptimize.accept(loader.loadClass("com.meizu.media.camera.impl.MzCamControllerImpl")
                .getDeclaredMethod("restoreFilterEffect", boolean.class));
        for (String method : new String[]{"q", "r"}) {
            deoptimize.accept(manager.getDeclaredMethod(method, int.class));
        }
        for (String listener : new String[]{"$a", "$b"}) {
            deoptimize.accept(loader.loadClass(manager.getName() + listener)
                    .getDeclaredMethod("onSelIndexCheck", int.class));
        }
        log.accept("Camera filter retention hooks registered", null);
    }

    private State state(Object manager) {
        synchronized (states) { return states.computeIfAbsent(manager, ignored -> new State()); }
    }

    private boolean enabled() {
        return settings.getBoolean(ModuleSettings.CAMERA_KEEP_FILTER, false);
    }

    private SharedPreferences saved(Object manager) throws IllegalAccessException {
        return ((Context) context.get(manager)).getApplicationContext().getSharedPreferences(FILE, 0);
    }

    private void syncButton(Object manager) {
        main.post(() -> {
            try {
                if (!enabled() || !state(manager).previewReady) return;
                Object target = controls.get(manager);
                if (target != null) {
                    // Use the native setter: it updates the drawable, mIsFilterOn
                    // and the related video settings from the actual restored result.
                    setButtonPressed.invoke(target, hasEffect.invoke(manager));
                }
            } catch (ReflectiveOperationException | RuntimeException error) {
                log.accept("Cannot synchronize restored camera filter button", error);
            }
        });
    }

    private void remember(Object manager) throws ReflectiveOperationException {
        if (!enabled() || ui.get(manager) == null) return;
        String effect = (String) current.get(manager);
        if (effect == null || effect.equals("null")) return;
        // Only actual selection methods write this record. Lifecycle/mode resets
        // must never overwrite it with Mznone; explicitly choosing Mznone does.
        saved(manager).edit().putString("effect", effect)
                .putBoolean("my_filter", (Boolean) isMy.invoke(manager)).apply();
        state(manager).initialized = true;
    }

    private boolean prepare(Object manager) throws ReflectiveOperationException {
        State state = state(manager);
        if (!enabled() || state.initialized || ui.get(manager) == null) return false;
        SharedPreferences saved = saved(manager);
        String effect = saved.getString("effect", NONE);
        if (NONE.equals(effect)) {
            state.initialized = true;
            return false;
        }
        boolean my = (Boolean) isMy.invoke(manager);
        // The camera itself persists the selected classic/custom filter family.
        if (my != saved.getBoolean("my_filter", my)) return false;
        List<?> filters = (List<?>) (my ? myList : classicList).get(manager);
        if (!filters.contains(effect)) return false;
        pending.set(manager, effect);
        state.initialized = true;
        // C keeps native file-existence, mode support, rendering and capture logic.
        log.accept("Restoring saved camera filter: " + effect, null);
        return true;
    }
}
