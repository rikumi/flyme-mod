package dev.rikumi.flymemod;

import android.animation.ValueAnimator;
import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

final class NotificationSwipeHooks {
    private static final String BASE = "com.android.systemui.SwipeHelper";
    private static final String HELPER = "com.android.systemui.statusbar.notification.stack.NotificationSwipeHelper";
    private final Class<?> helperClass, rowClass;
    private final Field touched, swiping, longPress, menuIntercepting, baseTranslation, initialTouch;
    private final Method canDismiss, getTranslation, setTranslation, updateProgress;
    private final int haptic;
    private final Consumer<Context> settings;
    private final BooleanSupplier enabled;
    private final BiConsumer<String, Throwable> log;
    private final Map<Object, State> states = new WeakHashMap<>();
    private final ThreadLocal<State> moving = new ThreadLocal<>();

    NotificationSwipeHooks(ClassLoader loader, Consumer<Context> settings, BooleanSupplier enabled,
                           BiConsumer<String, Throwable> log) throws ReflectiveOperationException {
        this.settings = settings; this.enabled = enabled; this.log = log;
        helperClass = loader.loadClass(HELPER);
        rowClass = loader.loadClass("com.android.systemui.statusbar.notification.row.ExpandableNotificationRow");
        Class<?> base = loader.loadClass(BASE);
        touched = base.getField("mTouchedView"); swiping = base.getField("mIsSwiping");
        longPress = base.getField("mLongPressSent"); menuIntercepting = base.getField("mMenuRowIntercepting");
        baseTranslation = base.getField("mTranslation"); initialTouch = base.getField("mInitialTouchPos");
        canDismiss = loader.loadClass("com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout")
                .getDeclaredMethod("canChildBeDismissed", View.class);
        getTranslation = helperClass.getDeclaredMethod("getTranslation", View.class);
        setTranslation = helperClass.getDeclaredMethod("setTranslation", View.class, float.class);
        updateProgress = base.getDeclaredMethod("updateSwipeProgressFromOffset", View.class, boolean.class);
        haptic = loader.loadClass("android.view.HapticFeedbackConstants").getField("FLYME_SLIDE_HORIZONTAL").getInt(null);
        base.getDeclaredMethod("onTouchEvent", MotionEvent.class);
        base.getDeclaredMethod("isSwipeDismissible");
        base.getDeclaredMethod("resetSwipeStates", boolean.class);
        helperClass.getDeclaredMethod("onDownUpdate", View.class, MotionEvent.class);
        helperClass.getDeclaredMethod("onMoveUpdate", View.class, MotionEvent.class, float.class, float.class);
        helperClass.getDeclaredMethod("snapChild", View.class, float.class, float.class);
        helperClass.getDeclaredMethod("getViewTranslationAnimator", View.class, float.class, ValueAnimator.AnimatorUpdateListener.class);
    }

