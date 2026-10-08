package dev.rikumi.flymemod;

import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.view.Gravity;

/** Retains the ClipDrawable API expected by BrightnessSliderView's volume updates. */
final class RoundedSliderProgress extends ClipDrawable {
    private final float radius;
    private final Path path = new Path();
    private final float[] radii = new float[8];

    RoundedSliderProgress(Drawable drawable, float radius) {
        super(drawable, Gravity.BOTTOM, ClipDrawable.VERTICAL);
        this.radius = radius;
    }

    @Override public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        int level = Math.max(0, Math.min(10000, getLevel()));
        // Match ClipDrawable's integer rounding so the curve starts at the visible edge.
        int height = bounds.height() - bounds.height() * (10000 - level) / 10000;
        if (height <= 0 || bounds.width() <= 0) return;
        float corner = Math.min(radius, Math.min(bounds.width() / 2f, height / 2f));
        radii[0] = radii[1] = radii[2] = radii[3] = corner;
        path.reset();
        path.addRoundRect(bounds.left, bounds.bottom - height, bounds.right, bounds.bottom,
                radii, Path.Direction.CW);
        int save = canvas.save();
        canvas.clipPath(path);
        super.draw(canvas);
        canvas.restoreToCount(save);
    }

    @Override public ConstantState getConstantState() { return null; }
}
