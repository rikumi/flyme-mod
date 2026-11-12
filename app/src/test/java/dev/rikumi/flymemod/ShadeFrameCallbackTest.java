package dev.rikumi.flymemod;

import java.util.ArrayDeque;

public final class ShadeFrameCallbackTest {
    private static int checks;
    private static void check(boolean value) {
        checks++;
        if (!value) throw new AssertionError("check " + checks);
    }

    public static void main(String[] args) {
        ArrayDeque<Runnable> frames = new ArrayDeque<>();
        int[] input = {0}, visible = {-1}, renders = {0};
        ShadeFrameCallback callback = new ShadeFrameCallback(frames::add, frames::remove, () -> {
            visible[0] = input[0];
            renders[0]++;
        });
        for (int i = 1; i <= 1000; i++) {
            input[0] = i;
            callback.request();
        }
        check(frames.size() == 1 && renders[0] == 0);
        frames.remove().run();
        check(visible[0] == 1000 && renders[0] == 1);

        // A release before the next vsync must start the fling at the latest pose.
        input[0] = 1200;
        callback.request();
        callback.flush();
        check(visible[0] == 1200 && renders[0] == 2 && frames.isEmpty());
        callback.flush();
        check(renders[0] == 2);

        // A reset/secondary expansion must not replay an old drag after taking ownership.
        callback.request();
        Runnable abandoned = frames.peek();
        callback.cancel();
        abandoned.run();
        check(frames.isEmpty() && renders[0] == 2);
        input[0] = 1500;
        callback.request();
        frames.remove().run();
        check(visible[0] == 1500 && renders[0] == 3);

        // Invalidations during rendering belong to the next frame.
        ShadeFrameCallback[] reentrant = new ShadeFrameCallback[1];
        int[] count = {0};
        reentrant[0] = new ShadeFrameCallback(frames::add, frames::remove, () -> {
            if (++count[0] == 1) reentrant[0].request();
        });
        reentrant[0].request();
        frames.remove().run();
        check(count[0] == 1 && frames.size() == 1);
        frames.remove().run();
        check(count[0] == 2 && frames.isEmpty());
        System.out.println("PASS: " + checks + " shade frame scheduling checks");
    }
}
