package dev.rikumi.flymemod;

/** Mean RGB color accepted only when the mean of the three channel variances is low. */
final class NavigationColorSampler {
    private long count, red, green, blue, squares;
    private boolean transparent;

    void add(int color) {
        // Task captures have no reliable background behind transparent pixels.
        if ((color >>> 24) != 255) transparent = true;
        int r = (color >>> 16) & 255, g = (color >>> 8) & 255, b = color & 255;
        count++;
        red += r; green += g; blue += b;
        squares += (long) r * r + (long) g * g + (long) b * b;
    }

    Integer color(int varianceLimit) {
        if (transparent || count == 0) return null;
        // E[x²] - E[x]²; compare before dividing to retain boundary precision.
        long variance = count * squares - red * red - green * green - blue * blue;
        if (variance > 3L * varianceLimit * count * count) return null;
        return 0xff000000 | ((int) (red / count) << 16)
                | ((int) (green / count) << 8) | (int) (blue / count);
    }
}
