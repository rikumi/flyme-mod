package dev.rikumi.flymemod;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import java.lang.reflect.Field;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

final class MediaOutputSurfaceHooks {
    static void install(ClassLoader loader, SignalHooks.Installer installer,
            Consumer<Context> settings, BooleanSupplier light, BooleanSupplier saturation,
            IntSupplier color, Consumer<View> contour, Consumer<View> backdrop,
            BooleanSupplier dark, IntSupplier darkColor)
            throws ReflectiveOperationException {
        String name = "com.android.systemui.media.dialog.MediaOutputDialog";
        loader.loadClass(name).getDeclaredMethod("onCreate", Bundle.class);
        Field content = loader.loadClass("com.android.systemui.media.dialog.MediaOutputBaseDialog")
                .getDeclaredField("mDialogView");
        content.setAccessible(true);
        installer.hook(name, "onCreate", chain -> {
            Object result = chain.proceed();
            Dialog dialog = (Dialog) chain.getThisObject();
            View root = (View) content.get(dialog);
            Window window = dialog.getWindow();
            if (root == null || window == null) return result;
            final int dimFlag = WindowManager.LayoutParams.FLAG_DIM_BEHIND;
            final boolean originalDim = (window.getAttributes().flags & dimFlag) != 0;
            final float originalDimAmount = window.getAttributes().dimAmount;
            Drawable original = root.getBackground();
            Drawable originalWindow = window.getDecorView().getBackground();
            ViewOutlineProvider originalOutline = root.getOutlineProvider();
            GradientDrawable surface = new GradientDrawable();
            float radius = original instanceof GradientDrawable gradient
                    ? gradient.getCornerRadius() : 28f * root.getResources().getDisplayMetrics().density;
            surface.setCornerRadius(radius);
            class Surface implements ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
                boolean applied, dimApplied;
                int previousColor = Integer.MIN_VALUE;
                void update() {
                    settings.accept(root.getContext());
                    boolean dim = dark.getAsBoolean();
                    if (dim || dimApplied) {
                        WindowManager.LayoutParams attributes = window.getAttributes();
                        boolean targetFlag = dim || originalDim;
                        float targetAmount = dim ? Color.alpha(darkColor.getAsInt()) / 255f : originalDimAmount;
                        if (((attributes.flags & dimFlag) != 0) != targetFlag
                                || Float.compare(attributes.dimAmount, targetAmount) != 0) {
                            attributes.flags = targetFlag ? attributes.flags | dimFlag : attributes.flags & ~dimFlag;
                            attributes.dimAmount = targetAmount;
                            window.setAttributes(attributes);
                        }
                        dimApplied = dim;
                    }
                    boolean replace = light.getAsBoolean() || saturation.getAsBoolean();
                    int fill = light.getAsBoolean() ? color.getAsInt() : Color.TRANSPARENT;
                    if (fill != previousColor) { surface.setColor(fill); previousColor = fill; }
                    if (replace == applied) return;
                    applied = replace;
                    root.setBackground(replace ? surface : original);
                    root.setOutlineProvider(replace ? ViewOutlineProvider.BACKGROUND : originalOutline);
                    window.setBackgroundDrawable(replace ? new ColorDrawable(Color.TRANSPARENT) : originalWindow);
                    root.invalidateOutline();
                }
                @Override public boolean onPreDraw() { update(); return true; }
                @Override public void onViewAttachedToWindow(View view) {
                    root.getViewTreeObserver().addOnPreDrawListener(this);
                    update();
                }
                @Override public void onViewDetachedFromWindow(View view) {
                    if (root.getViewTreeObserver().isAlive()) root.getViewTreeObserver().removeOnPreDrawListener(this);
                }
            }
            Surface state = new Surface();
            state.update();
            root.addOnAttachStateChangeListener(state);
            if (root.isAttachedToWindow()) state.onViewAttachedToWindow(root);
            contour.accept(root);
            backdrop.accept(root);
            return result;
        }, Bundle.class);
    }
}
