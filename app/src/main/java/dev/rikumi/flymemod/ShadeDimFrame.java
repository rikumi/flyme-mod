package dev.rikumi.flymemod;

/** Last submitted dim surface state; surface identity invalidates cached transactions. */
final class ShadeDimFrame {
    private Object surface;
    private float fraction;
    private int color;

    boolean matches(Object currentSurface, float currentFraction, int currentColor) {
        return currentSurface != null && currentSurface == surface
                && currentFraction > 0f && currentFraction == fraction && currentColor == color;
    }

    void record(Object currentSurface, float currentFraction, int currentColor) {
        surface = currentSurface;
        fraction = currentFraction;
        color = currentColor;
    }
}
