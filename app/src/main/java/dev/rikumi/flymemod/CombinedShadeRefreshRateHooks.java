package dev.rikumi.flymemod;

import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;

/** Request the display's highest same-resolution rate only while the merged shade is visible. */
final class CombinedShadeRefreshRateHooks {
    private static final String CONTROLLER = "com.android.systemui.shade.NotificationShadeWindowControllerImpl";
    private final Class<?> stateType;
    private final Field params, root, interactorLazy, panelVisible, expanded, dozing, keyguard;
    private final Field minRate, maxRate;
    private final Map<Object, Rates> overrides = new WeakHashMap<>();
    private final BiConsumer<String, Throwable> log;

    CombinedShadeRefreshRateHooks(ClassLoader loader, BiConsumer<String, Throwable> log)
            throws ReflectiveOperationException {
        this.log = log;
        Class<?> controller = loader.loadClass(CONTROLLER);
        stateType = loader.loadClass("com.android.systemui.shade.NotificationShadeWindowState");
        controller.getDeclaredMethod("applyKeyguardFlags", stateType);
        params = controller.getField("mLpChanged");
        root = controller.getField("mWindowRootView");
        interactorLazy = controller.getField("mShadeInteractorLazy");
        panelVisible = stateType.getField("panelVisible");
        expanded = stateType.getField("shadeOrQsExpanded");
        dozing = stateType.getField("dozing");
        keyguard = stateType.getField("keyguardShowing");
        minRate = WindowManager.LayoutParams.class.getField("preferredMinDisplayRefreshRate");
        maxRate = WindowManager.LayoutParams.class.getField("preferredMaxDisplayRefreshRate");
    }

    void install(SignalHooks.Installer installer) {
        installer.hook(CONTROLLER, "applyKeyguardFlags", chain -> {
            Object controller = chain.getThisObject();
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) params.get(controller);
            Rates previous = overrides.remove(controller);
            // Restore before native policy runs, including its doze/fingerprint constraints.
            if (previous != null) previous.restore(lp, minRate, maxRate);
            Object result = chain.proceed();
            Object state = chain.getArg(0);
            if (dozing.getBoolean(state) || keyguard.getBoolean(state)
                    || !(panelVisible.getBoolean(state) || expanded.getBoolean(state))) return result;
            try {
                Object lazy = interactorLazy.get(controller);
                Object interactor = interactorLazy.getType().getMethod("get").invoke(lazy);
                Object mode = interactor.getClass().getMethod("isClassicsMode").invoke(interactor);
                if (!Boolean.TRUE.equals(mode.getClass().getMethod("getValue").invoke(mode))) return result;
                View view = (View) root.get(controller);
                Display display = view == null ? null : view.getDisplay();
                if (display == null) return result;
                Display.Mode current = display.getMode();
                float target = current.getRefreshRate();
                for (Display.Mode supported : display.getSupportedModes()) {
                    if (supported.getPhysicalWidth() == current.getPhysicalWidth()
                            && supported.getPhysicalHeight() == current.getPhysicalHeight()) {
                        target = Math.max(target, supported.getRefreshRate());
                    }
                }
                if (target <= 60.1f) return result;
                overrides.put(controller, new Rates(lp, minRate, maxRate));
                // A mode ID takes precedence over preferredRefreshRate; avoid pinning a 60Hz mode.
                lp.preferredDisplayModeId = 0;
                lp.preferredRefreshRate = target;
                minRate.setFloat(lp, target);
                maxRate.setFloat(lp, target);
            } catch (ReflectiveOperationException | RuntimeException error) {
                Rates saved = overrides.remove(controller);
                if (saved != null) saved.restore(lp, minRate, maxRate);
                log.accept("Cannot request merged shade high refresh rate", error);
            }
            return result;
        }, stateType);
    }

    private static final class Rates {
        final int mode;
        final float preferred, minimum, maximum;
        Rates(WindowManager.LayoutParams lp, Field min, Field max) throws IllegalAccessException {
            mode = lp.preferredDisplayModeId;
            preferred = lp.preferredRefreshRate;
            minimum = min.getFloat(lp);
            maximum = max.getFloat(lp);
        }
        void restore(WindowManager.LayoutParams lp, Field min, Field max) throws IllegalAccessException {
            lp.preferredDisplayModeId = mode;
            lp.preferredRefreshRate = preferred;
            min.setFloat(lp, minimum);
            max.setFloat(lp, maximum);
        }
    }
}
