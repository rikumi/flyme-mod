package dev.rikumi.flymemod;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.DecelerateInterpolator;
import android.widget.SeekBar;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Both shade hosts use Flyme's full-player MediaScrollView, not the compact QS tile. */
final class MediaSwipeHooks {
    private static final String HANDLER = "com.android.systemui.media.controls.ui.view.MediaCarouselScrollHandler";
    private static final String SCROLL = "com.android.systemui.media.controls.ui.view.MediaScrollView";
    private final Consumer<Context> settings;
    private final BooleanSupplier enabled;
    private final BiConsumer<String, Throwable> log;
    private final Field scrollView, mediaContent, disabled;
    private final Method intercept, touch, setTranslation, resetTranslation, setTarget;
    private final Method animatorInstance, animatorCancel;
    private final int horizontalHapticFeedback;
    private final Map<View, State> states = new WeakHashMap<>();

    MediaSwipeHooks(ClassLoader loader, Consumer<Context> settings, BooleanSupplier enabled,
                    BiConsumer<String, Throwable> log) throws ReflectiveOperationException {
        this.settings = settings; this.enabled = enabled; this.log = log;
        Class<?> handler = loader.loadClass(HANDLER), scroll = loader.loadClass(SCROLL);
        scrollView = handler.getField("scrollView"); mediaContent = handler.getField("mediaContent");
        disabled = handler.getField("scrollingDisabled");
        intercept = handler.getMethod("onInterceptTouch", MotionEvent.class);
        touch = handler.getMethod("onTouch", MotionEvent.class);
        setTranslation = handler.getMethod("setContentTranslation", float.class);
        resetTranslation = handler.getMethod("resetTranslation", boolean.class);
        setTarget = scroll.getMethod("setAnimationTargetX", float.class);
        handler.getDeclaredMethod("onScroll", MotionEvent.class, MotionEvent.class, float.class);
        handler.getDeclaredMethod("onFling", float.class, float.class);
        Class<?> animator = loader.loadClass("com.android.wm.shell.shared.animation.PhysicsAnimator");
        animatorInstance = animator.getMethod("getInstance", Object.class);
        animatorCancel = animator.getMethod("cancel");
        // Flyme's Switch/QSTileViewImpl use this named preset via View feedback.
        // Short createOneShot effects are remapped to mBack press with fixed strength.
        horizontalHapticFeedback = loader.loadClass("android.view.HapticFeedbackConstants")
                .getField("FLYME_SLIDE_HORIZONTAL").getInt(null);
    }

