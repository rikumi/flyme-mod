package com.rikumi.flymemod;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.graphics.Rect;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Whole-shade row reveal/dismissal; secondary QS expansion keeps its geometry animation. */
final class RowRevealHooks {
    private static final String CENTER = "com.flyme.systemui.controlcenter.phone.CenterController";
    private static final String QS = "com.flyme.systemui.qs.MzQSImpl";
    // Short row stagger within the existing opening transition, with no extra animator.
    private static final float ROW_DELAY = .22f;
    private final Consumer<Context> settings;
    private final BooleanSupplier enabled;
    private final Field separateController, fullController, miniController, controllerView;
    private final Field separateHeader, separateStatusBar, combinedHeader, combinedStatusBar;
    private final Field miniNetwork, miniSliders;
    private final Field notificationStack, shadeInteractor;
    private final Method combinedMode, flowValue, notificationTop;
    private final ThreadLocal<View> currentNotifications = new ThreadLocal<>();
    private final Class<?> tileType;
    private final Map<Object, State> states = new WeakHashMap<>();

    RowRevealHooks(ClassLoader loader, Consumer<Context> settings, BooleanSupplier enabled)
            throws ReflectiveOperationException {
        this.settings = settings;
        this.enabled = enabled;
        separateController = field(loader.loadClass(CENTER), "mQsController");
        separateHeader = field(loader.loadClass(CENTER), "mHeader");
        separateStatusBar = field(loader.loadClass(CENTER), "mQSStatusBar");
        Class<?> qs = loader.loadClass(QS);
        combinedHeader = field(qs, "mHeader");
        combinedStatusBar = field(qs, "mQSStatusBar");
        fullController = field(qs, "mMzQSPanelController");
        miniController = field(qs, "mMzQQSPanelController");
        controllerView = field(loader.loadClass("com.flyme.systemui.controlcenter.phone.MzQSPanelController"), "mView");
        Class<?> miniPanel = loader.loadClass("com.flyme.systemui.controlcenter.phone.MzQQSPanel");
        miniNetwork = field(miniPanel, "mConnectivityTilesWrapper");
        miniSliders = field(miniPanel, "mSliderWrapper");
        tileType = loader.loadClass("com.android.systemui.plugins.qs.QSTileView");
        Class<?> panel = loader.loadClass("com.android.systemui.shade.NotificationPanelViewController");
        notificationStack = field(panel, "mNotificationStackScrollLayout");
        shadeInteractor = field(panel, "mShadeInteractor");
        combinedMode = loader.loadClass("com.android.systemui.shade.domain.interactor.ShadeInteractor")
                .getMethod("isExpandToQsEnabled");
        flowValue = loader.loadClass("kotlinx.coroutines.flow.StateFlow").getMethod("getValue");
        notificationTop = notificationStack.getType().getMethod("getTopPadding");
    }

    void install(SignalHooks.Installer installer) {
        installer.hook(CENTER, "setAnimationScale", chain -> {
            Object result = chain.proceed();
            if (enabled.getAsBoolean()) {
                keepHeaderScale(chain.getThisObject(), separateHeader, separateStatusBar);
            }
            return result;
        }, float.class);
        installer.hook(QS, "setQsScale", chain -> {
            Object result = chain.proceed();
            if (enabled.getAsBoolean()) {
                keepHeaderScale(chain.getThisObject(), combinedHeader, combinedStatusBar);
            }
            return result;
        }, float.class);
        installer.hook("com.android.systemui.shade.NotificationPanelViewController", "setAnimationAlpha", chain -> {
            View previous = currentNotifications.get();
            Object panel = chain.getThisObject();
            boolean combined = enabled.getAsBoolean()
                    && Boolean.TRUE.equals(flowValue.invoke(combinedMode.invoke(shadeInteractor.get(panel))));
            currentNotifications.set(combined ? (View) notificationStack.get(panel) : null);
            try {
                Object result = chain.proceed();
                // The single pre-draw callback runs after native notification and QS writes.
                if (combined && contentProgress((Float) chain.getArg(0)) <= 0f) {
                    View notifications = currentNotifications.get();
                    if (notifications != null) notifications.setAlpha(0f);
                }
                return result;
            } finally {
                if (previous == null) currentNotifications.remove(); else currentNotifications.set(previous);
            }
        }, float.class);
        installer.hook(CENTER, "setAnimationAlpha", chain -> {
            float progress = (Float) chain.getArg(0);
            Object result = chain.proceed(new Object[]{enabled.getAsBoolean() ? revealCurve(contentProgress(progress)) : progress});
            update(chain.getThisObject(), progress, true);
            return result;
        }, float.class);
        installer.hook(QS, "setQsPanelAlpha", chain -> {
            float progress = (Float) chain.getArg(0);
            Object result = chain.proceed(new Object[]{enabled.getAsBoolean() ? revealCurve(contentProgress(progress)) : progress});
            update(chain.getThisObject(), progress, false);
            return result;
        }, float.class);
    }

