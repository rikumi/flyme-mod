package dev.rikumi.flymemod;

final class BackGestureDirectionPolicy {
    private BackGestureDirectionPolicy() {}

    static boolean isHorizontalFirstMove(float deltaX, float deltaY) {
        double absoluteX = Math.abs(deltaX);
        return absoluteX > 0d && Math.abs(deltaY) <= absoluteX * 5d;
    }

    static boolean isInwardFirstMove(float deltaX, float deltaY, boolean leftEdge) {
        return (leftEdge ? deltaX > 0f : deltaX < 0f) && isHorizontalFirstMove(deltaX, deltaY);
    }
}
