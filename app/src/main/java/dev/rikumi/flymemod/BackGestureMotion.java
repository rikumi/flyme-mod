package dev.rikumi.flymemod;

import android.graphics.PointF;
import android.view.MotionEvent;

/** Read the first sample beyond touch slop, including samples batched into a MOVE event. */
final class BackGestureMotion {
    private BackGestureMotion() {}

    static PointF firstDisplacement(MotionEvent event, float startX, float startY, boolean raw, float slop) {
        float offsetX = raw ? event.getRawX() - event.getX() : 0f;
        float offsetY = raw ? event.getRawY() - event.getY() : 0f;
        for (int i = 0; i < event.getHistorySize(); i++) {
            float dx = event.getHistoricalX(i) + offsetX - startX;
            float dy = event.getHistoricalY(i) + offsetY - startY;
            if (Math.hypot(dx, dy) > slop) return new PointF(dx, dy);
        }
        float dx = event.getX() + offsetX - startX;
        float dy = event.getY() + offsetY - startY;
        return Math.hypot(dx, dy) > slop ? new PointF(dx, dy) : null;
    }
}
