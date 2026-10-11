package dev.rikumi.flymemod;

import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.view.View;
import android.view.ViewTreeObserver;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Array;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.DoubleSupplier;

/** Compose saturated, brightened live SurfaceFlinger background under the tile foreground. */
final class TileBackdropHooks {
    private static final String NETWORK = "com.flyme.systemui.qs.tileimpl.ConnectivityQSTileViewImpl";
    private static final String SLIDER_OWNER = "com.android.systemui.settings.brightness.BrightnessSliderView";
    static final String SHADER = """
            uniform shader sksl_layer;
            uniform shader sksl_background;
            uniform float sksl_left, sksl_top, sksl_right, sksl_bottom, sksl_alpha;
            uniform float smoothCorner;
            uniform float saturationAmount;
            uniform float cornerRadius;
            uniform float2 shapeSize;
            uniform float4 corner0, corner1, corner2, corner3;
            float2 cubic(float2 a, float2 b, float2 c, float2 d, float t) {
                float s = 1.0 - t;
                return s*s*s*a + 3.0*s*s*t*b + 3.0*s*t*t*c + t*t*t*d;
            }
            float mask(float2 p) {
                float2 size = float2(sksl_right-sksl_left,sksl_bottom-sksl_top);
                float scale = min(size.x/shapeSize.x,size.y/shapeSize.y);
                float2 q = min(p-float2(sksl_left,sksl_top),float2(sksl_right,sksl_bottom)-p)*shapeSize/size;
                if (q.x < -0.5 || q.y < -0.5) return 0.0;
                if (smoothCorner < 0.5) {
                    float distance = cornerRadius > 0.0
                            ? cornerRadius - length(max(float2(cornerRadius)-q,float2(0.0))) : min(q.x,q.y);
                    return smoothstep(-0.5,0.5,distance*scale);
                }
                if (q.x >= corner3.x || q.y >= corner0.y)
                    return smoothstep(-0.5,0.5,min(q.x,q.y)*scale);
                float2 a = corner0.xy, b = corner0.zw, c = corner1.xy, d = corner1.zw;
                if (q.x > d.x) { a = d; b = corner2.xy; c = corner2.zw; d = corner3.xy; }
                // Binary search needs only x. Compute scalar polynomial coefficients once
                // instead of evaluating both coordinates of the cubic twelve times.
                float x3 = -a.x + 3.0*b.x - 3.0*c.x + d.x;
                float x2 = 3.0*a.x - 6.0*b.x + 3.0*c.x;
                float x1 = 3.0*(b.x-a.x);
                float lo = 0.0, hi = 1.0;
                for (int i = 0; i < 12; i++) {
                    float t = (lo+hi)*0.5;
                    if (((x3*t+x2)*t+x1)*t+a.x < q.x) lo = t; else hi = t;
                }
                float t = (lo+hi)*0.5, s = 1.0-t;
                float2 tangent = 3.0*s*s*(b-a) + 6.0*s*t*(c-b) + 3.0*t*t*(d-c);
                float distance = (q.y-cubic(a,b,c,d,t).y) * tangent.x / max(length(tangent), 0.0001);
                return smoothstep(-0.5,0.5,distance*scale)
                        * smoothstep(-0.5,0.5,min(q.x,q.y)*scale);
            }
            half4 main(float2 p) {
                half4 layer = sksl_layer.eval(p);
                // Regions are composed recursively across the whole window. Avoid sampling
                // the backdrop or solving the corner curve where this tile cannot contribute.
                if (sksl_alpha <= 0.0 || layer.a >= 1.0) return layer;
                float coverage = mask(p)*sksl_alpha;
                if (coverage <= 0.0) return layer;
                half4 background = sksl_background.eval(p);
                half luminance = dot(background.rgb, half3(0.2126,0.7152,0.0722));
                half3 saturated = clamp(half3(luminance) + (background.rgb-half3(luminance))*(1.0+saturationAmount), 0.0, background.a);
                // Fixed +10% brightness applies only to the sampled backdrop. Preserve its
                // premultiplied alpha and leave icons, text and tile foreground unchanged.
                half3 brightened = min(saturated*1.1, half3(background.a));
                half4 composed = layer + half4(brightened,background.a)*(1.0-layer.a);
                return mix(layer,composed,coverage);
            }
            """;
    private final TileCornerHooks corners;
    private final BooleanSupplier enabled;
    private final IntSupplier networkStyle;
    private final DoubleSupplier saturationAmount;
    private final BiConsumer<String, Throwable> log;
    private final Consumer<String> trace;
    private final Method createEffect, createDrawable, setFloat, setFloat2, setFloat4;
    private final Field trackerNode;
    private final Class<?> regionArray;
    private final Field regionId, regionRect, regionAlpha;
    private volatile long shaderId;
    private final AtomicBoolean reportedRegions = new AtomicBoolean();
    private final Map<Object,Long> effects = new WeakHashMap<>();
    private final Method getRoot;
    private final Set<String> reportedTypes = new HashSet<>();
    private final Method createCurve, curvePoints;
    private final Object curveCompanion;
    private final Field tileBackground, customTileBackground, smoothRadius, smoothness;
    private final Class<?> smoothDrawable;
    private final Field smoothCornersEnabled, wifiContainer, bluetoothContainer;
    private final Map<View, WeakReference<State>> states = new WeakHashMap<>();

