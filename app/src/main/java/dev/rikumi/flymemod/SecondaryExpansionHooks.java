package dev.rikumi.flymemod;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import java.lang.ref.WeakReference;
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
    private BooleanSupplier slowRebound = () -> false;
    private Consumer<Object> claimVisuals = ignored -> {};
    private final BiConsumer<String, Throwable> log;
    private final Field shade, shadeFraction, root, tileLayout, implShade, spec, cellWidth, gap, alignOffset;
    private final Field conflictingGesture, trackingPointer, velocityTracker;
    private final Field[] miniCards;
    private final Method mode, flow, container, fullyExpanded, tracking, stopTracking, translation, plugin, cancelSpring;
    private final Method pageAt, pageCount, expansionFraction, handleTouch, customizing;
    private final Map<View, WeakReference<Object>> touchOwners = new WeakHashMap<>();
    private final ThreadLocal<Boolean> routingCancel = new ThreadLocal<>();
    private final Map<View, Pivot> pivots = new WeakHashMap<>();
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
        expansionFraction = qs.getMethod("computeExpansionFraction");
        handleTouch = qs.getMethod("handleTouch", MotionEvent.class, boolean.class, boolean.class);
        customizing = qs.getMethod("isCustomizing");
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

    boolean allowsTouch(Object qs) { return ownsGesture(qs); }

    void install(SignalHooks.Installer installer) {
        installer.hook("com.android.systemui.shade.NotificationPanelView", "dispatchTouchEvent", chain -> {
            View panel = (View) chain.getThisObject();
            WeakReference<Object> reference = touchOwners.get(panel);
            Object qs = reference == null ? null : reference.get();
            MotionEvent event = (MotionEvent) chain.getArg(0);
            if (qs != null && (Boolean) customizing.invoke(qs)) {
                clear(qs);
                return chain.proceed();
            }
            if (qs == null || !enabled.getAsBoolean()
                    || !Boolean.TRUE.equals(flow.invoke(mode.invoke(shade.get(qs))))) return chain.proceed();
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) clear(qs);
            if (action == MotionEvent.ACTION_DOWN && shadeFraction.getFloat(qs) >= .99f && isAllTilesExpanded(qs)) {
                // Observe DOWN before a clickable tile consumes the rest of the stream.
                Drag drag = new Drag();
                drag.fullOnDown = true;
                drag.startY = event.getY(); drag.startX = event.getX();
                drags.put(qs, drag);
            }
            Drag drag = drags.get(qs);
            if (drag == null || !drag.fullOnDown) return chain.proceed();
            float distance = event.getY() - drag.startY;
            if (action == MotionEvent.ACTION_MOVE && (drag.overpull
                    || (distance > ViewConfiguration.get(panel.getContext()).getScaledTouchSlop()
                    && distance > Math.abs(event.getX() - drag.startX)))) {
                if (!drag.overpull) {
                    drag.overpull = true;
                    MotionEvent cancel = MotionEvent.obtain(event);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    routingCancel.set(true);
                    try { chain.proceed(new Object[]{cancel}); }
                    finally { routingCancel.remove(); cancel.recycle(); }
                }
                return handleTouch.invoke(qs, event, false, false);
            }
            if (drag.overpull && (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)) {
                return handleTouch.invoke(qs, event, false, false);
            }
            return chain.proceed();
        }, MotionEvent.class);
        installer.hook(IMPL, "setQsExpansion", chain -> {
            Object result = chain.proceed();
            Object owner = chain.getThisObject();
            View view = (View) root.get(owner);
            if (view == null) return result;
            settings.accept(view.getContext());
            Panels state = panels.get(owner);
            View full = state != null && state.full.getRootView() == view.getRootView()
                    ? state.full : find(view, "mz_quick_settings_panel");
            View mini = state != null && state.mini.getRootView() == view.getRootView()
                    ? state.mini : find(view, "mz_quick_settings_panel_mini");
            if (full == null || mini == null) return result;
            boolean active = enabled.getAsBoolean() && Boolean.TRUE.equals(flow.invoke(mode.invoke(implShade.get(owner))));
            float f = ((Number) chain.getArg(0)).floatValue();
            if (Float.isNaN(f)) return result;
            if (active && (state == null || state.mini != mini || state.full != full)) {
                if (state != null) restoreClips(state);
                state = new Panels(full, mini, miniCards);
                panels.put(owner, state); miniPanels.put(mini, state);
            }
            if (state == null) return result;
            state.fraction = f; state.active = active;
            if (state.geometryDirty || !active) {
                state.geometryDirty = false;
                alignMini(state);
            }
            render(state);
            if (!active) { panels.remove(owner); miniPanels.remove(mini); }
            return result;
        }, float.class, float.class, float.class, float.class);
        // Re-evaluate after layout, including reordered tiles with unchanged panel bounds.
        installer.hook("com.flyme.systemui.controlcenter.qs.UnifiedTileLayout", "onLayout", chain -> {
            Object result = chain.proceed();
            for (Pivot pivot : pivots.values()) pivot.calculated = false;
            for (Panels state : panels.values()) { alignMini(state); render(state); }
            return result;
        }, boolean.class, int.class, int.class, int.class, int.class);
        installer.hook("android.widget.LinearLayout", "onLayout", chain -> {
            Object result = chain.proceed();
            Panels state = miniPanels.get(chain.getThisObject());
            if (state != null) {
                for (Pivot pivot : pivots.values()) pivot.calculated = false;
                alignMini(state); render(state);
            }
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
            if (Boolean.TRUE.equals(routingCancel.get())) return chain.proceed();
            Object qs = chain.getThisObject();
            if ((Boolean) customizing.invoke(qs)) { clear(qs); return chain.proceed(); }
            MotionEvent event = (MotionEvent) chain.getArg(0);
            View view = (View) container.invoke(qs);
            if (view == null) return chain.proceed();
            for (View current = view; current != null;
                 current = current.getParent() instanceof View parent ? parent : null) {
                if (current.getClass().getName().equals("com.android.systemui.shade.NotificationPanelView")) {
                    touchOwners.put(current, new WeakReference<>(qs));
                    break;
                }
            }
            settings.accept(view.getContext());
            if (!enabled.getAsBoolean() || !Boolean.TRUE.equals(flow.invoke(mode.invoke(shade.get(qs))))) {
                clear(qs); return chain.proceed();
            }
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                clear(qs);
                // QS can remain fully expanded while the shade itself is closed (especially
                // with no notifications). That DOWN belongs to opening the whole shade.
                if ((Boolean) chain.getArg(1) || shadeFraction.getFloat(qs) <= 0f) return chain.proceed();
                Drag drag = new Drag();
                drag.fullOnDown = shadeFraction.getFloat(qs) >= .99f && isAllTilesExpanded(qs);
                // In merged shade mode, the all-tiles QS can be fully expanded
                // while the overall shade fraction is well below 1.0.
                if (!drag.fullOnDown && ((Boolean) chain.getArg(1) || shadeFraction.getFloat(qs) < .99f)) {
                    return chain.proceed();
                }
                drag.startY = event.getY(0);
                drag.startX = event.getX(0);
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
                if (drag.secondary) claim(qs, drag);
            }
            if (action == MotionEvent.ACTION_MOVE && drag.fullOnDown) {
                float distance = event.getY(0) - drag.startY;
                if (distance > 0f) {
                    drag.overpull = true;
                    claim(qs, drag);
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
            if ((Boolean) customizing.invoke(qs)) { clear(qs); return chain.proceed(); }
            Drag drag = drags.get(qs);
            if (!ownsGesture(qs)) return chain.proceed();
            stopSpring(qs);
            translation.invoke(qs, drag.overpull ? drag.translation : 0f);
            return null;
        }, float.class);
        installer.hook("com.android.systemui.shade.NotificationPanelViewController", "onHeightUpdated", chain -> {
            Object result = chain.proceed();
            Object panel = chain.getThisObject();
            if (panel.getClass().getField("mExpandedFraction").getFloat(panel) <= 0f) {
                clear(panel.getClass().getField("mQsController").get(panel));
            }
            return result;
        }, float.class);
    }

    private boolean isAllTilesExpanded(Object qs) throws ReflectiveOperationException {
        return (Boolean) fullyExpanded.invoke(qs)
                || ((Number) expansionFraction.invoke(qs)).floatValue() >= .99f;
    }

    private void alignMini(Panels state) throws ReflectiveOperationException {
        View mini = state.mini;
        if (!state.active) {
            restoreGeometry(state);
            return;
        }
        Object layout = tileLayout.get(state.full);
        ViewGroup first = layout == null ? null : (ViewGroup) pageAt.invoke(layout, 0);
        if (first == null || first.getWidth() <= 0 || cellWidth.getInt(first) <= 0) { state.geometryDirty = true; return; }
        int cw = cellWidth.getInt(first), spacing = gap.getInt(first);
        View common = state.full.getRootView();
        int left = coordinate(first, common, false) + first.getPaddingLeft() + alignOffset.getInt(first)
                - coordinate(mini, common, false);
        int top = coordinate(first, common, true) + first.getPaddingTop() - coordinate(mini, common, true);
        int right = mini.getWidth() - left - (4 * cw + 3 * spacing);
        if (left < 0 || top < 0 || right < 0) { state.geometryDirty = true; return; }
        if (mini.getPaddingLeft() != left || mini.getPaddingTop() != top || mini.getPaddingRight() != right)
            mini.setPadding(left, top, right, state.bottom);
        alignSpacers(state, state.wrapper, spacing);
        alignSpacers(state, state.sliders, spacing);
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
        boolean transitioning = state.active && state.fraction > 0f && state.fraction < 1f;
        if (!transitioning) restoreClips(state);
        boolean[] matched = state.matched;
        java.util.Arrays.fill(matched, false);
        View[] collapsed = state.collapsed;
        // During the initial merged-shade reveal QS expansion remains zero.
        // Its hidden all-tiles panel does not need a traversal on every height frame.
        int count = state.active && state.fraction <= 0f ? 0 : ((Number) pageCount.invoke(layout)).intValue();
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
                float tileAlpha = state.active ? (fixed ? 1f : fullFade) : 1f;
                // Native scaling affects the content tile, not the decorated grid cell.
                if (child instanceof ViewGroup cell && cell.getChildCount() > 0) {
                    View tile = cell.getChildAt(0);
                    // Fading the stationary cell creates an alpha layer bounded by its
                    // original rectangle. The row pivot moves the tile outside that rectangle,
                    // so fade the transformed tile itself instead of its untransformed parent.
                    child.setAlpha(1f);
                    tile.setAlpha(tileAlpha);
                    applyRowPivot(tile, child, group, transitioning && !fixed && fullFade < 1f, state);
                } else child.setAlpha(tileAlpha);
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
                applyRowPivot(collapsed[i], collapsed[i], state.mini, transitioning && !matched[i] && miniFade < 1f, state);
                collapsed[i].setScaleX(scale); collapsed[i].setScaleY(scale);
            }
        } else {
            state.full.setAlpha(fullFade); state.full.setVisibility(fullFade > 0f ? View.VISIBLE : View.INVISIBLE);
            state.mini.setAlpha(miniFade); state.mini.setVisibility(miniFade > 0f ? View.VISIBLE : View.INVISIBLE);
            for (View child : collapsed) if (child != null) {
                restorePivot(child);
                child.setAlpha(1f); child.setVisibility(View.VISIBLE);
                child.setScaleX(.85f + .15f * miniFade); child.setScaleY(.85f + .15f * miniFade);
            }
        }
    }

    private void applyRowPivot(View tile, View cell, View rowRoot, boolean active, Panels state) {
        if (!active) { restorePivot(tile); return; }
        for (View current = tile.getParent() instanceof View parent ? parent : null;
             current != null; current = current.getParent() instanceof View parent ? parent : null) {
            if (current instanceof ViewGroup group) {
                state.clips.computeIfAbsent(group, Clip::new);
                releaseClip(group);
            }
            // The outer notification/QS container also sets its own clip bounds on layout.
            if (current.getClass().getName().equals("com.android.systemui.shade.NotificationPanelView")) break;
        }
        if (state.clipListener == null) {
            state.clipObserver = state.full.getViewTreeObserver();
            state.clipListener = () -> {
                // Native layout and the primary reveal animation can overwrite these flags
                // after setQsExpansion. Reassert only the cached ancestor chain before draw.
                for (ViewGroup group : state.clips.keySet()) releaseClip(group);
                return true;
            };
            state.clipObserver.addOnPreDrawListener(state.clipListener);
        }
        Pivot original = pivots.computeIfAbsent(tile, Pivot::new);
        if (original.calculated) {
            tile.setPivotX(original.targetX); tile.setPivotY(original.targetY);
            return;
        }
        float left = Float.POSITIVE_INFINITY, right = Float.NEGATIVE_INFINITY;
        if (rowRoot instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                View sibling = group.getChildAt(i);
                if (sibling.getVisibility() == View.GONE || sibling.getTop() > cell.getTop() + 1
                        || sibling.getBottom() <= cell.getTop() + 1) continue;
                left = Math.min(left, sibling.getLeft());
                right = Math.max(right, sibling.getRight());
            }
        }
        float center = Float.isFinite(left) ? (left + right) / 2f : rowRoot.getWidth() / 2f;
        original.targetX = center - coordinate(tile, rowRoot, false);
        original.targetY = coordinate(cell, rowRoot, true) - coordinate(tile, rowRoot, true);
        original.calculated = true;
        tile.setPivotX(original.targetX); tile.setPivotY(original.targetY);
    }

    private static void restoreClips(Panels state) {
        if (state.clipListener != null) {
            ViewTreeObserver observer = state.clipObserver != null && state.clipObserver.isAlive()
                    ? state.clipObserver : state.full.getViewTreeObserver();
            if (observer.isAlive()) observer.removeOnPreDrawListener(state.clipListener);
            state.clipListener = null;
            state.clipObserver = null;
        }
        for (Map.Entry<ViewGroup, Clip> entry : state.clips.entrySet()) {
            ViewGroup view = entry.getKey(); Clip clip = entry.getValue();
            view.setClipChildren(clip.children); view.setClipToPadding(clip.padding);
            view.setClipToOutline(clip.outline); view.setClipBounds(clip.bounds);
        }
        state.clips.clear();
    }

    private static void releaseClip(ViewGroup group) {
        if (group.getClipChildren()) group.setClipChildren(false);
        if (group.getClipToPadding()) group.setClipToPadding(false);
        if (group.getClipToOutline()) group.setClipToOutline(false);
        if (group.getClipBounds() != null) group.setClipBounds(null);
    }

    private static final class Clip {
        final boolean children, padding, outline;
        final Rect bounds;
        Clip(ViewGroup view) {
            children = view.getClipChildren(); padding = view.getClipToPadding();
            outline = view.getClipToOutline(); bounds = view.getClipBounds();
        }
    }

    private void restorePivot(View view) {
        Pivot original = pivots.remove(view);
        if (original == null) return;
        if (original.explicit) { view.setPivotX(original.x); view.setPivotY(original.y); }
        else view.resetPivot();
    }

    private static final class Pivot {
        final float x, y;
        final boolean explicit;
        boolean calculated;
        float targetX, targetY;
        Pivot(View view) { x = view.getPivotX(); y = view.getPivotY(); explicit = view.isPivotSet(); }
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
        final View full, mini, wrapper, sliders;
        final View[] collapsed;
        final boolean[] matched;
        boolean geometryDirty = true;
        int left, top, right, bottom;
        final Map<View, Integer> spacers = new WeakHashMap<>();
        final Map<ViewGroup, Clip> clips = new WeakHashMap<>();
        ViewTreeObserver clipObserver;
        ViewTreeObserver.OnPreDrawListener clipListener;
        boolean active;
        float fraction;
        Panels(View full, View mini, Field[] cards) throws IllegalAccessException {
            this.full = full; this.mini = mini;
            wrapper = find(mini, "connectivity_slider_wrapper");
            sliders = find(mini, "slider_wrapper");
            collapsed = new View[cards.length];
            matched = new boolean[cards.length];
            for (int i = 0; i < cards.length; i++) collapsed[i] = (View) cards[i].get(mini);
            capturePadding();
        }
        void capturePadding() {
            left = mini.getPaddingLeft(); top = mini.getPaddingTop();
            right = mini.getPaddingRight(); bottom = mini.getPaddingBottom();
        }
    }

    static float overpullTranslation(float distance) { return Math.max(0f, distance) * .5f / .54f; }

    void setSlowRebound(BooleanSupplier enabled) { slowRebound = enabled; }

    void setVisualOwner(Consumer<Object> claimVisuals) { this.claimVisuals = claimVisuals; }

    private void claim(Object qs, Drag drag) {
        if (drag.claimed) return;
        drag.claimed = true;
        claimVisuals.accept(qs);
    }

    private void rebound(Object qs, Drag drag) {
        stopSpringQuietly(qs);
        float start = drag.translation;
        ValueAnimator animator = ValueAnimator.ofFloat(start, 0f);
        drag.animator = animator;
        animator.setDuration(ReboundTimingHooks.duration(280L, slowRebound.getAsBoolean()));
        animator.setInterpolator(new DecelerateInterpolator());
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
        if (old.claimed || old.overpull || old.secondary) {
            stopSpring(qs);
            finishTracking(qs);
            translation.invoke(qs, 0f);
        }
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
    private static final class Drag { float startX, startY, translation; boolean fullOnDown, overpull, secondary, claimed; ValueAnimator animator; }
}
