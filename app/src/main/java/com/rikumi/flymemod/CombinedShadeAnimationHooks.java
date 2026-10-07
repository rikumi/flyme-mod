package com.rikumi.flymemod;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.os.SystemClock;
import android.view.View;
import android.view.MotionEvent;
import android.view.animation.DecelerateInterpolator;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/** Flyme SystemUI 16260625: gesture-driven reveal and dismissal, with native height settling. */
final class CombinedShadeAnimationHooks {
    private static final String PANEL = "com.android.systemui.shade.NotificationPanelViewController";
    private static final String QS = "com.android.systemui.shade.QuickSettingsControllerImpl";
    private static final long MIN_TRANSITION_DURATION_MS = 300L;
    private static final float REVEAL_FRACTION = .07f;
    private static final float DISMISSAL_DISTANCE_DP = 24f;
    private static final float CONTAINER_TRANSLATION_FACTOR = .54f;
    private final Consumer<Context> settings;
    private final BooleanSupplier enabled, animationFix, collapseFix, originFix;
    private final BiConsumer<String, Throwable> log;
    private Predicate<Object> secondaryOwner = ignored -> false;
    private Predicate<Object> secondaryTouchOwner = ignored -> false;
    private final Map<Object, Gesture> gestures = new WeakHashMap<>();
    private final ThreadLocal<MotionEvent> touchEvent = new ThreadLocal<>();
    private final Field view, qs, interactor, fraction, closedOnDown, filter, expand, collapse;
    private final Field initialY, pointer, touchSlop;
    private final Method tracking, closing, maxHeight, alpha, getQs, getContainer, translation;
    private final Method expandEnabled, flowValue, damping;
    private final Method instantCollapse, cancelSpring;
    private final Method eventTouchSlop;
    private final Field panelLazy;
    private final Method lazyValue, cancelHeight, trackingStopped, expandingFinished;

    CombinedShadeAnimationHooks(ClassLoader loader, Consumer<Context> settings,
                                BooleanSupplier enabled, BooleanSupplier collapseFix) throws ReflectiveOperationException {
        this(loader, settings, enabled, collapseFix, () -> false, (message, error) -> {});
    }

    CombinedShadeAnimationHooks(ClassLoader loader, Consumer<Context> settings,
                                BooleanSupplier enabled, BooleanSupplier collapseFix,
                                BooleanSupplier originFix, BiConsumer<String, Throwable> log) throws ReflectiveOperationException {
        this.settings = settings;
        this.enabled = () -> enabled.getAsBoolean() || originFix.getAsBoolean();
        this.animationFix = enabled;
        this.collapseFix = collapseFix;
        this.originFix = originFix;
        this.log = log;
        Class<?> panel = loader.loadClass(PANEL);
        Class<?> quickSettings = loader.loadClass(QS);
        view = field(panel, "mView");
        qs = field(panel, "mQsController");
        interactor = field(panel, "mShadeInteractor");
        fraction = field(panel, "mExpandedFraction");
        closedOnDown = field(panel, "mPanelClosedOnDown");
        filter = field(panel, "mShowingFilterPanel");
        expand = field(panel, "mExpandAnimatorSet");
        collapse = field(panel, "mCollapseAnimatorSet");
        initialY = field(panel, "mInitialExpandY");
        pointer = field(panel, "mTrackingPointer");
        touchSlop = field(panel, "mTouchSlop");
        eventTouchSlop = method(panel, "getTouchSlop", MotionEvent.class);
        panelLazy = field(quickSettings, "mPanelViewControllerLazy");
        lazyValue = panelLazy.getType().getMethod("get");
        cancelHeight = method(panel, "cancelHeightAnimator");
        trackingStopped = method(panel, "onTrackingStopped", boolean.class);
        expandingFinished = method(panel, "notifyExpandingFinished");
        tracking = method(panel, "isTracking");
        closing = method(panel, "isClosing");
        maxHeight = method(panel, "getMaxPanelHeight");
        alpha = method(panel, "setAnimationAlpha", float.class);
        instantCollapse = method(panel, "instantCollapsePanel");
        getQs = method(quickSettings, "getQs");
        getContainer = method(quickSettings, "getContainer");
        translation = method(quickSettings, "setQSTranslationY", float.class);
        cancelSpring = loader.loadClass("com.android.systemui.plugins.qs.QS").getMethod("cancelQsHeaderAnimator");
        expandEnabled = loader.loadClass("com.android.systemui.shade.domain.interactor.ShadeInteractor")
                .getMethod("isExpandToQsEnabled");
        flowValue = loader.loadClass("kotlinx.coroutines.flow.StateFlow").getMethod("getValue");
        damping = loader.loadClass("com.flyme.systemui.utils.SystemUICommonUtils")
                .getMethod("getDampingInterpolation", float.class);
    }

