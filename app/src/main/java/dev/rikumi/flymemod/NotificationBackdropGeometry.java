package dev.rikumi.flymemod;

import android.graphics.Rect;

/** Bounds used by the device ROM's NotificationBackgroundView.draw() legacy branch. */
final class NotificationBackdropGeometry {
    private NotificationBackdropGeometry() {}

    static void clipSides(Rect bounds, float left, float right) {
        // Round inward: no compositor pixel may extend beyond the native clip.
        int clippedLeft = Math.max(bounds.left, (int)Math.ceil(left));
        int clippedRight = Math.min(bounds.right, (int)Math.floor(right));
        if (clippedLeft >= clippedRight) bounds.setEmpty();
        else bounds.set(clippedLeft, bounds.top, clippedRight, bounds.bottom);
    }

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
