package dev.rikumi.flymemod;

import android.graphics.Rect;

/** Bounds used by the device ROM's NotificationBackgroundView.draw() legacy branch. */
final class NotificationBackdropGeometry {
    private NotificationBackdropGeometry() {}

    static void setBounds(Rect result, int viewWidth, int actualWidth, int actualHeight,
            int clipBottom, boolean bottomRounded, boolean bottomClips, boolean expanding,
            boolean alignedRight) {
        int bottom = actualHeight;
        if (bottomRounded && bottomClips && !expanding) bottom -= clipBottom;
        int left = alignedRight ? viewWidth - actualWidth : 0;
        int right = alignedRight ? viewWidth : actualWidth;
        if (expanding) {
            left = (int) ((viewWidth - actualWidth) / 2f);
            right = left + actualWidth;
        }
        result.set(left, 0, right, bottom);
    }
}
