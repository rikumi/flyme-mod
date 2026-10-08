package dev.rikumi.flymemod;

import android.content.Context;
import android.graphics.Insets;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;
import java.lang.reflect.Method;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Include the scaled QS ancestors in Flyme network-dialog source/return geometry. */
final class NetworkCardAnimationHooks {
    private static final String CONTROLLER = "com.android.systemui.animation.GhostedViewTransitionAnimatorController";
    private final Consumer<Context> settings;
    private final BooleanSupplier enabled;
    private final Class<?> stateClass, launchableClass;
    private final Method source, insets, padding, top, bottom, left, right;

    NetworkCardAnimationHooks(ClassLoader loader, Consumer<Context> settings, BooleanSupplier enabled)
            throws ReflectiveOperationException {
        this.settings = settings;
        this.enabled = enabled;
        Class<?> controller = loader.loadClass(CONTROLLER);
        stateClass = loader.loadClass("com.android.systemui.animation.TransitionAnimator$State");
        launchableClass = loader.loadClass("com.android.systemui.animation.LaunchableView");
        source = controller.getMethod("getGhostedView");
        insets = controller.getMethod("getBackgroundInsets");
        padding = launchableClass.getMethod("getPaddingForLaunchAnimation");
        top = stateClass.getMethod("setTop", int.class);
        bottom = stateClass.getMethod("setBottom", int.class);
        left = stateClass.getMethod("setLeft", int.class);
        right = stateClass.getMethod("setRight", int.class);
        controller.getMethod("fillGhostedViewState", stateClass);
    }

    void install(SignalHooks.Installer installer) {
        installer.hook(CONTROLLER, "fillGhostedViewState", chain -> {
            Object result = chain.proceed();
            View view = target(chain.getThisObject());
            if (view == null || view.getWidth() <= 0 || view.getHeight() <= 0) return result;
            Insets inset = (Insets) insets.invoke(chain.getThisObject());
            Rect pad = launchableClass.isInstance(view) ? (Rect) padding.invoke(view) : new Rect();
            RectF bounds = new RectF(inset.left + pad.left, inset.top + pad.top,
                    view.getWidth() - inset.right + pad.right, view.getHeight() - inset.bottom + pad.bottom);
            Matrix matrix = new Matrix();
            view.transformMatrixToGlobal(matrix);
            matrix.mapRect(bounds);
            // Keep the system's screen origin, including window offsets, while using
            // the full view/ancestor matrix for dimensions and launch padding.
            float[] origin = {0f, 0f};
            matrix.mapPoints(origin);
            int[] screen = new int[2];
            view.getLocationOnScreen(screen);
            bounds.offset(screen[0] - origin[0], screen[1] - origin[1]);
            Object state = chain.getArg(0);
            top.invoke(state, Math.round(bounds.top));
            bottom.invoke(state, Math.round(bounds.bottom));
            left.invoke(state, Math.round(bounds.left));
            right.invoke(state, Math.round(bounds.right));
            return result;
        }, stateClass);
        for (String method : new String[]{"getCurrentTopCornerRadius", "getCurrentBottomCornerRadius"}) {
            installer.hook(CONTROLLER, method, chain -> {
                Object result = chain.proceed();
                View view = target(chain.getThisObject());
                if (view == null) return result;
                float scale = 1f;
                for (android.view.ViewParent parent = view.getParent(); parent instanceof View ancestor;
                     parent = ancestor.getParent()) scale *= ancestor.getScaleX();
                return ((Number) result).floatValue() * scale;
            });
        }
    }

    private View target(Object controller) throws ReflectiveOperationException {
        View view = (View) source.invoke(controller);
        if (view == null) return null;
        settings.accept(view.getContext());
        if (!enabled.getAsBoolean()) return null;
        for (View current = view; current != null;
             current = current.getParent() instanceof View parent ? parent : null) {
            if (current.getClass().getName().equals("com.flyme.systemui.qs.tileimpl.ConnectivityQSTileViewImpl")) return view;
        }
        return null;
    }
}
