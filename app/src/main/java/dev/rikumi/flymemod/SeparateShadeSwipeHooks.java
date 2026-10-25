package dev.rikumi.flymemod;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.LinearInterpolator;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

final class SeparateShadeSwipeHooks {
    private static final String PANEL = "com.android.systemui.shade.NotificationPanelViewController";
    private static final String CENTER = "com.flyme.systemui.controlcenter.phone.CenterController";
    private static final String ROOT = "com.android.systemui.shade.NotificationShadeWindowView";
    private final Consumer<Context> settings;
    private final BooleanSupplier enabled;
    private final BiConsumer<String, Throwable> log;
    private final WeakHashMap<View, WeakReference<Object>> panels = new WeakHashMap<>();
    private final ThreadLocal<Boolean> bypass = new ThreadLocal<>();
    private final Field panelView, panelCenter, sharedContainer, shadeInteractor, barState, notificationStatusBar;
    private final Field centerView, centerBehind, centerStatusBar, editing, disabled, deviceCenter;
    private final Field panelFraction, centerFraction, panelInstant, centerInstant, surface, dimColor;
    private final Method combined, flowValue, isKeyguardShowing, isDeviceCenterShowing;
    private final Method panelExpand, centerExpand, panelCollapse, centerCollapse;
    private final Method panelSetFraction, centerSetFraction, panelAlpha, centerAlpha, centerScale;
    private final Method scrimAlpha;
    private final ClassLoader loader;
    private final SeparateShadeSwipeBackdrop backdrop;
    private final Class<?> notificationBackground;
    private final Method notificationAlphaChanged;
    private final Class<?> pagesType;
    private final Field currentPage, pagesPager, pagesDragging, pagesSwitching, pagesRecycler, edgeSpring;
    private final Method pagerScrollState, edgeFactory, cancelEdgeSpring;
    private State state;
    private Candidate candidate;
    private boolean drainTouch;
    private BiConsumer<Object, Boolean> contentPose = (owner, visible) -> {};

    void setContentPose(BiConsumer<Object, Boolean> pose) { contentPose = pose; }
    void setBlurRadiusOverride(IntSupplier radius) { backdrop.setBlurRadiusOverride(radius); }
    boolean drawsCenterBackdrop(View view) {
        return state != null && state.behind == view || backdrop.hasPendingRoot(view.getRootView());
    }

