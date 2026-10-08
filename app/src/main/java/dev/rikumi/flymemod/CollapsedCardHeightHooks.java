package dev.rikumi.flymemod;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Flyme 16260625: replace the mini panel's fixed row height with the expanded grid height. */
final class CollapsedCardHeightHooks {
    private static final String IMPL = "com.flyme.systemui.qs.MzQSImpl";
    private static final String MINI = "com.flyme.systemui.controlcenter.phone.MzQQSPanel";
    private static final String CONTROLLER = "com.android.systemui.shade.QuickSettingsControllerImpl";
    private final Consumer<Context> settings;
    private final BooleanSupplier enabled;
    private final BiConsumer<String, Throwable> log;
    private final Class<?> component;
    private final Field root, mini, container, shade, controllerQs;
    private final Field[] cards;
    private final Method classics, value, getContainer, updateMin, getHeight, setHeight;
    private final Map<View, State> panels = new WeakHashMap<>();
    private final Map<View, State> containers = new WeakHashMap<>();
    private BooleanSupplier geometryEnabled = () -> false;

    void setGeometryEnabled(BooleanSupplier enabled) { geometryEnabled = enabled; }
    CollapsedCardHeightHooks(ClassLoader loader, Consumer<Context> settings,
                             BooleanSupplier enabled, BiConsumer<String, Throwable> log)
            throws ReflectiveOperationException {
        this.settings = settings;
        this.enabled = enabled;
        this.log = log;
        Class<?> impl = loader.loadClass(IMPL);
        root = impl.getField("mRootView");
        mini = impl.getField("mQSPanelMini");
        container = impl.getField("mMzQSContainer");
        shade = impl.getField("mShadeInteractor");
        component = loader.loadClass("com.flyme.systemui.qs.dagger.MzQsFragmentComponent");
        Class<?> panel = loader.loadClass("com.flyme.systemui.controlcenter.phone.MzQSPanel");
        cards = new Field[]{panel.getField("mConnectivityTilesWrapper"),
                panel.getField("mBrightnessView"), panel.getField("mVolumeView")};
        classics = loader.loadClass("com.android.systemui.shade.domain.interactor.ShadeInteractor")
                .getMethod("isClassicsMode");
        value = loader.loadClass("kotlinx.coroutines.flow.StateFlow").getMethod("getValue");
        getContainer = loader.loadClass("com.android.systemui.plugins.qs.QS").getMethod("getContainer");
        Class<?> controller = loader.loadClass(CONTROLLER);
        controllerQs = controller.getField("mQs");
        updateMin = controller.getMethod("updateMinHeight");
        getHeight = controller.getMethod("getExpansionHeight");
        setHeight = controller.getMethod("setExpansionHeight", float.class);
    }

    void install(SignalHooks.Installer installer) {
        installer.hook(IMPL, "onComponentCreated", chain -> {
            Object result = chain.proceed();
            attach(chain.getThisObject());
            return result;
        }, component, Bundle.class);
        installer.hook("android.widget.LinearLayout", "onMeasure", chain -> {
            State state = panels.get(chain.getThisObject());
            if (state != null) syncHeight(state, true);
            return chain.proceed();
        }, int.class, int.class);
        installer.hook(MINI, "onDensityOrFontScaleChanged", chain -> {
            Object result = chain.proceed();
            State state = panels.get(chain.getThisObject());
            if (state != null) {
                View wrapper = state.wrapper.get();
                if (wrapper != null) state.originalHeight = wrapper.getLayoutParams().height;
                syncHeight(state, false);
            }
            return result;
        });
        installer.hook(IMPL, "getQsMinExpansionHeight", chain -> {
            int original = (Integer) chain.proceed();
            State state = panels.get(mini.get(chain.getThisObject()));
            if (state == null) return original;
            syncHeight(state, false);
            if (!isCombined(chain.getThisObject()) || !state.managed) {
                return original;
            }
            View wrapper = state.wrapper.get();
            // During a requested relayout the old bottom is still cached. Report the pending
            // height delta now, so clipping/notification padding never use the shorter endpoint.
            return wrapper == null ? original : pendingMinimum(original,
                    wrapper.getHeight(), wrapper.getLayoutParams().height);
        });
        installer.hook(CONTROLLER, "setExpansionHeight", chain -> {
            Object controller = chain.getThisObject();
            Object qs = controllerQs.get(controller);
            State state = qs == null ? null : containers.get(getContainer.invoke(qs));
            if (state != null) {
                state.controller = new WeakReference<>(controller);
                syncHeight(state, false);
                if (state.managed || geometryEnabled.getAsBoolean()) {
                    float before = ((Number) getHeight.invoke(controller)).floatValue();
                    updateMin.invoke(controller);
                    float after = ((Number) getHeight.invoke(controller)).floatValue();
                    if (before != after && ((Number) chain.getArg(0)).floatValue() == before) {
                        return chain.proceed(new Object[]{after});
                    }
                }
            }
            return chain.proceed();
        }, float.class);
    }

