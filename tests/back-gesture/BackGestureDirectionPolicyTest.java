package dev.rikumi.flymemod;

public final class BackGestureDirectionPolicyTest {
    public static void main(String[] args) {
        check(BackGestureDirectionPolicy.isHorizontalFirstMove(12f, 3f), "rightward first move");
        check(BackGestureDirectionPolicy.isHorizontalFirstMove(-12f, 3f), "leftward first move");
        check(BackGestureDirectionPolicy.isHorizontalFirstMove(3f, 12f), "ratio below five");
        check(BackGestureDirectionPolicy.isHorizontalFirstMove(-3f, -12f), "negative ratio below five");
        check(BackGestureDirectionPolicy.isHorizontalFirstMove(4f, 4f), "diagonal ratio one");
        for (float dx : new float[] { -4f, 4f }) {
            for (float sign : new float[] { -1f, 1f }) {
                check(BackGestureDirectionPolicy.isHorizontalFirstMove(dx, sign * 20f), "ratio five is included");
                check(!BackGestureDirectionPolicy.isHorizontalFirstMove(dx, sign * Math.nextUp(20f)), "ratio above five is rejected");
            }
        }
        check(!BackGestureDirectionPolicy.isHorizontalFirstMove(0f, 1f), "vertical movement is rejected");
        check(!BackGestureDirectionPolicy.isHorizontalFirstMove(0f, -1f), "negative vertical movement is rejected");
        check(!BackGestureDirectionPolicy.isHorizontalFirstMove(0f, 0f), "zero movement is not horizontal");
        System.out.println("PASS: first-frame direction policy");
    }

    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }
}