    void install(SignalHooks.Installer installer, Consumer<Method> deoptimize) throws ReflectiveOperationException {
        installer.hook(HELPER, "onDownUpdate", chain -> {
            Object result = chain.proceed();
            Object helper = chain.getThisObject();
            State state = states.computeIfAbsent(helper, State::new);
            state.begin((View) chain.getArg(0), (MotionEvent) chain.getArg(1));
            return result;
        }, View.class, MotionEvent.class);
        installer.hook(BASE, "onTouchEvent", chain -> {
            Object helper = chain.getThisObject();
            if (!helperClass.isInstance(helper)) return chain.proceed();
            State state = states.get(helper);
            MotionEvent event = (MotionEvent) chain.getArg(0);
            int action = event.getActionMasked();
            boolean terminal = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL;
            State previous = moving.get();
            try {
                if (state != null && state.active) {
                    if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) state.stop();
                    else if (action == MotionEvent.ACTION_CANCEL) state.cancelTransition();
                    else if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP) state.prepare(event);
                    if (state.active && state.dragging && action == MotionEvent.ACTION_MOVE) moving.set(state);
                    if (state.active && state.dragging && action == MotionEvent.ACTION_UP) {
                        state.cancelTransition(); state.lag = 0; state.apply();
                    }
                }
                return chain.proceed();
            } finally {
                moving.set(previous);
                if (state != null && terminal) state.stop();
            }
        }, MotionEvent.class);
        installer.hook(HELPER, "setTranslation", chain -> {
            State state = moving.get();
            if (state == null || !state.active || state.applying || state.owner.get() != chain.getThisObject()
                    || state.row.get() != chain.getArg(0)) return chain.proceed();
            state.displayed = state.target + state.lag;
            return chain.proceed(new Object[]{chain.getArg(0), state.displayed});
        }, View.class, float.class);
        installer.hook(HELPER, "onMoveUpdate", chain -> {
            State state = moving.get();
            if (state == null || !state.active || state.owner.get() != chain.getThisObject()
                    || state.row.get() != chain.getArg(0)) return chain.proceed();
            return chain.proceed(new Object[]{chain.getArg(0), chain.getArg(1), state.displayed, state.displayed - state.base});
        }, View.class, MotionEvent.class, float.class, float.class);
        installer.hook(BASE, "isSwipeDismissible", chain -> {
            State state = states.get(chain.getThisObject());
            if (state != null && state.active && state.dragging && !state.motion.followsFinger()) return false;
            return chain.proceed();
        });
        installer.hook(BASE, "resetSwipeStates", chain -> {
            stop(chain.getThisObject(), null);
            return chain.proceed();
        }, boolean.class);
        installer.hook(HELPER, "snapChild", chain -> {
            stop(chain.getThisObject(), (View) chain.getArg(0));
            return chain.proceed();
        }, View.class, float.class, float.class);
        installer.hook(HELPER, "getViewTranslationAnimator", chain -> {
            stop(chain.getThisObject(), (View) chain.getArg(0));
            return chain.proceed();
        }, View.class, float.class, ValueAnimator.AnimatorUpdateListener.class);
        Class<?> base = helperClass.getSuperclass();
        deoptimize.accept(base.getDeclaredMethod("onTouchEvent", MotionEvent.class));
        deoptimize.accept(base.getDeclaredMethod("onInterceptTouchEvent", MotionEvent.class));
        deoptimize.accept(base.getDeclaredMethod("isDismissGesture", MotionEvent.class));
        deoptimize.accept(helperClass.getDeclaredMethod("handleUpEvent", MotionEvent.class, View.class, float.class, float.class));
    }

    private void stop(Object owner, View view) {
        State state = states.get(owner);
        if (state != null && (view == null || state.row.get() == view)) state.stop();
    }

    void refresh() {
        if (enabled.getAsBoolean()) return;
        for (State state : new ArrayList<>(states.values())) {
            state.cancelTransition();
            if (state.active && state.dragging) { state.target = state.base + state.nativeDistance; state.lag = 0; state.apply(); }
            state.stop();
        }
    }

    private final class State implements View.OnAttachStateChangeListener {
        final WeakReference<Object> owner;
        WeakReference<View> row = new WeakReference<>(null);
        MediaSwipeMotion motion;
        ValueAnimator transition;
        boolean active, dragging, applying;
        float downX, base, nativeDistance, target, lag, displayed;

        State(Object owner) { this.owner = new WeakReference<>(owner); }

        void begin(View view, MotionEvent event) throws ReflectiveOperationException {
            stop();
            settings.accept(view.getContext());
            if (!enabled.getAsBoolean() || !rowClass.isInstance(view) || !(Boolean) canDismiss.invoke(null, view)) return;
            row = new WeakReference<>(view);
            downX = event.getRawX();
            motion = new MediaSwipeMotion(view.getResources().getDisplayMetrics().density);
            active = true;
            view.addOnAttachStateChangeListener(this);
        }

        void prepare(MotionEvent event) throws ReflectiveOperationException {
            Object helper = owner.get(); View view = row.get();
            if (helper == null || view == null || touched.get(helper) != view || !enabled.getAsBoolean()
                    || !(Boolean) canDismiss.invoke(null, view) || longPress.getBoolean(helper) || menuIntercepting.getBoolean(helper)) {
                stop(); return;
            }
            if (!swiping.getBoolean(helper)) return;
            if (!dragging) {
                if (event.getActionMasked() != MotionEvent.ACTION_MOVE) return;
                dragging = true;
                base = baseTranslation.getFloat(helper);
                displayed = (Float) getTranslation.invoke(helper, view);
                lag = 0;
            }
            nativeDistance = event.getX() - initialTouch.getFloat(helper);
            float distance = event.getRawX() - downX;
            boolean changed = motion.update(distance);
            target = base + motion.target(distance);
            if (changed) {
                cancelTransition();
                lag = displayed - target;
                try { view.performHapticFeedback(haptic); }
                catch (RuntimeException error) { log.accept("Cannot play notification swipe threshold feedback", error); }
                transition = ValueAnimator.ofFloat(lag, 0);
                transition.setDuration(MediaSwipeMotion.TRANSITION_MS);
                transition.setInterpolator(new DecelerateInterpolator());
                transition.addUpdateListener(animation -> { lag = (Float) animation.getAnimatedValue(); apply(); });
                transition.start();
            }
        }

        void apply() {
            Object helper = owner.get(); View view = row.get();
            if (!active || !dragging || helper == null || view == null) return;
            try {
                if (touched.get(helper) != view || !swiping.getBoolean(helper) || !(Boolean) canDismiss.invoke(null, view)) { stop(); return; }
                applying = true;
                displayed = target + lag;
                setTranslation.invoke(helper, view, displayed);
                updateProgress.invoke(helper, view, true);
            } catch (ReflectiveOperationException | RuntimeException error) {
                log.accept("Cannot update notification swipe displacement", error);
                stop();
            } finally { applying = false; }
        }

        void cancelTransition() {
            if (transition != null) { transition.cancel(); transition = null; }
        }

        void stop() {
            cancelTransition();
            View view = row.get();
            if (view != null) view.removeOnAttachStateChangeListener(this);
            row.clear();
            active = dragging = false;
        }

        @Override public void onViewAttachedToWindow(View view) { }
        @Override public void onViewDetachedFromWindow(View view) { stop(); }
    }
}
