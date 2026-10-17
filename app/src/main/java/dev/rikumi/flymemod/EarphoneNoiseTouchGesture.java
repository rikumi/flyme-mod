package dev.rikumi.flymemod;

import android.graphics.Matrix;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.TouchDelegate;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;

/** Shares a tap with the icon and a drag with Flyme's native ToggleSeekBar. */
final class EarphoneNoiseTouchGesture {
    private final ViewGroup parent;
    private final View slider;
    private final TouchDelegate icon;
    private final float startX, startY;
    private final int slop;
    private boolean iconCancelled, cancelled;

    EarphoneNoiseTouchGesture(ViewGroup parent, View icon, View slider, Rect bounds, MotionEvent down) {
        this.parent = parent;
        this.slider = slider;
        this.icon = new TouchDelegate(bounds, icon);
        startX = down.getX(); startY = down.getY();
        slop = ViewConfiguration.get(parent.getContext()).getScaledTouchSlop();
        // Keep the shade from intercepting before ToggleSeekBar recognizes vertical motion.
        parent.requestDisallowInterceptTouchEvent(true);
    }
    void dispatch(MotionEvent event, boolean enabled) {
        int action = event.getActionMasked();
        boolean finish = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL;
        try {
            if (cancelled) return;
            boolean cancel = !enabled || action == MotionEvent.ACTION_CANCEL;
            boolean moved = Math.abs(event.getX() - startX) > slop || Math.abs(event.getY() - startY) > slop
                    || event.getPointerCount() > 1;
            if (!iconCancelled) {
                MotionEvent copy = MotionEvent.obtain(event);
                try {
                    if (cancel || moved) { copy.setAction(MotionEvent.ACTION_CANCEL); iconCancelled = true; }
                    icon.onTouchEvent(copy);
                } finally { copy.recycle(); }
            }
            MotionEvent copy = MotionEvent.obtain(event);
            try {
                if (cancel) copy.setAction(MotionEvent.ACTION_CANCEL);
                // Account for the slider's own position and scale, including QS animations.
                Matrix transform = new Matrix();
                parent.transformMatrixToGlobal(transform);
                slider.transformMatrixToLocal(transform);
                copy.transform(transform);
                slider.dispatchTouchEvent(copy);
            } finally { copy.recycle(); }
            if (cancel) { cancelled = true; parent.requestDisallowInterceptTouchEvent(false); }
        } finally {
            if (finish) parent.requestDisallowInterceptTouchEvent(false);
        }
    }
}
