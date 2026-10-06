package com.rikumi.flymemod;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.VelocityTracker;
import android.view.animation.DecelerateInterpolator;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Flyme SystemUI 16260625: aligned collapsed cards, per-card transitions and expanded overpull. */
final class SecondaryExpansionHooks {
    private static final String QS = "com.android.systemui.shade.QuickSettingsControllerImpl";
    private static final String IMPL = "com.flyme.systemui.qs.MzQSImpl";
    private final Consumer<Context> settings;
    private final BooleanSupplier enabled;
    private final BiConsumer<String, Throwable> log;
    private final Field shade, shadeFraction, root, tileLayout, implShade, spec, cellWidth, gap, alignOffset;
    private final Field conflictingGesture, trackingPointer, velocityTracker;
    private final Field[] miniCards;
    private final Method mode, flow, container, fullyExpanded, tracking, stopTracking, translation, plugin, cancelSpring;
    private final Method pageAt, pageCount;
    private final Map<Object, Drag> drags = new WeakHashMap<>();
    private final Map<Object, Panels> panels = new WeakHashMap<>();
    private final Map<View, Panels> miniPanels = new WeakHashMap<>();

    SecondaryExpansionHooks(ClassLoader loader, Consumer<Context> settings, BooleanSupplier enabled,
                            BiConsumer<String, Throwable> log) throws ReflectiveOperationException {
        this.settings = settings; this.enabled = enabled; this.log = log;
        Class<?> qs = loader.loadClass(QS), impl = loader.loadClass(IMPL);
        shade = qs.getField("mShadeInteractor");
        shadeFraction = qs.getField("mShadeExpandedFraction");
        root = impl.getField("mRootView");
        implShade = impl.getField("mShadeInteractor");
        container = qs.getMethod("getContainer"); fullyExpanded = qs.getMethod("getFullyExpanded");
        tracking = qs.getMethod("isTracking"); translation = qs.getMethod("setQSTranslationY", float.class);
        stopTracking = qs.getDeclaredMethod("setTracking", boolean.class); stopTracking.setAccessible(true);
        conflictingGesture = qs.getField("mConflictingExpansionGesture");
        trackingPointer = qs.getField("mTrackingPointer");
        velocityTracker = qs.getField("mQsVelocityTracker");
        plugin = qs.getMethod("getQs");
        cancelSpring = loader.loadClass("com.android.systemui.plugins.qs.QS").getMethod("cancelQsHeaderAnimator");
        mode = loader.loadClass("com.android.systemui.shade.domain.interactor.ShadeInteractor").getMethod("isExpandToQsEnabled");
        flow = loader.loadClass("kotlinx.coroutines.flow.StateFlow").getMethod("getValue");
        Class<?> panel = loader.loadClass("com.flyme.systemui.controlcenter.phone.MzQSPanel");
        tileLayout = panel.getField("mTileLayout");
        Class<?> pages = loader.loadClass("com.flyme.systemui.controlcenter.qs.PagedUnifiedTileLayout");
        pageAt = pages.getMethod("getPageAt", int.class); pageCount = pages.getMethod("getNumPages");
        Class<?> params = loader.loadClass("com.flyme.systemui.controlcenter.qs.CellLayoutLayoutParams");
        spec = params.getField("spec");
        Class<?> grid = loader.loadClass("com.flyme.systemui.controlcenter.qs.UnifiedTileLayout");
        cellWidth = grid.getField("mCellWidth");
        gap = grid.getField("mCellMarginHorizontal"); alignOffset = grid.getField("mAlignOffsetX");
        Class<?> mini = loader.loadClass("com.flyme.systemui.controlcenter.phone.MzQQSPanel");
        miniCards = new Field[]{mini.getField("mConnectivityTilesWrapper"), mini.getField("mBrightnessView"), mini.getField("mVolumeView")};
    }

    boolean ownsGesture(Object qs) {
        Drag drag = drags.get(qs);
        return enabled.getAsBoolean() && drag != null && (drag.secondary || drag.overpull || drag.animator != null);
    }

    boolean allowsTouch(Object qs) { return enabled.getAsBoolean() && drags.containsKey(qs); }

