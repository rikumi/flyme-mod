package dev.rikumi.flymemod;

/** Latest input wins until the next traversal; a re-grab starts at the visible pose. */
final class ShadeSwipeFrame {
    private float position, renderedPosition;
    private boolean pending;

    ShadeSwipeFrame(float position) { this.position = renderedPosition = position; }

    float position() { return position; }

    boolean moveTo(float next) {
        if (position == next) return false;
        position = next;
        return request();
    }

    boolean request() {
        if (pending) return false;
        pending = true;
        return true;
    }

    boolean consume() {
        if (!pending) return false;
        pending = false;
        renderedPosition = position;
        return true;
    }

    void takeOver() {
        position = renderedPosition;
        pending = false;
    }
}
