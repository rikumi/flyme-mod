package dev.rikumi.flymemod;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.Build;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.SeekBar;
import java.lang.ref.WeakReference;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.*;

/** ColorOS AGSL algorithms, with Flyme 16260625 view-local contour overlays. */
final class ColorOsMaterialHooks {
    private final Consumer<Context> settings;
    private final BooleanSupplier contourEnabled, splitNetworkEnabled;
    private final BiConsumer<String, Throwable> log;
    private final Map<View, Host> hosts = new WeakHashMap<>();
    private final ClassLoader loader;
    private final Map<String, String> sources = new HashMap<>();
    private Context assets;

    ColorOsMaterialHooks(ClassLoader loader, Consumer<Context> settings,
            BooleanSupplier contour, BooleanSupplier splitNetwork, BiConsumer<String, Throwable> log)
            throws ReflectiveOperationException {
        this.loader = loader; this.settings = settings; contourEnabled = contour; splitNetworkEnabled = splitNetwork; this.log = log;

    }

    void install(ClassLoader loader, SignalHooks.Installer installer) throws ReflectiveOperationException {
        if (Build.VERSION.SDK_INT < 33) return;
        View.class.getDeclaredMethod("invalidateOutline");
        installer.hook("android.view.View", "invalidateOutline", chain -> {
            Object result = chain.proceed();
            View view = (View) chain.getThisObject();
            Host host = hosts.get(view);
            if (host != null && host.outline != null) {
                host.outline.maskWidth = 0;
                host.outline.invalidateSelf();
            }
            return result;
        });
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
        // Controllers supply the two card roots after XML inflation; initialize() binds the sliders later.
        for (String panel : new String[]{"com.flyme.systemui.controlcenter.phone.MzQSPanel",
                "com.flyme.systemui.controlcenter.phone.MzQQSPanel"}) {
            Class<?> panelClass = loader.loadClass(panel);
            panelClass.getDeclaredMethod("initialize");
            panelClass.getField("mBrightnessView"); panelClass.getField("mVolumeView");
            installer.hook(panel, "initialize", chain -> {
                Object result = chain.proceed(); Object owner = chain.getThisObject();
                if (field(owner, "mConnectivityTilesWrapper") instanceof View wrapper) track(wrapper, 1);
                for (String name : new String[]{"mBrightnessView", "mVolumeView"})
                    if (field(owner, name) instanceof View card) track(card);
                return result;
            });
            for (String setter : new String[]{"setBrightnessView", "setVolumeView"}) {
                panelClass.getDeclaredMethod(setter, View.class);
                installer.hook(panel, setter, chain -> {
                    Object result = chain.proceed();
                    if (chain.getArg(0) instanceof View card) track(card);
                    return result;
                }, View.class);
            }
        }
        installer.hook("com.flyme.systemui.media.controls.ui.view.MediaCarouseTransitionLayout", "setBackground", chain -> {
            Object result = chain.proceed(); track((View) chain.getThisObject()); return result;
        });
    }

    void refresh() {
        for (View view : new ArrayList<>(hosts.keySet())) if (view != null) apply(view);
    }

    // Invoked after SplitNetworkCardHooks has applied/restored each row's native background.
    void updateNetworkRows(View card) {
        try {
            track(card, 1);
            for (String name : new String[]{"wifiContainer", "bluetoothContainer"}) {
                if (field(card, name) instanceof View row) {
                    if (row.getParent() instanceof View parent && parent != card) track(parent, 1);
                    track(row, 2);
                }
            }
        } catch (ReflectiveOperationException error) { log.accept("Cannot bind split network contours", error); }
    }

