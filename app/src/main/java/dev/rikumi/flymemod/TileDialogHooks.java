package dev.rikumi.flymemod;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.ColorFilter;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.widget.TextView;
import java.util.function.BooleanSupplier;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

final class TileDialogHooks {
    private final BooleanSupplier light;
    private final IntSupplier color;
    private final Consumer<Context> settings;
    private final Consumer<View> contour, backdrop;
    private final BiConsumer<String, Throwable> log;
    private final BooleanSupplier dark;
    private final Method nightMode, setBarColor;
    private final Field arc, arcBackground, dot;

    TileDialogHooks(ClassLoader loader, BooleanSupplier light, IntSupplier color,
                    Consumer<Context> settings, Consumer<View> contour, Consumer<View> backdrop,
                    BiConsumer<String, Throwable> log, BooleanSupplier dark)
            throws ReflectiveOperationException {
        loader.loadClass("com.flyme.qs.CustomTileDialog").getDeclaredMethod("onCreate", Bundle.class);
        this.light = light; this.color = color; this.settings = settings;
        this.contour = contour; this.backdrop = backdrop;
        this.log = log;
        this.dark = dark;
        Class<?> loading = loader.loadClass("com.meizu.common.widget.LoadingView");
        loading.getDeclaredMethod("onDraw", Canvas.class);
        setBarColor = loading.getMethod("setBarColor", int.class);
        nightMode = View.class.getMethod("actInMzNightMode", int.class);
        arc = loading.getDeclaredField("mPaintArc"); arc.setAccessible(true);
        arcBackground = loading.getDeclaredField("mPaintArcBack"); arcBackground.setAccessible(true);
        dot = loading.getDeclaredField("mDotPaint"); dot.setAccessible(true);
    }

