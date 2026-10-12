package dev.rikumi.flymemod;

/** Prevent injected contours from being baked into native icon recordings or masks. */
final class IconContourDrawScope implements AutoCloseable {
    private static final ThreadLocal<Boolean> raw = ThreadLocal.withInitial(() -> false);
    private final boolean previous;

    private IconContourDrawScope() {
        previous = raw.get();
        raw.set(true);
    }

    static IconContourDrawScope enter() { return new IconContourDrawScope(); }
    static boolean isRaw() { return raw.get(); }

    @Override public void close() {
        if (previous) raw.set(true);
        else raw.remove();
    }
}