    TileBackdropHooks(ClassLoader loader, BooleanSupplier enabled, DoubleSupplier saturationAmount,
                      IntSupplier networkStyle, TileCornerHooks corners, BiConsumer<String, Throwable> log,
                      Consumer<String> trace)
            throws ReflectiveOperationException {
        this.enabled = enabled; this.log = log; this.trace = trace;
        this.networkStyle = networkStyle; this.corners = corners;
        this.saturationAmount = saturationAmount;
        // Validate SkSL locally too, so syntax errors reach the module log before SF registration.
        new RuntimeShader(SHADER);
        getRoot = View.class.getDeclaredMethod("getViewRootImpl"); getRoot.setAccessible(true);
        Class<?> rootType = loader.loadClass("android.view.ViewRootImpl");
        createEffect = rootType.getMethod("createFlymeEffect", String.class);
        createDrawable = rootType.getMethod("createFlymeEffectDrawable", long.class);
        Class<?> drawableType = loader.loadClass("com.android.internal.graphics.drawable.FlymeEffectDrawable");
        setFloat = drawableType.getMethod("setFloatUniform", String.class, float.class);
        setFloat2 = drawableType.getMethod("setFloat2Uniform", String.class, float.class, float.class);
        setFloat4 = drawableType.getMethod("setFloat4Uniform", String.class, float.class, float.class, float.class, float.class);
        trackerNode = drawableType.getDeclaredField("mRenderNode"); trackerNode.setAccessible(true);
        Class<?> region = loader.loadClass("com.android.internal.graphics.drawable.FlymeEffectDrawable$FlymeEffectRegion");
        regionArray = Array.newInstance(region,0).getClass();
        regionId = region.getField("flymeEffectId"); regionRect = region.getField("rect"); regionAlpha = region.getField("alpha");
        Class<?> smooth = loader.loadClass("com.android.systemui.qs.CustomSmoothCornerDrawable");
        smoothDrawable = smooth;
        smoothRadius = smooth.getField("radius"); smoothness = smooth.getField("smoothness");
        tileBackground = loader.loadClass("com.android.systemui.qs.tileimpl.QSTileViewImpl").getField("backgroundDrawable");
        customTileBackground = loader.loadClass("com.flyme.systemui.qs.tileimpl.FlymeCustomQSTileView").getField("backgroundDrawable");
        Class<?> curve = loader.loadClass("com.meizu.common.util.SmoothCornerPathGenerator$BezierCurve");
        curveCompanion = curve.getField("Companion").get(null);
        createCurve = curveCompanion.getClass().getMethod("create", float.class, float.class);
        curvePoints = curve.getMethod("getPoints");
        smoothCornersEnabled = loader.loadClass("com.flyme.systemui.utils.SystemUICommonUtils")
                .getField("isEnableSmoothRoundCorner");
        Class<?> network = loader.loadClass(NETWORK);
        wifiContainer = network.getField("wifiContainer");
        bluetoothContainer = network.getField("bluetoothContainer");
    }

    void install(SignalHooks.Installer installer) {
        installer.hook(SLIDER_OWNER, "initBrightnessViewComponents", chain -> {
            Object result = chain.proceed();
            observe((View) chain.getThisObject());
            return result;
        });
        for (String method : new String[]{"setupLayout", "updateResources"})
            installer.hook(NETWORK, method, chain -> {
                Object result = chain.proceed();
                observeNetworkRows((View) chain.getThisObject());
                return result;
            });
        installer.hook("android.view.ViewRootImpl", "dispatchFlymeEffectRegions", chain -> {
            Object result = chain.proceed();
            if (reportedRegions.get() || shaderId == 0) return result;
            Object[] regions = (Object[]) chain.getArg(0);
            int count = 0; Object sample = null;
            for (Object region : regions) {
                if (regionId.getLong(region) == shaderId) { count++; sample = region; }
            }
            if (sample != null && reportedRegions.compareAndSet(false,true))
                trace.accept("Live tile backdrop compositor regions: count=" + count + ", bounds="
                        + regionRect.get(sample) + ", alpha=" + regionAlpha.getFloat(sample));
            return result;
        }, regionArray, long.class);
    }

