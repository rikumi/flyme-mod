package dev.rikumi.flymemod;

import java.util.function.Consumer;

/** Coalesces input callbacks, with a synchronous handoff before a fling takes ownership. */
final class ShadeFrameCallback implements Runnable {
    private final Consumer<Runnable> post, remove;
    private final Runnable render;
    private boolean pending;

    ShadeFrameCallback(Consumer<Runnable> post, Consumer<Runnable> remove, Runnable render) {
        this.post = post;
        this.remove = remove;
        this.render = render;
    }

    void request() {
        if (pending) return;
        pending = true;
        post.accept(this);
    }

    void flush() {
        if (!pending) return;
        remove.accept(this);
        run();
    }

    void cancel() {
        if (!pending) return;
        pending = false;
        remove.accept(this);
    }

    @Override public void run() {
        if (!pending) return;
        pending = false;
        render.run();
    }
}
