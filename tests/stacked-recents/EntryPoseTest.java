package dev.rikumi.flymemod;

import java.util.Random;

/** Changes native geometry between the animation callback and live-surface submission. */
public final class EntryPoseTest {
    private static int checks;
    private static void near(float actual, float expected) {
        checks++;
        if (Math.abs(actual - expected) > .004f)
            throw new AssertionError(actual + " != " + expected);
    }
    private static float center(float origin, float scroll, float translation,
            float size, float pivot, float scale) {
        return origin - scroll + translation + pivot + (size / 2 - pivot) * scale;
    }
    public static void main(String[] args) {
        // A late native translation of 24px used to be added to an offset computed
        // for the previous baseline. The surface could display that 24px excursion
        // before dispatchDraw recomputed it in the same animation progress interval.
        float target = 370f, origin = 90f, scroll = 30f, size = 600f, pivot = 210f, scale = .8f;
        float nativeTranslation = 18f;
        float translation = StackedRecentsGeometry.translationForCenter(target, origin, scroll, size, pivot, scale);
        float offset = translation - nativeTranslation;
        float nextNative = nativeTranslation + 24f;
        near(center(origin, scroll, nextNative + offset, size, pivot, scale), target + 24f);
        offset += nativeTranslation - nextNative;
        near(center(origin, scroll, nextNative + offset, size, pivot, scale), target);

        Random random = new Random(12600);
        // Both directions/axes: native baseline, layout, scroll, size and pivot may
        // change repeatedly while the animation's projected center is monotonic.
        for (int direction : new int[]{-1, 1}) {
            float previous = direction < 0 ? 1100f : 180f;
            float start = previous, end = direction < 0 ? 180f : 1100f;
            for (int frame = 0; frame <= 120; frame++) {
                float progress = frame / 120f;
                float wanted = start + (end - start) * progress;
                for (int callback = 0; callback < 10; callback++) {
                    float left = random.nextInt(1600), scrolled = random.nextInt(1600);
                    float extent = 100 + random.nextInt(1600), anchor = random.nextFloat() * extent;
                    float zoom = .35f + random.nextFloat() * 1.4f;
                    float x = StackedRecentsGeometry.translationForCenter(wanted, left, scrolled, extent, anchor, zoom);
                    float actual = center(left, scrolled, x, extent, anchor, zoom);
                    near(actual, wanted);
                    checks++;
                    if (direction * (actual - previous) < -.004f) throw new AssertionError("trajectory reversed");
                    previous = actual;
                    // Same-frame repeated submission is idempotent, including after a native page snap.
                    float resubmitted = StackedRecentsGeometry.translationForCenter(wanted, left, scrolled + 200,
                            extent, anchor + 30, zoom);
                    near(center(left, scrolled + 200, resubmitted, extent, anchor + 30, zoom), actual);
                }
            }
        }
        // Zero scale and a corner pivot cannot introduce NaN/division by zero.
        near(center(50, 70, StackedRecentsGeometry.translationForCenter(100, 50, 70, 600, 0, 0), 600, 0, 0), 100);
        System.out.println("PASS: " + checks + " late-write, scroll/pivot, monotonic entry and handoff geometry checks");
    }
}
