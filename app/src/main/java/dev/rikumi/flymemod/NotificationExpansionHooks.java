package dev.rikumi.flymemod;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Restores AOSP expansion eligibility and release decisions, using native row sizing and fling timing. */
final class NotificationExpansionHooks {
    private static final String ROW = "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow";
    private static final String HELPER = "com.android.systemui.ExpandHelper";
    private final Consumer<Context> settings;
    private final BooleanSupplier enabled;
    private final BiConsumer<String, Throwable> log;
    private final Map<String, Field> fields = new java.util.HashMap<>();
    private final Map<Object, ValueAnimator> settling = new WeakHashMap<>();
    private final Map<Object, Pull> pulls = new WeakHashMap<>();
    private final ThreadLocal<MotionEvent> touch = new ThreadLocal<>();
    private final Field expandable, summary, nonGrouped;
    private final Method publicRow, protectedRow, promoted, getHeight, naturalHeight, setHeight, setView;
    private final Method expandedChild, lockedChild, expansionChanged, expansionCancelled, flingTiming;
    private final Method nativeCancel, monitorGet, monitorEnd;
    private final Field qsStack;
    private final Method stackView, childAtRaw, rowExpandable, guts;

    NotificationExpansionHooks(ClassLoader loader, Consumer<Context> settings, BooleanSupplier enabled,
                               BiConsumer<String, Throwable> log) throws ReflectiveOperationException {
        this.settings = settings; this.enabled = enabled; this.log = log;
        Class<?> row = loader.loadClass(ROW), helper = loader.loadClass(HELPER);
        expandable = row.getField("mExpandable"); summary = row.getField("mIsSummaryWithChildren");
        nonGrouped = row.getField("mEnableNonGroupedNotificationExpand");
        publicRow = row.getMethod("shouldShowPublic"); protectedRow = row.getMethod("isAppProtected");
        promoted = row.getMethod("isPromotedOngoing");
        rowExpandable = row.getMethod("isExpandable"); guts = row.getMethod("areGutsExposed");
        Class<?> qs = loader.loadClass("com.android.systemui.shade.QuickSettingsControllerImpl");
        qsStack = qs.getField("mNotificationStackScrollLayoutController");
        stackView = qsStack.getType().getMethod("getView");
        childAtRaw = loader.loadClass("com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout")
                .getMethod("getChildAtRawPosition", float.class, float.class);
        for (String name : new String[]{"mContext", "mExpanding", "mEnabled", "mOldHeight", "mSmallSize",
                "mNaturalHeight", "mScaler", "mCallback", "mResizedView", "mScaleAnimation", "mFlingAnimationUtils", "mExpansionStyle"}) {
            fields.put(name, helper.getField(name));
        }
        Class<?> scaler = loader.loadClass(HELPER + "$ViewScaler");
        getHeight = scaler.getMethod("getHeight"); naturalHeight = scaler.getMethod("getNaturalHeight");
        setHeight = scaler.getMethod("setHeight", float.class);
        setView = scaler.getMethod("setView", loader.loadClass("com.android.systemui.statusbar.notification.row.ExpandableView"));
        Class<?> callback = loader.loadClass(HELPER + "$Callback");
        expandedChild = callback.getMethod("setUserExpandedChild", View.class, boolean.class);
        lockedChild = callback.getMethod("setUserLockedChild", View.class, boolean.class);
        expansionCancelled = callback.getMethod("setExpansionCancelled", View.class);
        expansionChanged = callback.getMethod("expansionStateChanged", boolean.class);
        flingTiming = loader.loadClass("com.android.wm.shell.animation.FlingAnimationUtils")
                .getMethod("apply", Animator.class, float.class, float.class, float.class);
        nativeCancel = loader.loadClass("androidx.core.animation.Animator").getMethod("cancel");
        Class<?> monitor = loader.loadClass("com.android.internal.jank.InteractionJankMonitor");
        monitorGet = monitor.getMethod("getInstance"); monitorEnd = monitor.getMethod("end", int.class);
    }

    void install(SignalHooks.Installer installer) {
        String qs = "com.android.systemui.shade.QuickSettingsControllerImpl";
        // Flyme's collapsed-QS interception treats any downward drag at the top of the
        // notification list as a second QS pull. Give notification-origin gestures priority.
        installer.hook(qs, "onIntercept", chain -> notificationPull(chain.getThisObject(), (MotionEvent) chain.getArg(0))
                ? false : chain.proceed(), MotionEvent.class);
        installer.hook(qs, "handleTouch", chain -> notificationPull(chain.getThisObject(), (MotionEvent) chain.getArg(0))
                ? false : chain.proceed(), MotionEvent.class, boolean.class, boolean.class);
        installer.hook(ROW, "isExpandable", chain -> {
            View row = (View) chain.getThisObject(); settings.accept(row.getContext());
            if (!enabled.getAsBoolean() || (summary.getBoolean(row) && !(Boolean) publicRow.invoke(row))
                    || (Boolean) protectedRow.invoke(row) || (Boolean) promoted.invoke(row)) return chain.proceed();
            return expandable.getBoolean(row);
        });
        installer.hook(ROW, "isShowingExpanded", chain -> {
            View row = (View) chain.getThisObject(); settings.accept(row.getContext());
            if (!enabled.getAsBoolean()) return chain.proceed();
            boolean previous = nonGrouped.getBoolean(row);
            nonGrouped.setBoolean(row, true);
            try { return chain.proceed(); } finally { nonGrouped.setBoolean(row, previous); }
        });
        for (String method : new String[]{"onTouchEvent", "onInterceptTouchEvent"}) {
            installer.hook(HELPER, method, chain -> {
                MotionEvent previous = touch.get(); touch.set((MotionEvent) chain.getArg(0));
                try { return chain.proceed(); }
                finally { if (previous == null) touch.remove(); else touch.set(previous); }
            }, MotionEvent.class);
        }
        installer.hook(HELPER, "startExpanding", chain -> {
            ValueAnimator old = settling.remove(chain.getThisObject());
            if (old != null) old.cancel();
            return chain.proceed();
        }, loaderViewClass(), int.class);
        installer.hook(HELPER, "finishExpanding", chain -> {
            Object helper = chain.getThisObject();
            settings.accept((Context) field(helper, "mContext"));
            if (!enabled.getAsBoolean() || !fields.get("mExpanding").getBoolean(helper)) return chain.proceed();
            boolean abort = (Boolean) chain.getArg(0);
            MotionEvent event = touch.get();
            if (event != null && (event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL)) {
                abort = !fields.get("mEnabled").getBoolean(helper)
                        || event.getActionMasked() == MotionEvent.ACTION_CANCEL;
            }
            finish(helper, abort, (Float) chain.getArg(1), (Boolean) chain.getArg(2));
            return null;
        }, boolean.class, float.class, boolean.class);
    }