    private void attach(Object owner) throws ReflectiveOperationException {
        View panel = (View) mini.get(owner);
        View parent = (View) container.get(owner);
        if (panel == null || parent == null) return;
        View wrapper = find(panel, "connectivity_slider_wrapper");
        if (wrapper == null) return;
        State state = new State(owner, wrapper, wrapper.getLayoutParams().height);
        panels.put(panel, state);
        containers.put(parent, state);
        panel.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (b - t == ob - ot && r - l == or - ol) return;
            Object controller = state.controller.get();
            if (controller != null) {
                try {
                    // Native setter refreshes min height, QS clip, notification top padding and
                    // expansion state together. The existing operating-area scale hook composes.
                    setHeight.invoke(controller, ((Number) getHeight.invoke(controller)).floatValue());
                } catch (ReflectiveOperationException error) {
                    log.accept("Cannot refresh restored collapsed QS height", error);
                }
            }
        });
        View full = find((View) root.get(owner), "mz_quick_settings_panel");
        if (full != null) {
            WeakReference<View> panelRef = new WeakReference<>(panel);
            for (Field field : cards) {
                View card = (View) field.get(full);
                if (card != null) card.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                    View target = panelRef.get();
                    if (target != null && b - t != ob - ot) target.requestLayout();
                });
            }
        }
    }

    private boolean isCombined(Object owner) throws ReflectiveOperationException {
        return Boolean.TRUE.equals(value.invoke(classics.invoke(shade.get(owner))));
    }

    private void syncHeight(State state, boolean measuring) throws ReflectiveOperationException {
        Object owner = state.owner.get();
        View wrapper = state.wrapper.get();
        if (owner == null || wrapper == null) return;
        settings.accept(wrapper.getContext());
        boolean active = enabled.getAsBoolean() && isCombined(owner);
        int desired = state.originalHeight;
        if (active) {
            View full = find((View) root.get(owner), "mz_quick_settings_panel");
            int measured = 0;
            if (full != null) for (Field field : cards) {
                View card = (View) field.get(full);
                if (card != null) measured = Math.max(measured, card.getMeasuredHeight());
            }
            // Expanded children are measured before the overlaid mini panel in the FrameLayout.
            // Keep the original height if the full grid is not ready on the first measure.
            if (measured > 0) desired = measured;

        }
        state.overridden = active && desired != state.originalHeight;
        ViewGroup.LayoutParams params = wrapper.getLayoutParams();
        if (desired > 0 && params.height != desired) {
            params.height = desired;
            state.managed = true;
            if (!measuring) wrapper.requestLayout();
        }
    }

    static int pendingMinimum(int original, int laidOutHeight, int desiredHeight) {
        return laidOutHeight > 0 && desiredHeight > 0
                ? original + desiredHeight - laidOutHeight : original;
    }

    private static View find(View view, String name) {
        if (view == null) return null;
        int id = view.getResources().getIdentifier(name, "id", "com.android.systemui");
        return id == 0 ? null : view.findViewById(id);
    }

    private static final class State {
        final WeakReference<Object> owner;
        final WeakReference<View> wrapper;
        WeakReference<Object> controller = new WeakReference<>(null);
        int originalHeight;
        boolean overridden;
        boolean managed;
        State(Object owner, View wrapper, int originalHeight) {
            this.owner = new WeakReference<>(owner);
            this.wrapper = new WeakReference<>(wrapper);
            this.originalHeight = originalHeight;
        }
    }
}