    void setSecondaryOwner(Predicate<Object> owner) { secondaryOwner = owner; }
    void setSecondaryTouchOwner(Predicate<Object> owner) { secondaryTouchOwner = owner; }

    void releaseForSecondary(Object quickSettings) {
        try {
            // Remove the old owner before cancel() delivers its synchronous end callbacks.
            Gesture previous = gestures.remove(quickSettings);
            if (previous != null && previous.positionAnimator != null) previous.positionAnimator.cancel();
            Object panel = lazyValue.invoke(panelLazy.get(quickSettings));
            cancel(expand, panel);
            cancel(collapse, panel);
            stopSpring(quickSettings);
            cancelHeight.invoke(panel);
            if ((Boolean) tracking.invoke(panel)) trackingStopped.invoke(panel, true);
            expandingFinished.invoke(panel);
            translation.invoke(quickSettings, 0f);
            alpha.invoke(panel, 1f);
        } catch (ReflectiveOperationException error) {
            log.accept("Cannot transfer merged shade animation ownership", error);
        }
    }

    void install(SignalHooks.Installer installer) {
        installer.hook(PANEL, "flingExpands", chain -> {
            Object panel = chain.getThisObject();
            settings.accept(((View) view.get(panel)).getContext());
            MotionEvent event = touchEvent.get();
            if (!originFix.getAsBoolean() || !(Boolean) tracking.invoke(panel)
                    || !isCombined(panel) || filter.getBoolean(panel)
                    || (event != null && event.getActionMasked() == MotionEvent.ACTION_CANCEL)) {
                return chain.proceed();
            }
            float threshold = event == null ? touchSlop.getInt(panel)
                    : ((Number) eventTouchSlop.invoke(panel, event)).floatValue();
            float distance = ((Number) chain.getArg(3)).floatValue() - initialY.getFloat(panel);
            // This is the physical drag, before the 56dp visual origin compensation.
            return expandsFromDrag(distance, threshold);
        }, float.class, float.class, float.class, float.class);
        installer.hook(PANEL + "$TouchHandler", "handleTouch", chain -> {
            MotionEvent previous = touchEvent.get();
            touchEvent.set((MotionEvent) chain.getArg(0));
            try {
                return chain.proceed();
            } finally {
                if (previous == null) touchEvent.remove();
                else touchEvent.set(previous);
            }
        }, MotionEvent.class);
        installer.hook(QS, "handleTouch", chain -> {
            if (secondaryTouchOwner.test(chain.getThisObject())) return chain.proceed();
            Gesture gesture = gestures.get(chain.getThisObject());
            Object panel = gesture == null ? null : gesture.panel.get();
            // A reversed panel-dismissal drag belongs to the panel, not the second QS pull.
            if (enabled.getAsBoolean() && gesture != null && gesture.dismissal
                    && !gesture.released && panel != null && (Boolean) tracking.invoke(panel)
                    && isCombined(panel) && !filter.getBoolean(panel)) return false;
            return chain.proceed();
        }, MotionEvent.class, boolean.class, boolean.class);
        installer.hook(PANEL, "onHeightUpdated", chain -> {
            Object result = chain.proceed();
            resetIfCollapsed(chain.getThisObject());
            return result;
        }, float.class);
        installer.hook(PANEL, "onExpandingFinished", chain -> {
            Object result = chain.proceed();
            Object panel = chain.getThisObject();
            resetIfCollapsed(panel);
            Gesture gesture = gestures.get(qs.get(panel));
            if (gesture != null && (gesture.originFixed || gesture.minimumDuration) && gesture.settling) {
                gesture.nativeFinished = gesture.expanding ? fraction.getFloat(panel) >= 1f
                        : fraction.getFloat(panel) <= 0f;
                if (gesture.positionAnimator != null) return result;
            }
            if (enabled.getAsBoolean() && gesture != null && (gesture.dismissal || gesture.originFixed || gesture.minimumDuration)
                    && gesture.settling && gesture.expanding && fraction.getFloat(panel) >= 1f) {
                translation.invoke(qs.get(panel), 0f);
                alpha.invoke(panel, 1f);
                gestures.remove(qs.get(panel));
            }
            return result;
        });
        installer.hook(PANEL, "fling", chain -> {
            Object panel = chain.getThisObject();
            settings.accept(((View) view.get(panel)).getContext());
            if (secondaryOwner.test(qs.get(panel))) return chain.proceed();
            Gesture gesture = gestures.get(qs.get(panel));
            if (gesture != null && gesture.releasedByFling && gesture.expanding != (Boolean) chain.getArg(1)) {
                // A command or reversed gesture can change the endpoint before the old
                // visual timer finishes. Preserve its current position, but retire its writer.
                ValueAnimator previousAnimator = gesture.positionAnimator;
                gesture.positionAnimator = null;
                if (previousAnimator != null) previousAnimator.cancel();
                gesture.nativeFinished = false;
                gesture.releasedByFling = false;
            }
            // Launcher input-focus transfer and command-driven expansion can reach fling
            // without a tracking height callback. Give that path the same visual timer.
            if (animationFix.getAsBoolean() && gesture == null && (Boolean) chain.getArg(1)
                    && isCombined(panel) && !filter.getBoolean(panel)
                    && fraction.getFloat(panel) <= REVEAL_FRACTION
                    && !secondaryOwner.test(qs.get(panel))
                    && getQs.invoke(qs.get(panel)) != null && getContainer.invoke(qs.get(panel)) != null) {
                gesture = new Gesture();
                gesture.panel = new WeakReference<>(panel);
                gesture.openingStarted = SystemClock.uptimeMillis();
                gesture.openingRemaining = MIN_TRANSITION_DURATION_MS;
                gesture.minimumDuration = true;
                if (originFix.getAsBoolean()) {
                    gesture.referenceDistance = referenceDistance((View) getContainer.invoke(qs.get(panel)));
                    gesture.originFixed = gesture.referenceDistance > 0f;
                }
                gesture.translation = gesture.originFixed
                        ? -gesture.referenceDistance / CONTAINER_TRANSLATION_FACTOR
                        : revealTranslation(fraction.getFloat(panel), ((Number) maxHeight.invoke(panel)).floatValue(), 0f);
                gesture.alpha = 0f;
                gestures.put(qs.get(panel), gesture);
                translation.invoke(qs.get(panel), gesture.translation);
                alpha.invoke(panel, 0f);
            }
            if (gesture != null && gesture.minimumDuration && gesture.positionAnimator != null) {
                // A native layout correction may fling again; keep the original release timer.
                return chain.proceed();
            }
            MotionEvent releaseEvent = touchEvent.get();
            if (animationFix.getAsBoolean() && gesture != null && !gesture.dismissal
                    && !gesture.releasedByFling && (Boolean) chain.getArg(1)
                    && isCombined(panel) && !filter.getBoolean(panel)
                    && !secondaryOwner.test(qs.get(panel))
                    && (releaseEvent == null || releaseEvent.getActionMasked() != MotionEvent.ACTION_CANCEL)) {
                gesture.openingRemaining = remainingOpeningDuration(SystemClock.uptimeMillis() - gesture.openingStarted);
                gesture.minimumDuration = gesture.openingRemaining > 0L || !(Boolean) tracking.invoke(panel);
            }
            if (animationFix.getAsBoolean() && gesture != null && gesture.dismissal
                    && !gesture.releasedByFling && (Boolean) tracking.invoke(panel)
                    && isCombined(panel) && !filter.getBoolean(panel) && !secondaryOwner.test(qs.get(panel))) {
                gesture.minimumDuration = true;
            }
            if (enabled.getAsBoolean() && gesture != null && (gesture.dismissal || gesture.originFixed || gesture.minimumDuration)
                    && isCombined(panel) && !filter.getBoolean(panel)) {
                // Native height settling retains the release height on an upward drag (<= 7%).
                // Own visual translation/alpha through that animation; do not return to QS spring.
                gesture.released = true;
                gesture.releasedByFling = true;
                gesture.settling = true;
                gesture.expanding = (Boolean) chain.getArg(1);
                // Flyme starts the return spring at 7% even after a downward overshoot.
                // Use that native animation endpoint, retaining our actual visual position.
                gesture.releaseFraction = Math.min(REVEAL_FRACTION, fraction.getFloat(panel));
                gesture.releaseTranslation = gesture.translation;
                gesture.releaseAlpha = gesture.alpha;
                if (gesture.originFixed || gesture.minimumDuration) {
                    stopSpring(qs.get(panel));
                    startPositionAnimator(panel, qs.get(panel), gesture);
                }
            }
            // endMotionEvent has already decided whether the release should collapse. Avoid
            // intercepting opening, canceled swipes, programmatic closure or lockscreen gestures.
            if (collapseFix.getAsBoolean() && (gesture == null || (!gesture.originFixed && !gesture.minimumDuration)) && !(Boolean) chain.getArg(1)
                    && (Float) chain.getArg(0) < 0f && (Boolean) tracking.invoke(panel)
                    && !closedOnDown.getBoolean(panel) && !filter.getBoolean(panel)
                    && Boolean.TRUE.equals(flowValue.invoke(expandEnabled.invoke(interactor.get(panel))))) {
                // Native cleanup aborts height animations, resets expansion and finishes closing.
                // No new fling is started, so neither fixed 7% start nor blur collapse is replayed.
                instantCollapse.invoke(panel);
                Object quickSettings = qs.get(panel);
                Object plugin = getQs.invoke(quickSettings);
                if (plugin != null) cancelSpring.invoke(plugin);
                translation.invoke(quickSettings, 0f);
                gestures.remove(quickSettings);
                return null;
            }
            return chain.proceed();
        }, float.class, boolean.class, float.class, boolean.class);
        installer.hook(PANEL, "onTrackingStarted", chain -> {
            Object panel = chain.getThisObject();
            settings.accept(((View) view.get(panel)).getContext());
            Gesture previous = gestures.remove(qs.get(panel));
            if (previous != null && previous.positionAnimator != null) previous.positionAnimator.cancel();
            if (enabled.getAsBoolean() && isCombined(panel)
                    && !filter.getBoolean(panel)) {
                // A quick reopen may interrupt the previous close before its final height update.
                // Stop all old writers before letting this gesture own alpha and translation.
                cancel(expand, panel);
                cancel(collapse, panel);
                stopSpring(qs.get(panel));
                // A completed upward dismissal does not always leave mPanelClosedOnDown set
                // for the next gesture. At zero expansion, discard every writer and stale
                // alpha/translation before the new gesture starts so a quick reopen is visible.
                if (fraction.getFloat(panel) <= 0f) resetReveal(panel);
            }
            return chain.proceed();
        });
        installer.hook(PANEL, "updateHeightAnimation", chain -> {
            Object panel = chain.getThisObject();
            settings.accept(((View) view.get(panel)).getContext());
            // isExpandToQsEnabled already requires classics mode, SHADE and enabled QS.
            if (!enabled.getAsBoolean() || filter.getBoolean(panel)
                    || !Boolean.TRUE.equals(flowValue.invoke(expandEnabled.invoke(interactor.get(panel))))) {
                return chain.proceed();
            }
            Object quickSettings = qs.get(panel);
            if (secondaryOwner.test(quickSettings)) return null;
            if (getQs.invoke(quickSettings) == null || getContainer.invoke(quickSettings) == null) {
                return chain.proceed();
            }
            Gesture gesture = gestures.get(quickSettings);
            boolean isTracking = (Boolean) tracking.invoke(panel);
            if (gesture != null && (gesture.dismissal || gesture.originFixed || gesture.minimumDuration) && gesture.released) {
                if (gesture.settling && !gesture.originFixed && !gesture.minimumDuration) updateDismissal(panel, quickSettings, gesture);
                return null;
            }
            if (!isTracking || ((Boolean) closing.invoke(panel)
                    && (gesture == null || !gesture.dismissal))) return chain.proceed();
            // ACTION_UP starts the spring before handleTouch clears tracking. Do not overwrite it.
            if (gesture != null && gesture.released) return null;
            if (gesture == null) {
                cancel(expand, panel);
                cancel(collapse, panel);
                stopSpring(quickSettings);
                gesture = new Gesture();
                gesture.panel = new WeakReference<>(panel);
                gesture.openingStarted = SystemClock.uptimeMillis();
                gesture.dismissal = !closedOnDown.getBoolean(panel);
                if (originFix.getAsBoolean()) {
                    gesture.referenceDistance = referenceDistance((View) getContainer.invoke(quickSettings));
                    gesture.originFixed = gesture.referenceDistance > 0f;
                }
                gestures.put(quickSettings, gesture);
            }
            if (gesture.originFixed) {
                updateDismissal(panel, quickSettings, gesture);
                return null;
            }
            if (gesture.dismissal) {
                updateDismissal(panel, quickSettings, gesture);
                return null;
            }
            float progress = Math.max(0f, Math.min(1f, fraction.getFloat(panel)));
            float height = ((Number) maxHeight.invoke(panel)).floatValue();
            float damped = progress <= REVEAL_FRACTION ? 0f
                    : ((Number) damping.invoke(null, progress - REVEAL_FRACTION)).floatValue();
            gesture.translation = revealTranslation(progress, height, damped);
            gesture.alpha = limitOpeningAlpha(gesture, revealAlpha(progress));
            // Native translation distributes movement across header, tiles and notifications.
            translation.invoke(quickSettings, gesture.translation);
            alpha.invoke(panel, gesture.alpha);
            return null;
        }, float.class);
        installer.hook(QS, "startQsHeaderAnimator", chain -> {
            Object quickSettings = chain.getThisObject();
            if (secondaryOwner.test(quickSettings)) return chain.proceed();
            Gesture gesture = gestures.get(quickSettings);
            if (enabled.getAsBoolean() && gesture != null && (gesture.originFixed || gesture.minimumDuration)) {
                // fling may request another native header spring after our release animator
                // has started. Keep one position writer and restore the release position.
                translation.invoke(quickSettings, gesture.translation);
                gesture.released = true;
                stopSpring(quickSettings);
                return null;
            }
            if (enabled.getAsBoolean() && gesture != null && !gesture.released) {
                // Stock ACTION_UP resets translation to maxHeight * .07. Restore the last drag
                // position so the original velocity/damping spring starts without a jump.
                translation.invoke(quickSettings, gesture.translation);
                gesture.released = true;
                if (gesture.dismissal || gesture.originFixed || gesture.minimumDuration) {
                    // An upward dismissal must not spring back to the fully open position.
                    stopSpring(quickSettings);
                    return null;
                }
            }
            return chain.proceed();
        }, float.class);
    }

