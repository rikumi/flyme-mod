package dev.rikumi.flymemod;

/** Continuous stack coordinates in units of the original page spacing. */
final class StackedRecentsGeometry {
    private static final double LOG_TWO = Math.log(2d);
    private static final float LEFT_EFFECT_START = 1.25f;
    private static final float LEFT_CULL_DISTANCE = 3f;

    static float curve(float distance) {
        return Float.isFinite(distance) ? (float) (Math.log1p(Math.abs(distance)) / LOG_TWO) : 0f;
    }

    private static float leftProgress(float distance) {
        float amount = curve(distance);
        return amount / (amount + 3f);
    }

    static float offset(float distance, float cardWidth, float anchor, float edgeInset) {
        // Only horizontal spacing converges just beyond the viewport edge.
        // Cards retain a readable minimum scale and opacity there.
        // The right branch expands with d * (1 + log(1 + d)), whose positive
        // second derivative keeps successive gaps growing instead of shrinking.
        return distance < 0f ? -(anchor + edgeInset) * leftProgress(distance)
                : cardWidth * .425f * distance * (1f + curve(distance));
    }

    static float scale(float distance) {
        float amount = curve(distance);
        return distance < 0f ? Math.max(.72f, 1f - .065f * amount)
                : Math.min(1.22f, 1f + .08f * amount);
    }

    static float entryScale(float nativeScale, float startScale, float stackScale,
            float progress, float fullscreenStrength) {
        return nativeScale + nativeScale * (stackScale - 1f) * progress * fullscreenStrength
                + (startScale - nativeScale) * (1f - progress);
    }

    static float alpha(float distance) {
        return distance < -LEFT_EFFECT_START
                ? Math.max(.25f, 1f - .28f * curve(distance + LEFT_EFFECT_START)) : 1f;
    }

    static float leftEdgeVisibility(float distance) {
        // Fade and blur start together; reverse motion remains continuous.
        float progress = Math.max(0f, Math.min(1f,
                (distance + LEFT_CULL_DISTANCE) / (LEFT_CULL_DISTANCE - LEFT_EFFECT_START)));
        return progress * progress * (3f - 2f * progress);
    }

    static float blurDp(float distance) {
        return distance < -LEFT_EFFECT_START ? Math.min(6f, 3f * curve(distance + LEFT_EFFECT_START)) : 0f;
    }

    static float titleVisibility(float distance) {
        float progress = Math.max(0f, Math.min(1f, distance + 1.5f));
        return progress * progress * (3f - 2f * progress);
    }

    static int previousPage(int running, int next, int count) {
        // Preserve an explicit horizontal selection. The next MRU item is index + 1.
        return running >= 0 && next == running && running + 1 < count ? running + 1 : -1;
    }

    private StackedRecentsGeometry() {}
}
