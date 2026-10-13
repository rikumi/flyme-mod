package dev.rikumi.flymemod;

import android.view.View;

/** One visible status bar moves vertically between the actual two shade endpoints. */
final class SeparateShadeStatusBar {
    private final View bar, notificationBar;
    private final float x, y, deltaY, notificationAlpha;
    private final int notificationVisibility;
    private float appliedY;

    SeparateShadeStatusBar(View bar, View notificationBar) {
        this.bar = bar; this.notificationBar = notificationBar;
        x = bar.getTranslationX(); y = appliedY = bar.getTranslationY();
        notificationAlpha = notificationBar.getAlpha();
        notificationVisibility = notificationBar.getVisibility();
        float dy = 0f;
        int[] centerLocation = new int[2], notificationLocation = new int[2];
        for (String name : new String[]{"battery", "statusIcons"}) {
            int id = bar.getResources().getIdentifier(name, "id", "com.android.systemui");
            View source = id == 0 ? null : bar.findViewById(id);
            View target = id == 0 ? null : notificationBar.findViewById(id);
            if (source == null || target == null || source.getWidth() == 0 || target.getWidth() == 0) continue;
            source.getLocationOnScreen(centerLocation); target.getLocationOnScreen(notificationLocation);
            dy = notificationLocation[1] + target.getHeight() * .5f
                    - centerLocation[1] - source.getHeight() * .5f;
            break;
        }
        deltaY = dy;
    }

    void apply(float centerFraction) {
        float notificationFraction = 1f - Math.max(0f, Math.min(1f, centerFraction));
        // This is the notification panel's actual KeyguardStatusBarView, not the
        // separate phone status-bar window. Keep its icons out of the horizontal slide.
        notificationBar.setAlpha(0f); notificationBar.setVisibility(View.INVISIBLE);
        appliedY = y + deltaY * notificationFraction;
        bar.setTranslationX(x); bar.setTranslationY(appliedY);
        bar.setAlpha(1f); bar.setVisibility(View.VISIBLE);
    }

    void restore() {
        if (bar.getTranslationY() == appliedY) bar.setTranslationY(y);
        if (notificationBar.getAlpha() == 0f) notificationBar.setAlpha(notificationAlpha);
        if (notificationBar.getVisibility() == View.INVISIBLE) notificationBar.setVisibility(notificationVisibility);
    }
}