    SeparateShadeSwipeHooks(ClassLoader loader, Consumer<Context> settings, BooleanSupplier enabled,
                           BiConsumer<String, Throwable> log) throws ReflectiveOperationException {
        this.loader = loader; this.settings = settings; this.enabled = enabled; this.log = log;
        backdrop = new SeparateShadeSwipeBackdrop(loader);
        notificationBackground = loader.loadClass("com.android.systemui.statusbar.notification.row.NotificationBackgroundView");
        notificationAlphaChanged = notificationBackground.getDeclaredMethod("onFlymeAlphaChanged", View.class);
        loader.loadClass(CENTER).getDeclaredMethod("shouldHideBlurForControlCenter");
        pagesType = loader.loadClass("com.flyme.systemui.controlcenter.qs.PagedUnifiedTileLayout");
        currentPage = field(pagesType, "mCurrentPage"); pagesPager = field(pagesType, "mViewPager");
        pagesDragging = field(pagesType, "mIsDragging"); pagesSwitching = field(pagesType, "mIsSwitching");
        pagerScrollState = pagesPager.getType().getMethod("getScrollState");
        pagesRecycler = field(pagesType, "mRecyclerView");
        edgeFactory = pagesRecycler.getType().getMethod("getEdgeEffectFactory");
        edgeSpring = field(loader.loadClass("com.flyme.systemui.controlcenter.qs.PagedUnifiedTileLayout$2$1$1"), "$spring");
        cancelEdgeSpring = edgeSpring.getType().getMethod("cancel");
        Class<?> panel = loader.loadClass(PANEL), center = loader.loadClass(CENTER);
        panelView = field(panel, "mView"); panelCenter = field(panel, "mCenterController");
        sharedContainer = field(panel, "mSharedNotificationContainer");
        shadeInteractor = field(panel, "mShadeInteractor"); barState = field(panel, "mBarState");
        panelFraction = field(panel, "mExpandedFraction"); centerFraction = field(center, "mExpandedFraction");
        panelInstant = field(panel, "mInstantExpanding"); centerInstant = field(center, "mInstantExpanding");
        centerView = field(center, "mControlCenterPanelView");
        centerStatusBar = field(center, "mQSStatusBar");
        notificationStatusBar = field(panel, "mKeyguardStatusBar");
        centerBehind = field(center, "mBehindView"); disabled = field(center, "mQsDisabled");
        editing = field(center, "mQSPanelStateInteractor"); deviceCenter = field(center, "mDeviceCenterInteractor");
        surface = field(panel, "mBackgroundSurface"); dimColor = field(panel, "mDimColor");
        combined = loader.loadClass("com.android.systemui.shade.domain.interactor.ShadeInteractor")
                .getMethod("isClassicsMode");
        flowValue = loader.loadClass("kotlinx.coroutines.flow.StateFlow").getMethod("getValue");
        isKeyguardShowing = method(panel, "isKeyguardShowing");
        isDeviceCenterShowing = deviceCenter.getType().getMethod("isShowingDeviceCenter");
        panelExpand = method(panel, "expand", boolean.class); centerExpand = method(center, "expandPanel", boolean.class);
        panelCollapse = method(panel, "instantCollapsePanel"); centerCollapse = method(center, "instantCollapse");
        panelSetFraction = method(panel, "setExpandedFraction", float.class);
        centerSetFraction = method(center, "setExpandedFraction", float.class);
        panelAlpha = method(panel, "setAnimationAlpha", float.class); centerAlpha = method(center, "setAnimationAlpha", float.class);
        centerScale = method(center, "setAnimationScale", float.class);
        scrimAlpha = loader.loadClass("com.android.systemui.scrim.ScrimView").getMethod("setViewAlpha", float.class);
        method(panel, "canExpandCenterControlPanel", MotionEvent.class);
        method(panel, "dispatchEventToControlCenter", MotionEvent.class);
        Class<?> touchHandler = loader.loadClass(PANEL + "$TouchHandler");
        method(touchHandler, "onInterceptTouchEvent", MotionEvent.class);
        method(touchHandler, "onTouch", View.class, MotionEvent.class);
        // Resolve lifecycle/animation members before installing any hooks.
        for (Class<?> type : new Class<?>[]{panel, center}) {
            method(type, "startExpandAnimator");
            for (String name : new String[]{"mExpandAnimatorSet", "mCollapseAnimatorSet", "mBlurRadiusSpring", "mBlurScaleSpring"}) field(type, name);
        }
    }

