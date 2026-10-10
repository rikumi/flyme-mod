package dev.rikumi.flymemod;

/** One content visibility boundary, expressed in physical dp from the open position. */
final class RevealBoundary {
    private RevealBoundary() {}
    static boolean visible(float dragDp, boolean opening) {
        return dragDp - (opening ? 56f : 0f) > -24f;
    }
}