    private void updateDismissal(Object panel, Object quickSettings, Gesture gesture)
            throws ReflectiveOperationException {
        float progress = fraction.getFloat(panel);
        float height = ((Number) maxHeight.invoke(panel)).floatValue();
        if (!gesture.released) {
            MotionEvent event = touchEvent.get();
            if (event != null && event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                int index = event.findPointerIndex(pointer.getInt(panel));
                gesture.fingerDistance = event.getY(Math.max(0, index)) - initialY.getFloat(panel);
            }
            // A layout callback can still report fraction=1 before the first MOVE. The finger
            // has not moved; neither that value nor the native 7% height is an overshoot.
            float distance = DISMISSAL_DISTANCE_DP * ((View) view.get(panel)).getResources()
                    .getDisplayMetrics().density;
            if (gesture.originFixed && !gesture.dismissal) {
                gesture.translation = originTranslation(gesture.fingerDistance, gesture.referenceDistance);
                gesture.alpha = limitOpeningAlpha(gesture, Math.max(0f, Math.min(1f,
                        gesture.fingerDistance / gesture.referenceDistance)));
            } else {
                gesture.translation = dragTranslation(gesture.fingerDistance, distance);
                gesture.alpha = dragAlpha(gesture.fingerDistance, distance);
            }
            translation.invoke(quickSettings, gesture.translation);
            alpha.invoke(panel, gesture.alpha);
            return;
        }
        if (gesture.settling && gesture.expanding) {
            // A canceled dismissal returns from its actual visual position, rather than jumping
            // to the native fade-in threshold at 7% height.
            float remaining = Math.max(.0001f, 1f - gesture.releaseFraction);
            float amount = Math.max(0f, Math.min(1f, (progress - gesture.releaseFraction) / remaining));
            gesture.translation = gesture.releaseTranslation * (1f - amount);
            gesture.alpha = gesture.releaseAlpha + (1f - gesture.releaseAlpha) * amount;
        } else if (gesture.originFixed && gesture.settling) {
            float amount = Math.max(0f, Math.min(1f,
                    (gesture.releaseFraction - progress) / Math.max(.0001f, gesture.releaseFraction)));
            float target = -gesture.referenceDistance / CONTAINER_TRANSLATION_FACTOR;
            gesture.translation = gesture.releaseTranslation + (target - gesture.releaseTranslation) * amount;
            gesture.alpha = gesture.releaseAlpha * (1f - amount);
        } else {
            float distance = DISMISSAL_DISTANCE_DP * ((View) view.get(panel)).getResources()
                    .getDisplayMetrics().density;
            gesture.alpha = dismissalAlpha(progress, height, distance);
            gesture.translation = dismissalTranslation(progress, height, distance);
            if (gesture.settling && gesture.releaseTranslation > 0f) {
                float amount = Math.max(0f, Math.min(1f,
                        (REVEAL_FRACTION - progress) / REVEAL_FRACTION));
                gesture.translation = gesture.releaseTranslation * (1f - amount)
                        + gesture.translation * amount;
                gesture.alpha = gesture.releaseAlpha * (1f - amount) + gesture.alpha * amount;
            }
        }
        translation.invoke(quickSettings, gesture.translation);
        alpha.invoke(panel, gesture.alpha);
    }

