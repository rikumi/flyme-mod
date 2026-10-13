package dev.rikumi.flymemod;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RenderNode;

/** Keep expensive static contour pixels in a hardware layer during shade transforms. */
final class ContourRenderCache {
    private final RenderNode node = new RenderNode("FlymeMod contour cache");
    private final Rect recordedBounds = new Rect();
    private long recordedGeneration = -1;

    void draw(Canvas canvas, Rect bounds, Paint paint, long generation, int alpha) {
        if (!node.hasDisplayList() || generation != recordedGeneration || !recordedBounds.equals(bounds)) {
            node.setPosition(bounds.left, bounds.top, bounds.right, bounds.bottom);
            node.setUseCompositingLayer(true, null);
            Canvas recording = node.beginRecording(bounds.width(), bounds.height());
            try {
                recording.translate(-bounds.left, -bounds.top);
                paint.setAlpha(255);
                recording.drawRect(bounds, paint);
            } finally { node.endRecording(); }
            recordedBounds.set(bounds); recordedGeneration = generation;
        }
        // Alpha and parent translation/scale change composition, not the cached pixels.
        node.setAlpha(Math.max(0, Math.min(255, alpha)) / 255f);
        canvas.drawRenderNode(node);
    }

    void clear() {
        node.discardDisplayList(); node.setUseCompositingLayer(false, null);
        recordedGeneration = -1; recordedBounds.setEmpty();
    }
}
