package com.rikumi.flymemod;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.Build;
import android.view.View;
import android.widget.SeekBar;
import java.lang.ref.WeakReference;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.*;

/** ColorOS AGSL algorithms, with Flyme 16260625 view and wallpaper lifecycles. */
final class ColorOsMaterialHooks {
    private final Consumer<Context> settings;
    private final BooleanSupplier contourEnabled, materialEnabled;
    private final BiConsumer<String, Throwable> log;
    private final Map<View, Host> hosts = new WeakHashMap<>();
    private final Map<Drawable, Backdrop> backdrops = new WeakHashMap<>();
    private final Class<?> wallpaperClass, managerClass;
    private final Map<String, String> sources = new HashMap<>();
    private Context assets;
    private boolean failed;

    ColorOsMaterialHooks(ClassLoader loader, Consumer<Context> settings,
            BooleanSupplier contour, BooleanSupplier material, BiConsumer<String, Throwable> log)
            throws ReflectiveOperationException {
        this.settings = settings; contourEnabled = contour; materialEnabled = material; this.log = log;
        wallpaperClass = loader.loadClass("com.flyme.systemui.wallpaper.WallpaperBlurDrawable");
        managerClass = loader.loadClass("com.flyme.systemui.wallpaper.WallpaperBlurDrawableManager");
        for (String name : new String[]{"mBitmapPaint", "mColorPaint", "mBitmapShader", "mBitmapShaderMatrix",
                "mCenterCropMatrix", "mInvertMatrix", "mOutLocation", "mRectPath"}) wallpaperClass.getField(name);
        wallpaperClass.getMethod("getTotalInvertMatrix");
        managerClass.getMethod("getInstance", Context.class);
        managerClass.getMethod("addBlurDrawableTo", View.class, int.class, float.class);
    }

    void install(ClassLoader loader, SignalHooks.Installer installer) throws ReflectiveOperationException {
        if (Build.VERSION.SDK_INT < 33) return;
        Class<?> state = loader.loadClass("com.android.systemui.plugins.qs.QSTile$State");
        for (String name : new String[]{"com.android.systemui.qs.tileimpl.QSTileViewImpl",
                "com.flyme.systemui.qs.tileimpl.FlymeCustomQSTileView"}) {
            installer.hook(name, "updateResources", chain -> {
                Object result = chain.proceed(); track((View) chain.getThisObject()); return result;
            });
            installer.hook(name, "handleStateChanged", chain -> {
                Object result = chain.proceed(); track((View) chain.getThisObject()); return result;
            }, state);
        }
        installer.hook("com.flyme.systemui.controlcenter.phone.MzQSPanel", "onFinishInflate", chain -> {
            Object result = chain.proceed(); Object panel = chain.getThisObject();
            for (String name : new String[]{"mConnectivityTilesWrapper", "mBrightnessSlider", "mVolumeSlider"})
                if (field(panel, name) instanceof View view) track(view);
            return result;
        });
        installer.hook("com.flyme.systemui.media.controls.ui.view.MediaCarouseTransitionLayout", "setBackground", chain -> {
            Object result = chain.proceed(); track((View) chain.getThisObject()); return result;
        });
        installer.hook(wallpaperClass.getName(), "draw", chain -> {
            Drawable drawable = (Drawable) chain.getThisObject();
            Backdrop backdrop = backdrops.get(drawable);
            View view = backdrop == null ? null : backdrop.owner.get();
            if (view == null || !materialEnabled.getAsBoolean() || failed) return chain.proceed();
            try {
                Shader input = (Shader) field(drawable, "mBitmapShader");
                if (input == null) return chain.proceed();
                view.getLocationInWindow((int[]) field(drawable, "mOutLocation"));
                Matrix inverse = (Matrix) field(drawable, "mInvertMatrix");
                inverse.set((Matrix) wallpaperClass.getMethod("getTotalInvertMatrix").invoke(drawable));
                Matrix matrix = (Matrix) field(drawable, "mBitmapShaderMatrix");
                matrix.set((Matrix) field(drawable, "mCenterCropMatrix")); matrix.postConcat(inverse);
                input.setLocalMatrix(matrix);
                backdrop.shader.setInputShader("uBitmap", input);
                backdrop.update(night(view), active(view));
                backdrop.paint.set((Paint) field(drawable, "mBitmapPaint"));
                backdrop.paint.setShader(backdrop.shader);
                Canvas canvas = (Canvas) chain.getArg(0);
                Path path = (Path) field(drawable, "mRectPath");
                canvas.drawPath(path, backdrop.paint);
                canvas.drawPath(path, (Paint) field(drawable, "mColorPaint"));
                return null;
            } catch (Exception | LinkageError error) {
                fail(error); return chain.proceed();
            }
        }, Canvas.class);
    }

    void refresh() {
        for (View view : new ArrayList<>(hosts.keySet())) if (view != null) apply(view);
    }

