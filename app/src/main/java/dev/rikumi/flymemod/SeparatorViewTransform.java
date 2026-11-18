package dev.rikumi.flymemod;

import android.graphics.Matrix;
import android.graphics.RectF;
import android.view.View;

/** Maps resting panel geometry through a notification branch, including sibling branches. */
final class SeparatorViewTransform {
    static float map(View notifications, View panel, RectF bounds, Matrix matrix) {
        View common = commonAncestor(notifications, panel);
        if (common == null) return -1f;
        float x = 0f, y = 0f, opacity = 1f;
        for (View child = notifications; child != common; child = parent(child)) {
            View parent = parent(child);
            x += child.getLeft() - parent.getScrollX();
            y += child.getTop() - parent.getScrollY();
            opacity *= child.getAlpha();
        }
        for (View child = panel; child != common; child = parent(child)) {
            View parent = parent(child);
            x -= child.getLeft() - parent.getScrollX();
            y -= child.getTop() - parent.getScrollY();
        }
        bounds.offset(-x, -y);
        matrix.reset();
        notifications.transformMatrixToGlobal(matrix);
        panel.transformMatrixToLocal(matrix);
        matrix.mapRect(bounds);
        return opacity;
    }

    private static View parent(View view) {
        return view.getParent() instanceof View parent ? parent : null;
    }

    private static View commonAncestor(View source, View target) {
        for (View a = source; a != null; a = parent(a))
            for (View b = target; b != null; b = parent(b))
                if (a == b) return a;
        return null;
    }
}