    private boolean isCombined(Object panel) throws ReflectiveOperationException {
        return Boolean.TRUE.equals(flowValue.invoke(expandEnabled.invoke(interactor.get(panel))));
    }

    private void resetIfCollapsed(Object panel) throws ReflectiveOperationException {
        settings.accept(((View) view.get(panel)).getContext());
        if (enabled.getAsBoolean() && fraction.getFloat(panel) <= 0f && isCombined(panel)
                && !filter.getBoolean(panel)) {
            Gesture gesture = gestures.get(qs.get(panel));
            if (gesture != null && (gesture.originFixed || gesture.minimumDuration) && gesture.positionAnimator != null) {
                if (!gesture.expanding) gesture.nativeFinished = true;
                return;
            }
            // This runs synchronously at the zero-height update, not on the next frame/gesture.
            resetReveal(panel);
        }
    }

    private void resetReveal(Object panel) throws ReflectiveOperationException {
        Object quickSettings = qs.get(panel);
        Gesture previous = gestures.remove(quickSettings);
        if (previous != null && previous.positionAnimator != null) previous.positionAnimator.cancel();
        cancel(expand, panel);
        cancel(collapse, panel);
        stopSpring(quickSettings);
        translation.invoke(quickSettings, 0f);
        if (getQs.invoke(quickSettings) != null && getContainer.invoke(quickSettings) != null) {
            alpha.invoke(panel, 0f);
        }
    }