    void install(SignalHooks.Installer installer, Consumer<Method> deoptimize) throws ReflectiveOperationException {
        backdrop.install(installer);
        backdrop.deoptimizeCallers(loader, deoptimize);

        for (String name : new String[]{"canExpandCenterControlPanel", "dispatchEventToControlCenter"}) {
            installer.hook(PANEL, name, chain -> blockVerticalSwitch(chain.getThisObject())
                    ? false : chain.proceed(), MotionEvent.class);
        }
        Class<?> touchHandler = loader.loadClass(PANEL + "$TouchHandler");
        deoptimize.accept(method(touchHandler, "onInterceptTouchEvent", MotionEvent.class));
        deoptimize.accept(method(touchHandler, "onTouch", View.class, MotionEvent.class));

        installer.hook(CENTER, "shouldHideBlurForControlCenter", chain ->
                state != null && state.center == chain.getThisObject() ? false : chain.proceed());
        for (String name : new String[]{"updateLiveBlurAlpha", "setBlurBgForLive"})
            for (Method target : notificationBackground.getDeclaredMethods())
                if (target.getName().equals(name)) deoptimize.accept(target);
        installer.hook(PANEL, "onFinishInflate", chain -> {
            Object result = chain.proceed();
            Object panel = chain.getThisObject();
            panels.put((View) panelView.get(panel), new WeakReference<>(panel));
            return result;
        });
        installer.hook(ROOT, "dispatchTouchEvent", chain -> {
            if (Boolean.TRUE.equals(bypass.get())) return chain.proceed();
            View root = (View) chain.getThisObject();
            MotionEvent event = (MotionEvent) chain.getArg(0);
            try {
                if (handle(root, event)) return true;
            } catch (ReflectiveOperationException | RuntimeException error) {
                log.accept("Cannot switch separate shade horizontally", error);
                if (state != null) finishSafely(state, state.sourceCenter);
                candidate = null;
                return true; // Children already received CANCEL; do not send an orphaned UP.
            }
            return chain.proceed();
        }, MotionEvent.class);
        installer.hook(ROOT, "onDetachedFromWindow", chain -> {
            if (state != null && state.root == chain.getThisObject()) finishSafely(state, state.sourceCenter);
            candidate = null;
            backdrop.onDetached((View) chain.getThisObject());
            return chain.proceed();
        });
        // Neither native reveal nor queued blur springs may overwrite a horizontal transition.
        for (String name : new String[]{PANEL, CENTER}) {
            installer.hook(name, "startExpandAnimator", chain -> owns(chain.getThisObject()) ? null : chain.proceed());
            for (String setter : new String[]{"setAnimationAlpha", "setBlurRadius", "setBlurScaleFactor"}) {
                installer.hook(name, setter, chain -> owns(chain.getThisObject()) && !Boolean.TRUE.equals(bypass.get())
                        ? null : chain.proceed(), float.class);
            }
        }
        installer.hook(PANEL, "showBackgroundBlurDimLayer", chain -> {
            Object result = chain.proceed();
            if (owns(chain.getThisObject())) applySurface(state);
            return result;
        }, float.class);
        // These small setters can be inlined into animation/flow callbacks.
        for (Class<?> type : new Class<?>[]{loader.loadClass(PANEL), loader.loadClass(CENTER)}) {
            deoptimize.accept(method(type, "updateHeightAnimation", float.class));
            for (String name : new String[]{"setAnimationAlpha", "setBlurRadius", "setBlurScaleFactor"}) {
                deoptimize.accept(method(type, name, float.class));
            }
        }
    }

    private boolean owns(Object owner) { return state != null && (state.panel == owner || state.center == owner); }

    private boolean blockVerticalSwitch(Object panel) throws ReflectiveOperationException {
        return enabled.getAsBoolean() && barState.getInt(panel) == 0
                && !Boolean.TRUE.equals(isKeyguardShowing.invoke(panel))
                && !Boolean.TRUE.equals(flowValue.invoke(combined.invoke(shadeInteractor.get(panel))));
    }

    private boolean eligible(Object panel) throws ReflectiveOperationException {
        Object center = panelCenter.get(panel);
        return enabled.getAsBoolean() && barState.getInt(panel) == 0
                && !Boolean.TRUE.equals(isKeyguardShowing.invoke(panel))
                && !Boolean.TRUE.equals(flowValue.invoke(combined.invoke(shadeInteractor.get(panel))))
                && !disabled.getBoolean(center)
                && !Boolean.TRUE.equals(flowValue.invoke(editing.getType().getMethod("isEditing").invoke(editing.get(center))))
                && !Boolean.TRUE.equals(isDeviceCenterShowing.invoke(deviceCenter.get(center)));
    }

    private Object panelFor(View root) throws IllegalAccessException {
        for (WeakReference<Object> reference : panels.values()) {
            Object panel = reference.get();
            if (panel != null && ((View) panelView.get(panel)).getRootView() == root) return panel;
        }
        return null;
    }

