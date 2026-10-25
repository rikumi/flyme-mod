package dev.rikumi.flymemod;

final class ThemeIconPolicy {
    static final int NONE = 0, COLOR_PARTS = 1, WHITE_CUTOUT = 2, PRESERVE_RED = 3, INACTIVE_TAB = 4;
    static int mode(String packageName, String name) {
        // Theme color applies to controls, never to icon artwork or application branding.
        return NONE;
    }
}
