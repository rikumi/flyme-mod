package dev.rikumi.flymemod;

final class LauncherExtraRowPolicy {
    static final String DIRECTORY = "flymemod-extra-row-v1";
    static final int WIDGET_HOST = 0x464d01;

    static boolean isSupportedSize(int width, int height) {
        int shortSide = Math.min(width, height), longSide = Math.max(width, height);
        // Physical display modes, not the inset or multi-window application viewport.
        return shortSide > 0 && Math.abs((double) longSide / shortSide - 21.0 / 9.0) <= 0.02;
    }

    static int rowHeight(int nativeHeight, int expandedRows) {
        return Math.max(1, Math.round(nativeHeight * (expandedRows - 1f) / expandedRows));
    }
}
