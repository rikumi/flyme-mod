package dev.rikumi.flymemod;

final class SeparateShadeSwipeMotion {
    private SeparateShadeSwipeMotion() {}

    static float resist(float position, float width) {
        float limit = Math.max(1f, width * .16f);
        if (position < 0f) return -limit * (1f - (float) Math.exp(position / limit));
        if (position > width) return width + limit * (1f - (float) Math.exp((width - position) / limit));
        return position;
    }

    static float fraction(float position, float width) {
        return Math.max(0f, Math.min(1f, position / Math.max(1f, width)));
    }

    static boolean horizontal(float dx, float dy, float slop) {
        return Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.3f;
    }

    static boolean canStart(boolean inCenter, int page, boolean pagerIdle, float dx) {
        return inCenter ? page == 0 && pagerIdle && dx > 0f : dx < 0f;
    }

    static boolean centerTarget(float position, float width, float velocity, float minimumFling) {
        if (Math.abs(velocity) >= minimumFling) return velocity > 0f;
        return position >= width * .5f;
    }

    static float spring(float seconds, float start, float target, float velocity) {
        double omega = 22.0, damping = .78;
        double decay = omega * damping, frequency = omega * Math.sqrt(1.0 - damping * damping);
        double offset = start - target;
        return target + (float) (Math.exp(-decay * seconds)
                * (offset * Math.cos(frequency * seconds)
                    + (velocity + decay * offset) / frequency * Math.sin(frequency * seconds)));
    }
}
