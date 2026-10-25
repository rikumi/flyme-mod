package dev.rikumi.flymemod;

final class RevealBoundary {
    private RevealBoundary() {}
    static boolean visible(float dragDp, boolean opening) {

        return dragDp - (opening ? 56f : 0f) > -8f;
    }
}
