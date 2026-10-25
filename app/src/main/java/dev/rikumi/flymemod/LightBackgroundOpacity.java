package dev.rikumi.flymemod;

final class LightBackgroundOpacity {
    static int effective(int value, boolean night) {
        return Math.max(0, Math.min(40, value)) + (night ? 0 : 60);
    }
}
