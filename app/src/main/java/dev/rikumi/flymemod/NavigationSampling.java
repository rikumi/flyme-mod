package dev.rikumi.flymemod;

/** Fixed sample locations in one raw-task capture, including the backing's 1px seam. */
final class NavigationSampling {
    static final int ROW_POINTS = 20;
    static final int REGION_ROWS = 3;
    static final int REGION_COLUMNS = 4;
    static final int REGION_POINTS = REGION_ROWS * REGION_COLUMNS;
    final int left, navigationTop, width, navigationHeight;
    final int captureTop, captureHeight;
    private final int[] xs = new int[ROW_POINTS + REGION_POINTS];
    private final int[] ys = new int[ROW_POINTS + REGION_POINTS];

    NavigationSampling(int left, int navigationTop, int width, int navigationHeight) {
        if (navigationTop <= 1 || width <= 0 || navigationHeight <= 0) {
            throw new IllegalArgumentException("Invalid navigation sampling bounds");
        }
        this.left = left; this.navigationTop = navigationTop;
        this.width = width; this.navigationHeight = navigationHeight;
        captureTop = navigationTop - 2;
        captureHeight = navigationHeight + 2;
        for (int i = 0; i < ROW_POINTS; i++) {
            // Cosine spacing: dense at both edges, sparse around the middle.
            xs[i] = (int) Math.round((width - 1) * (1 - Math.cos(Math.PI * i / (ROW_POINTS - 1))) / 2);
        }
        int regionHeight = navigationHeight + 1;
        for (int row = 0; row < REGION_ROWS; row++) {
            for (int column = 0; column < REGION_COLUMNS; column++) {
                int index = ROW_POINTS + row * REGION_COLUMNS + column;
                xs[index] = (2 * column + 1) * width / (2 * REGION_COLUMNS);
                ys[index] = 1 + (2 * row + 1) * regionHeight / (2 * REGION_ROWS);
            }
        }
    }

    boolean matches(int left, int top, int width, int height) {
        return this.left == left && navigationTop == top && this.width == width && navigationHeight == height;
    }

    int x(int point) { return xs[point]; }
    int y(int point) { return ys[point]; }

    Integer sample(PixelReader pixels) {
        int[] row = new int[ROW_POINTS];
        for (int i = 0; i < ROW_POINTS; i++) row[i] = pixels.colorAt(xs[i], ys[i]);
        int reference = pixels.colorAt(xs[ROW_POINTS], ys[ROW_POINTS]);
        boolean uniform = true;
        for (int i = ROW_POINTS + 1; i < xs.length; i++) {
            // The interior gate is exact; the ~1% tolerance belongs only to the
            // dominant-color vote among the twenty exterior sample points.
            if (pixels.colorAt(xs[i], ys[i]) != reference) uniform = false;
        }
        return uniform ? NavigationColorSampler.dominantColor(row) : null;
    }

    interface PixelReader {
        int colorAt(int x, int y);
    }
}
