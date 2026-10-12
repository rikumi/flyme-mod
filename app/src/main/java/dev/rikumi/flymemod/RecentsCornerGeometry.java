package dev.rikumi.flymemod;

/** RoundedCorners/WindowMetrics already use logical pixels; density is applied exactly once. */
final class RecentsCornerGeometry {
    static final int MIN_DP = 10, MAX_DP = 90;
    static int clamp(int dp) { return Math.max(MIN_DP, Math.min(MAX_DP, dp)); }
    static int hardwareDefault(float logicalRadius, float density) {
        if (!(logicalRadius > 0) || !(density > 0)
                || !Float.isFinite(logicalRadius) || !Float.isFinite(density)) return 0;
        return clamp((int) (Math.ceil((double) logicalRadius / density / 2d) * 2d));
    }
    static float cardPixels(int dp, float density, float cardWidth, float taskWidth) {
        if (!(density > 0) || !(cardWidth > 0) || !(taskWidth > 0)
                || !Float.isFinite(density) || !Float.isFinite(cardWidth) || !Float.isFinite(taskWidth)) return Float.NaN;
        // dp describes the complete application's logical space. The native inverse
        // thumbnail matrix maps this card-local radius back to that space later.
        return clamp(dp) * density * (cardWidth / taskWidth);
    }
    static float interpolated(float task, float window, float progress) {
        return task + progress * (window - task);
    }
    static float drawn(float task, float window, float progress, float parentScale, float taskScale) {
        float interpolated = interpolated(task, window, progress);
        // Flyme caps the scaled radius at the card radius. A small custom card must
        // not also cap the full-screen endpoint; let that cap reach the window radius.
        return Math.min(interpolated / parentScale / taskScale, Math.max(task, interpolated));
    }
}