    private boolean handle(View root, MotionEvent event) throws ReflectiveOperationException {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) drainTouch = false;
        if (state == null && drainTouch) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) drainTouch = false;
            return true;
        }
        if (state != null) {
            State current = state;
            if (!eligible(current.panel)) { finishSafely(current, current.sourceCenter); return true; }
            if (action == MotionEvent.ACTION_DOWN) {
                if (current.animator != null) { current.animator.cancel(); current.animator = null; }
                current.downX = event.getRawX(); current.base = current.position;
                current.velocity.clear(); current.velocity.addMovement(event);
                current.dragging = true;
            } else if (current.dragging) {
                current.velocity.addMovement(event);
                if (action == MotionEvent.ACTION_MOVE && event.getPointerCount() == 1) {
                    current.position = SeparateShadeSwipeMotion.resist(current.base + current.downX - event.getRawX(), current.width);
                    render(current);
                } else if (action == MotionEvent.ACTION_UP) {
                    current.velocity.computeCurrentVelocity(1000, current.maxFling);
                    float speed = -current.velocity.getXVelocity();
                    settle(current, SeparateShadeSwipeMotion.centerTarget(current.position, current.width, speed, current.minFling), speed);
                } else if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_POINTER_DOWN) {
                    settle(current, current.sourceCenter, 0f);
                }
            }
            return true;
        }
        if (action == MotionEvent.ACTION_DOWN) {
            candidate = null;
            Object panel = panelFor(root);
            if (panel == null) return false;
            settings.accept(root.getContext());
            if (!eligible(panel)) return false;
            Object center = panelCenter.get(panel);
            boolean inCenter = centerFraction.getFloat(center) >= .999f;
            if (!inCenter && panelFraction.getFloat(panel) < .999f) return false;
            View host = (View) (inCenter ? centerView.get(center) : panelView.get(panel));
            View pages = inCenter ? findPages(host) : null;
            if (inCenter && !firstPageIdle(pages)) return false;
            View pager = pages == null ? null : (View) pagesPager.get(pages);
            if (SeparateShadeSwipeTouchTargets.reserved(host, event.getRawX(), event.getRawY(), pager)
                    || (!inCenter && SeparateShadeSwipeTouchTargets.reserved((View) sharedContainer.get(panel), event.getRawX(), event.getRawY(), null))) return false;
            candidate = new Candidate(root, panel, inCenter, event.getRawX(), event.getRawY(), pages);
        } else if (candidate != null) {
            Candidate pending = candidate;
            if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                candidate = null;
            } else if (action == MotionEvent.ACTION_MOVE) {
                ViewConfiguration configuration = ViewConfiguration.get(root.getContext());
                float dx = event.getRawX() - pending.x, dy = event.getRawY() - pending.y;
                if (Math.abs(dy) > configuration.getScaledTouchSlop() && Math.abs(dy) >= Math.abs(dx)) candidate = null;
                else if (SeparateShadeSwipeMotion.horizontal(dx, dy, configuration.getScaledTouchSlop())) {
                    candidate = null;

                    if (!SeparateShadeSwipeMotion.canStart(pending.center, pending.pages == null ? 0 : currentPage.getInt(pending.pages),
                            !pending.center || firstPageBoundary(pending.pages), dx)) return false;
                    State current = new State(pending, panelCenter.get(pending.panel), configuration);
                    state = current;
                    drainTouch = true;
                    // Cancel the original child target once through the original dispatch path.
                    MotionEvent cancel = MotionEvent.obtain(event); cancel.setAction(MotionEvent.ACTION_CANCEL);
                    bypass.set(true);
                    try { root.dispatchTouchEvent(cancel); }
                    finally { bypass.remove(); cancel.recycle(); }
                    prepare(current);
                    current.position = SeparateShadeSwipeMotion.resist(current.base - dx, current.width);
                    current.velocity.addMovement(event);
                    render(current);
                    return true;
                }
            }
        }
        return false;
    }

    private void prepare(State current) throws ReflectiveOperationException {
        if (current.pages != null) {
            Object factory = edgeFactory.invoke(pagesRecycler.get(current.pages));
            if (edgeSpring.getDeclaringClass().isInstance(factory)) cancelEdgeSpring.invoke(edgeSpring.get(factory));
            ((View) pagesPager.get(current.pages)).setTranslationX(0f);
        }
        backdrop.begin(current.panel, current.center, current.root, current.sourceCenter);
        current.notificationViews.add(new Foreground((View) panelView.get(current.panel)));
        View shared = (View) sharedContainer.get(current.panel);
        if (!descendant(shared, (View) panelView.get(current.panel))) current.notificationViews.add(new Foreground(shared));

        for (String id : new String[]{"clock_container_surface", "keyguard_root_view"}) {
            int resource = current.root.getResources().getIdentifier(id, "id", "com.android.systemui");
            View clock = resource == 0 ? null : current.root.findViewById(resource);
            if (clock != null && current.notificationViews.stream().noneMatch(item -> descendant(clock, item.view))) {
                current.notificationViews.add(new Foreground(clock));
            }
        }
        ViewGroup center = (ViewGroup) centerView.get(current.center);
        View bar = (View)centerStatusBar.get(current.center);
        current.behind = (View) centerBehind.get(current.center); current.behindAlpha = current.behind.getAlpha();
        for (int i = 0; i < center.getChildCount(); i++) {
            View child = center.getChildAt(i);
            if (descendant(bar,child) && child instanceof ViewGroup group) {

                for (int j=0;j<group.getChildCount();j++)
                    if (group.getChildAt(j)!=bar) current.centerViews.add(new Foreground(group.getChildAt(j)));
            } else if (child != current.behind) current.centerViews.add(new Foreground(child));
        }
        for (String id : new String[]{"scrim_behind", "scrim_notifications"}) {
            int resource = current.root.getResources().getIdentifier(id, "id", "com.android.systemui");
            View view = resource == 0 ? null : current.root.findViewById(resource);
            if (view != null) current.notificationBackgrounds.add(new Foreground(view));
        }
        bypass.set(true);
        try {
            cancelAnimations(current.panel); cancelAnimations(current.center);
            // Expand without vertical animation, then complete its deferred layout immediately.
            panelExpand.invoke(current.panel, false); centerExpand.invoke(current.center, false);
            panelInstant.setBoolean(current.panel, false); centerInstant.setBoolean(current.center, false);
            panelSetFraction.invoke(current.panel, 1f); centerSetFraction.invoke(current.center, 1f);
            cancelAnimations(current.panel); cancelAnimations(current.center);
            contentPose.accept(current.panel, true); contentPose.accept(current.center, true);
            panelAlpha.invoke(current.panel, 1f); centerAlpha.invoke(current.center, 1f);
            centerScale.invoke(current.center, 1f); scrimAlpha.invoke(current.behind, 1f);
            refreshNotificationAlpha(shared);
        } finally { bypass.remove(); }
        current.statusBar = new SeparateShadeStatusBar(bar,(View)notificationStatusBar.get(current.panel));
        current.preDraw = () -> {
            if (state != current) return true;
            try {
                if (!eligible(current.panel) || current.root.getWidth() != (int) current.width
                        || panelFraction.getFloat(current.panel) < .999f || centerFraction.getFloat(current.center) < .999f) finishSafely(current, current.sourceCenter);
                else render(current);
            } catch (ReflectiveOperationException | RuntimeException error) {
                log.accept("Cannot update separate shade swipe frame", error); finishSafely(current, current.sourceCenter);
            }
            return true;
        };
        current.root.getViewTreeObserver().addOnPreDrawListener(current.preDraw);
    }

    private void refreshNotificationAlpha(View view) throws ReflectiveOperationException {
        if (notificationBackground.isInstance(view)) notificationAlphaChanged.invoke(view, view);
        if (view instanceof ViewGroup group)
            for (int i = 0; i < group.getChildCount(); i++) refreshNotificationAlpha(group.getChildAt(i));
    }

    private void render(State current) throws ReflectiveOperationException {
        float fraction = SeparateShadeSwipeMotion.fraction(current.position, current.width);
        for (Foreground item : current.notificationViews) item.move(-current.position);
        for (Foreground item : current.centerViews) item.move(current.width - current.position);
        current.statusBar.apply(fraction);
        current.behind.setAlpha(current.behindAlpha * fraction);
        for (Foreground item : current.notificationBackgrounds) item.view.setAlpha(item.alpha * (1f - fraction));
        backdrop.apply(fraction);
        applySurface(current);
    }

    private void applySurface(State current) throws IllegalAccessException {
        SurfaceControl layer = (SurfaceControl) surface.get(current.panel);
        if (layer == null || !layer.isValid()) return;
        float fraction = SeparateShadeSwipeMotion.fraction(current.position, current.width);
        // Preserve Flyme/the module's current color; only blend this native layer's opacity.
        try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
            float opacity = fraction;
            transaction.setAlpha(layer, Color.alpha(dimColor.getInt(current.panel)) / 255f * opacity).apply();
        }
    }

    private void settle(State current, boolean toCenter, float velocity) {
        current.dragging = false;
        float start = current.position, target = toCenter ? current.width : 0f;
        ValueAnimator animator = ValueAnimator.ofFloat(0f, .6f);
        current.animator = animator; animator.setDuration(600L); animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(frame -> {
            if (state != current) return;
            current.position = SeparateShadeSwipeMotion.spring((Float) frame.getAnimatedValue(), start, target, velocity);
            try { render(current); }
            catch (ReflectiveOperationException | RuntimeException error) {
                log.accept("Cannot settle separate shade swipe", error); finishSafely(current, current.sourceCenter);
            }
        });
        animator.addListener(new AnimatorListenerAdapter() {
            boolean cancelled;
            @Override public void onAnimationCancel(Animator animation) { cancelled = true; }
            @Override public void onAnimationEnd(Animator animation) {
                if (!cancelled && state == current) finishSafely(current, toCenter);
            }
        });
        animator.start();
    }

    private void finishSafely(State current, boolean toCenter) {
        if (state != current) return;
        state = null; candidate = null;
        if (current.animator != null) current.animator.cancel();
        current.velocity.recycle();
        if (current.preDraw != null && current.root.getViewTreeObserver().isAlive()) current.root.getViewTreeObserver().removeOnPreDrawListener(current.preDraw);
        bypass.set(true);
        try {
            // A lockscreen, mode change or external collapse must keep its native destination.
            Object winner = toCenter ? current.center : current.panel;
            boolean keepOpen = current.root.isAttachedToWindow() && barState.getInt(current.panel) == 0
                    && !Boolean.TRUE.equals(isKeyguardShowing.invoke(current.panel))
                    && !Boolean.TRUE.equals(flowValue.invoke(combined.invoke(shadeInteractor.get(current.panel))))
                    && (toCenter ? centerFraction : panelFraction).getFloat(winner) >= .999f;
            if (keepOpen) { cancelAnimations(current.panel); cancelAnimations(current.center); }
            if (toCenter) panelCollapse.invoke(current.panel); else centerCollapse.invoke(current.center);
            if (keepOpen) {
                contentPose.accept(current.panel, !toCenter); contentPose.accept(current.center, toCenter);
                (toCenter ? centerSetFraction : panelSetFraction).invoke(winner, 1f);
                (toCenter ? centerAlpha : panelAlpha).invoke(winner, 1f);
                scrimAlpha.invoke(current.behind, toCenter ? 1f : 0f);
                cancelAnimations(current.panel); cancelAnimations(current.center);
            }
            backdrop.finish(toCenter, keepOpen);
            if (keepOpen && !toCenter) ShadeDimFrame.removeImmediately(current.panel, surface);
        } catch (ReflectiveOperationException | RuntimeException error) {
            log.accept("Cannot restore native separate shade after swipe", error);
        } finally {
            if (current.statusBar != null) current.statusBar.restore();
            for (Foreground item : current.notificationViews) item.restorePosition();
            for (Foreground item : current.centerViews) item.restorePosition();
            for (Foreground item : current.notificationBackgrounds) item.restore();
            if (current.behind != null) current.behind.setAlpha(current.behindAlpha);
            bypass.remove();
            current.root.invalidate();
        }
    }

    private static void cancelAnimations(Object owner) throws ReflectiveOperationException {
        method(owner.getClass(), "abortAnimations").invoke(owner);
        for (String name : new String[]{"mExpandAnimatorSet", "mCollapseAnimatorSet", "mBlurRadiusSpring", "mBlurScaleSpring"}) {
            Field field = field(owner.getClass(), name); Object value = field.get(owner);
            if (value instanceof Animator animator) { animator.cancel(); field.set(owner, null); }
            else if (value != null) value.getClass().getMethod("cancel").invoke(value);
        }
    }

    private boolean firstPageIdle(View pages) throws ReflectiveOperationException {
        return firstPageBoundary(pages) && (Integer) pagerScrollState.invoke(pagesPager.get(pages)) == 0;
    }

    private boolean firstPageBoundary(View pages) throws IllegalAccessException {

        return pages != null && currentPage.getInt(pages) == 0 && !pagesDragging.getBoolean(pages)
                && !pagesSwitching.getBoolean(pages);
    }

    private View findPages(View view) {
        if (pagesType.isInstance(view)) return view;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            View found = findPages(group.getChildAt(i)); if (found != null) return found;
        }
        return null;
    }

    private static boolean descendant(View child, View ancestor) {
        for (android.view.ViewParent parent = child == null ? null : child.getParent(); parent != null; parent = parent.getParent()) if (parent == ancestor) return true;
        return child == ancestor;
    }
    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static Method method(Class<?> type, String name, Class<?>... args) throws NoSuchMethodException {
        Method method = type.getDeclaredMethod(name, args); method.setAccessible(true); return method;
    }
    private record Candidate(View root, Object panel, boolean center, float x, float y, View pages) {}
    private static final class Foreground {
        final View view; final float alpha; float x, appliedX;
        Foreground(View view) { this.view = view; x = appliedX = view.getTranslationX(); alpha = view.getAlpha(); }
        void move(float offset) {
            if (view.getTranslationX() != appliedX) x = view.getTranslationX();
            appliedX = x + offset; view.setTranslationX(appliedX);
        }
        void restorePosition() { if (view.getTranslationX() == appliedX) view.setTranslationX(x); }
        void restore() { restorePosition(); view.setAlpha(alpha); }
    }
    private static final class State {
        SeparateShadeStatusBar statusBar;
        final View root, pages; final Object panel, center; final boolean sourceCenter;
        final float width, minFling, maxFling;
        final VelocityTracker velocity = VelocityTracker.obtain();
        final List<Foreground> notificationViews = new ArrayList<>(), centerViews = new ArrayList<>(), notificationBackgrounds = new ArrayList<>();
        View behind; float behindAlpha = 1f, downX, base, position; boolean dragging = true;
        ValueAnimator animator; ViewTreeObserver.OnPreDrawListener preDraw;
        State(Candidate candidate, Object center, ViewConfiguration configuration) {
            root = candidate.root; panel = candidate.panel; this.center = center; sourceCenter = candidate.center;
            pages = candidate.pages;
            width = Math.max(1, root.getWidth()); downX = candidate.x;
            base = position = sourceCenter ? width : 0f;
            minFling = Math.max(configuration.getScaledMinimumFlingVelocity(), 600f * root.getResources().getDisplayMetrics().density);
            maxFling = configuration.getScaledMaximumFlingVelocity();
        }
    }
}
