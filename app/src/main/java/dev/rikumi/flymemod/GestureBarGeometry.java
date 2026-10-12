package dev.rikumi.flymemod;

/** Native ripple size is unscaled; its final capsule uses a 1.35x expansion. */
final class GestureBarGeometry {
    static final float PRESSED_SCALE = 1.35f;
    static int rippleSize(int nativeSize, float barLength, float rippleHeight, float barThickness) {
        // Equal padding: (rippleWidth - barLength)/2 = (rippleHeight - barThickness)/2.
        float equalPaddingWidth = barLength + Math.max(0f, rippleHeight - barThickness);
        if (equalPaddingWidth <= nativeSize * PRESSED_SCALE) return nativeSize;
        return Math.max(nativeSize, (int) Math.ceil(equalPaddingWidth / PRESSED_SCALE));
    }
}
