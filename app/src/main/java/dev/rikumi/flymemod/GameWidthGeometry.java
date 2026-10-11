package dev.rikumi.flymemod;

/** Stable stored mode IDs; conditional menu entries never change another mode's ID. */
final class GameWidthGeometry {
    static final String[] NAMES = {"避开挖孔居中", "16:9 居中", "18:9 居中", "19.5:9 居中", "19.5:9 拉伸"};
    static int[] options(int width, int height) {
        if (matches(width, height, 21f / 9f)) return new int[]{0, 1, 2, 3};
        if (matches(width, height, 20f / 9f)) return new int[]{0, 1, 4};
        return new int[]{0, 1};
    }
    static boolean matches(int width, int height, float ratio) {
        return Math.min(width, height) > 0
                && Math.abs(Math.max(width, height) / (float) Math.min(width, height) - ratio) < .04f;
    }
    static int validMode(int mode, int width, int height) {
        for (int option : options(width, height)) if (option == mode) return mode;
        return 0;
    }
    static int width(int mode, int width, int height, int cutoutInset) {
        if (width <= height || height <= 0) return width;
        mode = validMode(mode, width, height);
        float ratio = switch (mode) { case 1 -> 16f / 9f; case 2 -> 2f; case 3, 4 -> 19.5f / 9f; default -> 0f; };
        return ratio == 0f ? Math.max(1, width - 2 * Math.min(Math.max(0, cutoutInset), (width - 1) / 2))
                : Math.min(width, Math.round(height * ratio));
    }
}