    void observe(View view) {
        if (view == null) return;
        if (view.getClass().getName().equals("com.android.systemui.settings.brightness.ToggleSeekBar")) {
            for (android.view.ViewParent parent = view.getParent(); parent instanceof View ancestor;
                 parent = ancestor.getParent()) {
                if (ancestor.getClass().getName().equals(SLIDER_OWNER)) { observe(ancestor); return; }
            }
            return; // The owner is bound after inflation; its outline clips the complete slider.
        }
        int role = NETWORK.equals(view.getClass().getName()) ? 1 : 0;
        observe(view, role);
        if (role == 1) observeNetworkRows(view);
    }

    private void observeNetworkRows(View card) {
        try {
            observe((View) wifiContainer.get(card), 2);
            observe((View) bluetoothContainer.get(card), 2);
        } catch (ReflectiveOperationException | RuntimeException error) {
            log.accept("Cannot bind separate network backdrop shapes", error);
        }
    }

    private void observe(View view, int networkRole) {
        if (corners != null) corners.observe(view, networkRole);
        if (view == null || states.containsKey(view)) return;
        State state = new State(view, networkRole);
        states.put(view, new WeakReference<>(state));
        view.addOnAttachStateChangeListener(state);
        if (view.isAttachedToWindow()) state.onViewAttachedToWindow(view);
    }

    void observePanel(View panel) {
        try {
            for (String name : new String[]{"mConnectivityTilesWrapper", "mBrightnessSlider", "mVolumeSlider"})
                if (name.equals("mConnectivityTilesWrapper"))
                    observe((View) panel.getClass().getField(name).get(panel), 1);
                else observe((View) panel.getClass().getField(name).get(panel));
        } catch (ReflectiveOperationException | RuntimeException error) {
            log.accept("Cannot find live tile backdrop surfaces", error);
        }
    }

    void refresh() {
        for (WeakReference<State> reference : states.values()) {
            State state = reference.get();
            if (state != null) state.update();
        }
    }

