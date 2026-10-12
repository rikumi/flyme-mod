package dev.rikumi.flymemod;

import java.util.Locale;

/** Verified Flyme surface shades; only background resources/drawing use this policy. */
final class DarkBackgroundPolicy {
    static final int[] SHADES = { 0x0b0b0c, 0x232323 };

    static final int[] LEGACY_BRIGHT_SHADES = { 0x0b0b0c, 0x232323, 0x17171a, 0x202020 };
    static final int[] LEGACY_LOWEST_SHADES = { 0x0b0b0c, 0x232323, 0x1c1c1e, 0x2d2d2d };

    static int[] legacyShades(int family) {
        return family == 1 ? LEGACY_BRIGHT_SHADES : family == 2 ? LEGACY_LOWEST_SHADES : SHADES;
    }
    static int replaceLegacy(int color, boolean enabled, boolean night, int family) {
        if (enabled && night) for (int rgb : legacyShades(family))
            if ((color & 0xffffff) == rgb) return color & 0xff000000;
        return color;
    }

    static final int[] STORE_GLOBAL_SHADES = { 0x0b0b0c, 0x232323, 0x17171a, 0x202020, 0x1c1c1e, 0x2d2d2d };

    static int replaceStoreGlobal(int color, boolean enabled, boolean night) {
        if (enabled && night) for (int rgb : STORE_GLOBAL_SHADES)
            if ((color & 0xffffff) == rgb) return color & 0xff000000;
        return color;
    }

    static int replace(int color, boolean enabled, boolean night) {
        if (enabled && night) for (int rgb : SHADES)
            if ((color & 0xffffff) == rgb) return color & 0xff000000;
        return color;
    }

    static boolean resource(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.contains("on_surface") || lower.contains("onsurface") || lower.contains("inverse")
                || lower.contains("text") || lower.contains("stroke") || lower.contains("shadow")) return false;
        return lower.equals("fd_sys_color_surface_default") || lower.equals("activity_background_color")
                || lower.equals("window_background") || lower.equals("window_background_color")
                || lower.equals("background_color") || lower.equals("white_gradient_start_color")
                || lower.equals("white_gradient_center_color") || lower.equals("white_gradient_end_color");
    }
}