    private void stopSpring(Object quickSettings) throws ReflectiveOperationException {
        Object plugin = getQs.invoke(quickSettings);
        if (plugin != null) cancelSpring.invoke(plugin);
    }

    static float revealTranslation(float progress, float height, float damped) {
        return height * Math.min(progress, REVEAL_FRACTION)
                + height * REVEAL_FRACTION * 4f * damped;
    }

    static float revealAlpha(float progress) {
        return Math.max(0f, Math.min(1f, progress / REVEAL_FRACTION));
    }

    static float dismissalAlpha(float progress, float height, float fadeDistance) {
        return Math.max(0f, 1f - dismissalTravel(progress, height, fadeDistance) / fadeDistance);
    }

    static float dismissalTranslation(float progress, float height, float fadeDistance) {
        if (progress > REVEAL_FRACTION) {
            // MOVE uses 0.7 * finger delta; MzQSImpl moves the card container by 0.54 *
            // the supplied translation. Compensate so the cards follow at half finger speed.
            float fingerDistance = (progress - REVEAL_FRACTION) * height / .7f;
            return fingerDistance * .5f / CONTAINER_TRANSLATION_FACTOR;
        }
        // Combined MOVE maps finger distance to maxHeight * .07 + distance * .7.
        // Native header/container translation coefficients are about .5, so compensate here
        // to follow the finger only through the first 24dp, then remain invisible and stationary.
        return -2f * dismissalTravel(progress, height, fadeDistance);
    }

