package dev.rikumi.flymemod;

import android.content.Context;
import android.animation.ValueAnimator;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.view.View;
import android.view.MotionEvent;
import android.view.animation.LinearInterpolator;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.graphics.Rect;
import android.os.Trace;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.BiConsumer;

/** Whole-shade row reveal/dismissal; secondary QS expansion keeps its geometry animation. */
final class RowRevealHooks {
    private static final String CENTER = "com.flyme.systemui.controlcenter.phone.CenterController";
    private static final String QS = "com.flyme.systemui.qs.MzQSImpl";
    private static final String PANEL_CONTROLLER = "com.flyme.systemui.controlcenter.phone.MzQSPanelController";
    private static final String SHADE = "com.android.systemui.shade.NotificationPanelViewController";
    // One time-driven content transition; native geometry and blur remain independent.
    private static final float ROW_DELAY = .22f;
    private final Consumer<Context> settings;
    private final BiConsumer<String, Throwable> log;
    private final BooleanSupplier enabled;
    private final Field separateController, fullController, miniController, controllerView;
    private final Field separateHeader, separateStatusBar, combinedHeader, combinedStatusBar;
    private final Field miniNetwork, miniSliders, nativeScale;
    private final Field notificationStack, shadeInteractor;
    private final Method combinedMode, flowValue, notificationTop;
    private final ThreadLocal<View> currentNotifications = new ThreadLocal<>();
    private final ThreadLocal<NotificationRegion> currentNotificationRegion = new ThreadLocal<>();
    private final Class<?> tileType;
    private final Map<Object, State> states = new WeakHashMap<>();
    private final Map<Object, Float> rawAlpha = new WeakHashMap<>();
    private final ThreadLocal<AlphaSeed> alphaSeed = new ThreadLocal<>();
    private final Map<Object, Reveal> reveals = new WeakHashMap<>();
    private final List<BiConsumer<Object, Boolean>> visibilityListeners = new ArrayList<>();
    private final List<BiConsumer<Object, Float>> progressListeners = new ArrayList<>();
    private final Map<View, HeaderBinding> headerBindings = new WeakHashMap<>();
    private final ThreadLocal<MotionEvent> touch = new ThreadLocal<>();
    private final ThreadLocal<Reveal> currentReveal = new ThreadLocal<>();
    private final ThreadLocal<Boolean> writingReveal = new ThreadLocal<>();
    private final Field centerTracking, centerClosed, centerY, centerPointer, centerContext, centerFraction;
    private final Field panelY, panelPointer, panelClosed, panelView, panelFraction;
    private final Method panelTracking, centerAlpha, panelAlpha, centerCollapseTranslation;
    private static final long CONTENT_DURATION_MS = 300L;

