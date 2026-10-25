package dev.rikumi.flymemod;

import java.util.Arrays;

final class ThemeColorPalette {
    private static final int[] SOURCE = {
            0x206cff, 0x1564ed, 0x1368fb, 0x198ded,
            0xe1ecfe, 0xd0e1fe, 0xa3c4fe, 0x639bfd, 0x3b82fc,
            0x095ef1, 0x0d57d9, 0x1051c1, 0x124aab, 0x172747,
    };
    // Native primary shades (including night variants) map directly to the selected accent.
    private static final int[] PRIMARY_ACCENTS = {
            0x35c44f, 0x29b15b, 0xe42d22, 0xba1a1a, 0xffbe0a, 0xdca50c,
    };
    private final int selectedRgb;
    private final int themeFamily;
    private final boolean enabled;
    private final int[] source;
    private final int[] replacement;
    private final int[] output;

    ThemeColorPalette(boolean enabled, int color) {
        this(enabled, color, SOURCE);
    }

    ThemeColorPalette(boolean enabled, int color, int[] colors) {
        this(enabled, color, colors, -1);
    }

    ThemeColorPalette(boolean enabled, int color, int[] colors, int themeFamily) {
        this.themeFamily = themeFamily;
        this.enabled = enabled;
        int rgb = color & 0xffffff;
        selectedRgb = rgb;
        int[] originalPalette = new int[]{
                rgb, rgb, rgb, rgb,
                blend(rgb, 0xffffff, .87f), blend(rgb, 0xffffff, .80f),
                blend(rgb, 0xffffff, .61f), blend(rgb, 0xffffff, .34f),
                blend(rgb, 0xffffff, .17f), blend(rgb, 0, .04f),
                blend(rgb, 0, .13f), blend(rgb, 0, .23f), blend(rgb, 0, .32f),
                blend(rgb, 0x181818, .78f),
        };
        source = Arrays.stream(colors).map(value -> value & 0xffffff).distinct().sorted().toArray();
        replacement = new int[source.length];
        for (int i = 0; i < source.length; i++) {
            int known = -1;
            for (int j = 0; j < SOURCE.length; j++) if (source[i] == SOURCE[j]) known = j;
            replacement[i] = known >= 0 ? originalPalette[known] : tonalColor(source[i], rgb);
        }
        output = replacement.clone();
        Arrays.sort(output);
    }

    private static int tonalColor(int source, int target) {
        int r = (source >> 16) & 255, g = (source >> 8) & 255, b = source & 255;
        int minimum = Math.min(r, Math.min(g, b));
        int maximum = Math.max(r, Math.max(g, b));
        for (int primary : PRIMARY_ACCENTS) if (source == primary) return target;
        // Keep pale containers and dark foregrounds distinct from the main selected accent.
        if (maximum < 180) return blend(target, 0x181818, 1f - maximum / 251f);
        if (minimum > 50) return blend(target, 0xffffff, (minimum - 19) / 236f);
        return target;
    }

    int replace(int color) {
        if (!enabled) return color;
        int rgb = color & 0xffffff;
        // Multiple framework layers may resolve one color. Never transform our own output again.
        if (Arrays.binarySearch(output, rgb) >= 0) return color;
        int index = Arrays.binarySearch(source, rgb);
        return index < 0 ? color : (color & 0xff000000) | replacement[index];
    }

    int replaceResource(int color, boolean primary) {
        if (!enabled) return color;
        int rgb = color & 0xffffff;
        if (!isChromatic(rgb) || themeFamily == 0 || themeFamily > 0 && family(rgb) != themeFamily) return color;
        if (primary) return (color & 0xff000000) | selectedRgb;
        int mapped = replace(color);
        if (mapped != color || Arrays.binarySearch(output, rgb) >= 0) return mapped;

        return (color & 0xff000000) | selectedRgb;
    }

    // Match the same hue intervals used when auditing the APK resources.
    static int family(int color) {
        float r = (color >> 16 & 255) / 255f, g = (color >> 8 & 255) / 255f, b = (color & 255) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), delta = max - min;
        if (max <= .05f || delta / max < (max >= .75f ? .04f : .45f)) return 0;
        float hue = max == r ? 60f * ((g - b) / delta) :
                max == g ? 60f * ((b - r) / delta + 2f) : 60f * ((r - g) / delta + 4f);
        if (hue < 0) hue += 360f;
        if (hue >= 195 && hue <= 250) return 1;
        if (hue >= 85 && hue <= 165) return 2;
        if (hue <= 15 || hue >= 345) return 3;
        if (hue >= 36 && hue <= 75) return 4;
        if (hue > 15 && hue < 36) return 5;
        return 0;
    }

    private static boolean isChromatic(int rgb) {
        int r = rgb >> 16, g = (rgb >> 8) & 255, b = rgb & 255;
        int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        return max > 0 && max - min >= 10;
    }

    private static int blend(int color, int background, float amount) {
        int result = 0;
        for (int shift = 0; shift <= 16; shift += 8) {
            int component = Math.round(((color >> shift) & 255) * (1 - amount)
                    + ((background >> shift) & 255) * amount);
            result |= component << shift;
        }
        return result;
    }
}
