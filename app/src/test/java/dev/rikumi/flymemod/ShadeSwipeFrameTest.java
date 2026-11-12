package dev.rikumi.flymemod;

/** Host-JVM scenarios; no signing key, Android runtime or test framework required. */
public final class ShadeSwipeFrameTest {
    public static void main(String[] args) {
        for (int hz : new int[]{60, 90, 120}) latestInputPerFrame(hz);
        regrabUsesVisiblePose();
        nativeInvalidationsCoalesce();
        terminalPositionAndSpringAgree();
        System.out.println("PASS: latest input at 60/90/120Hz, idle suppression, re-grab, native invalidation and fling endpoint");
    }

    private static void latestInputPerFrame(int hz) {
        ShadeSwipeFrame frame = new ShadeSwipeFrame(0f);
        int requests = 0, renders = 0;
        for (int tick = 1; tick <= hz; tick++) {
            for (int sample = 1; sample <= 4; sample++) {
                float position = (tick - 1 + sample / 4f) / hz * 1080f;
                if (frame.moveTo(position)) requests++;
            }
            check(frame.consume(), "frame missing");
            renders++;
            near(frame.position(), tick * 1080f / hz);
            check(!frame.consume(), "duplicate render in one traversal");
        }
        check(requests == hz && renders == hz, "touch rate leaked into render rate");
        for (int tick = 0; tick < hz; tick++) {
            check(!frame.moveTo(1080f), "stationary input invalidated");
            check(!frame.consume(), "idle traversal rendered");
        }
    }

    private static void regrabUsesVisiblePose() {
        ShadeSwipeFrame frame = new ShadeSwipeFrame(0f);
        frame.moveTo(600f); frame.consume();
        frame.moveTo(720f); // A queued spring update that has not reached the screen.
        frame.takeOver();
        near(frame.position(), 600f);
        check(!frame.consume(), "cancelled spring frame survived handoff");
        frame.moveTo(580f); frame.consume();
        near(frame.position(), 580f);
    }

    private static void nativeInvalidationsCoalesce() {
        ShadeSwipeFrame frame = new ShadeSwipeFrame(400f);
        check(frame.request(), "native layer refresh missing");
        check(!frame.request(), "duplicate native layer refresh");
        frame.moveTo(450f);
        check(frame.consume(), "native + input frame missing");
        near(frame.position(), 450f);
        check(!frame.consume(), "native update caused an idle redraw loop");
    }

    private static void terminalPositionAndSpringAgree() {
        ShadeSwipeFrame frame = new ShadeSwipeFrame(480f);
        frame.moveTo(560f); // The UP can carry a newer position than the last MOVE.
        check(SeparateShadeSwipeMotion.centerTarget(frame.position(), 1080f, 0f, 600f), "UP position ignored");
        for (int i = 1; i <= 72; i++) {
            frame.moveTo(SeparateShadeSwipeMotion.spring(i / 120f, 560f, 1080f, 900f));
            frame.consume();
        }
        check(Math.abs(frame.position() - 1080f) < 1f, "spring endpoint changed");
        check(!SeparateShadeSwipeMotion.centerTarget(900f, 1080f, -800f, 600f), "reverse fling lost");
    }

    private static void near(float actual, float expected) { check(Math.abs(actual - expected) < .01f, "position drift"); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
