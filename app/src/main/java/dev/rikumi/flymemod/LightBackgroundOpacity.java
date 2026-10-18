package dev.rikumi.flymemod;

/** Slider stores 0–40%; day surfaces add 60 percentage points for dark text. */
final class LightBackgroundOpacity {
    static int effective(int value, boolean night) {
        return Math.max(0, Math.min(40, value)) + (night ? 0 : 60);
    }
}