    static float dragTranslation(float fingerDistance, float fadeDistance) {
        return fingerDistance > 0f ? fingerDistance * .5f / CONTAINER_TRANSLATION_FACTOR
                : -2f * Math.min(fadeDistance, -fingerDistance);
    }

    static float dragAlpha(float fingerDistance, float fadeDistance) {
        return Math.max(0f, Math.min(1f, 1f + fingerDistance / fadeDistance));
    }

    static float originTranslation(float fingerDistance, float referenceDistance) {
        float offset = fingerDistance - referenceDistance;
        return (offset > 0f ? offset * .5f : offset) / CONTAINER_TRANSLATION_FACTOR;
    }

    static boolean expandsFromDrag(float distance, float touchSlop) {
        return distance >= Math.max(1f, touchSlop);
    }

    private float limitOpeningAlpha(Gesture gesture, float nativeAlpha) {
        if (!animationFix.getAsBoolean() || gesture.dismissal) return nativeAlpha;
        float elapsed = Math.max(0L, SystemClock.uptimeMillis() - gesture.openingStarted);
        return Math.min(nativeAlpha, Math.min(1f, elapsed / MIN_TRANSITION_DURATION_MS));
    }

    static long remainingOpeningDuration(long elapsed) {
        return Math.max(0L, MIN_TRANSITION_DURATION_MS - Math.max(0L, elapsed));
    }

