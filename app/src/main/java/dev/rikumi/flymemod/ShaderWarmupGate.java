package dev.rikumi.flymemod;

/** GPU presentation, never source registration, grants permission to use a custom effect. */
final class ShaderWarmupGate {
    private long generation;
    private boolean ready;

    static boolean canCompile(boolean visible, boolean touching, long now, long quietUntil) {
        return visible && !touching && now >= quietUntil;
    }

    static boolean windowIdle(int barState, float panelFraction, float centerFraction) {
        return centerFraction <= 0f && (barState == 1 || (barState == 0 && panelFraction <= 0f));
    }

    synchronized long begin() {
        ready = false;
        return ++generation;
    }

    synchronized boolean presented(long ticket) {
        if (ticket != generation) return false;
        ready = true;
        return true;
    }

    synchronized void invalidate() {
        generation++;
        ready = false;
    }

    synchronized boolean ready() { return ready; }
}
