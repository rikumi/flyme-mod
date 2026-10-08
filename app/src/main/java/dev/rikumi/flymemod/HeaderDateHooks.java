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

    HeaderDateHooks(Consumer<Context> settings, BooleanSupplier enabled) {
        this.settings = settings;
        this.enabled = enabled;
    }

    void install(SignalHooks.Installer installer) {
        for (String method : new String[]{"onFinishInflate", "updateVisibilities", "updateResources", "updateDateLocation"}) {
            installer.hook(HEADER, method, chain -> {
                Object result = chain.proceed();
                apply((View) chain.getThisObject());
                return result;
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
