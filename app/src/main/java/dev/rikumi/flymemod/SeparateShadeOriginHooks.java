package dev.rikumi.flymemod;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

final class SeparateShadeOriginHooks {
    private static final String CENTER = "com.flyme.systemui.controlcenter.phone.CenterController";
    private static final float CARD_FACTOR = .54f;
    private final Consumer<Context> settings;
    private final BooleanSupplier enabled;
    private BooleanSupplier slowRebound = () -> false;
    private final BiConsumer<String, Throwable> log;
    private final Field context, tracking, closed, initialY, pointer, spring;
    private final Method translation, touchSlop, alpha;
    private final Map<Object, State> states = new WeakHashMap<>();
    private final ThreadLocal<MotionEvent> event = new ThreadLocal<>();
    private final ThreadLocal<Boolean> ownWrite = new ThreadLocal<>();

    SeparateShadeOriginHooks(ClassLoader loader, Consumer<Context> settings, BooleanSupplier enabled,
                             BiConsumer<String, Throwable> log) throws ReflectiveOperationException {
        this.settings = settings;
        this.enabled = enabled;
        this.log = log;
        Class<?> center = loader.loadClass(CENTER);
        context = field(center, "mContext");
        tracking = field(center, "mTracking");
        closed = field(center, "mCenterClosedOnDown");
        initialY = field(center, "mInitialTouchY");
        pointer = field(center, "mTrackingPointer");
        spring = field(center, "mSpringBackAnim");
        translation = method(center, "setQSTranslationY", float.class);
        alpha = method(center, "setAnimationAlpha", float.class);
        touchSlop = method(center, "getTouchSlop", MotionEvent.class);
    }

    void setSlowRebound(BooleanSupplier enabled) { slowRebound = enabled; }

    void onVisibilityChanged(Object center, boolean visible) {
        State state = states.get(center);
        if (state != null) state.hidden = !visible;
    }