    private void startPositionAnimator(Object panel, Object quickSettings, Gesture gesture) throws ReflectiveOperationException {
        float closingTarget = gesture.originFixed ? -gesture.referenceDistance / CONTAINER_TRANSLATION_FACTOR
                : -2f * DISMISSAL_DISTANCE_DP * ((View) view.get(panel)).getResources().getDisplayMetrics().density;
        float target = gesture.expanding ? 0f : closingTarget;
        float fadeDistance = DISMISSAL_DISTANCE_DP * ((View) view.get(panel))
                .getResources().getDisplayMetrics().density;
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        ValueAnimator previousAnimator = gesture.positionAnimator;
        gesture.positionAnimator = animator;
        if (previousAnimator != null) previousAnimator.cancel();
        long minimum = animationFix.getAsBoolean() ? MIN_TRANSITION_DURATION_MS : 280L;
        animator.setDuration(gesture.expanding
                ? ReboundTimingHooks.duration(Math.max(minimum, gesture.openingRemaining), animationFix.getAsBoolean())
                : minimum);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(frame -> {
            if (gestures.get(quickSettings) != gesture || gesture.positionAnimator != animator) return;
            float amount = (Float) frame.getAnimatedValue();
            gesture.translation = gesture.releaseTranslation + (target - gesture.releaseTranslation) * amount;
            if (gesture.expanding) {
                gesture.alpha = gesture.releaseAlpha + (1f - gesture.releaseAlpha) * amount;
            } else {
                // Closing alpha follows actual travel, not the minimum position duration.
                gesture.alpha = closingAlpha(gesture.releaseAlpha, gesture.releaseTranslation,
                        gesture.translation, fadeDistance);
            }
            try {
                translation.invoke(quickSettings, gesture.translation);
                alpha.invoke(panel, gesture.alpha);
            } catch (ReflectiveOperationException error) {
                log.accept("Cannot animate QS translation origin", error);
            }
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (gesture.positionAnimator != animation) return;
                gesture.positionAnimator = null;
                if (gestures.get(quickSettings) != gesture || !gesture.nativeFinished) return;
                try {
                    if (gesture.expanding) {
                        gestures.remove(quickSettings);
                        translation.invoke(quickSettings, 0f);
                        alpha.invoke(panel, 1f);
                    } else resetReveal(panel);
                } catch (ReflectiveOperationException error) {
                    log.accept("Cannot finish QS translation origin animation", error);
                }
            }
        });
        animator.start();
    }

    static float closingAlpha(float releaseAlpha, float releaseTranslation, float translation, float fadeDistance) {
        float travel = Math.max(0f, releaseTranslation - translation) * CONTAINER_TRANSLATION_FACTOR;
        return Math.max(0f, Math.min(1f, releaseAlpha - travel / Math.max(1f, fadeDistance)));
    }

    private static float referenceDistance(View container) {
        return 56f * container.getResources().getDisplayMetrics().density;
    }

    private static float dismissalTravel(float progress, float height, float fadeDistance) {
        return Math.max(0f, Math.min(fadeDistance, (REVEAL_FRACTION - progress) * height / .7f));
    }

    private static void cancel(Field field, Object panel) throws IllegalAccessException {
        Animator animator = (Animator) field.get(panel);
        if (animator != null) animator.cancel();
        field.set(panel, null);
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Method method(Class<?> type, String name, Class<?>... parameters)
            throws NoSuchMethodException {
        Method method = type.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method;
    }

    private static final class Gesture {
        WeakReference<Object> panel = new WeakReference<>(null);
        ValueAnimator positionAnimator;
        float translation, alpha, releaseFraction, releaseTranslation, releaseAlpha, fingerDistance, referenceDistance;
        long openingStarted, openingRemaining;
        boolean released, releasedByFling, dismissal, settling, expanding, originFixed, nativeFinished, minimumDuration;
    }
}