    void install(SignalHooks.Installer installer) {
        installer.hook("com.flyme.qs.CustomTileDialog", "onCreate", chain -> {
            Object result = chain.proceed();
            Dialog dialog = (Dialog) chain.getThisObject();
            Window window = dialog.getWindow();
            if (window == null) return result;
            View root = window.findViewById(id(dialog, "root_tile_dialog"));
            View footer = window.findViewById(id(dialog, "more_fl"));
            View button = window.findViewById(id(dialog, "more_btn"));
            if (root == null || footer == null || !(button instanceof TextView text)) return result;
            settings.accept(root.getContext());
            View loading = window.findViewById(id(dialog, "dialog_loading"));
            if (loading != null) {
                nightMode.invoke(loading, 0);
                loading.setForceDarkAllowed(false);
                setBarColor.invoke(loading, themeBlue(loading));
            }
            try { new Surface(window, root, footer, text).bind(); }
            catch (RuntimeException error) { log.accept("Cannot style control center detail dialog", error); }
            return result;
        }, Bundle.class);
        installer.hook("com.meizu.common.widget.LoadingView", "onDraw", chain -> {
            View view = (View) chain.getThisObject();
            if (!dialogLoading(view)) return chain.proceed();
            Paint foreground = (Paint) arc.get(view), background = (Paint) arcBackground.get(view), dots = (Paint) dot.get(view);
            int fg = foreground.getColor(), bg = background.getColor(), dotColor = dots.getColor();
            ColorFilter fgFilter = foreground.getColorFilter(), bgFilter = background.getColorFilter(), dotFilter = dots.getColorFilter();
            int blue = themeBlue(view);
            foreground.setColor(blue); dots.setColor(blue);
            boolean night = (view.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
            if (dark.getAsBoolean() || night) background.setColor(0x1affffff);
            foreground.setColorFilter(null); background.setColorFilter(null); dots.setColorFilter(null);
            try { return chain.proceed(); }
            finally {
                foreground.setColor(fg); background.setColor(bg); dots.setColor(dotColor);
                foreground.setColorFilter(fgFilter); background.setColorFilter(bgFilter); dots.setColorFilter(dotFilter);
            }
        }, Canvas.class);
    }

    private static int themeBlue(View view) {
        int id = view.getResources().getIdentifier("qs_square_tile_active_bg_color", "color", "com.android.systemui");
        return view.getContext().getColor(id);
    }

    private static boolean dialogLoading(View view) {
        int id = view.getResources().getIdentifier("dialog_loading", "id", "com.android.systemui");
        if (id == 0 || view.getId() != id) return false;
        int rootId = view.getResources().getIdentifier("root_tile_dialog", "id", "com.android.systemui");
        for (android.view.ViewParent parent = view.getParent(); parent instanceof View ancestor; parent = ancestor.getParent())
            if (ancestor.getId() == rootId) return true;
        return false;
    }

    private static int id(Dialog dialog, String name) {
        return dialog.getContext().getResources().getIdentifier(name, "id", "com.android.systemui");
    }

    private final class Surface {
        final Window window;
        final View root, footer;
        final TextView button;
        final Drawable windowBackground, footerBackground, buttonBackground;
        final ColorStateList foreground;
        final ViewGroup.MarginLayoutParams footerParams;
        final ViewGroup.LayoutParams buttonParams;
        final int footerHeight, buttonHeight, compactHeight;
        final int left, top, right, bottom, start, end;
        final GradientDrawable surface = new GradientDrawable(), capsule = new GradientDrawable();
        final Drawable ripple;
        boolean applied;
        int previousColor = Integer.MIN_VALUE;

        Surface(Window window, View root, View footer, TextView button) {
            this.window = window; this.root = root; this.footer = footer; this.button = button;
            windowBackground = window.getDecorView().getBackground();
            footerBackground = footer.getBackground();
            buttonBackground = button.getBackground(); foreground = button.getTextColors();
            footerParams = (ViewGroup.MarginLayoutParams) footer.getLayoutParams();
            buttonParams = button.getLayoutParams();
            footerHeight = footerParams.height; buttonHeight = buttonParams.height;
            left = footerParams.leftMargin; top = footerParams.topMargin;
            right = footerParams.rightMargin; bottom = footerParams.bottomMargin;
            start = footerParams.getMarginStart(); end = footerParams.getMarginEnd();
            int backgroundId = root.getResources().getIdentifier("qs_dialog_bg", "drawable", "com.android.systemui");
            Drawable nativeSurface = root.getContext().getDrawable(backgroundId);
            float radius = nativeSurface instanceof GradientDrawable gradient ? gradient.getCornerRadius() : 0;
            float density = root.getResources().getDisplayMetrics().density;
            // Capsule end radius follows the dialog corner, with a 4dp inset.
            float height = radius > 0 ? 2 * (radius - 4 * density) : footerHeight - 16 * density;
            android.graphics.Paint.FontMetrics metrics = button.getPaint().getFontMetrics();
            compactHeight = Math.round(1.5f * Math.round(Math.max(metrics.bottom - metrics.top + 12 * density,
                    Math.min(footerHeight - 8 * density, height))));
            surface.setCornerRadius(radius);
            capsule.setCornerRadius(10000f);
            int activeId = root.getResources().getIdentifier("qs_square_tile_active_bg_color", "color", "com.android.systemui");
            capsule.setColor(root.getContext().getColor(activeId));
            GradientDrawable mask = new GradientDrawable();
            mask.setColor(Color.WHITE); mask.setCornerRadius(10000f);
            ripple = new RippleDrawable(ColorStateList.valueOf(0x33ffffff), capsule, mask);
        }

        void bind() {
            // A transparent rounded surface also defines the effect mask when light tiles are off.
            root.setBackground(surface);
            root.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
            update();
            contour.accept(root); contour.accept(button); backdrop.accept(root);
            root.getViewTreeObserver().addOnPreDrawListener(() -> { update(); return true; });
        }

        void update() {
            boolean enabled = light.getAsBoolean();
            int fill = enabled ? color.getAsInt() : Color.TRANSPARENT;
            if (previousColor != fill) { surface.setColor(fill); previousColor = fill; }
            if (enabled == applied) return;
            applied = enabled;
            if (enabled) {
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                footer.setBackground(null);
                button.setTextColor(Color.WHITE); button.setBackground(ripple);
                footerParams.height = compactHeight; buttonParams.height = compactHeight;
                int margin = Math.round(12 * root.getResources().getDisplayMetrics().density);
                footerParams.setMargins(margin, top, margin, margin);
                footerParams.setMarginStart(margin); footerParams.setMarginEnd(margin);
            } else {
                window.setBackgroundDrawable(windowBackground);
                footer.setBackground(footerBackground);
                button.setTextColor(foreground); button.setBackground(buttonBackground);
                footerParams.height = footerHeight; buttonParams.height = buttonHeight;
                footerParams.setMargins(left, top, right, bottom);
                footerParams.setMarginStart(start); footerParams.setMarginEnd(end);
            }
            footer.setLayoutParams(footerParams);
            button.setLayoutParams(buttonParams);
            root.invalidateOutline(); button.invalidateOutline();
        }
    }
}