    private void track(View view) { track(view, 0); }
    private void track(View view, int networkRole) {
        settings.accept(view.getContext());
        if (!hosts.containsKey(view)) {
            hosts.put(view, new Host());
            view.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> apply(v));
            view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                public void onViewAttachedToWindow(View v) { apply(v); }
                public void onViewDetachedFromWindow(View v) { remove(v); }
            });
        }
        if (networkRole != 0) hosts.get(view).networkRole = networkRole;
        apply(view);
    }

    private void apply(View view) {
        if (Build.VERSION.SDK_INT < 33) return;
        Host host = hosts.get(view);
        if (host == null) return;
        try {
            boolean split = splitNetworkEnabled.getAsBoolean();
            boolean visible = host.networkRole == 1 ? !split : host.networkRole != 2 || split;
            if (!contourEnabled.getAsBoolean() || !visible) {
                if (host.outline != null) { view.getOverlay().remove(host.outline); host.outline = null; }
            } else {
                if (host.outline == null) host.outline = new Contour(view);
                // Restore attachment even if a native transition cleared its overlay.
                view.getOverlay().remove(host.outline);
                view.getOverlay().add(host.outline);
                host.outline.maskWidth = 0; // Layout/settings refresh may replace the native outline without resizing.
                host.outline.setBounds(0, 0, view.getWidth(), view.getHeight());
                host.outline.invalidateSelf();
            }

        } catch (Exception | LinkageError error) { fail(view, error); }
    }

    private void remove(View view) {
        Host host = hosts.get(view);
        if (host == null) return;
        if (host.outline != null) { view.getOverlay().remove(host.outline); host.outline = null; }

    }
    private void fail(View view, Throwable error) {
        Host host = hosts.get(view);
        if (host != null && !host.errorLogged) {
            host.errorLogged = true;
            log.accept("Cannot render ColorOS contour for " + view.getClass().getName(), error);
        }
    }
    private String source(View view, String name) throws Exception {
        if (sources.containsKey(name)) return sources.get(name);
        if (assets == null) assets = view.getContext().createPackageContext("dev.rikumi.flymemod", 0);
        try (java.io.InputStream input = assets.getAssets().open("coloros_material/" + name + ".agsl")) {
            String value = new String(input.readAllBytes(), StandardCharsets.UTF_8); sources.put(name, value); return value;
        }
    }
    private static Object field(Object owner, String name) throws ReflectiveOperationException { return owner.getClass().getField(name).get(owner); }
    private static boolean night(View view) { return (view.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES; }
    private static Drawable nativeFill(View view, Drawable fallback) {
        try { if (field(view, "backgroundDrawable") instanceof Drawable drawable) return drawable; }
        catch (ReflectiveOperationException ignored) { }
        return fallback;
    }
    private static Drawable surface(View view, Drawable drawable) {
        drawable = nativeFill(view, drawable);
        if (drawable instanceof LayerDrawable layers) {
            int id = view.getResources().getIdentifier("background", "id", "com.android.systemui");
            Drawable surface = layers.findDrawableByLayerId(id);
            if (surface != null) drawable = surface;
        }
        while (drawable instanceof DrawableWrapper wrapper && wrapper.getDrawable() != null) drawable = wrapper.getDrawable();
        if (view instanceof SeekBar seek && seek.getProgressDrawable() instanceof LayerDrawable layers) {
            Drawable background = layers.findDrawableByLayerId(android.R.id.background);
            if (background != null) drawable = background;
        }
        return drawable;
    }
    private static float radius(View view, Drawable drawable) {
        // Both slider cards use the root's native outline rather than the progress Drawable's rectangle.
        if (view.getClass().getName().equals("com.android.systemui.settings.brightness.BrightnessSliderView")) {
            int id = view.getResources().getIdentifier("qs_corner_radius", "dimen", "com.android.systemui");
            if (id != 0) return view.getResources().getDimension(id);
        }
        drawable = surface(view, drawable);
        if (drawable instanceof GradientDrawable gradient && gradient.getCornerRadius() > 0) return gradient.getCornerRadius();
        try { return ((Number) field(drawable, "radius")).floatValue(); } catch (ReflectiveOperationException ignored) { }
        try { return ((Number) field(view, "mClipCornerRadius")).floatValue(); } catch (ReflectiveOperationException ignored) { }
        return 14f * view.getResources().getDisplayMetrics().density;
    }
    private static final class Host { Contour outline; int networkRole; boolean errorLogged; }

    private final class Contour extends Drawable {
        final WeakReference<View> owner;
        final RuntimeShader round, nativeCurve;
        final Method generatePath;
        final Field outlinePath;
        ViewOutlineProvider maskProvider;
        int maskWidth, maskHeight;
        float maskRadius = -1, maskStroke;
        boolean maskSmooth;
        Path maskSource;
        int maskGeneration;
        float maskSmoothness;
        final Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        int alpha = 255;
        final float[] nearParams = new float[]{0, .4f, .2f, .2f, 0, 0};
        final float[] farParams = new float[]{0, .2f, .2f, .2f, 0, 0};
        Contour(View view) throws Exception {
            owner = new WeakReference<>(view);
            round = new RuntimeShader(source(view, "contour_round"));
            {
                nativeCurve = new RuntimeShader(source(view, "contour_native_path"));
                Class<?> generator = loader.loadClass("com.meizu.common.util.SmoothCornerPathGenerator");
                generatePath = generator.getMethod("genSmoothCornerPath", float.class, float.class,
                        float.class, float.class, float.class, float.class, boolean.class);
                // Verified against the target Flyme framework Outline: public Path mPath.
                outlinePath = Outline.class.getField("mPath");
            }
        }
        public void draw(Canvas canvas) {
            View view = owner.get(); Rect bounds = getBounds();
            if (view == null || bounds.isEmpty()) return;
            try {
                float radius = Math.min(radius(view, view.getBackground()), Math.min(bounds.width(), bounds.height()) / 2f);
                RuntimeShader shader;
                Drawable surface = surface(view, view.getBackground());
                boolean slider = view.getClass().getName().equals("com.android.systemui.settings.brightness.BrightnessSliderView");
                boolean naturalCard = surface != null && surface.getClass().getName()
                        .equals("com.android.systemui.qs.CustomSmoothCornerDrawable");
                if (slider || naturalCard) {
                    shader = updateNativeMask(view, bounds, radius, surface, slider) ? nativeCurve : round;
                } else shader = round; // Native GradientDrawable/round-rect backgrounds retain their actual circular arcs.
                shader.setFloatUniform("u_size", bounds.width(), bounds.height());
                shader.setFloatUniform("u_corner", radius); shader.setFloatUniform("u_weight", .2f);
                shader.setColorUniform("uColor", Color.WHITE); // Native ratio walks the perimeter from the bottom-right corner. This places the
                // near peak at top center and the far peak at bottom center for any aspect ratio.
                shader.setFloatUniform("uRatio", .5f + bounds.width() / (4f * (bounds.width() + bounds.height())));
                // GradientStrokeLineAdapter QS light/night templates, scaled from ColorOS's 6px stroke.
                float width = 2f * view.getResources().getDisplayMetrics().density;
                nearParams[0] = farParams[0] = width; nearParams[1] = night(view) ? .2f : .4f;
                shader.setFloatUniform("uNearLineParams", nearParams);
                shader.setFloatUniform("uFarLineParams", farParams);
                paint.setShader(shader); paint.setAlpha(alpha);
                canvas.drawRect(bounds, paint);
            } catch (Exception | LinkageError error) { fail(view, error); }
        }
        private boolean updateNativeMask(View view, Rect bounds, float radius, Drawable surface, boolean slider)
                throws ReflectiveOperationException {
            float stroke = 2f * view.getResources().getDisplayMetrics().density;
            boolean natural = true;
            ViewOutlineProvider provider = slider ? view.getOutlineProvider() : null;
            Path nativePath = null;
            float smoothness = .2f;
            if (!slider && surface != null) {
                smoothness = ((Number) field(surface, "smoothness")).floatValue();
                if (field(surface, "path") instanceof Path path) nativePath = path;
            }
            if (maskWidth == bounds.width() && maskHeight == bounds.height() && maskRadius == radius
                    && maskStroke == stroke && maskSmooth == natural && maskSource == nativePath
                    && maskGeneration == (nativePath == null ? 0 : nativePath.getGenerationId())
                    && maskSmoothness == smoothness && maskProvider == provider) return true;
            Path path;
            if (slider) {
                // The slider's rectangular track is clipped by this exact provider, not by a
                // separately generated rounded rectangle. Copy its current path and coordinates.
                if (provider == null) return false;
                Outline outline = new Outline();
                provider.getOutline(view, outline);
                Rect rect = new Rect();
                if (outline.getRect(rect)) {
                    path = new Path();
                    float outlineRadius = Math.max(0f, outline.getRadius());
                    path.addRoundRect(rect.left, rect.top, rect.right, rect.bottom,
                            outlineRadius, outlineRadius, Path.Direction.CW);
                } else if (outlinePath.get(outline) instanceof Path actual && !actual.isEmpty()) {
                    path = new Path(actual);
                } else return false;
            } else if (nativePath != null && !nativePath.isEmpty()) path = new Path(nativePath);
            else path = (Path) generatePath.invoke(null, 0f, 0f, (float) bounds.width(),
                    (float) bounds.height(), smoothness, radius, false);
            // Copy the native outline into a local mask; no compositor or background rendering is changed.
            Bitmap bitmap = Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.ARGB_8888);
            Canvas mask = new Canvas(bitmap);
            mask.clipPath(path);
            maskPaint.setStyle(Paint.Style.STROKE);
            // Red stores inward edge distance; alpha stores curve coverage. Keep ColorOS's original
            // fade-toward-center math instead of substituting a uniform-brightness stroke.
            for (float distance = stroke; distance > 0f; distance -= .5f) {
                int red = Math.round(255f * Math.min(1f, distance / stroke));
                maskPaint.setColor(Color.argb(255, red, 0, 0));
                maskPaint.setStrokeWidth(distance * 2f);
                mask.drawPath(path, maskPaint);
            }
            nativeCurve.setInputBuffer("uMask", new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
            maskWidth = bounds.width(); maskHeight = bounds.height(); maskRadius = radius;
            maskStroke = stroke; maskSmooth = natural;
            maskSource = nativePath; maskSmoothness = smoothness; maskProvider = provider;
            maskGeneration = nativePath == null ? 0 : nativePath.getGenerationId();
            return true;
        }
        public void setAlpha(int value) { alpha = value; invalidateSelf(); }
        public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
