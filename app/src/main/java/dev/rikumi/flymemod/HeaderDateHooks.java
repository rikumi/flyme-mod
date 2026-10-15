package dev.rikumi.flymemod;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** The standalone and combined panels both use this Flyme header. */
final class HeaderDateHooks {
    private static final String HEADER = "com.flyme.systemui.statusbar.phone.StatusBarHeaderView";
    private final Consumer<Context> settings;
    private final BooleanSupplier enabled;
    private final java.util.Map<View, Snapshot> originals = new java.util.WeakHashMap<>();
    private final java.util.Set<View> headers = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private final ThreadLocal<Integer> depth = ThreadLocal.withInitial(() -> 0);

    private record Snapshot(float size, int visibility, int gravity, boolean baseline,
                            int clockGravity, int dateGravity, int dateBottom) {}

    private void restore(View header) throws ReflectiveOperationException {
        Snapshot old = originals.remove(header);
        if (old == null) return;
        Class<?> type = header.getClass();
        ((TextView) type.getField("mDateExpanded").get(header)).setTextSize(TypedValue.COMPLEX_UNIT_PX, old.size());
        ((View) type.getField("mLunarView").get(header)).setVisibility(old.visibility());
        LinearLayout container = (LinearLayout) type.getField("mTimeContainer").get(header);
        container.setGravity(old.gravity()); container.setBaselineAligned(old.baseline());
        View clock = (View) type.getField("mClockView").get(header);
        LinearLayout.LayoutParams cp = (LinearLayout.LayoutParams) clock.getLayoutParams();
        cp.gravity = old.clockGravity(); clock.setLayoutParams(cp);
        View group = (View) type.getField("mDateGroup").get(header);
        LinearLayout.LayoutParams dp = (LinearLayout.LayoutParams) group.getLayoutParams();
        dp.gravity = old.dateGravity(); dp.bottomMargin = old.dateBottom(); group.setLayoutParams(dp);
    }

    void refresh() {
        for (View header : new java.util.ArrayList<>(headers)) {
            try {
                header.getClass().getMethod("updateResources").invoke(header);
                header.getClass().getMethod("updateDateLocation").invoke(header);
                header.requestLayout(); header.invalidate();
            } catch (ReflectiveOperationException | RuntimeException error) {
                log.accept("Cannot refresh lunar date visibility", error);
            }
        }
    }
    private final java.util.function.BiConsumer<String, Throwable> log;

    HeaderDateHooks(Consumer<Context> settings, BooleanSupplier enabled,
                    java.util.function.BiConsumer<String, Throwable> log) {
        this.log = log;
        this.settings = settings;
        this.enabled = enabled;
    }

    void install(SignalHooks.Installer installer) {
        for (String method : new String[]{"onFinishInflate", "updateVisibilities", "updateResources", "updateDateLocation"}) {
            installer.hook(HEADER, method, chain -> {
                View header = (View) chain.getThisObject();
                headers.add(header);
                int previous = depth.get();
                if (previous == 0) restore(header);
                depth.set(previous + 1);
                try { return chain.proceed(); }
                finally {
                    depth.set(previous);
                    if (previous == 0) apply(header);
                }
            });
        }
    }

    private void apply(View header) throws ReflectiveOperationException {
        settings.accept(header.getContext());
        if (!enabled.getAsBoolean()) return;
        Class<?> type = header.getClass();
        TextView lunar = (TextView) type.getField("mLunarView").get(header);
        TextView date = (TextView) type.getField("mDateExpanded").get(header);
        TextView time = (TextView) type.getField("mTime").get(header);
        View clock = (View) type.getField("mClockView").get(header);
        LinearLayout group = (LinearLayout) type.getField("mDateGroup").get(header);
        LinearLayout container = (LinearLayout) type.getField("mTimeContainer").get(header);
        // updateVisibilities is called during inflation, before mClockView is assigned.
        if (lunar == null || date == null || time == null || clock == null || group == null || container == null) return;
        LinearLayout.LayoutParams originalClock = (LinearLayout.LayoutParams) clock.getLayoutParams();
        LinearLayout.LayoutParams originalDate = (LinearLayout.LayoutParams) group.getLayoutParams();
        originals.putIfAbsent(header, new Snapshot(date.getTextSize(), lunar.getVisibility(),
                container.getGravity(), container.isBaselineAligned(), originalClock.gravity,
                originalDate.gravity, originalDate.bottomMargin));
        lunar.setVisibility(View.GONE);
        int size = header.getResources().getIdentifier("flyme_date_group_text_size", "dimen", "com.android.systemui");
        if (size != 0) {
            float textSize = header.getResources().getDimensionPixelSize(size) * 1.25f;
            if (date.getTextSize() != textSize) date.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize);
        }
        container.setGravity(Gravity.BOTTOM);
        container.setBaselineAligned(false);
        LinearLayout.LayoutParams clockParams = (LinearLayout.LayoutParams) clock.getLayoutParams();
        if (clockParams.gravity != Gravity.BOTTOM) {
            clockParams.gravity = Gravity.BOTTOM;
            clock.setLayoutParams(clockParams);
        }
        LinearLayout.LayoutParams timeParams = (LinearLayout.LayoutParams) time.getLayoutParams();
        LinearLayout.LayoutParams dateParams = (LinearLayout.LayoutParams) group.getLayoutParams();
        int bottom = clockParams.bottomMargin + timeParams.bottomMargin;
        if (dateParams.gravity != Gravity.BOTTOM || dateParams.bottomMargin != bottom) {
            dateParams.gravity = Gravity.BOTTOM;
            dateParams.bottomMargin = bottom;
            group.setLayoutParams(dateParams);
        }
    }

}
