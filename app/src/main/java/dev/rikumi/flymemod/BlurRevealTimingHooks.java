package dev.rikumi.flymemod;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.view.animation.AccelerateDecelerateInterpolator;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.BiConsumer;

/** Keep wallpaper blur ahead of visible shade content, including release-animation frames. */
final class BlurRevealTimingHooks {
    private static final float BLUR_TRANSITION_RANGE = .30f;
    private static final float CONTENT_HOLD_RANGE = .24f;
    private static final long MIN_BLUR_EXIT_MS = 300L;
    private final BiConsumer<String, Throwable> log;
    private final BooleanSupplier enabled;
    private final Map<Object, State> states = new WeakHashMap<>();
    private final ThreadLocal<Boolean> writing = new ThreadLocal<>();
    private final Method combined, value;
    private final Field shade;

    BlurRevealTimingHooks(ClassLoader loader, BooleanSupplier enabled, BiConsumer<String, Throwable> log) throws ReflectiveOperationException {
        this.enabled = enabled;
        this.log = log;
        Class<?> panel = loader.loadClass("com.android.systemui.shade.NotificationPanelViewController");
        shade = panel.getDeclaredField("mShadeInteractor");
        shade.setAccessible(true);
        combined = loader.loadClass("com.android.systemui.shade.domain.interactor.ShadeInteractor")
                .getMethod("isExpandToQsEnabled");
        value = loader.loadClass("kotlinx.coroutines.flow.StateFlow").getMethod("getValue");
    }

    void install(ClassLoader loader, SignalHooks.Installer installer) throws ReflectiveOperationException {
        String[] names = {"com.android.systemui.shade.NotificationPanelViewController",
                "com.flyme.systemui.controlcenter.phone.CenterController"};
        for (int i = 0; i < names.length; i++) {
            String name = names[i];
            boolean panel = i == 0;
            Class<?> type = loader.loadClass(name);
            Field max = type.getDeclaredField(panel ? "sBlur_RADIUS_MAX" : "sBLUR_RADIUS_MAX");
            Field radius = type.getDeclaredField("mCurrentBlurRadius");
            Field fraction = type.getDeclaredField("mExpandedFraction");
            Method setter = type.getDeclaredMethod("setBlurRadius", float.class);
            max.setAccessible(true); radius.setAccessible(true); fraction.setAccessible(true); setter.setAccessible(true);
            installer.hook(name, "setAnimationAlpha", chain -> {
                Object owner = chain.getThisObject();
                boolean active = enabled.getAsBoolean()
                        && (!panel || Boolean.TRUE.equals(value.invoke(combined.invoke(shade.get(owner)))));
                State state = states.computeIfAbsent(owner, ignored -> new State());
                state.active = active;
                float content = Math.max(0f, Math.min(1f, (Float) chain.getArg(0)));
                if (!active || content > state.content + .001f) cancelExit(state);
                else if (content < state.content - .001f) state.closing = true;
                // Whatever easing the content alpha hook applies, it is the actual visible content.
                state.content = content;
                Object result = chain.proceed();
                if (active && state.hasRadius) {
                    writing.set(true);
                    try { setter.invoke(owner, state.nativeRadius); }
                    finally { writing.remove(); }
                }
                return result;
            }, float.class);
            installer.hook(name, "setBlurRadius", chain -> {
                Object owner = chain.getThisObject();
                State state = states.get(owner);
                if (state == null) return chain.proceed();
                if (!state.active || !enabled.getAsBoolean()
                        || (panel && !Boolean.TRUE.equals(value.invoke(combined.invoke(shade.get(owner)))))) {
                    cancelExit(state);
                    state.lastApplied = (Float) chain.getArg(0);
                    return chain.proceed();
                }
                float nativeRadius = (Float) chain.getArg(0);
                if (!Boolean.TRUE.equals(writing.get())) {
                    state.nativeRadius = nativeRadius;
                    state.hasRadius = true;
                }
                float maximum = ((Number) max.get(null)).floatValue();
                if (maximum <= 0f) return chain.proceed();
                float blur = RowRevealHooks.revealCurve(nativeRadius / maximum / BLUR_TRANSITION_RANGE);
                // Do not let the independent blur spring clear the backdrop before content disappears.
                blur = Math.max(blur, RowRevealHooks.revealCurve(state.content / CONTENT_HOLD_RANGE));
                if (fraction.getFloat(owner) <= 0f && state.content <= 0f) blur = 0f;
                float target = blur * maximum;
                if (state.closing && !state.exitLimited && target < state.lastApplied - .01f) {
                    startExit(owner, setter, state);
                }
                // Keep the backdrop fading after the content/height has already reached zero.
                target = Math.max(target, state.exitFloor);
                // Native release springs seed themselves with mCurrentBlurRadius, which
                // already contains our accelerated blur. Applying the reveal curve again
                // can increase that radius on the first closing frame. Neither this
                // feedback nor a spring overshoot may brighten/deepen a closing backdrop.
                target = state.closing ? Math.min(target, state.lastApplied)
                        : Math.max(target, state.lastApplied);
                state.lastApplied = target;
                // The early full-blur plateau needs no repeated interactor/spring updates.
                if (Math.abs(radius.getFloat(owner) - target) < .01f) return null;
                return chain.proceed(new Object[]{target});
            }, float.class);
            installer.hook(name, "animateBlurRadiusTo", chain -> {
                State state = states.get(chain.getThisObject());
                if (state != null) {
                    if ((Boolean) chain.getArg(1)) cancelExit(state);
                    else state.closing = true;
                }
                return chain.proceed();
            }, float.class, boolean.class);
            installer.hook(name, "onTrackingStarted", chain -> {
                State state = states.get(chain.getThisObject());
                if (state != null) cancelExit(state);
                return chain.proceed();
            });
        }
    }

    private void startExit(Object owner, Method setter, State state) {
        state.exitLimited = true;
        state.exitFloor = state.lastApplied;
        ValueAnimator animator = ValueAnimator.ofFloat(state.exitFloor, 0f);
        state.exitAnimator = animator;
        animator.setDuration(MIN_BLUR_EXIT_MS);
        animator.setInterpolator(new AccelerateDecelerateInterpolator());
        animator.addUpdateListener(frame -> {
            if (state.exitAnimator != animator) return;
            state.exitFloor = (Float) frame.getAnimatedValue();
            writeBlur(owner, setter, state);
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (state.exitAnimator != animation) return;
                state.exitAnimator = null;
                state.exitFloor = 0f;
                writeBlur(owner, setter, state);
            }
        });
        animator.start();
    }

    private void writeBlur(Object owner, Method setter, State state) {
        Boolean previous = writing.get();
        writing.set(true);
        try { setter.invoke(owner, state.nativeRadius); }
        catch (ReflectiveOperationException error) {
            cancelExit(state);
            log.accept("Cannot animate control center blur exit", error);
        } finally {
            if (previous == null) writing.remove(); else writing.set(previous);
        }
    }

    private static void cancelExit(State state) {
        ValueAnimator animator = state.exitAnimator;
        state.exitAnimator = null;
        state.exitFloor = 0f;
        state.exitLimited = false;
        state.closing = false;
        if (animator != null) animator.cancel();
    }

    private static final class State {
        boolean active, hasRadius;
        boolean exitLimited, closing;
        float content, nativeRadius, lastApplied, exitFloor;
        ValueAnimator exitAnimator;
    }
}