    private Class<?> loaderViewClass() { return setView.getParameterTypes()[0]; }
    private Object field(Object helper, String name) throws IllegalAccessException { return fields.get(name).get(helper); }

    private boolean notificationPull(Object qs, MotionEvent event) throws ReflectiveOperationException {
        Pull pull = pulls.get(qs);
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN && (pull == null || pull.downTime != event.getDownTime())) {
            View stack = (View) stackView.invoke(qsStack.get(qs));
            settings.accept(stack.getContext());
            Object row = childAtRaw.invoke(stack, event.getRawX(), event.getRawY());
            pull = new Pull(event.getDownTime(), enabled.getAsBoolean() && row != null
                    && rowExpandable.getDeclaringClass().isInstance(row)
                    && (Boolean) rowExpandable.invoke(row) && !(Boolean) guts.invoke(row));
            pulls.put(qs, pull);
        }
        return enabled.getAsBoolean() && pull != null && pull.notification && pull.downTime == event.getDownTime();
    }

    private static final class Pull {
        final long downTime;
        final boolean notification;
        Pull(long downTime, boolean notification) { this.downTime = downTime; this.notification = notification; }
    }

    private void finish(Object helper, boolean abort, float velocity, boolean animate) throws ReflectiveOperationException {
        Object scaler = field(helper, "mScaler"), callback = field(helper, "mCallback");
        View row = (View) field(helper, "mResizedView");
        float height = ((Number) getHeight.invoke(scaler)).floatValue();
        float oldHeight = fields.get("mOldHeight").getFloat(helper);
        int small = fields.get("mSmallSize").getInt(helper);
        boolean wasClosed = oldHeight == small;
        boolean expanded = releaseExpanded(oldHeight, height, small,
                fields.get("mNaturalHeight").getFloat(helper), velocity, abort);
        float target = expanded ? ((Number) naturalHeight.invoke(scaler)).floatValue() : small;
        nativeCancel.invoke(field(helper, "mScaleAnimation"));
        ValueAnimator previous = settling.remove(helper);
        if (previous != null) previous.cancel();
        expansionChanged.invoke(callback, false);
        fields.get("mExpanding").setBoolean(helper, false);
        fields.get("mExpansionStyle").setInt(helper, 0);
        if (target == height || !animate || !fields.get("mEnabled").getBoolean(helper)) {
            setHeight.invoke(scaler, target);
            complete(helper, scaler, callback, row, expanded, wasClosed, false);
            return;
        }
        ValueAnimator animator = ValueAnimator.ofFloat(height, target);
        settling.put(helper, animator);
        animator.addUpdateListener(frame -> {
            try { setHeight.invoke(scaler, (Float) frame.getAnimatedValue()); }
            catch (ReflectiveOperationException error) { log.accept("Cannot resize notification during expansion", error); }
        });
        animator.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;
            @Override public void onAnimationCancel(Animator animation) { cancelled = true; }
            @Override public void onAnimationEnd(Animator animation) {
                if (settling.get(helper) == animation) settling.remove(helper);
                try { complete(helper, scaler, callback, row, expanded, wasClosed, cancelled); }
                catch (ReflectiveOperationException error) { log.accept("Cannot finish native notification expansion", error); }
            }
        });
        if (expanded != (velocity >= 0f)) velocity = 0f;
        flingTiming.invoke(field(helper, "mFlingAnimationUtils"), animator, height, target, velocity);
        animator.start();
    }

    private void complete(Object helper, Object scaler, Object callback, View row, boolean expanded,
                          boolean wasClosed, boolean cancelled) throws ReflectiveOperationException {
        if (cancelled) expansionCancelled.invoke(callback, row);
        else {
            expandedChild.invoke(callback, row, expanded);
            if (!fields.get("mExpanding").getBoolean(helper)) setView.invoke(scaler, new Object[]{null});
        }
        lockedChild.invoke(callback, row, false);
        if (wasClosed) monitorEnd.invoke(monitorGet.invoke(null), 3);
    }

    static boolean releaseExpanded(float oldHeight, float height, int small, float natural, float velocity, boolean abort) {
        boolean wasClosed = oldHeight == small;
        if (abort) return !wasClosed;
        return (wasClosed ? height > oldHeight && velocity >= 0f : height >= oldHeight || velocity > 0f)
                || natural == small;
    }
}
