package dev.rikumi.flymemod;

public final class ShaderWarmupGateTest {
    private static int checks;
    private static void check(boolean value) {
        checks++;
        if (!value) throw new AssertionError("check " + checks);
    }

    public static void main(String[] args) {
        check(!ShaderWarmupGate.canCompile(true, true, 2000, 1000));
        check(!ShaderWarmupGate.canCompile(true, false, 999, 1000));
        check(!ShaderWarmupGate.canCompile(false, false, 2000, 1000));
        check(ShaderWarmupGate.canCompile(true, false, 1000, 1000));
        check(ShaderWarmupGate.windowIdle(1, 1f, 0f)); // Idle lockscreen can warm before first pull.
        check(ShaderWarmupGate.windowIdle(0, 0f, 0f));
        check(!ShaderWarmupGate.windowIdle(0, .01f, 0f)); // A first pull must never start compilation.
        check(!ShaderWarmupGate.windowIdle(0, 1f, 0f)); // Nor may pausing on the open panel.
        check(!ShaderWarmupGate.windowIdle(1, 1f, .01f));
        check(!ShaderWarmupGate.windowIdle(2, 1f, 0f));
        ShaderWarmupGate gate = new ShaderWarmupGate();
        check(!gate.ready());
        long first = gate.begin();
        check(!gate.ready()); // CPU compilation/registration cannot authorize first use.
        check(gate.presented(first) && gate.ready());
        long resized = gate.begin();
        check(!gate.ready()); // A larger shader needs its own presented warmup.
        check(!gate.presented(first) && !gate.ready());
        check(gate.presented(resized) && gate.ready());
        gate.invalidate(); // Detach, timeout or failed presentation.
        check(!gate.ready());
        check(!gate.presented(resized) && !gate.ready());
        long retry = gate.begin();
        gate.invalidate();
        check(!gate.presented(retry) && !gate.ready());
        System.out.println("PASS: " + checks + " idle shader/presentation gate checks");
    }
}