    RowRevealHooks(ClassLoader loader, Consumer<Context> settings, BooleanSupplier enabled, BiConsumer<String, Throwable> log)
            throws ReflectiveOperationException {
        Class<?> center = loader.loadClass(CENTER);
        Class<?> shade = loader.loadClass(SHADE);
        centerTracking = field(center, "mTracking"); centerClosed = field(center, "mCenterClosedOnDown");
        centerY = field(center, "mInitialTouchY"); centerPointer = field(center, "mTrackingPointer");
        centerContext = field(center, "mContext"); centerFraction = field(center, "mExpandedFraction");
        panelY = field(shade, "mInitialExpandY"); panelPointer = field(shade, "mTrackingPointer");
        panelClosed = field(shade, "mPanelClosedOnDown"); panelView = field(shade, "mView");
        panelFraction = field(shade, "mExpandedFraction");
        panelTracking = shade.getDeclaredMethod("isTracking"); panelTracking.setAccessible(true);
        centerAlpha = center.getDeclaredMethod("setAnimationAlpha", float.class);
        centerCollapseTranslation = center.getDeclaredMethod("setCollapseAnimationTranslationY", float.class);
        centerCollapseTranslation.setAccessible(true);
        panelAlpha = shade.getDeclaredMethod("setAnimationAlpha", float.class);
        centerAlpha.setAccessible(true); panelAlpha.setAccessible(true);
        this.settings = settings; this.log = log;
        this.enabled = enabled;
        separateController = field(loader.loadClass(CENTER), "mQsController");
        separateHeader = field(loader.loadClass(CENTER), "mHeader");
        separateStatusBar = field(loader.loadClass(CENTER), "mQSStatusBar");
        Class<?> qs = loader.loadClass(QS);
        combinedHeader = field(qs, "mHeader");
        combinedStatusBar = field(qs, "mQSStatusBar");
        fullController = field(qs, "mMzQSPanelController");
        miniController = field(qs, "mMzQQSPanelController");
        controllerView = field(loader.loadClass(PANEL_CONTROLLER), "mView");
        nativeScale = field(loader.loadClass(PANEL_CONTROLLER), "mQSScale");
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

    void install(SignalHooks.Installer installer, Consumer<Method> deoptimize) throws ReflectiveOperationException {
        panelAlpha.getDeclaringClass().getDeclaredMethod("instantCollapse");
        // Notification launches call instantCollapse directly through ShadeControllerImpl;
        // instantCollapsePanel is only the dialog-aware wrapper around this shared entry.
        installer.hook(SHADE, "instantCollapse", chain -> {
            Object owner = chain.getThisObject();
            if (active(owner, false)) {
                closeImmediately(owner);
                for (BiConsumer<Object, Float> listener : progressListeners) listener.accept(owner, 0f);
            }
            return chain.proceed();
        });
        ClassLoader loader = panelAlpha.getDeclaringClass().getClassLoader();
        for (String name : new String[]{SHADE, "com.android.systemui.shade.ShadeControllerImpl"}) {
            for (Method method : loader.loadClass(name).getDeclaredMethods()) {
                String methodName = method.getName();
                if (methodName.equals("instantCollapsePanel") || methodName.equals("instantCollapseShade")
                        || methodName.equals("collapsePanels") || methodName.equals("unlockAnimationStarted")
                        || methodName.startsWith("lambda$unlockAnimationStarted$")) deoptimize.accept(method);
            }
        }
        deoptimize.accept(centerAlpha);
        deoptimize.accept(centerCollapseTranslation);
        deoptimize.accept(panelAlpha);
        for (String callback : new String[]{QS + "$2", QS + "$3"}) {
            for (Method method : panelAlpha.getDeclaringClass().getClassLoader().loadClass(callback).getDeclaredMethods()) {
                if (method.getName().equals("onAnimationUpdate") || method.getName().equals("onAnimationEnd"))
                    deoptimize.accept(method);
            }
        }
        deoptimize.accept(combinedHeader.getDeclaringClass().getDeclaredMethod("setQsAlpha", float.class));
        for (String callback : new String[]{CENTER + "$7", CENTER + "$8"}) {
            try {
                for (Method method : centerAlpha.getDeclaringClass().getClassLoader().loadClass(callback).getDeclaredMethods())
                    if (method.getName().equals("onAnimationUpdate") || method.getName().equals("onAnimationEnd"))
                        deoptimize.accept(method);
            } catch (ClassNotFoundException error) {
                log.accept("Cannot deoptimize separate header alpha callback", error);
            }
        }
        installer.hook(View.class.getName(), "setAlpha", chain -> {
            NotificationRegion region = currentNotificationRegion.get();
            if (enabled.getAsBoolean() && region != null && region.view == chain.getThisObject()) {
                // NPVC writes the undelayed parent alpha after setQsPanelAlpha. The
                // pre-draw row renderer already owns this exact stack's delayed alpha.
                // Suppress only that native write, not other views or endpoint/reset writes.
                return null;
            }
            HeaderBinding binding = headerBindings.get(chain.getThisObject());
            Object owner = binding == null ? null : binding.owner.get();
            if (!enabled.getAsBoolean() || owner == null || reveals.get(owner) != binding.reveal)
                return chain.proceed();
            // Separate headers are siblings of the QS container. Merged headers
            // already inherit the root's reveal alpha and must not fade a second time.
            return chain.proceed(new Object[]{headerAlpha(binding.reveal, binding.separate)});
        }, float.class);
        installer.hook(CENTER, "setCollapseAnimationTranslationY", chain -> {
            Reveal reveal = reveals.get(chain.getThisObject());
            if (enabled.getAsBoolean() && reveal != null && reveal.progress > 0f
                    && (Float) chain.getArg(0) == 0f) {
                // Native collapse $8 resets both header roots before our content fade
                // finishes. The next origin/spring frame restores their old position,
                // making the still-visible header jump down for one frame.
                reveal.pendingTranslationReset = true;
                return null;
            }
            return chain.proceed();
        }, float.class);
        // Launcher handoff calls fling(open), then onTrackingStopped(false).
        // Preserve those hook boundaries even if ART compiled the small wrappers.
        for (Class<?> owner : new Class<?>[]{centerAlpha.getDeclaringClass(), panelAlpha.getDeclaringClass()}) {
            for (Method method : owner.getDeclaredMethods()) {
                if (method.getName().equals("finishInputFocusTransfer")
                        || method.getName().equals("endMotionEvent")
                        || method.getName().equals("fling")) deoptimize.accept(method);
            }
        }
        installer.hook(CENTER + "$TouchHandler", "onTouch", chain -> {
            MotionEvent previous = touch.get(); touch.set((MotionEvent) chain.getArg(1));
            try { return chain.proceed(); }
            finally { if (previous == null) touch.remove(); else touch.set(previous); }
        }, View.class, MotionEvent.class);
        installer.hook(SHADE + "$TouchHandler", "handleTouch", chain -> {
            MotionEvent previous = touch.get(); touch.set((MotionEvent) chain.getArg(0));
            try { return chain.proceed(); }
            finally { if (previous == null) touch.remove(); else touch.set(previous); }
        }, MotionEvent.class);
        for (String name : new String[]{CENTER, SHADE}) {
            boolean separate = name.equals(CENTER);
            installer.hook(name, "onTrackingStarted", chain -> {
                Object owner = chain.getThisObject();
                if (active(owner, separate)) {
                    Reveal reveal = reveals.computeIfAbsent(owner, ignored -> new Reveal());
                    boolean tracking = separate ? centerTracking.getBoolean(owner) : (Boolean) panelTracking.invoke(owner);
                    if (!reveal.gesture || !tracking) {
                        reveal.gesture = true;
                        reveal.settling = false;
                        reveal.opening = (separate ? centerClosed : panelClosed).getBoolean(owner)
                                || (separate ? centerFraction : panelFraction).getFloat(owner) <= 0f;
                        // Start from the last rendered pose when interrupting an animation.
                        if (!reveal.initialized) {
                            reveal.progress = reveal.opening ? 0f : 1f;
                            reveal.target = !reveal.opening;
                            reveal.initialized = true;
                        }
                    }
                }
                return chain.proceed();
            });
            installer.hook(name, "updateHeightAnimation", chain -> {
                Object owner = chain.getThisObject();
                Reveal reveal = reveals.get(owner);
                MotionEvent event = touch.get();
                if (active(owner, separate) && reveal != null && reveal.gesture && event != null
                        && event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    int index = event.findPointerIndex((separate ? centerPointer : panelPointer).getInt(owner));
                    if (index >= 0) {
                        float drag = event.getY(index) - (separate ? centerY : panelY).getFloat(owner);
                        Context context = context(owner, separate);
                        // A single physical boundary relative to the open position.
                        // Neither native alpha nor origin/overscroll branches can retrigger it.
                        request(owner, reveal, RevealBoundary.visible(drag / context.getResources()
                                .getDisplayMetrics().density, reveal.opening), separate);
                    }
                }
                Object result = chain.proceed();
                if (reveal != null && reveal.settling) {
                    float fraction = (separate ? centerFraction : panelFraction).getFloat(owner);
                    // Retire the release guard only after native height reaches its
                    // chosen endpoint, after the old height thresholds have run.
                    if (reveal.target ? fraction >= 1f : fraction <= 0f) reveal.settling = false;
                }
                return result;
            }, float.class);
            installer.hook(name, "onTrackingStopped", chain -> {
                Object owner = chain.getThisObject();
                Reveal reveal = reveals.get(owner);
                if (active(owner, separate) && reveal != null && reveal.gesture) {
                    request(owner, reveal, (Boolean) chain.getArg(0), separate);
                    reveal.gesture = false;
                }
                return chain.proceed();
            }, boolean.class);
            installer.hook(name, "fling", chain -> {
                Object owner = chain.getThisObject();
                if (active(owner, separate)) {
                    Reveal reveal = reveals.computeIfAbsent(owner, ignored -> new Reveal());
                    settle(owner, reveal, (Boolean) chain.getArg(1), separate);
                }
                return chain.proceed();
            }, separate ? new Class<?>[]{float.class, boolean.class}
                    : new Class<?>[]{float.class, boolean.class, float.class, boolean.class});
        }

        // Native animators read the visible (already eased) alpha to seed their next
        // transition. Feeding it back through contentProgress/revealCurve a second
        // time can reset a partially revealed shade to zero on launcher handoff.
        // Seed only their content animator with the last untransformed progress.
        for (String owner : new String[]{CENTER, SHADE}) {
            int contentIndex = owner.equals(CENTER) ? 1 : 0;
            installer.hook(owner, "startExpandAnimator", chain -> {
                Object ownerObject = chain.getThisObject();
                boolean separate = owner.equals(CENTER);
                if (active(ownerObject, separate)) {
                    Reveal reveal = reveals.computeIfAbsent(ownerObject, ignored -> new Reveal());
                    if (!reveal.gesture && !reveal.settling) request(ownerObject, reveal, true, separate);
                }
                AlphaSeed previous = alphaSeed.get();
                Float progress = rawAlpha.get(chain.getThisObject());
                if (enabled.getAsBoolean() && progress != null) alphaSeed.set(new AlphaSeed(contentIndex, progress));
                else alphaSeed.remove();
                try { return chain.proceed(); }
                finally { if (previous == null) alphaSeed.remove(); else alphaSeed.set(previous); }
            });
            installer.hook(owner, "startCollapseAnimator", chain -> {
                Object ownerObject = chain.getThisObject();
                boolean separate = owner.equals(CENTER);
                if (active(ownerObject, separate)) {
                    Reveal reveal = reveals.computeIfAbsent(ownerObject, ignored -> new Reveal());
                    if (!reveal.gesture && !reveal.settling) request(ownerObject, reveal, false, separate);
                }
                AlphaSeed previous = alphaSeed.get();
                Float progress = rawAlpha.get(chain.getThisObject());
                if (enabled.getAsBoolean() && progress != null) alphaSeed.set(new AlphaSeed(contentIndex, progress));
                else alphaSeed.remove();
                try { return chain.proceed(); }
                finally { if (previous == null) alphaSeed.remove(); else alphaSeed.set(previous); }
            }, owner.equals(CENTER) ? new Class<?>[]{float.class} : new Class<?>[0]);
        }
        installer.hook(ValueAnimator.class.getName(), "ofFloat", chain -> {
            AlphaSeed seed = alphaSeed.get();
            if (seed == null || seed.index++ != seed.contentIndex) return chain.proceed();
            float[] values = (float[]) chain.getArg(0);
            if (values.length != 2) return chain.proceed();
            return chain.proceed(new Object[]{new float[]{seed.progress, values[1]}});
        }, float[].class);
        installer.hook(PANEL_CONTROLLER, "setQsScale", chain -> {
            View root = (View) controllerView.get(chain.getThisObject());
            if (root != null) settings.accept(root.getContext());
            // Both the standalone opening scale animator and setQsExpansion write
            // directly here, including after the reveal has reached alpha=1.
            // Keep the native stored scale at 1 as well, so subsequently added tiles
            // and new animators cannot inherit a stale, individually centered scale.
            // Our pre-draw renderer supplies the shared row pivots and transition
            // scale; outside that transition the tiles remain at their open size.
            if (!enabled.getAsBoolean()) return chain.proceed();
            // The renderer owns the tile scales until pre-draw. Native expansion
            // used to reset every tile to 1 each frame, immediately undone below.
            // Keep its stored endpoint (also used when adding a tile), without
            // invalidating every tile twice on every merged-shade height update.
            if (nativeScale.getFloat(chain.getThisObject()) == 1f) return null;
            return chain.proceed(new Object[]{1f});
        }, float.class);
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
        installer.hook(SHADE, "setAnimationAlpha", chain -> {
            Object panel = chain.getThisObject();
            float input = (Float) chain.getArg(0);
            rememberAlpha(panel, input);
            if (!active(panel, false)) { retire(panel); return chain.proceed(); }
            Reveal reveal = visual(panel, input, false);
            // MOVE/height/native alpha callbacks do not advance the row timeline.
            // Applying the same content pose again rebuilds endpoint state and
            // repeats backdrop/header/notification writes while dragging.
            if (reveal.hasApplied && !Boolean.TRUE.equals(writingReveal.get())) return null;
            for (BiConsumer<Object, Float> listener : progressListeners) listener.accept(panel, reveal.progress);
            View previous = currentNotifications.get();
            NotificationRegion previousRegion = currentNotificationRegion.get();
            Reveal previousReveal = currentReveal.get();
            currentNotificationRegion.remove();
            currentNotifications.set((View) notificationStack.get(panel));
            currentReveal.set(reveal);
            try {
                Object result = chain.proceed(new Object[]{encodedProgress(reveal.progress)});
                reveal.hasApplied = true;
                return result;
            } finally {
                if (previous == null) currentNotifications.remove(); else currentNotifications.set(previous);
                if (previousRegion == null) currentNotificationRegion.remove(); else currentNotificationRegion.set(previousRegion);
                if (previousReveal == null) currentReveal.remove(); else currentReveal.set(previousReveal);
            }
        }, float.class);
        installer.hook(CENTER, "setAnimationAlpha", chain -> {
            float progress = (Float) chain.getArg(0);
            rememberAlpha(chain.getThisObject(), progress);
            if (enabled.getAsBoolean()) {
                Reveal reveal = visual(chain.getThisObject(), progress, true);
                bindHeaders(chain.getThisObject(), reveal, true);
                // The content timer can finish while tracking still retains a
                // positive height/radius. Publish its actual pose so release
                // callbacks cannot keep or recreate a dismissed dim surface.
                for (BiConsumer<Object, Float> listener : progressListeners)
                    listener.accept(chain.getThisObject(), reveal.progress);
                progress = encodedProgress(reveal.progress);
            }
            else retire(chain.getThisObject());
            Object result = chain.proceed(new Object[]{enabled.getAsBoolean() ? revealCurve(contentProgress(progress)) : progress});
            update(chain.getThisObject(), progress, true);
            return result;
        }, float.class);
        installer.hook(QS, "setQsPanelAlpha", chain -> {
            float progress = (Float) chain.getArg(0);
            Reveal reveal = currentReveal.get();
            if (reveal != null) reveals.put(chain.getThisObject(), reveal);
            else reveal = reveals.get(chain.getThisObject());
            if (enabled.getAsBoolean() && reveal != null) progress = encodedProgress(reveal.progress);
            if (enabled.getAsBoolean() && reveal != null) bindHeaders(chain.getThisObject(), reveal, false);
            Object result = chain.proceed(new Object[]{enabled.getAsBoolean() ? revealCurve(contentProgress(progress)) : progress});
            update(chain.getThisObject(), progress, false);
            return result;
        }, float.class);
    }

    private void retire(Object owner) {
        Reveal reveal = reveals.get(owner);
        if (reveal == null) return;
        reveals.values().removeIf(value -> value == reveal);
        ValueAnimator animator = reveal.animator;
        reveal.animator = null;
        if (animator != null) animator.cancel();
    }

    private void closeImmediately(Object owner) {
        // Keep an initialized zero pose, including its QS/header aliases. Removing
        // the entry lets a delayed native alpha=1 callback initialize a new reveal.
        Reveal reveal = reveals.computeIfAbsent(owner, ignored -> new Reveal());
        ValueAnimator animator = reveal.animator;
        reveal.animator = null;
        reveal.initialized = true;
        reveal.target = reveal.gesture = reveal.settling = reveal.hasApplied = false;
        reveal.progress = reveal.nativeInput = reveal.closingHeaderAlpha = 0f;
        if (animator != null) animator.cancel();
        for (BiConsumer<Object, Boolean> listener : visibilityListeners) listener.accept(owner, false);
    }
    static float headerAlpha(float progress, boolean separate) {
        float alpha = revealCurve((progress - .20f) / .80f);
        // Merged headers inherit the container alpha. Compensate for that alpha
        // so both panel modes use the same delayed header visibility curve.
        float parent = revealCurve(progress);
        return separate ? alpha : parent <= 0f ? 0f : Math.min(1f, alpha / parent);
    }
    private static float headerAlpha(Reveal reveal, boolean separate) {
        // Merged headers share the row timeline. Native alpha callbacks and the
        // gesture-position animator must not introduce a second fading clock.
        if (!separate) return headerAlpha(reveal.progress, false);
        if (reveal.target) return headerAlpha(reveal.progress, separate);
        // Closing headers use the real native/gesture alpha rather than the
        // 300ms row timeline, so a short upward swipe can clear them immediately.
        float alpha = Math.min(reveal.closingHeaderAlpha,
                revealCurve((contentProgress(reveal.nativeInput) - .20f) / .80f));
        reveal.closingHeaderAlpha = alpha;
        float parent = revealCurve(reveal.progress);
        return separate ? alpha : parent <= 0f ? 0f : Math.min(1f, alpha / parent);
    }
    private void bindHeaders(Object owner, Reveal reveal, boolean separate) throws IllegalAccessException {
        for (Field field : new Field[]{separate ? separateHeader : combinedHeader,
                separate ? separateStatusBar : combinedStatusBar}) {
            View header = (View) field.get(owner);
            if (header == null) continue;
            HeaderBinding old = headerBindings.get(header);
            if (old == null || old.owner.get() != owner || old.reveal != reveal || old.separate != separate)
                headerBindings.put(header, new HeaderBinding(owner, reveal, separate));
            header.setAlpha(headerAlpha(reveal, separate));
        }
    }
    private static final class HeaderBinding {
        final WeakReference<Object> owner;
        final Reveal reveal;
        final boolean separate;
        HeaderBinding(Object owner, Reveal reveal, boolean separate) {
            this.owner = new WeakReference<>(owner); this.reveal = reveal; this.separate = separate;
        }
    }
    private Context context(Object owner, boolean separate) throws IllegalAccessException {
        return separate ? (Context) centerContext.get(owner) : ((View) panelView.get(owner)).getContext();
    }
    private boolean active(Object owner, boolean separate) throws ReflectiveOperationException {
        settings.accept(context(owner, separate));
        return enabled.getAsBoolean() && (separate || Boolean.TRUE.equals(
                flowValue.invoke(combinedMode.invoke(shadeInteractor.get(owner)))));
    }
    private Reveal visual(Object owner, float input, boolean separate) throws ReflectiveOperationException {
        Reveal reveal = reveals.computeIfAbsent(owner, ignored -> new Reveal());
        if (!Boolean.TRUE.equals(writingReveal.get())) reveal.nativeInput = input;
        if (!reveal.initialized && !Boolean.TRUE.equals(writingReveal.get())) {
            request(owner, reveal, input > .18f, separate);
        }
        return reveal;
    }
    private void settle(Object owner, Reveal reveal, boolean visible, boolean separate) {
        // fling is the actual release decision. onTrackingStopped's boolean is
        // merely bookkeeping on launcher input-focus transfer, not another goal.
        reveal.gesture = false;
        reveal.settling = true;
        request(owner, reveal, visible, separate);
    }
    void addVisibilityListener(BiConsumer<Object, Boolean> listener) {
        visibilityListeners.add(listener);
    }

    /** A horizontal switch owns both full-open poses; stop independent vertical timers. */
    void setHorizontalPose(Object owner, boolean visible) {
        Reveal reveal = reveals.computeIfAbsent(owner, ignored -> new Reveal());
        ValueAnimator animator = reveal.animator;
        reveal.animator = null;
        if (animator != null) animator.cancel();
        reveal.initialized = true;
        reveal.target = visible;
        reveal.gesture = reveal.settling = reveal.hasApplied = reveal.pendingTranslationReset = false;
        reveal.progress = visible ? 1f : 0f;
        reveal.nativeInput = encodedProgress(reveal.progress);
        for (BiConsumer<Object, Boolean> listener : visibilityListeners) listener.accept(owner, visible);
        for (BiConsumer<Object, Float> listener : progressListeners) listener.accept(owner, reveal.progress);
    }

    void addProgressListener(BiConsumer<Object, Float> listener) {
        progressListeners.add(listener);
    }

    boolean hasVisibleContent(Object owner) {
        Reveal reveal = reveals.get(owner);
        return enabled.getAsBoolean() && reveal != null && reveal.progress > 0f;
    }

    float backdropProgress(Object owner) {
        Reveal reveal = reveals.get(owner);
        return enabled.getAsBoolean() && reveal != null && reveal.initialized
                ? reveal.progress : Float.NaN;
    }

    private void request(Object owner, Reveal reveal, boolean visible, boolean separate) {
        if (reveal.initialized && reveal.target == visible) return;
        if (!visible) reveal.closingHeaderAlpha = revealCurve((reveal.progress - .20f) / .80f);
        reveal.initialized = true; reveal.target = visible;
        // The threshold can start a fade while the finger is still down. Tell
        // position and backdrop owners before the first alpha animation frame.
        for (BiConsumer<Object, Boolean> listener : visibilityListeners) listener.accept(owner, visible);
        ValueAnimator old = reveal.animator;
        reveal.animator = null;
        if (old != null) old.cancel();
        ValueAnimator animator = ValueAnimator.ofFloat(reveal.progress, visible ? 1f : 0f);
        reveal.animator = animator;
        animator.setDuration(CONTENT_DURATION_MS);
        animator.setInterpolator(new LinearInterpolator()); // Row curves provide ease-out / reverse ease-in.
        animator.addUpdateListener(frame -> {
            if (reveal.animator != frame || !enabled.getAsBoolean()) return;
            reveal.progress = (Float) frame.getAnimatedValue();
            writingReveal.set(true);
            try {
                (separate ? centerAlpha : panelAlpha).invoke(owner, encodedProgress(reveal.progress));
                if (separate && reveal.progress <= 0f && reveal.pendingTranslationReset) {
                    reveal.pendingTranslationReset = false;
                    centerCollapseTranslation.invoke(owner, 0f);
                }
            }
            catch (ReflectiveOperationException error) { log.accept("Cannot apply content reveal", error); }
            finally { writingReveal.remove(); }
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (reveal.animator == animation) reveal.animator = null;
            }
        });
        animator.start();
    }
    private static float encodedProgress(float progress) {
        return progress <= 0f ? 0f : .18f + .82f * progress;
    }
    private static final class Reveal {
        boolean initialized, target, gesture, opening, settling, pendingTranslationReset, hasApplied;
        float progress, nativeInput = 1f, closingHeaderAlpha = 1f;
        ValueAnimator animator;
    }

