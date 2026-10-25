package dev.rikumi.flymemod;

import android.content.Context;
import android.view.View;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

final class HeadsUpWidthHooks {
    private static final String ROW = "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow";
    private static final String STACK = "com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout";
    private static final String LANDSCAPE = "com.flyme.notification.view.LandscapeHeadsUpNotificationView";
    private static final String MEDIA = "com.android.systemui.statusbar.notification.stack.MediaContainerView";
    private final Consumer<Context> settings;
    private final BooleanSupplier enabled;
    private final Class<?> stack, media;
    private final ClassLoader loader;
    private final Field expanded, shade;
    private final Field statusBarState, showingPublic;
    private final Method pinned, animatingAway, classics, value, onKeyguard, filter;
    private final Set<View> rows = Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<View> containers = Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<View> widened = Collections.newSetFromMap(new WeakHashMap<>());

    HeadsUpWidthHooks(ClassLoader loader, Consumer<Context> settings, BooleanSupplier enabled)
            throws ReflectiveOperationException {
        this.settings = settings; this.enabled = enabled; this.loader = loader;
        stack = loader.loadClass(STACK); expanded = stack.getField("mIsExpanded");
        media = loader.loadClass(MEDIA);
        statusBarState = stack.getField("mStatusBarState");
        shade = stack.getField("mShadeInteractor");
        classics = shade.getType().getMethod("isClassicsMode");
        value = loader.loadClass("kotlinx.coroutines.flow.StateFlow").getMethod("getValue");
        onKeyguard = stack.getMethod("onKeyguard");
        filter = stack.getMethod("isFilterStackLayout");
        Class<?> row = loader.loadClass(ROW);
        showingPublic = row.getField("mShowingPublic");
        pinned = row.getMethod("isPinned"); animatingAway = row.getMethod("isHeadsUpAnimatingAway");
        // Landscape notification inherits LinearLayout.onMeasure; hook the declaring class.
        Class<?> landscape = loader.loadClass(LANDSCAPE);
        if (!android.widget.LinearLayout.class.isAssignableFrom(landscape))
            throw new NoSuchMethodException(LANDSCAPE + " no longer inherits LinearLayout");
    }

    void install(SignalHooks.Installer installer) throws ReflectiveOperationException { install(installer, null); }

