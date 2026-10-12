package dev.rikumi.flymemod;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;

/** Preserve concrete platform icon types at public PackageManager API boundaries. */
final class SystemIconPackApiDrawable {
    static Drawable wrap(Resources resources, Drawable original, Drawable live) {
        if (original instanceof BitmapDrawable) return new BitmapIcon(resources, live);
        if (original instanceof AdaptiveIconDrawable) return new AdaptiveIcon(live);
        // An unknown concrete type may have additional caller contracts. Retain its native source.
        return original;
    }

    private static Bitmap pixels(Drawable drawable) {
        Drawable source = drawable;
        while (source instanceof DrawableWrapper wrapper && wrapper.getDrawable() != null)
            source = wrapper.getDrawable();
        if (source instanceof BitmapDrawable bitmap) return bitmap.getBitmap();
        int width = Math.max(1, drawable.getIntrinsicWidth());
        int height = Math.max(1, drawable.getIntrinsicHeight());
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Rect bounds = new Rect(drawable.getBounds());
        try {
            drawable.setBounds(0, 0, width, height);
            drawable.draw(new Canvas(bitmap));
        } finally {
            drawable.setBounds(bounds);
        }
        return bitmap;
    }

    private static final class BitmapIcon extends BitmapDrawable implements Drawable.Callback {
        private final Drawable live;
        BitmapIcon(Resources resources, Drawable live) {
            super(resources, pixels(live));
            this.live = live;
            live.setCallback(this);
        }
        @Override public void invalidateDrawable(Drawable who) {
            // getBitmap and ConstantState must describe the current image, not only draw().
            setBitmap(pixels(live));
            invalidateSelf();
        }
        @Override public void scheduleDrawable(Drawable who, Runnable action, long when) { scheduleSelf(action, when); }
        @Override public void unscheduleDrawable(Drawable who, Runnable action) { unscheduleSelf(action); }
    }

    private static final class AdaptiveIcon extends AdaptiveIconDrawable {
        private final Drawable live;
        AdaptiveIcon(Drawable live) {
            super(null, live);
            this.live = live;
        }
        // LiveIcon already holds the complete native/pack icon; avoid applying another mask/inset.
        @Override public void draw(Canvas canvas) {
            live.setBounds(getBounds());
            live.draw(canvas);
        }
        @Override public int getIntrinsicWidth() { return live == null ? super.getIntrinsicWidth() : live.getIntrinsicWidth(); }
        @Override public int getIntrinsicHeight() { return live == null ? super.getIntrinsicHeight() : live.getIntrinsicHeight(); }
    }
}