    void install(SignalHooks.Installer installer) {
        installer.hook(IMPL, "setQsExpansion", chain -> {
            Object result = chain.proceed();
            Object owner = chain.getThisObject();
            View view = (View) root.get(owner);
            if (view == null) return result;
            settings.accept(view.getContext());
            View full = find(view, "mz_quick_settings_panel"), mini = find(view, "mz_quick_settings_panel_mini");
            if (full == null || mini == null) return result;
            boolean active = enabled.getAsBoolean() && Boolean.TRUE.equals(flow.invoke(mode.invoke(implShade.get(owner))));
            float f = ((Number) chain.getArg(0)).floatValue();
            if (Float.isNaN(f)) return result;
            Panels state = panels.get(owner);
            if (active && (state == null || state.mini != mini || state.full != full)) {
                state = new Panels(full, mini);
                panels.put(owner, state); miniPanels.put(mini, state);
            }
            if (state == null) return result;
            state.fraction = f; state.active = active;
            alignMini(state);
            render(state);
            if (!active) { panels.remove(owner); miniPanels.remove(mini); }
            return result;
        }, float.class, float.class, float.class, float.class);
        // Re-evaluate after layout, including reordered tiles with unchanged panel bounds.
        installer.hook("com.flyme.systemui.controlcenter.qs.UnifiedTileLayout", "onLayout", chain -> {
            Object result = chain.proceed();
            for (Panels state : panels.values()) { alignMini(state); render(state); }
            return result;
        }, boolean.class, int.class, int.class, int.class, int.class);
        installer.hook("android.widget.LinearLayout", "onLayout", chain -> {
            Object result = chain.proceed();
            Panels state = miniPanels.get(chain.getThisObject());
            if (state != null) { alignMini(state); render(state); }
            return result;
        }, boolean.class, int.class, int.class, int.class, int.class);
        installer.hook("com.flyme.systemui.controlcenter.phone.MzQQSPanel", "onDensityOrFontScaleChanged", chain -> {
            Panels state = miniPanels.get(chain.getThisObject());
            if (state != null) restoreGeometry(state);
            Object result = chain.proceed();
            if (state != null) {
                state.capturePadding();
                state.spacers.clear();
                alignMini(state); render(state);
            }
            return result;
        });
        installer.hook(QS, "handleTouch", chain -> {
            Object qs = chain.getThisObject();
            MotionEvent event = (MotionEvent) chain.getArg(0);
            View view = (View) container.invoke(qs);
            if (view == null) return chain.proceed();
            settings.accept(view.getContext());
            if (!enabled.getAsBoolean() || !Boolean.TRUE.equals(flow.invoke(mode.invoke(shade.get(qs))))) {
                clear(qs); return chain.proceed();
            }
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                clear(qs);
                Drag drag = new Drag();
                drag.fullOnDown = (Boolean) fullyExpanded.invoke(qs);
                // In merged shade mode, the all-tiles QS can be fully expanded
                // while the overall shade fraction is well below 1.0.
                if (!drag.fullOnDown && ((Boolean) chain.getArg(1) || shadeFraction.getFloat(qs) < .99f)) {
                    return chain.proceed();
                }
                drag.startY = event.getY(0);
                drags.put(qs, drag);
                if (drag.fullOnDown) {
                    stopSpring(qs);
                    translation.invoke(qs, 0f);
                }
            }
            Drag drag = drags.get(qs);
            if (drag == null) return chain.proceed();
            if (action == MotionEvent.ACTION_MOVE && !drag.fullOnDown) {
                if (event.getY(0) < drag.startY) { clear(qs); return chain.proceed(); }
                drag.secondary = event.getY(0) > drag.startY;
            }
            if (action == MotionEvent.ACTION_MOVE && drag.fullOnDown) {
                float distance = event.getY(0) - drag.startY;
                if (distance > 0f) {
                    drag.overpull = true;
                    stopSpring(qs);
                    drag.translation = overpullTranslation(distance);
                    translation.invoke(qs, drag.translation);
                    return true;
                }
                drag.overpull = false;
                translation.invoke(qs, 0f);
            }
            // Do not let the native QS fling race the position-based rebound.
            // Running both animations caused the occasional one-frame snap.
            if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) && drag.overpull) {
                finishTracking(qs);
                rebound(qs, drag);
                return true;
            }
            Object result = chain.proceed();
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                if (drag.secondary) translation.invoke(qs, 0f);
                drags.remove(qs);
                return drag.secondary || drag.overpull ? true : result;
            }
            return drag.secondary && (Boolean) tracking.invoke(qs) ? true : result;
        }, MotionEvent.class, boolean.class, boolean.class);
        installer.hook(QS, "startQsHeaderAnimator", chain -> {
            Object qs = chain.getThisObject();
            Drag drag = drags.get(qs);
            if (!ownsGesture(qs)) return chain.proceed();
            stopSpring(qs);
            translation.invoke(qs, drag.overpull ? drag.translation : 0f);
            return null;
        }, float.class);
    }

    private void alignMini(Panels state) throws ReflectiveOperationException {
        View mini = state.mini;
        if (!state.active) {
            restoreGeometry(state);
            return;
        }
        Object layout = tileLayout.get(state.full);
        ViewGroup first = layout == null ? null : (ViewGroup) pageAt.invoke(layout, 0);
        if (first == null || first.getWidth() <= 0 || cellWidth.getInt(first) <= 0) return;
        int cw = cellWidth.getInt(first), spacing = gap.getInt(first);
        View common = state.full.getRootView();
        int left = coordinate(first, common, false) + first.getPaddingLeft() + alignOffset.getInt(first)
                - coordinate(mini, common, false);
        int top = coordinate(first, common, true) + first.getPaddingTop() - coordinate(mini, common, true);
        int right = mini.getWidth() - left - (4 * cw + 3 * spacing);
        if (left < 0 || top < 0 || right < 0) return;
        if (mini.getPaddingLeft() != left || mini.getPaddingTop() != top || mini.getPaddingRight() != right)
            mini.setPadding(left, top, right, state.bottom);
        View wrapper = find(mini, "connectivity_slider_wrapper");
        View sliders = find(mini, "slider_wrapper");
        alignSpacers(state, wrapper, spacing);
        alignSpacers(state, sliders, spacing);
    }

    private static void restoreGeometry(Panels state) {
        state.mini.setPadding(state.left, state.top, state.right, state.bottom);
        for (Map.Entry<View, Integer> entry : state.spacers.entrySet()) setWidth(entry.getKey(), entry.getValue());
    }

    private void alignSpacers(Panels state, View parent, int width) {
        if (!(parent instanceof ViewGroup group)) return;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getClass() == View.class) {
                state.spacers.putIfAbsent(child, child.getLayoutParams().width);
                setWidth(child, width);
            }
        }
    }

    private static void setWidth(View view, int width) {
        if (view.getLayoutParams().width != width) {
            view.getLayoutParams().width = width;
            view.requestLayout();
        }
    }

    private void render(Panels state) throws ReflectiveOperationException {
        Object layout = tileLayout.get(state.full);
        if (layout == null) return;
        float fullFade = clamp((state.fraction - .43f) / .57f);
        float miniFade = clamp(1f - state.fraction / .43f);
        boolean[] matched = new boolean[miniCards.length];
        View[] collapsed = new View[miniCards.length];
        for (int i = 0; i < miniCards.length; i++) collapsed[i] = (View) miniCards[i].get(state.mini);
        int count = ((Number) pageCount.invoke(layout)).intValue();
        for (int page = 0; page < count; page++) {
            ViewGroup group = (ViewGroup) pageAt.invoke(layout, page);
            if (group == null) continue;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                Object params = child.getLayoutParams();
                int card = spec.getDeclaringClass().isInstance(params) ? cardIndex((String) spec.get(params)) : -1;
                boolean fixed = state.active && page == 0 && card >= 0
                        && sameBounds(child, collapsed[card], state.full.getRootView());
                if (fixed) matched[card] = true;
                child.setAlpha(state.active ? (fixed ? 1f : fullFade) : 1f);
                // Native scaling affects the content tile, not the decorated grid cell.
                setTileScale(child, state.active && fixed ? 1f : .85f + .15f * fullFade);
            }
        }
        if (state.active) {
            state.full.setAlpha(1f);
            state.full.setVisibility(state.fraction > 0f ? View.VISIBLE : View.INVISIBLE);
            state.mini.setAlpha(1f);
            state.mini.setVisibility(state.fraction < .43f ? View.VISIBLE : View.INVISIBLE);
            for (int i = 0; i < collapsed.length; i++) if (collapsed[i] != null) {
                float alpha = matched[i] ? (state.fraction > 0f ? 0f : 1f) : miniFade;
                collapsed[i].setAlpha(alpha);
                collapsed[i].setVisibility(alpha > 0f ? View.VISIBLE : View.INVISIBLE);
                float scale = matched[i] ? 1f : .85f + .15f * miniFade;
                collapsed[i].setScaleX(scale); collapsed[i].setScaleY(scale);
            }
        } else {
            state.full.setAlpha(fullFade); state.full.setVisibility(fullFade > 0f ? View.VISIBLE : View.INVISIBLE);
            state.mini.setAlpha(miniFade); state.mini.setVisibility(miniFade > 0f ? View.VISIBLE : View.INVISIBLE);
            for (View child : collapsed) if (child != null) {
                child.setAlpha(1f); child.setVisibility(View.VISIBLE);
                child.setScaleX(.85f + .15f * miniFade); child.setScaleY(.85f + .15f * miniFade);
            }
        }
    }

    private static int cardIndex(String spec) {
        return "connectivity".equals(spec) ? 0 : "brightness".equals(spec) ? 1 : "volume".equals(spec) ? 2 : -1;
    }

    static boolean sameBounds(View full, View mini, View root) {
        return mini != null && full.getWidth() > 0 && full.getHeight() > 0
                && mini.getWidth() > 0 && mini.getHeight() > 0
                && Math.abs(coordinate(full, root, false) - coordinate(mini, root, false)) <= 1
                && Math.abs(coordinate(full, root, true) - coordinate(mini, root, true)) <= 1
                && Math.abs(full.getWidth() - mini.getWidth()) <= 1
                && Math.abs(full.getHeight() - mini.getHeight()) <= 1;
    }

    private static int coordinate(View child, View root, boolean vertical) {
        int position = 0;
        for (View node = child; node != root; ) {
            position += vertical ? node.getTop() : node.getLeft();
            if (!(node.getParent() instanceof View parent)) break;
            position -= vertical ? parent.getScrollY() : parent.getScrollX();
            node = parent;
        }
        // Both panels share the ancestor transform. Ignore gesture translations and tile scaling.
        return position;
    }

    private static float clamp(float value) { return Math.max(0f, Math.min(1f, value)); }

    private static final class Panels {
        final View full, mini;
        int left, top, right, bottom;
        final Map<View, Integer> spacers = new WeakHashMap<>();
        boolean active;
        float fraction;
        Panels(View full, View mini) {
            this.full = full; this.mini = mini;
            capturePadding();
        }
        void capturePadding() {
            left = mini.getPaddingLeft(); top = mini.getPaddingTop();
            right = mini.getPaddingRight(); bottom = mini.getPaddingBottom();
        }
    }

    static float overpullTranslation(float distance) { return Math.max(0f, distance) * .5f; }

    private void rebound(Object qs, Drag drag) {
        stopSpringQuietly(qs);
        float start = drag.translation;
        ValueAnimator animator = ValueAnimator.ofFloat(start, 0f);
        drag.animator = animator;
        animator.setDuration(280L); animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(frame -> {
            if (drags.get(qs) != drag) return;
            drag.translation = (Float) frame.getAnimatedValue();
            try { translation.invoke(qs, drag.translation); }
            catch (ReflectiveOperationException e) { log.accept("Cannot rebound fully expanded QS", e); }
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (drags.get(qs) != drag) return;
                drags.remove(qs);
                try { translation.invoke(qs, 0f); }
                catch (ReflectiveOperationException e) { log.accept("Cannot finish QS overpull", e); }
            }
        });
        animator.start();
    }

    private void clear(Object qs) throws ReflectiveOperationException {
        Drag old = drags.remove(qs);
        if (old == null) return;
        if (old.animator != null) old.animator.cancel();
        translation.invoke(qs, 0f);
    }
    private void stopSpring(Object qs) throws ReflectiveOperationException {
        Object target = plugin.invoke(qs); if (target != null) cancelSpring.invoke(target);
    }
    private void stopSpringQuietly(Object qs) {
        try { stopSpring(qs); } catch (ReflectiveOperationException e) { log.accept("Cannot cancel secondary QS spring", e); }
    }
    private void finishTracking(Object qs) throws ReflectiveOperationException {
        stopTracking.invoke(qs, false);
        conflictingGesture.setBoolean(qs, false);
        trackingPointer.setInt(qs, -1);
        VelocityTracker tracker = (VelocityTracker) velocityTracker.get(qs);
        if (tracker != null) {
            tracker.recycle();
            velocityTracker.set(qs, null);
        }
    }
    private static void setTileScale(View child, float scale) {
        if (child instanceof ViewGroup group && group.getChildCount() > 0) {
            View tile = group.getChildAt(0); tile.setScaleX(scale); tile.setScaleY(scale);
        }
    }
    private static View find(View root, String name) {
        int id = root.getResources().getIdentifier(name, "id", "com.android.systemui");
        return id == 0 ? null : root.findViewById(id);
    }
    private static final class Drag { float startY, translation; boolean fullOnDown, overpull, secondary; ValueAnimator animator; }
}
