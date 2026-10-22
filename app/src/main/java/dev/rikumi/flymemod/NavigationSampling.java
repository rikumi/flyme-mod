package dev.rikumi.flymemod;

/** Independent CPU implementation of ColorOS-style side-region / line fallback sampling. */
final class NavigationSampling {
    // Raw-buffer pixels, not dp. Keep a gap above the backing's 1px seam.
    private static final int SAMPLE_HEIGHT = 10;
    private static final int SIDE_INSET = 2;
    private static final int SIDE_WIDTH = 8;
    private static final int LINE_HEIGHT = 3;
    private static final int SIDE_VARIANCE_LIMIT = 100;
    private static final int LINE_VARIANCE_LIMIT = 25;
    final int left, navigationTop, width, navigationHeight;
    final int captureTop, captureHeight;

    NavigationSampling(int left, int navigationTop, int width, int navigationHeight) {
        if (navigationTop <= 1 || width <= 0 || navigationHeight <= 0) {
            throw new IllegalArgumentException("Invalid navigation sampling bounds");
        }
        this.left = left; this.navigationTop = navigationTop;
        this.width = width; this.navigationHeight = navigationHeight;
        captureHeight = Math.min(SAMPLE_HEIGHT, navigationTop - 1);
        captureTop = navigationTop - 1 - captureHeight;
    }

    boolean matches(int left, int top, int width, int height) {
        return this.left == left && navigationTop == top && this.width == width && navigationHeight == height;
    }

    Integer sample(PixelReader pixels) {
        // Accumulate both sides together: individually uniform but differently colored
        // sides must not arbitrarily select one side or produce their blended color.
        int inset = Math.min(SIDE_INSET, (width - 1) / 2);
        int sideWidth = Math.min(SIDE_WIDTH, (width - 2 * inset) / 2);
        if (sideWidth > 0) {
            NavigationColorSampler sides = new NavigationColorSampler();
            collect(sides, pixels, inset, inset + sideWidth, 0, captureHeight);
            collect(sides, pixels, width - inset - sideWidth, width - inset, 0, captureHeight);
            Integer color = sides.color(SIDE_VARIANCE_LIMIT);
            if (color != null) return color;
        }
        NavigationColorSampler line = new NavigationColorSampler();
        collect(line, pixels, 0, width, Math.max(0, captureHeight - LINE_HEIGHT), captureHeight);
        return line.color(LINE_VARIANCE_LIMIT);
    }

    private static void collect(NavigationColorSampler sample, PixelReader pixels,
            int left, int right, int top, int bottom) {
        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) sample.add(pixels.colorAt(x, y));
        }
    }

    interface PixelReader {
        int colorAt(int x, int y);
    }
}