    private void rememberAlpha(Object owner, float progress) {
        if (Boolean.TRUE.equals(writingReveal.get())) return;
        if (enabled.getAsBoolean()) {
            rawAlpha.put(owner, progress);
            Reveal reveal = reveals.get(owner);
            if (reveal != null) reveal.nativeInput = progress;
        }
        else rawAlpha.remove(owner);
    }

    private static final class AlphaSeed {
        final int contentIndex;
        final float progress;
        int index;
        AlphaSeed(int contentIndex, float progress) {
            this.contentIndex = contentIndex;
            this.progress = progress;
        }
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
        // Only the fixed content timeline drives this mapping. Physical gesture
        // reversal selects an endpoint; it never writes per-tile animation progress.
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
        if (!separate && notifications != null && state.notifications != null) {
            currentNotificationRegion.set(state.notifications);
        }
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
        Trace.beginSection("FlymeMod#rowReveal");
        try { renderRows(state); }
        finally { Trace.endSection(); }
    }

    private void renderRows(State state) {
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
                applyFrame(tile.view, tile.animationPivotX, tile.animationPivotY,
                        .85f + .15f * amount, .85f + .15f * amount,
                        tile.alpha * (parentAlpha > 0f ? amount / parentAlpha : 0f));
            }
        }
        if (state.notifications != null) {
            NotificationRegion region = state.notifications;
            float delay = Math.min(.8f, lastVisibleDelay + notificationGap);
            float amount = revealCurve((state.progress - delay) / (1f - delay));
            float scale = .85f + .15f * amount;
            applyFrame(region.view, region.view.getWidth() / 2f, region.top,
                    region.scaleX * scale, region.scaleY * scale, amount);
        }
    }

    private static void applyFrame(View view, float pivotX, float pivotY,
                                   float scaleX, float scaleY, float alpha) {
        // Compare actual properties, not cached values: secondary expansion can
        // change them between frames and must still be corrected before drawing.
        if (!view.isPivotSet() || view.getPivotX() != pivotX || view.getPivotY() != pivotY) {
            view.setPivotX(pivotX);
            view.setPivotY(pivotY);
        }
        if (view.getScaleX() != scaleX) view.setScaleX(scaleX);
        if (view.getScaleY() != scaleY) view.setScaleY(scaleY);
        if (view.getAlpha() != alpha) view.setAlpha(alpha);
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
