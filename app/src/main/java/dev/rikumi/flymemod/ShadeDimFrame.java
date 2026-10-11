package dev.rikumi.flymemod;

import java.lang.reflect.Field;

/** Last submitted dim surface state; surface identity invalidates cached transactions. */
final class ShadeDimFrame {
    private Object surface;
    private float fraction;
    private int color;

    static float effectiveFraction(float requested, float contentProgress) {
        // Unknown content retains native behavior. A completed fade owns zero
        // even when a queued repository/blur-scale callback still carries radius.
        return contentProgress <= 0f ? 0f : requested;
    }

    static void removeImmediately(Object owner, Field surfaceField) throws ReflectiveOperationException {
        Object surface = surfaceField.get(owner);
        if (surface == null) return;
        Class<?> type = Class.forName("android.view.SurfaceControl$Transaction");
        Object transaction = type.getConstructor().newInstance();
        try {
            type.getMethod("remove", surfaceField.getType()).invoke(transaction, surface);
            type.getMethod("apply").invoke(transaction);
            surfaceField.set(owner, null);
        } finally {
            type.getMethod("close").invoke(transaction);
        }
    }

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
