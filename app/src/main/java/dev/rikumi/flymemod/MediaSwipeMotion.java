package dev.rikumi.flymemod;

final class MediaSwipeMotion {
    static final float THRESHOLD_DP = 56f;
    static final float DAMPING = .22f;
    static final long TRANSITION_MS = 90;
    private int region;
    private final float threshold;

    MediaSwipeMotion(float density) { threshold = THRESHOLD_DP * density; }

    boolean update(float distance) {
        int next = Math.abs(distance) >= threshold ? (distance < 0 ? -1 : 1) : 0;
        boolean changed = next != region;
        region = next;
        return changed;
    }

    boolean followsFinger() { return region != 0; }
    float target(float distance) { return followsFinger() ? distance : distance * DAMPING; }
}