    private static void keepHeaderScale(Object owner, Field header, Field statusBar)
            throws IllegalAccessException {
        for (Field field : new Field[]{header, statusBar}) {
            View view = (View) field.get(owner);
            if (view == null) continue;
            view.setScaleX(1f);
            view.setScaleY(1f);
        }
    }

    private void update(Object owner, float progress, boolean separate) throws ReflectiveOperationException {
        Object controller = (separate ? separateController : fullController).get(owner);
        if (controller == null) return;
        View root = (View) controllerView.get(controller);
        State state = states.computeIfAbsent(owner, ignored -> new State());
        if (!state.animating) settings.accept(root.getContext());
        if (!enabled.getAsBoolean()) {
            restore(state);
            return;
        }
        state.progress = contentProgress(progress);
        if (state.progress == 0f || state.progress >= 1f) {
            restore(state);
            return;
        }
        // The same progress mapping runs backwards during dismissal and gesture reversal.
        // It introduces no new duration limit: alpha still reaches zero with the shade.
        state.animating = true;
        state.fullRoot = root;
        watch(root, state);
        if (!state.initialized) collect(root, root, state);
        if (!separate) {
            Object mini = miniController.get(owner);
            if (mini != null) {
                View miniRoot = (View) controllerView.get(mini);
                state.miniRoot = miniRoot;
                watch(miniRoot, state);
                // Animate the containers, including the sliders' shared background, exactly once.
                if (!state.initialized) {
                    collectTarget((View) miniNetwork.get(miniRoot), miniRoot, state);
                    collectTarget((View) miniSliders.get(miniRoot), miniRoot, state);
                }
            }
        }
        View notifications = currentNotifications.get();
        if (!separate && notifications != null && state.notifications == null) {
            state.notifications = new NotificationRegion(notifications,
                    ((Number) notificationTop.invoke(notifications)).floatValue());
            watch(notifications, state);
        }
        state.initialized = !state.tiles.isEmpty();
    }

    private void watch(View root, State state) {
        if (!state.layouts.containsKey(root)) {
            View.OnLayoutChangeListener layout = (view, l, t, r, b, ol, ot, or, ob) -> state.geometryDirty = true;
            root.addOnLayoutChangeListener(layout);
            state.layouts.put(root, layout);
        }
        // All panel children share a ViewTreeObserver. Register once on their common root.
        root = root.getRootView();
        if (state.draws.containsKey(root)) return;
        ViewTreeObserver.OnPreDrawListener listener = () -> {
            // Secondary expansion can overwrite the network wrapper after the alpha hook.
            // Reapply only while the whole shade is transitioning, after all frame updates.
            if (state.animating && enabled.getAsBoolean()) render(state);
            else if (!enabled.getAsBoolean()) restore(state);
            return true;
        };
        state.draws.put(root, listener);
        root.getViewTreeObserver().addOnPreDrawListener(listener);
    }

