package dev.rikumi.flymemod;

public final class SwipeMotionTest {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
    private static void near(float actual, float expected, float tolerance) { check(Math.abs(actual - expected) <= tolerance); }
    public static void main(String[] args) {
        for (float width : new float[]{720, 1080, 1440}) {
            // Exact one-to-one drag throughout the usable range; continuous, bounded edges.
            near(SeparateShadeSwipeMotion.resist(width * .4f, width), width * .4f, .001f);
            near(SeparateShadeSwipeMotion.resist(0, width), 0, .001f);
            near(SeparateShadeSwipeMotion.resist(width, width), width, .001f);
            float left = SeparateShadeSwipeMotion.resist(-width, width);
            float right = SeparateShadeSwipeMotion.resist(width * 2, width);
            check(left < 0 && left > -width * .16f);
            check(right > width && right < width * 1.16f);
            near(left, width - right, .002f);
            near(SeparateShadeSwipeMotion.fraction(left, width), 0, 0);
            near(SeparateShadeSwipeMotion.fraction(right, width), 1, 0);
            check(!SeparateShadeSwipeMotion.centerTarget(width * .4f, width, 0, 600));
            check(SeparateShadeSwipeMotion.centerTarget(width * .6f, width, 0, 600));
            check(SeparateShadeSwipeMotion.centerTarget(width * .1f, width, 900, 600));
            check(!SeparateShadeSwipeMotion.centerTarget(width * .9f, width, -900, 600));
            // Opposite-direction flings and releases outside either boundary converge.
            for (float start : new float[]{left, width * .2f, width * .8f, right}) {
                for (float target : new float[]{0, width}) {
                    for (float speed : new float[]{-4000, 0, 4000}) {
                        near(SeparateShadeSwipeMotion.spring(0, start, target, speed), start, .002f);
                        near(SeparateShadeSwipeMotion.spring(.6f, start, target, speed), target, .2f);
                        float step = .00005f;
                        float derivative = (SeparateShadeSwipeMotion.spring(step, start, target, speed) - start) / step;
                        near(derivative, speed, 35f);
                    }
                }
            }
        }
        check(SeparateShadeSwipeMotion.horizontal(-30, 5, 12));
        check(SeparateShadeSwipeMotion.horizontal(30, 5, 12));
        check(!SeparateShadeSwipeMotion.horizontal(10, 2, 12));
        check(!SeparateShadeSwipeMotion.horizontal(20, 25, 12));
        check(!SeparateShadeSwipeMotion.horizontal(20, 18, 12));
        check(Float.isFinite(SeparateShadeSwipeMotion.fraction(1, 0)));
        check(SeparateShadeSwipeMotion.canStart(false, 0, true, -30));
        check(!SeparateShadeSwipeMotion.canStart(false, 0, true, 30));
        check(SeparateShadeSwipeMotion.canStart(true, 0, true, 30));
        check(!SeparateShadeSwipeMotion.canStart(true, 0, true, -30));
        check(!SeparateShadeSwipeMotion.canStart(true, 1, true, 30));
        check(!SeparateShadeSwipeMotion.canStart(true, 1, true, -30));
        check(!SeparateShadeSwipeMotion.canStart(true, 0, false, 30));
        check(!SeparateShadeSwipeMotion.canStart(true, 0, true, 0));
        System.out.println("PASS: " + checks + " swipe direction, damping, velocity and spring checks");
    }
}