    private final class State implements View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
        final WeakReference<View> owner;
        final Outline outline = new Outline();
        final Rect bounds = new Rect();
        Drawable effect;
        WeakReference<Object> effectRoot = new WeakReference<>(null);
        final Rect appliedBounds = new Rect();
        ViewTreeObserver observer;
        float previousRadius = -1, previousSmoothness = -1;
        float previousSaturation = -1;
        boolean failed;
        final int networkRole;
        final boolean slider;
        final int backgroundId, radiusId;
        State(View view, int networkRole) {
            owner = new WeakReference<>(view); this.networkRole = networkRole;
            slider = view.getClass().getName().equals(SLIDER_OWNER);
            backgroundId = view.getResources().getIdentifier("background", "id", "com.android.systemui");
            radiusId = slider ? view.getResources().getIdentifier("qs_corner_radius", "dimen", "com.android.systemui") : 0;
        }
        @Override public void onViewAttachedToWindow(View view) {
            observer = view.getViewTreeObserver(); observer.addOnPreDrawListener(this);
            update();
        }
        @Override public void onViewDetachedFromWindow(View view) {
            if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(this);
            observer = null; remove(view); failed = false;
        }
        @Override public boolean onPreDraw() { update(); return true; }
        void remove(View view) {
            if (effect != null) {
                effect.setVisible(false,false); view.getOverlay().remove(effect);
            }
            effect = null; effectRoot.clear(); appliedBounds.setEmpty();
            previousRadius = previousSmoothness = -1;
            previousSaturation = -1;
        }
        void update() {
            View view = owner.get();
            if (view == null) return;
            if (!enabled.getAsBoolean() || !view.isAttachedToWindow()) { remove(view); return; }
            boolean split = networkStyle.getAsInt() != 0;
            if ((networkRole == 1 && split) || (networkRole == 2 && !split)) { remove(view); return; }
            if (failed || view.getWidth() == 0 || view.getHeight() == 0) return;
            try {
                Drawable background = view.getBackground();
                if (tileBackground.getDeclaringClass().isInstance(view)) background = (Drawable) tileBackground.get(view);
                else if (customTileBackground.getDeclaringClass().isInstance(view))
                    background = (Drawable) customTileBackground.get(view);
                outline.setEmpty();
                if (background instanceof LayerDrawable layers) {
                    Drawable surface = layers.findDrawableByLayerId(backgroundId);
                    if (surface != null) background = surface;
                }
                boolean smooth = smoothDrawable.isInstance(background);
                float radius = 0, smoothing = 0;
                if (slider) {
                    // The rectangular progress drawable is clipped by the owner's natural curve.
                    bounds.set(0, 0, view.getWidth(), view.getHeight());
                    radius = view.getResources().getDimension(radiusId);
                    smooth = smoothCornersEnabled.getBoolean(null);
                    if (smooth) {
                        smoothing = 0.2f;
                        // Same limit as SmoothCornerPathGenerator.getSmoothnessRadiusLimit.
                        radius = Math.min(radius, Math.min(bounds.width(), bounds.height()) / (2f*(1f+smoothing)));
                    }
                } else if (smooth) {
                    bounds.set(background.getBounds());
                    radius = smoothRadius.getFloat(background); smoothing = smoothness.getFloat(background);
                } else {
                    if (background != null) background.getOutline(outline);
                    if (!outline.getRect(bounds) && view.getOutlineProvider() != null)
                        view.getOutlineProvider().getOutline(view, outline);
                    if (!outline.getRect(bounds)) { remove(view); return; }
                    radius = Math.max(0, outline.getRadius());
                }
                if (bounds.isEmpty()) { remove(view); return; }
                if (corners != null && corners.isEnabled()) {
                    radius = corners.radius(view, bounds.width(), bounds.height());
                    smoothing = smooth ? corners.smoothing(radius, bounds.width(), bounds.height()) : 0f;
                    smooth &= smoothing > 0f;
                }
                Object root = getRoot.invoke(view);
                if (root == null) return;
                if (effectRoot.get() != root) remove(view);
                if (effect == null) {
                    Long id = effects.get(root);
                    if (id == null) {
                        id = (Long) createEffect.invoke(root,SHADER);
                        if (id == 0) throw new IllegalStateException("Flyme rejected tile backdrop shader");
                        effects.put(root,id); shaderId = id;
                    }
                    effect = (Drawable) createDrawable.invoke(root,id);
                    effectRoot = new WeakReference<>(root); view.getOverlay().add(effect);
                }
                if (!appliedBounds.equals(bounds)) {
                    effect.setBounds(bounds);
                    setFloat2.invoke(effect,"shapeSize",(float)bounds.width(),(float)bounds.height());
                    // The framework tracker has no display list on creation. Give RenderThread a
                    // valid empty display list so its position listener can submit nonempty regions.
                    RenderNode tracker = (RenderNode) trackerNode.get(effect);
                    tracker.beginRecording(bounds.width(),bounds.height()); tracker.endRecording();
                    appliedBounds.set(bounds);
                }
                if (radius != previousRadius || smoothing != previousSmoothness) {
                    setFloat.invoke(effect,"smoothCorner",smooth ? 1f : 0f);
                    setFloat.invoke(effect,"cornerRadius",radius);
                    if (smooth) {
                        float[] points = (float[]) curvePoints.invoke(createCurve.invoke(curveCompanion, radius, smoothing));
                        for (int i = 0; i < 4; i++) {
                            int j = i*4;
                            setFloat4.invoke(effect,"corner"+i, points[j], points[j+1],
                                    j+2 < points.length ? points[j+2] : 0f, j+3 < points.length ? points[j+3] : 0f);
                        }
                    }
                    previousRadius = radius; previousSmoothness = smoothing;
                }
                float alpha = view.getAlpha(); boolean visible = view.getVisibility() == View.VISIBLE;
                float saturation = (float) Math.max(0.5, Math.min(1.5, saturationAmount.getAsDouble()));
                if (saturation != previousSaturation) {
                    setFloat.invoke(effect,"saturationAmount",saturation);
                    previousSaturation = saturation;
                }
                for (android.view.ViewParent parent = view.getParent(); parent instanceof View ancestor;
                     parent = ancestor.getParent()) {
                    alpha *= ancestor.getAlpha(); visible &= ancestor.getVisibility() == View.VISIBLE;
                }
                effect.setAlpha(Math.round(Math.max(0f,Math.min(1f,alpha))*255));
                effect.setVisible(visible,false);
                if (visible && alpha > 0.99f && reportedTypes.add(view.getClass().getName()))
                    trace.accept("Live tile backdrop compositor attached: view=" + view.getClass().getSimpleName()
                            + ", bounds=" + bounds + ", saturation=" + (1f+saturation) + ", brightness=1.1, contrast=1.0");
            } catch (ReflectiveOperationException | RuntimeException error) {
                remove(view); failed = true;
                log.accept("Cannot render live saturated tile backdrop", error);
            }
        }
    }

}
