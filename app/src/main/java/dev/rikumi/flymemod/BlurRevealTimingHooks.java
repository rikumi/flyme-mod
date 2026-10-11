package dev.rikumi.flymemod;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.view.animation.AccelerateDecelerateInterpolator;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

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
    private final Method panelSetter, panelBarState;
    private WeakReference<Object> combinedOwner = new WeakReference<>(null);

    BlurRevealTimingHooks(ClassLoader loader, BooleanSupplier enabled, BiConsumer<String, Throwable> log) throws ReflectiveOperationException {
        this.enabled = enabled;
        this.log = log;
        Class<?> panel = loader.loadClass("com.android.systemui.shade.NotificationPanelViewController");
        shade = panel.getDeclaredField("mShadeInteractor");
        shade.setAccessible(true);
        panelSetter = panel.getDeclaredMethod("setBlurRadius", float.class);
        panelSetter.setAccessible(true);
        panelBarState = panel.getMethod("getBarState");
        combined = loader.loadClass("com.android.systemui.shade.domain.interactor.ShadeInteractor")
                .getMethod("isExpandToQsEnabled");
        value = loader.loadClass("kotlinx.coroutines.flow.StateFlow").getMethod("getValue");
    }

    void deoptimizeWriters(ClassLoader loader, Consumer<Method> deoptimize) throws ClassNotFoundException {
        for (String name : new String[]{"com.android.systemui.shade.NotificationPanelViewController",
                "com.flyme.systemui.controlcenter.phone.CenterController"}) {
            for (Method method : loader.loadClass(name).getDeclaredMethods()) {
                String methodName = method.getName();
                if (methodName.startsWith("lambda$initBlurSpring$") || methodName.equals("refreshBlurSpring")
                        || methodName.equals("animateBlurRadiusTo")) deoptimize.accept(method);
            }
        }
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
            Method barState = panel ? type.getMethod("getBarState") : null;
            Method visible = panel ? null : type.getMethod("shouldPanelBeVisible");
            Field keyguard = panel ? null : type.getField("mKeyguardStateController");
            Method showing = loader.loadClass("com.android.systemui.statusbar.policy.KeyguardStateController")
                    .getMethod("isShowing");
            max.setAccessible(true); radius.setAccessible(true); fraction.setAccessible(true); setter.setAccessible(true);
            installer.hook(name, "setAnimationAlpha", chain -> {
                Object owner = chain.getThisObject();
                boolean active = enabled.getAsBoolean()
                        && (panel ? (Integer) barState.invoke(owner) == 0
                            : !(Boolean) showing.invoke(keyguard.get(owner)) || (Boolean) visible.invoke(owner))
                        && (!panel || Boolean.TRUE.equals(value.invoke(combined.invoke(shade.get(owner)))));
                State state = states.computeIfAbsent(owner, ignored -> new State());
                state.active = active;
                if (panel && active && state.rowDriven) return chain.proceed();
                float content = Math.max(0f, Math.min(1f, (Float) chain.getArg(0)));
                if (!active) cancelExit(state);
                else if (!state.visibilityKnown) {
                    if (content > state.content + .001f) cancelExit(state);
                    else if (content < state.content - .001f) state.closing = true;
                }
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
                // Both controllers write the same repository. In merged mode an
                // idle separate controller must not clear/recreate its backdrop.
                if (!panel && ownsMergedBackdrop()) {
                    radius.setFloat(owner, (Float) chain.getArg(0));
                    return null;
                }
                State state = states.get(owner);
                if (state == null) return chain.proceed();
                // Keyguard uses the notification panel's expanded height for its own
                // layout. That height/content must never keep the QS blur alive.
                boolean lockscreenReset = panel ? (Integer) barState.invoke(owner) != 0
                        : (Boolean) showing.invoke(keyguard.get(owner)) && !(Boolean) visible.invoke(owner);
                if (lockscreenReset) {
                    reset(state);
                    return panel ? chain.proceed() : chain.proceed(new Object[]{0f});
                }
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
                if (panel && state.rowDriven) {
                    // Native springs keep their own state, but only row frames
                    // publish the merged backdrop. No second easing/exit animator.
                    if (!Boolean.TRUE.equals(writing.get())) {
                        radius.setFloat(owner, nativeRadius);
                        return null;
                    }
                    float target = RowRevealHooks.revealCurve(state.content) * maximum;
                    try {
                        if (target == state.lastApplied && state.rowPublished) return null;
                        state.lastApplied = target;
                        state.rowPublished = true;
                        return chain.proceed(new Object[]{target});
                    } finally {
                        radius.setFloat(owner, nativeRadius);
                    }
                }
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
                // Clamp spring overshoot without feeding the displayed radius back
                // into the native spring's next starting value.
                target = state.closing ? Math.min(target, state.lastApplied)
                        : Math.max(target, state.lastApplied);
                float previousTarget = state.lastApplied;
                // mCurrentBlurRadius belongs to the native spring. Both release and
                // tracking springs read it when restarting; storing our eased output
                // there applies the reveal curve again after every handoff.
                try {
                    if (Math.abs(previousTarget - target) < .01f
                            && (target != 0f || previousTarget == 0f)) return null;
                    state.lastApplied = target;
                    return chain.proceed(new Object[]{target});
                } finally {
                    radius.setFloat(owner, nativeRadius);
                }
            }, float.class);
            installer.hook(name, "animateBlurRadiusTo", chain -> {
                State state = states.get(chain.getThisObject());
                if (state != null) {
                    if ((Boolean) chain.getArg(1)) {
                        if (!state.visibilityKnown || state.contentVisible) cancelExit(state);
                    }
                    else state.closing = true;
                }
                return chain.proceed();
            }, float.class, boolean.class);
            installer.hook(name, "onTrackingStarted", chain -> {
                State state = states.get(chain.getThisObject());
                if (state != null && !state.visibilityKnown) cancelExit(state);
                return chain.proceed();
            });
            if (panel) {
                installer.hook(name, "resetBackgroundEffect", chain -> {
                    Object owner = chain.getThisObject();
                    State state = states.get(owner);
                    if (state != null && state.rowDriven && ownsMergedBackdrop()) {
                        // Height completion is not content completion. The final
                        // row frame publishes zero and removes the dim surface.
                        return state.content > 0f ? null : chain.proceed();
                    }
                    // notifyExpandingFinished also calls this when content reaches
                    // zero, before our blur-only tail finishes. Keep that tail, but
                    // allow lockscreen and disabled-mode resets immediately.
                    if (state != null && state.active && state.exitAnimator != null
                            && enabled.getAsBoolean() && (Integer) barState.invoke(owner) == 0
                            && Boolean.TRUE.equals(value.invoke(combined.invoke(shade.get(owner))))) return null;
                    if (state != null) reset(state);
                    return chain.proceed();
                });
            }
            // Lifecycle resets bypass the minimum exit duration and monotonic-radius
            // guard; otherwise a delayed animator can recreate a dismissed backdrop.
            String[] resets = panel ? new String[]{"instantCollapsePanel"}
                    : new String[]{"resetViews", "instantCollapse"};
            for (String resetMethod : resets) {
                type.getDeclaredMethod(resetMethod);
                installer.hook(name, resetMethod, chain -> {
                    Object owner = chain.getThisObject();
                    State state = states.get(owner);
                    boolean owned = state != null && state.active;
                    if (state != null) reset(state);
                    Object result = chain.proceed();
                    if (state != null) reset(state);
                    // Both controllers publish to the same blur interactor. An idle
                    // notification panel must not clear the separate control center.
                    if (owned) setter.invoke(owner, 0f);
                    return result;
                });
            }
        }
    }

    void onVisibilityChanged(Object owner, boolean visible) {
        State state = states.computeIfAbsent(owner, ignored -> new State());
        state.visibilityKnown = true;
        state.contentVisible = visible;
        if (visible) cancelExit(state);
        else state.closing = true;
    }

    void onRevealProgress(Object owner, float progress) {
        if (!panelSetter.getDeclaringClass().isInstance(owner)) return;
        try {
            if (!enabled.getAsBoolean() || (Integer) panelBarState.invoke(owner) != 0
                    || !Boolean.TRUE.equals(value.invoke(combined.invoke(shade.get(owner))))) return;
            State state = states.computeIfAbsent(owner, ignored -> new State());
            if (!state.rowDriven) cancelExit(state);
            state.rowDriven = true;
            state.active = true;
            state.content = Math.max(0f, Math.min(1f, progress));
            combinedOwner = new WeakReference<>(owner);
            writeBlur(owner, panelSetter, state);
        } catch (ReflectiveOperationException error) {
            log.accept("Cannot synchronize merged shade backdrop", error);
        }
    }

    private boolean ownsMergedBackdrop() throws ReflectiveOperationException {
        Object owner = combinedOwner.get();
        State state = states.get(owner);
        return owner != null && state != null && state.active && state.rowDriven
                && enabled.getAsBoolean() && (Integer) panelBarState.invoke(owner) == 0
                && Boolean.TRUE.equals(value.invoke(combined.invoke(shade.get(owner))));
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

    private static void reset(State state) {
        cancelExit(state);
        state.active = false;
        state.visibilityKnown = false;
        state.rowDriven = state.rowPublished = false;
        state.hasRadius = false;
        state.content = state.nativeRadius = state.lastApplied = 0f;
    }

    private static final class State {
        boolean active, hasRadius, visibilityKnown, contentVisible, rowDriven, rowPublished;
        boolean exitLimited, closing;
        float content, nativeRadius, lastApplied, exitFloor;
        ValueAnimator exitAnimator;
    }
}