    static float revealCurve(float progress) {
        float remaining = 1f - Math.max(0f, Math.min(1f, progress));
        // Visible progress uses cubic ease-out. Reversing it gives disappearance t^3 (ease-in).
        return 1f - remaining * remaining * remaining;
    }

    private static float contentProgress(float progress) {
        // Give the backdrop the first short part of the reveal; reverse on dismissal.
        return Math.max(0f, Math.min(1f, (progress - .18f) / .82f));
    }

    private void collect(View view, View root, State state) {
        if (tileType.isInstance(view)) {
            collectTarget(view, root, state);
            return;
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), root, state);
        }
    }

    private void collectTarget(View view, View root, State state) {
        if (view == null || view.getWidth() <= 0 || view.getHeight() <= 0) return;
        if (state.tiles.containsKey(view)) return;
        state.tiles.computeIfAbsent(view, ignored -> new Tile(view, root));
        state.geometryDirty = true;
        // Child/card widths can change without changing the outer panel's bounds.
        watch(view, state);
        for (View parent = view.getParent() instanceof View p ? p : null;
             parent != null; parent = parent.getParent() instanceof View p ? p : null) {
            if (parent instanceof ViewGroup group) {
                state.clips.computeIfAbsent(group, Clip::new);
                group.setClipChildren(false);
                group.setClipToPadding(false);
                group.setClipToOutline(false);
                group.setClipBounds(null);
            }
            if (parent == root) break;
        }
    }

    private void prepare(State state) {
        // A first opening may capture views before measure. Retry only on layout invalidation.
        if (state.fullRoot != null) collect(state.fullRoot, state.fullRoot, state);
        if (state.miniRoot != null) {
            try {
                collectTarget((View) miniNetwork.get(state.miniRoot), state.miniRoot, state);
                collectTarget((View) miniSliders.get(state.miniRoot), state.miniRoot, state);
            } catch (IllegalAccessException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
        state.pages.clear();
        Map<View, List<Tile>> pages = new WeakHashMap<>();
        for (Tile tile : state.tiles.values()) {
            pages.computeIfAbsent(tile.page, ignored -> new ArrayList<>()).add(tile);
        }
        for (Map.Entry<View, List<Tile>> entry : pages.entrySet()) {
            List<Tile> tiles = entry.getValue();
            tiles.sort(Comparator.comparingDouble(tile -> coordinate(tile.view, tile.page, false)));
            int rowCount = 0;
            float countedTop = Float.NEGATIVE_INFINITY;
            for (Tile tile : tiles) {
                float top = coordinate(tile.view, tile.page, false);
                if (Math.abs(top - countedTop) > 1f) {
                    rowCount++;
                    countedTop = top;
                }
            }
            // Delay depends on rows, not tile count; reserve at least 40% for the last row.
            float lastDelay = rowDelay(rowCount - 1, rowCount);
            float lastGap = rowCount > 1 ? lastDelay - rowDelay(rowCount - 2, rowCount) : ROW_DELAY;
            state.pages.add(new Page(entry.getKey(), tiles, lastDelay, lastGap));
            int row = -1;
            float previousTop = Float.NEGATIVE_INFINITY;
            for (Tile tile : tiles) {
                float top = coordinate(tile.view, tile.page, false);
                if (Math.abs(top - previousTop) > 1f) {
                    row++;
                    previousTop = top;
                }
                tile.delay = rowDelay(row, rowCount);
                tile.animationLeft = coordinate(tile.view, tile.page, true);
                tile.animationPivotY = previousTop - top;
            }
            // The collapsed row has its own padded wrapper. Use the actual row span,
            // rather than assuming its center is half the full-screen panel width.
            for (int first = 0; first < tiles.size();) {
                int end = first + 1;
                float delay = tiles.get(first).delay;
                float left = tiles.get(first).animationLeft;
                float right = left + tiles.get(first).view.getWidth();
                while (end < tiles.size() && tiles.get(end).delay == delay) {
                    Tile tile = tiles.get(end++);
                    left = Math.min(left, tile.animationLeft);
                    right = Math.max(right, tile.animationLeft + tile.view.getWidth());
                }
                float rowTop = coordinate(tiles.get(first).view, tiles.get(first).page, false);
                for (int i = 0; i < tiles.size(); i++) {
                    Tile occupied = tiles.get(i);
                    float top = coordinate(occupied.view, occupied.page, false);
                    if (top <= rowTop + 1f && top + occupied.view.getHeight() > rowTop + 1f) {
                        left = Math.min(left, occupied.animationLeft);
                        right = Math.max(right, occupied.animationLeft + occupied.view.getWidth());
                    }
                }
                float center = (left + right) / 2f;
                for (int i = first; i < end; i++) {
                    Tile tile = tiles.get(i);
                    tile.animationPivotX = center - tile.animationLeft;
                }
                first = end;
            }
        }
        state.geometryDirty = false;
    }

    private static float rowDelay(int row, int rowCount) {
        if (row <= 0 || rowCount <= 1) return 0f;
        float total = Math.min(.6f, (rowCount - 1) * ROW_DELAY);
        // Concave cumulative delay: larger early gaps, progressively shorter later gaps.
        return total * (float) Math.pow(row / (float) (rowCount - 1), .9f);
    }

    private void render(State state) {
        if (state.geometryDirty) prepare(state);
        float lastVisibleDelay = 0f;
        float notificationGap = ROW_DELAY;
        float parentAlpha = revealCurve(state.progress);
        for (int p = 0; p < state.pages.size(); p++) {
            Page page = state.pages.get(p);
            // Hidden expanded/other-page tiles need no property writes during collapsed reveal.
            if (!page.view.isShown()) continue;
            if (page.lastDelay >= lastVisibleDelay) {
                lastVisibleDelay = page.lastDelay;
                notificationGap = page.lastGap * .9f;
            }
            for (int i = 0; i < page.tiles.size(); i++) {
                Tile tile = page.tiles.get(i);
                float amount = revealCurve((state.progress - tile.delay) / (1f - tile.delay));
                tile.view.setPivotX(tile.animationPivotX);
                tile.view.setPivotY(tile.animationPivotY);
                tile.view.setScaleX(.85f + .15f * amount);
                tile.view.setScaleY(.85f + .15f * amount);
                tile.view.setAlpha(tile.alpha * (parentAlpha > 0f ? amount / parentAlpha : 0f));
            }
        }
        if (state.notifications != null) {
            NotificationRegion region = state.notifications;
            float delay = Math.min(.8f, lastVisibleDelay + notificationGap);
            float amount = revealCurve((state.progress - delay) / (1f - delay));
            region.view.setPivotX(region.view.getWidth() / 2f);
            region.view.setPivotY(region.top);
            float scale = .85f + .15f * amount;
            region.view.setScaleX(region.scaleX * scale);
            region.view.setScaleY(region.scaleY * scale);
            region.view.setAlpha(amount);
        }
    }

    private static float coordinate(View view, View page, boolean horizontal) {
        float value = 0f;
        for (View current = view; current != page;
             current = current.getParent() instanceof View p ? p : null) {
            if (current == null) break;
            value += horizontal ? current.getLeft() : current.getTop();
        }
        return value;
    }

    private static void restore(State state) {
        for (Map.Entry<View, View.OnLayoutChangeListener> entry : state.layouts.entrySet()) {
            entry.getKey().removeOnLayoutChangeListener(entry.getValue());
        }
        state.layouts.clear();
        if (state.notifications != null) {
            NotificationRegion region = state.notifications;
            region.view.setScaleX(region.scaleX);
            region.view.setScaleY(region.scaleY);
            region.view.setAlpha(revealCurve(state.progress));
            if (region.explicitPivot) {
                region.view.setPivotX(region.pivotX);
                region.view.setPivotY(region.pivotY);
            } else region.view.resetPivot();
            state.notifications = null;
        }
        for (Map.Entry<View, ViewTreeObserver.OnPreDrawListener> entry : state.draws.entrySet()) {
            ViewTreeObserver observer = entry.getKey().getViewTreeObserver();
            if (observer.isAlive()) observer.removeOnPreDrawListener(entry.getValue());
        }
        state.draws.clear();
        for (Tile tile : state.tiles.values()) {
            tile.view.setAlpha(tile.alpha);
            // Captured scale can be the native opening animator's initial .8 value.
            // Returning that value at alpha=1 would leave the shade permanently shrunk.
            tile.view.setScaleX(1f);
            tile.view.setScaleY(1f);
            if (tile.explicitPivot) {
                tile.view.setPivotX(tile.pivotX);
                tile.view.setPivotY(tile.pivotY);
            } else tile.view.resetPivot();
        }
        for (Map.Entry<ViewGroup, Clip> entry : state.clips.entrySet()) {
            entry.getKey().setClipChildren(entry.getValue().children);
            entry.getKey().setClipToPadding(entry.getValue().padding);
            entry.getKey().setClipToOutline(entry.getValue().outline);
            entry.getKey().setClipBounds(entry.getValue().bounds);
        }
        state.tiles.clear();
        state.clips.clear();
        state.pages.clear();
        state.fullRoot = null;
        state.miniRoot = null;
        state.initialized = false;
        state.geometryDirty = true;
        state.animating = false;
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field result = current.getDeclaredField(name);
                result.setAccessible(true);
                return result;
            } catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    private static final class State {
        boolean animating;
        boolean initialized, geometryDirty = true;
        float progress;
        View fullRoot, miniRoot;
        NotificationRegion notifications;
        final Map<View, Tile> tiles = new WeakHashMap<>();
        final Map<ViewGroup, Clip> clips = new WeakHashMap<>();
        final Map<View, ViewTreeObserver.OnPreDrawListener> draws = new WeakHashMap<>();
        final Map<View, View.OnLayoutChangeListener> layouts = new WeakHashMap<>();
        final List<Page> pages = new ArrayList<>();
    }

    private static final class Page {
        final View view;
        final List<Tile> tiles;
        final float lastDelay, lastGap;
        Page(View view, List<Tile> tiles, float lastDelay, float lastGap) {
            this.view = view;
            this.tiles = tiles;
            this.lastDelay = lastDelay;
            this.lastGap = lastGap;
        }
    }

    private static final class NotificationRegion {
        final View view;
        final float top, scaleX, scaleY, pivotX, pivotY;
        final boolean explicitPivot;
        NotificationRegion(View view, float top) {
            this.view = view;
            this.top = top;
            scaleX = view.getScaleX();
            scaleY = view.getScaleY();
            pivotX = view.getPivotX();
            pivotY = view.getPivotY();
            explicitPivot = view.isPivotSet();
        }
    }

    private static final class Tile {
        float delay, animationLeft, animationPivotX, animationPivotY;
        final View view, page;
        final float alpha, pivotX, pivotY;
        final boolean explicitPivot;
        Tile(View view, View root) {
            this.view = view;
            View page = root;
            for (View current = view; current != root && current != null;
                 current = current.getParent() instanceof View p ? p : null) {
                if (current.getClass().getName().equals("com.flyme.systemui.controlcenter.qs.UnifiedTileLayout")) {
                    page = current;
                    break;
                }
            }
            this.page = page;
            alpha = view.getAlpha();
            pivotX = view.getPivotX();
            pivotY = view.getPivotY();
            explicitPivot = view.isPivotSet();
        }
    }

    private static final class Clip {
        final boolean children, padding, outline;
        final Rect bounds;
        Clip(ViewGroup view) {
            children = view.getClipChildren();
            padding = view.getClipToPadding();
            outline = view.getClipToOutline();
            bounds = view.getClipBounds();
        }
    }
}
