package dev.rikumi.flymemod;

/** One content visibility boundary, expressed in physical dp from the open position. */
final class RevealBoundary {
    private RevealBoundary() {}
    static boolean visible(float dragDp, boolean opening) {
        // Wait until the header is closer to its resting position,
        // and hide it sooner when reversing upward across that same boundary.
        return dragDp - (opening ? 56f : 0f) > -8f;
    }
}
