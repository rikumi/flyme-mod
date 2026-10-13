package dev.rikumi.flymemod;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;

final class EarphoneNoiseRipple {
    static Drawable create() {
        Drawable mask=new Drawable() {
            private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override public void draw(Canvas canvas) {
                Rect bounds=getBounds();
                canvas.drawCircle(bounds.exactCenterX(),bounds.exactCenterY(),
                        Math.min(bounds.width(),bounds.height())/2f,paint);
            }
            @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
            @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
        return new RippleDrawable(ColorStateList.valueOf(0x40808080),null,mask);
    }
}
