package dev.rikumi.flymemod;

import android.view.View;

/** Compare live properties: native animation callbacks may overwrite them in the same frame. */
final class ShadeViewProperties {
    static void alpha(View view, float alpha) {
        if (view.getAlpha() != alpha) view.setAlpha(alpha);
    }

    static void unitScale(View view) {
        if (view == null) return;
        if (view.getScaleX() != 1f) view.setScaleX(1f);
        if (view.getScaleY() != 1f) view.setScaleY(1f);
    }

    private ShadeViewProperties() {}
}
