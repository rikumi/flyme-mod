package dev.rikumi.flymemod;

import android.view.View;
import android.view.ViewGroup;
import java.util.Map;
import java.util.WeakHashMap;

/** Preserve card/row clipping while allowing enlarged icon children past their slots. */
final class CardIconOverflow {
    private final Map<ViewGroup, Clip> originals = new WeakHashMap<>();

    void apply(View icon, View card, boolean enlarged) {
        if (card == null) return;
        for (View current = icon.getParent() instanceof View parent ? parent : null;
             current != null; current = current.getParent() instanceof View parent ? parent : null) {
            if (current instanceof ViewGroup group) {
                if (enlarged) {
                    originals.computeIfAbsent(group, Clip::new);
                    group.setClipChildren(false);
                    group.setClipToPadding(false);
                } else {
                    Clip saved = originals.remove(group);
                    if (saved != null) {
                        group.setClipChildren(saved.children);
                        group.setClipToPadding(saved.padding);
                    }
                }
            }
            if (current == card) break;
        }
    }

    private static final class Clip {
        final boolean children, padding;
        Clip(ViewGroup group) {
            children = group.getClipChildren();
            padding = group.getClipToPadding();
        }
    }
}