    private void track(View view) {
        settings.accept(view.getContext());
        if (!hosts.containsKey(view)) {
            hosts.put(view, new Host());
            view.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> apply(v));
            view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                public void onViewAttachedToWindow(View v) { apply(v); }
                public void onViewDetachedFromWindow(View v) { remove(v); }
            });
        }
        apply(view);
    }

    private void apply(View view) {
        if (Build.VERSION.SDK_INT < 33) return;
        Host host = hosts.get(view);
        if (host == null) return;
        try {
            if (failed || !contourEnabled.getAsBoolean()) {
                if (host.outline != null) { view.getOverlay().remove(host.outline); host.outline = null; }
            } else {
                if (host.outline == null) { host.outline = new Contour(view); view.getOverlay().add(host.outline); }
                host.outline.setBounds(0, 0, view.getWidth(), view.getHeight());
                host.outline.invalidateSelf();
            }
            boolean material = !failed && materialEnabled.getAsBoolean() && view.isAttachedToWindow();
            // SeekBars own a progress Drawable, not a background fill. Keep their native progress rendering.
            if (view instanceof SeekBar) material = false;
            if (!material) {
                if (host.wrapper != null && view.getBackground() == host.wrapper) view.setBackground(host.original);
                if (host.wrapper != null) wallpaperClass.getMethod("removeOnPreDrawListener").invoke(host.wrapper.backdrop);
                host.wrapper = null;
                return;
            }
            Drawable current = view.getBackground();
            try { if (field(view, "backgroundDrawable") instanceof Drawable fill && fill.getAlpha() == 0) return; }
            catch (ReflectiveOperationException ignored) { }
            if (current == host.wrapper) { view.invalidate(); return; }
            if (current == null) return;
            host.original = current;
            Drawable wallpaper;
            if (wallpaperClass.isInstance(current)) wallpaper = current;
            else {
                Object manager = managerClass.getMethod("getInstance", Context.class).invoke(null, view.getContext());
                wallpaper = (Drawable) managerClass.getMethod("addBlurDrawableTo", View.class, int.class, float.class)
                        .invoke(manager, view, 0, radius(view, current));
                wallpaperClass.getMethod("setForegroundColor", int.class).invoke(wallpaper, 0);
                float corner = radius(view, current);
                wallpaperClass.getMethod("setCornerRadius", float.class, float.class, float.class, float.class)
                        .invoke(wallpaper, corner, corner, corner, corner);
            }
            if (!backdrops.containsKey(wallpaper)) backdrops.put(wallpaper, new Backdrop(view));
            if (wallpaper == current) return;
            Drawable fill = nativeFill(view, current);
            if (current instanceof RippleDrawable ripple) {
                int id = view.getResources().getIdentifier("background", "id", "com.android.systemui");
                Drawable layer = ripple.findDrawableByLayerId(id);
                if (layer != null) fill = layer;
            }
            host.wrapper = new Material(current, wallpaper, fill, () -> {
                try { return !failed && materialEnabled.getAsBoolean() && field(wallpaper, "mBitmapShader") != null; }
                catch (ReflectiveOperationException error) { return false; }
            });
            view.setBackground(host.wrapper);
        } catch (Exception | LinkageError error) { fail(error); }
    }

    private void remove(View view) {
        Host host = hosts.get(view);
        if (host == null) return;
        if (host.outline != null) { view.getOverlay().remove(host.outline); host.outline = null; }
        if (host.wrapper != null && view.getBackground() == host.wrapper) view.setBackground(host.original);
        if (host.wrapper != null) {
            try { wallpaperClass.getMethod("removeOnPreDrawListener").invoke(host.wrapper.backdrop); }
            catch (ReflectiveOperationException ignored) { }
        }
        host.wrapper = null;
    }
    private void fail(Throwable error) { if (!failed) { failed = true; log.accept("Cannot render ColorOS materials", error); refresh(); } }
    private String source(View view, String name) throws Exception {
        if (sources.containsKey(name)) return sources.get(name);
        if (assets == null) assets = view.getContext().createPackageContext("com.rikumi.flymemod", 0);
        try (java.io.InputStream input = assets.getAssets().open("coloros_material/" + name + ".agsl")) {
            String value = new String(input.readAllBytes(), StandardCharsets.UTF_8); sources.put(name, value); return value;
        }
    }
    private static Object field(Object owner, String name) throws ReflectiveOperationException { return owner.getClass().getField(name).get(owner); }
    private static boolean night(View view) { return (view.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES; }
    private static boolean active(View view) {
        try { return ((Number) field(view, "lastState")).intValue() == 2; }
        catch (ReflectiveOperationException ignored) { return false; }
    }
    private static Drawable nativeFill(View view, Drawable fallback) {
        try { if (field(view, "backgroundDrawable") instanceof Drawable drawable) return drawable; }
        catch (ReflectiveOperationException ignored) { }
        return fallback;
    }
    private static float radius(View view, Drawable drawable) {
        drawable = nativeFill(view, drawable);
        while (drawable instanceof DrawableWrapper wrapper && wrapper.getDrawable() != null) drawable = wrapper.getDrawable();
        if (view instanceof SeekBar seek && seek.getProgressDrawable() instanceof LayerDrawable layers) {
            Drawable background = layers.findDrawableByLayerId(android.R.id.background);
            if (background != null) drawable = background;
        }
        if (drawable instanceof GradientDrawable gradient && gradient.getCornerRadius() > 0) return gradient.getCornerRadius();
        try { return ((Number) field(drawable, "radius")).floatValue(); } catch (ReflectiveOperationException ignored) { }
        try { return ((Number) field(view, "mClipCornerRadius")).floatValue(); } catch (ReflectiveOperationException ignored) { }
        return 14f * view.getResources().getDisplayMetrics().density;
    }
    private static final class Host { Drawable original; Material wrapper; Contour outline; }

    private final class Contour extends Drawable {
        final WeakReference<View> owner;
        final RuntimeShader round, smooth;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        int alpha = 255;
        final float[] nearParams = new float[]{0, .4f, .2f, .2f, 0, 0};
        final float[] farParams = new float[]{0, .2f, .2f, .2f, 0, 0};
        Contour(View view) throws Exception {
            owner = new WeakReference<>(view);
            round = new RuntimeShader(source(view, "contour_round"));
            smooth = new RuntimeShader(source(view, "contour_smooth"));
        }
        public void draw(Canvas canvas) {
            View view = owner.get(); Rect bounds = getBounds();
            if (view == null || bounds.isEmpty() || failed) return;
            try {
                float radius = Math.min(radius(view, view.getBackground()), Math.min(bounds.width(), bounds.height()) / 2f);
                RuntimeShader shader = radius >= Math.min(bounds.width(), bounds.height()) / 2f ? round : smooth;
                shader.setFloatUniform("u_size", bounds.width(), bounds.height());
                shader.setFloatUniform("u_corner", radius); shader.setFloatUniform("u_weight", .2f);
                shader.setColorUniform("uColor", Color.WHITE); shader.setFloatUniform("uRatio", .5f);
                // GradientStrokeLineAdapter QS light/night templates, scaled from ColorOS's 6px stroke.
                float width = 2f * view.getResources().getDisplayMetrics().density;
                nearParams[0] = farParams[0] = width; nearParams[1] = night(view) ? .2f : .4f;
                shader.setFloatUniform("uNearLineParams", nearParams);
                shader.setFloatUniform("uFarLineParams", farParams);
                paint.setShader(shader); paint.setAlpha(alpha);
                canvas.drawRect(bounds, paint);
            } catch (RuntimeException | LinkageError error) { fail(error); }
        }
        public void setAlpha(int value) { alpha = value; invalidateSelf(); }
        public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
    private final class Backdrop {
        final WeakReference<View> owner; final RuntimeShader shader; final Paint paint = new Paint();
        Boolean dark, enabled;
        Backdrop(View view) throws Exception { owner = new WeakReference<>(view); shader = new RuntimeShader(source(view, "material")); }
        void update(boolean night, boolean active) {
            if (dark != null && dark == night && enabled == active) return;
            dark = night; enabled = active;
            shader.setColorUniform("uBgLum", Color.parseColor(night ? "#A6404040" : "#99333333"));
            shader.setColorUniform("uBgOverlay", Color.parseColor("#80999999"));
            shader.setColorUniform("uFgLum", Color.parseColor(active ? "#B3E6E6E6" : night ? "#66262626" : "#4D8C8C8C"));
            shader.setColorUniform("uFgOverlay", Color.parseColor(active ? "#80CCCCCC" : night ? "#66383838" : "#80B2B2B2"));
            shader.setFloatUniform("uActive", active ? 1f : 0f);
        }
    }
    private static final class Material extends DrawableWrapper {
        final Drawable backdrop, fill; final BooleanSupplier ready; boolean suppress;
        Material(Drawable original, Drawable backdrop, Drawable fill, BooleanSupplier ready) {
            super(original); this.backdrop = backdrop; this.fill = fill; this.ready = ready;
            backdrop.setCallback(this);
        }
        public void draw(Canvas canvas) {
            if (!ready.getAsBoolean()) { super.draw(canvas); return; }
            if (!backdrop.getBounds().equals(getBounds())) backdrop.setBounds(getBounds());
            backdrop.draw(canvas);
            // Retain native ripple/state layers without painting an opaque fill over the sampled material.
            if (fill != getDrawable()) {
                int alpha = fill.getAlpha(); suppress = true;
                try { fill.setAlpha(0); super.draw(canvas); }
                finally { fill.setAlpha(alpha); suppress = false; }
            }
        }
        public void invalidateDrawable(Drawable drawable) { if (!suppress) invalidateSelf(); }
    }
}
