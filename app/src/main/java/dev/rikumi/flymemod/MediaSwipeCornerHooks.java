package dev.rikumi.flymemod;

import android.content.Context;
import android.graphics.Outline;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewOutlineProvider;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.ToDoubleFunction;

/** Keep Flyme's carousel clipping bounds/activation, changing only its configured corner. */
final class MediaSwipeCornerHooks {
    private static final String HANDLER = "com.android.systemui.media.controls.ui.view.MediaCarouselScrollHandler";
    private final Class<?> handler;
    private final Field scrollView;
    private final Consumer<Context> settings;
    // NaN means native geometry, including when either relevant setting is off.
    private final ToDoubleFunction<View> radius;
    private final Map<View, WeakReference<State>> states = new WeakHashMap<>();

    MediaSwipeCornerHooks(ClassLoader loader, Consumer<Context> settings, ToDoubleFunction<View> radius)
            throws ReflectiveOperationException {
        handler = loader.loadClass(HANDLER);
        scrollView = handler.getField("scrollView");
        this.settings = settings; this.radius = radius;
    }

    void install(SignalHooks.Installer installer) {
        for (String name : new String[]{"updateClipToOutline", "setContentTranslation", "onMediaScrollingChanged",
                "setCarouselBounds", "onSettingsButtonUpdated"}) {
            for (Method method : handler.getDeclaredMethods()) {
                if (!method.getName().equals(name)) continue;
                installer.hook(HANDLER, name, chain -> {
                    Object result = chain.proceed();
                    View view = (View) scrollView.get(chain.getThisObject());
                    settings.accept(view.getContext());
                    observe(view).update();
                    return result;
                }, method.getParameterTypes());
            }
        }
    }

    private State observe(View view) {
        WeakReference<State> reference = states.get(view);
        State state = reference == null ? null : reference.get();
        if (state == null) {
            state = new State(view);
            states.put(view, new WeakReference<>(state));
            view.addOnAttachStateChangeListener(state);
        }
        return state;
    }

    void refresh() {
        for (WeakReference<State> reference : new ArrayList<>(states.values())) {
            State state = reference.get();
            if (state != null) state.update();
        }
    }

    private final class State implements View.OnAttachStateChangeListener {
        final WeakReference<View> owner;
        ViewOutlineProvider original;
        float appliedRadius = Float.NaN;
        final Rect bounds = new Rect();
        final ViewOutlineProvider outline = new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline result) {
                if (original == null) { result.setEmpty(); return; }
                original.getOutline(view, result);
                float target = (float) radius.applyAsDouble(view);
                // Native provider owns carouselWidth/Height, not view or screen width.
                if (Float.isFinite(target) && target >= 0 && result.getRect(bounds))
                    result.setRoundRect(bounds, target);
            }
        };

        State(View view) { owner = new WeakReference<>(view); }
        void update() {
            View view = owner.get();
            if (view == null) return;
            float target = (float) radius.applyAsDouble(view);
            if (Float.isFinite(target) && target >= 0) {
                if (view.getOutlineProvider() != outline) {
                    original = view.getOutlineProvider();
                    if (original == null) return;
                    view.setOutlineProvider(outline);
                    appliedRadius = Float.NaN;
                }
                if (target != appliedRadius) {
                    appliedRadius = target;
                    view.invalidateOutline();
                }
            } else restore(view);
        }
        private void restore(View view) {
            if (view.getOutlineProvider() == outline) {
                view.setOutlineProvider(original);
                view.invalidateOutline();
            }
            original = null;
            appliedRadius = Float.NaN;
        }
        @Override public void onViewAttachedToWindow(View view) { update(); }
        @Override public void onViewDetachedFromWindow(View view) { restore(view); }
    }
}
