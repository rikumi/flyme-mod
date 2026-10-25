package dev.rikumi.flymemod;

import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import android.os.Build;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;

final class CombinedShadeRefreshRateHooks {
    private static final String CONTROLLER = "com.android.systemui.shade.NotificationShadeWindowControllerImpl";
    private final Class<?> stateType;
    private final Field params, root, interactorLazy, panelVisible, expanded, dozing, keyguard;
    private final Field minRate, maxRate;
    private final Method lazyValue, classicsMode, flowValue;
    private final Map<Object, Rates> overrides = new WeakHashMap<>();
    private final Map<Object, FrameVote> frameVotes = new WeakHashMap<>();
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
        lazyValue = interactorLazy.getType().getMethod("get");
        classicsMode = loader.loadClass("com.android.systemui.shade.domain.interactor.ShadeInteractor")
                .getMethod("isClassicsMode");
        flowValue = loader.loadClass("kotlinx.coroutines.flow.StateFlow").getMethod("getValue");
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
                    || !(panelVisible.getBoolean(state) || expanded.getBoolean(state))) {
                clearFrameVote(controller);
                return result;
            }
            try {
                Object lazy = interactorLazy.get(controller);
                Object interactor = lazyValue.invoke(lazy);
                if (!Boolean.TRUE.equals(flowValue.invoke(classicsMode.invoke(interactor)))) {
                    clearFrameVote(controller);
                    return result;
                }
                View view = (View) root.get(controller);
                Display display = view == null ? null : view.getDisplay();
                if (display == null) { clearFrameVote(controller); return result; }
                Display.Mode current = display.getMode();
                float target = current.getRefreshRate();
                for (Display.Mode supported : display.getSupportedModes()) {
                    if (supported.getPhysicalWidth() == current.getPhysicalWidth()
                            && supported.getPhysicalHeight() == current.getPhysicalHeight()) {
                        target = Math.max(target, supported.getRefreshRate());
                    }
                }
                if (target <= 60.1f) { clearFrameVote(controller); return result; }
                overrides.put(controller, new Rates(lp, minRate, maxRate));
                // A mode ID takes precedence over preferredRefreshRate; avoid pinning a 60Hz mode.
                lp.preferredDisplayModeId = 0;
                lp.preferredRefreshRate = target;
                minRate.setFloat(lp, target);
                maxRate.setFloat(lp, target);

                if (Build.VERSION.SDK_INT >= 35) {
                    FrameVote vote = frameVotes.get(controller);
                    if (vote == null || vote.view != view) {
                        clearFrameVote(controller);
                        vote = new FrameVote(view);
                        frameVotes.put(controller, vote);
                    }
                    view.setRequestedFrameRate(target);
                }
            } catch (ReflectiveOperationException | RuntimeException error) {
                Rates saved = overrides.remove(controller);
                if (saved != null) saved.restore(lp, minRate, maxRate);
                clearFrameVote(controller);
                log.accept("Cannot request merged shade high refresh rate", error);
            }
            return result;
        }, stateType);
    }

    private void clearFrameVote(Object controller) {
        FrameVote vote = frameVotes.remove(controller);
        if (vote != null && Build.VERSION.SDK_INT >= 35) vote.view.setRequestedFrameRate(vote.original);
    }

    private static final class FrameVote {
        final View view;
        final float original;
        FrameVote(View view) { this.view = view; original = view.getRequestedFrameRate(); }
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