    void install(SignalHooks.Installer installer) {
        installer.hook(CENTER + "$TouchHandler", "onTouch", chain -> {
            MotionEvent previous = event.get();
            event.set((MotionEvent) chain.getArg(1));
            try { return chain.proceed(); }
            finally { if (previous == null) event.remove(); else event.set(previous); }
        }, View.class, MotionEvent.class);
        installer.hook(CENTER, "onTrackingStarted", chain -> {
            Object center = chain.getThisObject();
            settings.accept((Context) context.get(center));
            State active = states.get(center);

            if (active() && active != null && !active.settling
                    && tracking.getBoolean(center)) return chain.proceed();
            State previous = states.remove(center);
            if (previous != null && previous.animator != null) previous.animator.cancel();
            Object result = chain.proceed();
            if (active()) {
                Object nativeSpring = spring.get(center);
                if (nativeSpring != null) nativeSpring.getClass().getMethod("cancel").invoke(nativeSpring);
                State state = new State();
                state.originFixed = enabled.getAsBoolean();
                state.opening = closed.getBoolean(center);
                state.reference = 56f * ((Context) context.get(center)).getResources().getDisplayMetrics().density;
                state.translation = state.opening && state.originFixed ? -state.reference / CARD_FACTOR : 0f;
                states.put(center, state);
            }
            return result;
        });
        installer.hook(CENTER, "setAnimationAlpha", chain -> {
            Object center = chain.getThisObject();
            State state = states.get(center);
            if (active() && state != null) {
                if (!state.settling) state.alpha = (Float) chain.getArg(0);
                if (closingFade(state) && (state.originFixed || state.settling)) {
                    float visibleAlpha = Math.min((Float) chain.getArg(0),
                            state.settling ? state.alpha : closingAlpha(center, state));
                    if (!state.settling) state.alpha = visibleAlpha;
                    return chain.proceed(new Object[]{visibleAlpha});
                }
            }
            return chain.proceed();
        }, float.class);
        installer.hook(CENTER, "setQSTranslationY", chain -> {
            State state = states.get(chain.getThisObject());
            if (active() && state != null && !Boolean.TRUE.equals(ownWrite.get())) {
                if (state.originFixed || state.hidden || (state.settling && !state.expanding)) return null;
                state.translation = (Float) chain.getArg(0);
            }
            return chain.proceed();
        }, float.class);
        installer.hook(CENTER, "updateHeightAnimation", chain -> {
            Object result = chain.proceed();
            Object center = chain.getThisObject();
            State state = states.get(center);
            if (!active() || state == null || state.hidden || !state.originFixed || state.settling || !tracking.getBoolean(center)) return result;
            MotionEvent motion = event.get();
            if (motion != null && motion.getActionMasked() == MotionEvent.ACTION_MOVE) {
                int index = motion.findPointerIndex(pointer.getInt(center));
                float drag = motion.getY(Math.max(0, index)) - initialY.getFloat(center);

                state.translation = CombinedShadeAnimationHooks.originTranslation(
                        drag + (state.opening ? 0f : state.reference), state.reference);
            }
            write(center, state.translation);
            return result;
        }, float.class);
        installer.hook(CENTER, "flingExpands", chain -> {
            Object center = chain.getThisObject();
            MotionEvent motion = event.get();
            if (!enabled.getAsBoolean() || !tracking.getBoolean(center) || motion == null
                    || motion.getActionMasked() == MotionEvent.ACTION_CANCEL) return chain.proceed();
            float drag = ((Number) chain.getArg(3)).floatValue() - initialY.getFloat(center);
            return CombinedShadeAnimationHooks.expandsFromDrag(drag, ((Number) touchSlop.invoke(center, motion)).floatValue());
        }, float.class, float.class, float.class, float.class);
        installer.hook(CENTER, "fling", chain -> {
            Object center = chain.getThisObject();
            State state = states.get(center);
            if (!active() || state == null || !tracking.getBoolean(center)) return chain.proceed();
            boolean expanding = (Boolean) chain.getArg(1);
            if (!state.originFixed && expanding) {
                states.remove(center);
                return chain.proceed();
            }
            if (state.settling) return chain.proceed();
            state.settling = true;
            state.expanding = expanding;
            state.nativeFinished = false;
            state.visualFinished = false;
            Object result = chain.proceed();
            float releaseTranslation = state.translation;
            float releaseAlpha = state.alpha;
            float target = expanding ? 0f : releaseTranslation;
            ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
            state.animator = animator;
            animator.setDuration(ReboundTimingHooks.duration(300L, expanding && slowRebound.getAsBoolean()));
            animator.setInterpolator(new DecelerateInterpolator());
            animator.addUpdateListener(frame -> {
                if (states.get(center) != state) return;
                float amount = (Float) frame.getAnimatedValue();
                state.translation = releaseTranslation + (target - releaseTranslation) * amount;
                if (!expanding) state.alpha = releaseAlpha * (1f - amount);
                try { write(center, state.translation); }
                catch (ReflectiveOperationException error) { log.accept("Cannot animate separate QS origin", error); }
            });
            animator.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator animation) {
                    if (states.get(center) != state) return;
                    state.animator = null;
                    state.visualFinished = true;
                    try { finish(center, state); }
                    catch (ReflectiveOperationException error) { log.accept("Cannot reset separate QS origin", error); }
                }
            });
            animator.start();
            return result;
        }, float.class, boolean.class);
        installer.hook(CENTER, "onFlingEnd", chain -> {
            Object result = chain.proceed();
            Object center = chain.getThisObject();
            State state = states.get(center);
            if (state != null && state.settling) {
                state.nativeFinished = true;
                finish(center, state);
            }
            return result;
        });
        installer.hook(CENTER, "startSpringBackAnimator", chain ->
                active() && states.containsKey(chain.getThisObject()) ? null : chain.proceed(), float.class);
    }

    private boolean active() { return enabled.getAsBoolean() || slowRebound.getAsBoolean(); }

    private void finish(Object center, State state) throws ReflectiveOperationException {
        if (!state.nativeFinished || !state.visualFinished || states.get(center) != state) return;
        states.remove(center);
        write(center, 0f);
    }

    private void write(Object center, float value) throws ReflectiveOperationException {
        ownWrite.set(true);
        try {
            translation.invoke(center, value);
            State state = states.get(center);
            if (state != null && closingFade(state)) alpha.invoke(center,
                    state.settling ? state.alpha : closingAlpha(center, state));
        }
        finally { ownWrite.remove(); }
    }
    private static boolean closingFade(State state) {
        return state.settling ? !state.expanding : !state.opening;
    }

    private float closingAlpha(Object center, State state) throws IllegalAccessException {
        float distance = 24f * ((Context) context.get(center)).getResources().getDisplayMetrics().density;
        return CombinedShadeAnimationHooks.closingAlpha(1f, 0f, state.translation, distance);
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static Method method(Class<?> type, String name, Class<?>... args) throws NoSuchMethodException {
        Method method = type.getDeclaredMethod(name, args); method.setAccessible(true); return method;
    }
    private static final class State {
        boolean opening, expanding, settling, nativeFinished, visualFinished, originFixed, hidden;
        float reference, translation, alpha = 1f;
        ValueAnimator animator;
    }
}
