package dev.rikumi.flymemod;

import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewGroup;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

final class MediaContourGeometry {
    final Rect visible = new Rect(), local = new Rect();
    final Path path = new Path();
    private final Path clip = new Path(), mapped = new Path();
    private final Rect rect = new Rect();
    private final RectF bounds = new RectF();
    private final Outline outline = new Outline();
    private final Matrix toAncestor = new Matrix(), inverse = new Matrix();
    private final Class<?> notificationOutline, transition, stack, mediaContainer;
    private final Method needsClip, childClip, rowClip;
    private final Field outlinePath, transitionBounds, stackClipping, stackPath, mediaClipPath, mediaClipHeight, mediaCorner;
    MediaContourGeometry(ClassLoader loader) throws ReflectiveOperationException {
        notificationOutline = loader.loadClass("com.android.systemui.statusbar.notification.row.ExpandableOutlineView");
        needsClip = notificationOutline.getMethod("childNeedsClipping", View.class);
        childClip = notificationOutline.getMethod("getCustomClipPath", View.class);
        rowClip = notificationOutline.getMethod("getClipPath", boolean.class);
        transition = loader.loadClass("com.android.systemui.util.animation.TransitionLayout");
        transitionBounds = transition.getField("boundsRect");
        stack = loader.loadClass("com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout");
        stackClipping = stack.getField("mShouldUseRoundedRectClipping");
        stackPath = stack.getField("mRoundedClipPath");
        outlinePath = Outline.class.getField("mPath");
        mediaContainer = loader.loadClass("com.android.systemui.statusbar.notification.stack.MediaContainerView");
        mediaClipPath = mediaContainer.getField("clipPath");
        mediaClipHeight = mediaContainer.getField("clipHeight");
        mediaCorner = mediaContainer.getField("cornerRadius");
    }
    boolean update(View view, Canvas canvas, float radius) throws ReflectiveOperationException {
        path.reset();
        path.addRoundRect(0, 0, view.getWidth(), view.getHeight(), radius, radius, Path.Direction.CW);
        // The background's own provider can be a smooth path rather than circular arcs.
        if (readOutline(view)) {
            if (outline.getRadius() != 0) path.set(clip);
            else path.op(clip, Path.Op.INTERSECT); // A default rectangular Drawable outline is not the fill's rounded shape.
        }
        toAncestor.reset(); inverse.reset();
        if (view.getClipBounds() != null) intersectRect(view.getClipBounds());
        if (!canvas.getClipBounds(rect)) return empty();
        intersectRect(rect);
        if (transition.isInstance(view)) intersectRect((Rect) transitionBounds.get(view));
        for (View child = view; child.getParent() instanceof View ancestor; child = ancestor) {
            toAncestor.postConcat(child.getMatrix());
            toAncestor.postTranslate(child.getLeft() - ancestor.getScrollX(), child.getTop() - ancestor.getScrollY());
            if (!toAncestor.invert(inverse)) return empty();
            if (notificationOutline.isInstance(ancestor) && Boolean.TRUE.equals(needsClip.invoke(ancestor, child))) {
                Path nativeClip = (Path) childClip.invoke(ancestor, child);
                if (nativeClip == null) nativeClip = (Path) rowClip.invoke(ancestor, false);
                if (nativeClip != null) intersect(nativeClip);
            }
            Rect ancestorClip = ancestor.getClipBounds();
            if (ancestorClip != null) intersectRect(ancestorClip);
            if (ancestor instanceof ViewGroup group) {
                if (group.getClipChildren()) intersectRect(0, 0, ancestor.getWidth(), ancestor.getHeight());
                if (group.getClipToPadding() && (ancestor.getPaddingLeft() != 0 || ancestor.getPaddingTop() != 0
                        || ancestor.getPaddingRight() != 0 || ancestor.getPaddingBottom() != 0)) intersectRect(ancestor.getPaddingLeft(), ancestor.getPaddingTop(),
                        ancestor.getWidth() - ancestor.getPaddingRight(), ancestor.getHeight() - ancestor.getPaddingBottom());
            }
            if (transition.isInstance(ancestor)) intersectRect((Rect) transitionBounds.get(ancestor));
            if (mediaContainer.isInstance(ancestor)) {
                Path nativeMediaClip = (Path) mediaClipPath.get(ancestor);
                if (nativeMediaClip != null && !nativeMediaClip.isEmpty()) intersect(nativeMediaClip);
                else {
                    clip.reset();
                    float corner = mediaCorner.getFloat(ancestor);
                    clip.addRoundRect(0, 0, ancestor.getWidth(), mediaClipHeight.getInt(ancestor),
                            corner, corner, Path.Direction.CW);
                    intersect(clip);
                }
            }
            if (ancestor.getClipToOutline() && readOutline(ancestor)) intersect(clip);
            if (stack.isInstance(ancestor) && stackClipping.getBoolean(ancestor))
                intersect((Path) stackPath.get(ancestor)); // Already includes native Y translation and group roundness.
            if (path.isEmpty()) return empty();
        }
        path.computeBounds(bounds, true);
        visible.set((int) Math.ceil(bounds.left), (int) Math.ceil(bounds.top),
                (int) Math.floor(bounds.right), (int) Math.floor(bounds.bottom));
        if (visible.isEmpty()) return empty();

        inverse.reset(); intersectRect(visible);
        path.offset(-visible.left, -visible.top);
        local.set(0, 0, visible.width(), visible.height());
        return !path.isEmpty();
    }
    private boolean readOutline(View view) throws IllegalAccessException {
        clip.reset();
        if (view.getOutlineProvider() == null) return false;
        outline.setEmpty(); view.getOutlineProvider().getOutline(view, outline);
        if (outline.getRect(rect)) {
            float radius = Math.max(0, outline.getRadius());
            clip.addRoundRect(rect.left, rect.top, rect.right, rect.bottom, radius, radius, Path.Direction.CW);
            return true;
        }
        if (outlinePath.get(outline) instanceof Path nativePath && !nativePath.isEmpty()) {
            clip.set(nativePath); return true;
        }
        return false;
    }
    private void intersectRect(Rect rect) { intersectRect(rect.left, rect.top, rect.right, rect.bottom); }
    private void intersectRect(float left, float top, float right, float bottom) {
        clip.reset(); clip.addRect(left, top, right, bottom, Path.Direction.CW); intersect(clip);
    }
    private void intersect(Path nativePath) {
        // Never mutate a path owned by the native provider, row or stack.
        mapped.set(nativePath); mapped.transform(inverse);
        path.op(mapped, Path.Op.INTERSECT);
    }
    private boolean empty() { path.reset(); visible.setEmpty(); local.setEmpty(); return false; }
}
