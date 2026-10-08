package dev.rikumi.flymemod;

import android.content.Context;
import android.view.View;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Flyme SystemUI 16260625: measure only floating notifications with 8dp side margins. */
final class HeadsUpWidthHooks {
    private static final String ROW = "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow";
    private static final String STACK = "com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout";
    private static final String LANDSCAPE = "com.flyme.notification.view.LandscapeHeadsUpNotificationView";
    private final Consumer<Context> settings;
    private final BooleanSupplier enabled;
    private final Class<?> stack;
    private final Field expanded;
    private final Method pinned, animatingAway;

    HeadsUpWidthHooks(ClassLoader loader, Consumer<Context> settings, BooleanSupplier enabled)
            throws ReflectiveOperationException {
        this.settings = settings; this.enabled = enabled;
        stack = loader.loadClass(STACK); expanded = stack.getField("mIsExpanded");
        Class<?> row = loader.loadClass(ROW);
        pinned = row.getMethod("isPinned"); animatingAway = row.getMethod("isHeadsUpAnimatingAway");
        // Landscape notification inherits LinearLayout.onMeasure; hook the declaring class.
        Class<?> landscape = loader.loadClass(LANDSCAPE);
        if (!android.widget.LinearLayout.class.isAssignableFrom(landscape))
            throw new NoSuchMethodException(LANDSCAPE + " no longer inherits LinearLayout");
    }

    void install(SignalHooks.Installer installer) {
        installer.hook(ROW, "onMeasure", chain -> {
            View row = (View) chain.getThisObject();
            settings.accept(row.getContext());
            if (!enabled.getAsBoolean() || !(row.getParent() instanceof View parent)
                    || !stack.isInstance(parent) || expanded.getBoolean(parent)
                    || !((Boolean) pinned.invoke(row) || (Boolean) animatingAway.invoke(row)))
                return chain.proceed();
            int available = parent.getMeasuredWidth();
            if (available <= 0) return chain.proceed();
            return chain.proceed(new Object[]{View.MeasureSpec.makeMeasureSpec(
                    contentWidth(available, row.getResources().getDisplayMetrics().density),
                    View.MeasureSpec.EXACTLY), chain.getArg(1)});
        }, int.class, int.class);
        installer.hook("android.widget.LinearLayout", "onMeasure", chain -> {
            View view = (View) chain.getThisObject();
            if (!LANDSCAPE.equals(view.getClass().getName())) return chain.proceed();
            settings.accept(view.getContext());
            if (!enabled.getAsBoolean()) return chain.proceed();
            int available = view.getResources().getDisplayMetrics().widthPixels;
            return chain.proceed(new Object[]{View.MeasureSpec.makeMeasureSpec(
                    contentWidth(available, view.getResources().getDisplayMetrics().density),
                    View.MeasureSpec.EXACTLY), chain.getArg(1)});
        }, int.class, int.class);
    }

    static int contentWidth(int available, float density) {
        return Math.max(0, available - 2 * Math.round(8f * density));
    }
}
