package dev.rikumi.flymemod;

import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import java.lang.reflect.Method;

final class SeparateShadeFixedBlur {
    final ViewGroup root;
    final int radius;
    private final View view;
    private final Drawable drawable;

    SeparateShadeFixedBlur(View root, int radius) throws ReflectiveOperationException {
        this.root = (ViewGroup) root;
        this.radius = Math.max(0, radius);
        Method getViewRoot = View.class.getDeclaredMethod("getViewRootImpl"); getViewRoot.setAccessible(true);
        Object viewRoot = getViewRoot.invoke(root);
        if (viewRoot == null) throw new IllegalStateException("Separate shade window is detached");
        Method create = viewRoot.getClass().getDeclaredMethod("createBackgroundBlurDrawable"); create.setAccessible(true);
        drawable = (Drawable) create.invoke(viewRoot);
        if (drawable == null) throw new IllegalStateException("Cannot create separate shade blur region");
        drawable.getClass().getMethod("setBlurRadius", int.class).invoke(drawable, this.radius);
        drawable.getClass().getMethod("setColor", int.class).invoke(drawable, 0);
        drawable.setAlpha(0);
        view = new View(root.getContext());
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        view.setClickable(false); view.setFocusable(false);
        view.setBackground(drawable);
        this.root.addView(view, 0, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        view.layout(0, 0, root.getWidth(), root.getHeight());
    }

    void alpha(float fraction) { drawable.setAlpha(Math.round(255f * Math.max(0f, Math.min(1f, fraction)))); }

    void close() {
        drawable.setAlpha(0); drawable.setVisible(false, false);
        view.setBackground(null);
        if (view.getParent() == root) root.removeView(view);
    }
}