    void install(ClassLoader loader, SignalHooks.Installer installer, Consumer<Method> deoptimize)
            throws ReflectiveOperationException {
        // Resizing/host migration invalidates the gesture's original scroll coordinates.
        installer.hook(HANDLER, "setCarouselBounds", chain -> {
            State state = observe(chain.getThisObject());
            state.abort();
            return chain.proceed();
        }, int.class, int.class);
        installer.hook(HANDLER, "setPlayerWidthPlusPadding", chain -> {
            observe(chain.getThisObject()).abort();
            return chain.proceed();
        }, int.class);
        installer.hook(HANDLER, "setScrollingDisabled", chain -> {
            if ((Boolean) chain.getArg(0)) observe(chain.getThisObject()).abort();
            return chain.proceed();
        }, boolean.class);
        installer.hook("android.view.ViewGroup", "dispatchTouchEvent", chain -> {
            State state = states.get(chain.getThisObject());
            if (state == null) return chain.proceed();
            MotionEvent event = (MotionEvent) chain.getArg(0);
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) state.begin(event);
            if (state.suppressUntilUp) {
                if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) state.stop();
                return true;
            }
            if (!state.active) return chain.proceed();
            state.before(event);
            try { return state.suppressUntilUp ? true : chain.proceed(); }
            finally {
                if (event.getActionMasked() == MotionEvent.ACTION_UP
                        || event.getActionMasked() == MotionEvent.ACTION_CANCEL) state.stop();
            }
        }, MotionEvent.class);
        installer.hook(SCROLL, "onInterceptTouchEvent", chain -> {
            State state = states.get(chain.getThisObject());
            if (state == null || !state.active) return chain.proceed();
            MotionEvent event = (MotionEvent) chain.getArg(0);
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) intercept.invoke(state.owner.get(), event);
            // ViewGroup still sends CANCEL to the original button when we claim the drag.
            return state.dragging;
        }, MotionEvent.class);
        installer.hook(SCROLL, "onTouchEvent", chain -> {
            State state = states.get(chain.getThisObject());
            if (state == null || !state.active) return chain.proceed();
            if (state.rejected) return true;
            touch.invoke(state.owner.get(), chain.getArg(0));
            // Avoid HorizontalScrollView's short-circuit and its competing scroll/fling.
            return true;
        }, MotionEvent.class);
        installer.hook("android.view.ViewGroup", "requestDisallowInterceptTouchEvent", chain -> {
            if (enabled.getAsBoolean() && (Boolean) chain.getArg(0)) {
                for (View view = (View) chain.getThisObject(); view != null;
                     view = view.getParent() instanceof View parent ? parent : null) {
                    State state = states.get(view);
                    if (state != null && state.active) return null;
                }
            }
            return chain.proceed();
        }, boolean.class);
        installer.hook(HANDLER, "onScroll", chain -> {
            State state = states.get(scrollView.get(chain.getThisObject()));
            if (state == null || !state.active || !state.dragging) return chain.proceed();
            // Movement is applied once at dispatch, including while the transition is animating.
            return true;
        }, MotionEvent.class, MotionEvent.class, float.class);
        installer.hook(HANDLER, "onFling", chain -> {
            State state = states.get(scrollView.get(chain.getThisObject()));
            if (state != null && state.active && !state.motion.followsFinger()) return false;
            return chain.proceed();
        }, float.class, float.class);
        // The native GestureDetector callbacks can otherwise inline the hooked methods.
        Class<?> listener = loader.loadClass(HANDLER + "$gestureListener$1");
        deoptimize.accept(listener.getDeclaredMethod("onScroll", MotionEvent.class, MotionEvent.class, float.class, float.class));
        deoptimize.accept(listener.getDeclaredMethod("onFling", MotionEvent.class, MotionEvent.class, float.class, float.class));
    }

    private State observe(Object owner) throws ReflectiveOperationException {
        ViewGroup view = (ViewGroup) scrollView.get(owner);
        State state = states.get(view);
        if (state == null) {
            state = new State(view, owner);
            states.put(view, state);
            view.addOnAttachStateChangeListener(state);
        }
        return state;
    }

    void onCreated(Object owner) throws ReflectiveOperationException { observe(owner); }

    void refresh() {
        if (!enabled.getAsBoolean()) for (State state : new ArrayList<>(states.values())) state.abort();
    }

    private final class State implements View.OnAttachStateChangeListener {
        final WeakReference<ViewGroup> view;
        final WeakReference<Object> owner;
        final int slop;
        MediaSwipeMotion motion;
        boolean active, dragging, rejected, suppressUntilUp;
        float downX, downY, distance, target, lag, displayed, startTranslation;
        int startScroll;
        ValueAnimator transition;

        State(ViewGroup view, Object owner) {
            this.view = new WeakReference<>(view); this.owner = new WeakReference<>(owner);
            slop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
        }

        void begin(MotionEvent event) throws ReflectiveOperationException {
            stop();
            ViewGroup root = view.get(); Object handler = owner.get();
            if (root == null || handler == null) return;
            settings.accept(root.getContext());
            if (!enabled.getAsBoolean() || disabled.getBoolean(handler)
                    || hitsSeekBar(root, event.getRawX(), event.getRawY())) return;
            motion = new MediaSwipeMotion(root.getResources().getDisplayMetrics().density);
            downX = event.getRawX(); downY = event.getRawY();
            distance = target = lag = displayed = 0;
            active = true; rejected = false;
            disallowParent(true);
        }

        void before(MotionEvent event) throws ReflectiveOperationException {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP
                    || action == MotionEvent.ACTION_CANCEL) { abort(); return; }
            if (rejected || (action != MotionEvent.ACTION_MOVE && action != MotionEvent.ACTION_UP)) return;
            distance = event.getRawX() - downX;
            float vertical = Math.abs(event.getRawY() - downY);
            if (!dragging) {
                if (action != MotionEvent.ACTION_MOVE) return;
                if (Math.max(Math.abs(distance), vertical) <= slop) return;
                if (vertical >= Math.abs(distance)) { rejected = true; disallowParent(false); return; }
                dragging = true;
                ViewGroup root = view.get();
                Object handler = owner.get();
                animatorCancel.invoke(animatorInstance.invoke(null, handler));
                startScroll = root.getScrollX();
                startTranslation = ((View) mediaContent.get(handler)).getTranslationX();
            }
            boolean changed = motion.update(distance);
            target = motion.target(distance);
            if (changed) {
                cancelTransition();
                lag = displayed - target;
                vibrate();
                transition = ValueAnimator.ofFloat(lag, 0);
                transition.setDuration(MediaSwipeMotion.TRANSITION_MS);
                transition.setInterpolator(new DecelerateInterpolator());
                transition.addUpdateListener(animation -> {
                    lag = (Float) animation.getAnimatedValue();
                    apply();
                });
                transition.start();
            }
            if (action == MotionEvent.ACTION_UP) { cancelTransition(); lag = 0; }
            apply();
        }

        void apply() {
            ViewGroup root = view.get(); Object handler = owner.get();
            if (!active || !dragging || root == null || handler == null) return;
            try {
                View content = (View) mediaContent.get(handler);
                displayed = target + lag;
                float desiredScroll = startScroll - startTranslation - displayed;
                int range = Math.max(0, content.getWidth() - root.getWidth() + root.getPaddingLeft() + root.getPaddingRight());
                int scroll = Math.max(0, Math.min(range, Math.round(desiredScroll)));
                root.scrollTo(scroll, root.getScrollY());
                float translation = desiredScroll < 0 ? -desiredScroll : desiredScroll > range ? range - desiredScroll : 0;
                // Keep handler, content, outline and release-animation target in agreement.
                setTranslation.invoke(handler, translation);
                setTarget.invoke(root, translation);
            } catch (ReflectiveOperationException | RuntimeException error) {
                log.accept("Cannot update media swipe displacement", error);
                stop();
            }
        }

        void vibrate() {
            ViewGroup root = view.get();
            if (root == null) return;
            try {
                root.performHapticFeedback(horizontalHapticFeedback);
            } catch (RuntimeException error) { log.accept("Cannot play media swipe threshold feedback", error); }
        }

        void cancelTransition() {
            if (transition != null) { transition.cancel(); transition = null; }
        }

        void stop() {
            cancelTransition();
            if (active) disallowParent(false);
            active = dragging = false;
            suppressUntilUp = false;
        }

        void abort() {
            boolean owned = active && dragging;
            if (active && dragging && owner.get() != null) {
                try {
                    // A CANCEL must not reach Flyme's UP-like dismissal with nonzero translation.
                    resetTranslation.invoke(owner.get(), false);
                    setTarget.invoke(view.get(), 0f);
                    long now = SystemClock.uptimeMillis();
                    MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0, 0, 0);
                    try { touch.invoke(owner.get(), cancel); }
                    finally { cancel.recycle(); }
                } catch (ReflectiveOperationException | RuntimeException error) {
                    log.accept("Cannot cancel media swipe", error);
                }
            }
            stop();
            suppressUntilUp = owned;
        }

        void disallowParent(boolean value) {
            ViewGroup root = view.get();
            ViewParent parent = root == null ? null : root.getParent();
            if (parent != null) parent.requestDisallowInterceptTouchEvent(value);
        }

        @Override public void onViewAttachedToWindow(View view) { }
        @Override public void onViewDetachedFromWindow(View view) { abort(); }
    }

    private static boolean hitsSeekBar(View view, float x, float y) {
        if (view.getVisibility() != View.VISIBLE) return false;
        if (view instanceof SeekBar) {
            Rect rect = new Rect();
            return view.getGlobalVisibleRect(rect) && rect.contains((int) x, (int) y);
        }
        if (view instanceof ViewGroup group) for (int i = group.getChildCount() - 1; i >= 0; i--)
            if (hitsSeekBar(group.getChildAt(i), x, y)) return true;
        return false;
    }
}