    void install(SignalHooks.Installer installer, Consumer<Method> deoptimize) throws ReflectiveOperationException {

        Method mediaMeasure = media.getMethod("onMeasure", int.class, int.class);
        installer.hook(mediaMeasure.getDeclaringClass().getName(), "onMeasure", chain -> {
            View view = (View) chain.getThisObject();
            if (!media.isInstance(view)) return chain.proceed();
            settings.accept(view.getContext());
            if (view.getParent() instanceof View parent && stack.isInstance(parent)) {
                containers.add(parent); rows.add(view);
                if (enabled.getAsBoolean() && statusBarState.getInt(parent) == 1
                        && !mergedPresentation(parent) && parent.getMeasuredWidth() > 0) {
                    widened.add(view);
                    return chain.proceed(new Object[]{View.MeasureSpec.makeMeasureSpec(
                            contentWidth(parent.getMeasuredWidth(), view.getResources().getDisplayMetrics().density),
                            View.MeasureSpec.EXACTLY), chain.getArg(1)});
                }
            }
            widened.remove(view);
            return chain.proceed();
        }, int.class, int.class);
        installer.hook(ROW, "onMeasure", chain -> {
            View row = (View) chain.getThisObject();
            settings.accept(row.getContext());
            if (row.getParent() instanceof View parent && stack.isInstance(parent)) {
                containers.add(parent); rows.add(row);
            }
            if (!enabled.getAsBoolean() || !(row.getParent() instanceof View parent)
                    || !stack.isInstance(parent)
                    || mergedPresentation(parent)
                    || !isolated(statusBarState.getInt(parent), expanded.getBoolean(parent),
                            (Boolean) pinned.invoke(row), (Boolean) animatingAway.invoke(row))) {
                widened.remove(row);
                return chain.proceed();
            }
            int available = parent.getMeasuredWidth();
            if (available <= 0) return chain.proceed();
            widened.add(row);
            return chain.proceed(new Object[]{View.MeasureSpec.makeMeasureSpec(
                    contentWidth(available, row.getResources().getDisplayMetrics().density),
                    View.MeasureSpec.EXACTLY), chain.getArg(1)});
        }, int.class, int.class);

        installer.hook(STACK, "setIsExpanded", chain -> {
            View parent = (View) chain.getThisObject();
            boolean changed = expanded.getBoolean(parent) != (Boolean) chain.getArg(0);
            Object result = chain.proceed();
            if (changed) invalidate(parent);
            return result;
        }, boolean.class);
        installer.hook(STACK, "setStatusBarState", chain -> {
            View parent = (View) chain.getThisObject();
            boolean changed = statusBarState.getInt(parent) != (Integer) chain.getArg(0);
            Object result = chain.proceed();
            if (changed) invalidate(parent);
            return result;
        }, int.class);
        Class<?> rowClass = pinned.getDeclaringClass();
        for (String name : new String[]{"setPinnedStatus", "setHeadsUp", "setHeadsUpAnimatingAway",
                "setOnKeyguard", "setHideSensitive"}) {
            for (Method method : rowClass.getDeclaredMethods()) {
                if (!method.getName().equals(name)) continue;
                installer.hook(ROW, name, chain -> {
                    View row = (View) chain.getThisObject();
                    boolean before = floatingWidth(row), beforePublic = showingPublic.getBoolean(row);
                    Object result = chain.proceed();
                    if ((before != floatingWidth(row) || beforePublic != showingPublic.getBoolean(row))
                            && row.getParent() instanceof View parent && stack.isInstance(parent)) invalidate(parent);
                    return result;
                }, method.getParameterTypes());
            }
        }
        installer.hook("android.widget.LinearLayout", "onMeasure", chain -> {
            View view = (View) chain.getThisObject();
            if (!LANDSCAPE.equals(view.getClass().getName())) return chain.proceed();
            settings.accept(view.getContext());
            containers.add(view);
            if (!enabled.getAsBoolean()) return chain.proceed();
            int available = view.getResources().getDisplayMetrics().widthPixels;
            return chain.proceed(new Object[]{View.MeasureSpec.makeMeasureSpec(
                    contentWidth(available, view.getResources().getDisplayMetrics().density),
                    View.MeasureSpec.EXACTLY), chain.getArg(1)});
        }, int.class, int.class);
        if (deoptimize != null) {

            deoptimize.accept(stack.getDeclaredMethod("setExpandedHeight", float.class));
            deoptimize.accept(loader.loadClass(STACK + "Controller$6").getDeclaredMethod("onStateChanged", int.class));
        }
    }

    private boolean floatingWidth(View row) throws ReflectiveOperationException {
        return enabled.getAsBoolean() && row.getParent() instanceof View parent && stack.isInstance(parent)
                && !mergedPresentation(parent)
                && isolated(statusBarState.getInt(parent), expanded.getBoolean(parent),
                    (Boolean) pinned.invoke(row), (Boolean) animatingAway.invoke(row));
    }

    private boolean mergedPresentation(View parent) throws ReflectiveOperationException {

        return expanded.getBoolean(parent) && !(Boolean) onKeyguard.invoke(parent)
                && !(Boolean) filter.invoke(parent)
                && Boolean.TRUE.equals(value.invoke(classics.invoke(shade.get(parent))));
    }

    void refresh() {
        for (View row : rows) row.forceLayout();
        for (View container : containers) container.requestLayout();
    }

    private void invalidate(View parent) {
        boolean needed = enabled.getAsBoolean();
        for (View row : rows) if (row.getParent() == parent && (needed || widened.contains(row))) {

            row.forceLayout(); needed = true;
        }
        if (needed) parent.requestLayout();
    }

    static boolean isolated(int presentationState, boolean expandedShade,
                            boolean pinned, boolean animatingAway) {

        return presentationState == 1 || (presentationState == 0 && !expandedShade && (pinned || animatingAway));
    }

    static int contentWidth(int available, float density) {
        return Math.max(0, available - 2 * Math.round(8f * density));
    }
}
