package dev.rikumi.flymemod;

import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Matrix;
import android.graphics.Path;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Array;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
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
            uniform float4 shapeClip;
            uniform float4 clipHole, clipHoleRadii;
            uniform float notificationShape;
            uniform float4 notificationCorners;
            uniform float4 corner0, corner1, corner2, corner3;
            float2 cubic(float2 a, float2 b, float2 c, float2 d, float t) {
                float s = 1.0 - t;
                return s*s*s*a + 3.0*s*s*t*b + 3.0*s*t*t*c + t*t*t*d;
            }
            float mask(float2 p) {
                float2 size = float2(sksl_right-sksl_left,sksl_bottom-sksl_top);
                float2 local = (p-float2(sksl_left,sksl_top))*shapeSize/size;
                if (local.x < shapeClip.x || local.y < shapeClip.y
                        || local.x >= shapeClip.z || local.y >= shapeClip.w) return 0.0;
                // QSContainerImpl clips OUT the notification area. Its two top
                // corners are rounded; the bottom corners remain square.
                if (local.x >= clipHole.x && local.x < clipHole.z
                        && local.y >= clipHole.y && local.y < clipHole.w) {
                    float2 r = min(clipHoleRadii.xy,(clipHole.zw-clipHole.xy)*0.5);
                    if (r.x <= 0.0 || r.y <= 0.0 || local.y >= clipHole.y+r.y
                            || (local.x >= clipHole.x+r.x && local.x <= clipHole.z-r.x)) return 0.0;
                    float2 center = float2(local.x < (clipHole.x+clipHole.z)*0.5
                            ? clipHole.x+r.x : clipHole.z-r.x,clipHole.y+r.y);
                    if (length((local-center)/r) <= 1.0) return 0.0;
                }
                float scale = min(size.x/shapeSize.x,size.y/shapeSize.y);
                float2 q = min(p-float2(sksl_left,sksl_top),float2(sksl_right,sksl_bottom)-p)*shapeSize/size;
                if (q.x < -0.5 || q.y < -0.5) return 0.0;
                if (smoothCorner < 0.5) {
                    float radius = notificationShape > 0.5
                            ? (local.x < shapeSize.x*0.5
                                ? (local.y < shapeSize.y*0.5 ? notificationCorners.x : notificationCorners.w)
                                : (local.y < shapeSize.y*0.5 ? notificationCorners.y : notificationCorners.z))
                            : cornerRadius;
                    radius = min(radius,min(shapeSize.x,shapeSize.y)*0.5);
                    float distance = radius > 0.0
                            ? radius - length(max(float2(radius)-q,float2(0.0))) : min(q.x,q.y);
                    return smoothstep(-0.5,0.5,distance*scale);
                }
                if (q.x >= corner3.x || q.y >= corner0.y)
                    return smoothstep(-0.5,0.5,min(q.x,q.y)*scale);
                if (q.x <= 0.0 || q.y <= 0.0) return 0.0;
                float2 a = corner0.xy, b = corner0.zw, c = corner1.xy, d = corner1.zw;
                if (q.x > d.x) { a = d; b = corner2.xy; c = corner2.zw; d = corner3.xy; }
                // The native curves are monotonic. The vertical-tangent segment starts
                // quadratically; seed it with sqrt(x), then use four bounded Newton steps.
                float x3 = -a.x + 3.0*b.x - 3.0*c.x + d.x;
                float x2 = 3.0*a.x - 6.0*b.x + 3.0*c.x;
                float x1 = 3.0*(b.x-a.x);
                float ratio = clamp((q.x-a.x)/(d.x-a.x),0.0,1.0);
                float t = x1 == 0.0 ? sqrt(ratio) : ratio;
                for (int i = 0; i < 4; i++) {
                    float error = ((x3*t+x2)*t+x1)*t+a.x-q.x;
                    float derivative = (3.0*x3*t+2.0*x2)*t+x1;
                    t = clamp(t-error/max(derivative,0.0001),0.0,1.0);
                }
                float s = 1.0-t;
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
    private final BooleanSupplier notificationsEnabled;
    private final DoubleSupplier notificationSaturation;
    private final Class<?> notificationBackground;
    private final Field notificationDrawable, notificationRadii, notificationRadius, notificationAlpha,
            notificationClipTop, notificationClipBottom, notificationExpanding,
            notificationBottomRounded, notificationBottomClips;
    private final Method notificationActualWidth, notificationActualHeight, notificationAlignedRight;
    private final IntSupplier networkStyle;
    private final DoubleSupplier saturationAmount;
    private final BiConsumer<String, Throwable> log;
    private final Consumer<String> trace;
    private final Method createEffect, createDrawable, setFloat, setFloat2, setFloat4;
    private final Field trackerNode;
    private final Class<?> regionArray;
    private final Field regionId, regionRect, regionAlpha;
    private volatile long shaderId;
    private final Set<Long> liveShaderIds = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean reportedRegions = new AtomicBoolean();
    private final Map<Object,Long> effects = new WeakHashMap<>();
    private final Method getRoot;
    private final Set<String> reportedTypes = new HashSet<>();
    private final Method createCurve, curvePoints;
    private final Object curveCompanion;
    private final Field tileBackground, customTileBackground, smoothRadius, smoothness;
    private final Class<?> smoothDrawable;
    private final Field smoothCornersEnabled, wifiContainer, bluetoothContainer;
    private final Class<?> qsContainer, mzQsContainer;
    private final Field fancyClipPath, fancyClipRadii;
    private final Class<?> notificationStack;
    private final Field stackClipping, stackClipLeft, stackClipTop, stackClipRight, stackClipBottom;
    private final Class<?> notificationOutline;
    private final Method rowNeedsClip, rowCustomClip, rowClip;
    private final Map<View, WeakReference<State>> states = new WeakHashMap<>();
    private boolean detailShowing;
    private final TileBackdropBatch batchCompositor;
    private volatile boolean batchFailed;

    TileBackdropHooks(ClassLoader loader, BooleanSupplier enabled, DoubleSupplier saturationAmount,
                      BooleanSupplier notificationsEnabled, DoubleSupplier notificationSaturation,
                      IntSupplier networkStyle, TileCornerHooks corners, BiConsumer<String, Throwable> log,
                      Consumer<String> trace)
            throws ReflectiveOperationException {
        this.enabled = enabled; this.log = log; this.trace = trace;
        this.networkStyle = networkStyle; this.corners = corners;
        this.saturationAmount = saturationAmount;
        this.notificationsEnabled=notificationsEnabled; this.notificationSaturation=notificationSaturation;
        notificationBackground=loader.loadClass("com.android.systemui.statusbar.notification.row.NotificationBackgroundView");
        notificationDrawable=notificationBackground.getField("mBackground");
        notificationRadii=notificationBackground.getField("mCornerRadii");
        notificationRadius=notificationBackground.getField("mClipCornerRadius");
        notificationAlpha=notificationBackground.getField("mDrawableAlpha");
        notificationClipTop=notificationBackground.getField("mClipTopAmount");
        notificationClipBottom=notificationBackground.getField("mClipBottomAmount");
        notificationExpanding=notificationBackground.getField("mExpandAnimationRunning");
        notificationBottomRounded=notificationBackground.getField("mBottomIsRounded");
        notificationBottomClips=notificationBackground.getField("mBottomAmountClips");
        notificationAlignedRight=notificationBackground.getMethod("isAlignedToRight");
        notificationActualWidth=notificationBackground.getDeclaredMethod("getActualWidth");
        notificationActualWidth.setAccessible(true);
        notificationActualHeight=notificationBackground.getDeclaredMethod("getActualHeight");
        notificationActualHeight.setAccessible(true);
        qsContainer = loader.loadClass("com.android.systemui.qs.QSContainerImpl");
        mzQsContainer = loader.loadClass("com.flyme.systemui.controlcenter.phone.MzQSContainerImpl");
        fancyClipPath = qsContainer.getField("mFancyClippingPath");
        fancyClipRadii = qsContainer.getField("mFancyClippingRadii");
        notificationStack=loader.loadClass("com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout");
        stackClipping=notificationStack.getField("mShouldUseRoundedRectClipping");
        stackClipLeft=notificationStack.getField("mRoundedRectClippingLeft");
        stackClipTop=notificationStack.getField("mRoundedRectClippingTop");
        stackClipRight=notificationStack.getField("mRoundedRectClippingRight");
        stackClipBottom=notificationStack.getField("mRoundedRectClippingBottom");
        notificationOutline=loader.loadClass("com.android.systemui.statusbar.notification.row.ExpandableOutlineView");
        rowNeedsClip=notificationOutline.getMethod("childNeedsClipping",View.class);
        rowCustomClip=notificationOutline.getMethod("getCustomClipPath",View.class);
        rowClip=notificationOutline.getMethod("getClipPath",boolean.class);
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
        TileBackdropBatch batch = null;
        try { batch = new TileBackdropBatch(loader,liveShaderIds,trace,log); }
        catch (ReflectiveOperationException | RuntimeException error) {
            log.accept("Cannot resolve batched live tile backdrop",error);
        }
        batchCompositor = batch;
    }

    void install(SignalHooks.Installer installer) throws ReflectiveOperationException {
        for(String host:new String[]{"com.android.systemui.qs.QSTileHost","com.android.systemui.qs.QSHostAdapter"}) {
            notificationBackground.getClassLoader().loadClass(host).getDeclaredMethod("setShowingDetail",boolean.class);
            installer.hook(host,"setShowingDetail",chain->{
                detailShowing=(Boolean)chain.getArg(0);
                refresh();
                return chain.proceed();
            },boolean.class);
        }
        installPositionUpdateOptimization(installer);
        notificationBackground.getDeclaredMethod("onAttachedToWindow");
        notificationBackground.getDeclaredMethod("setCustomBackground",Drawable.class);
        notificationBackground.getDeclaredMethod("setActualHeight",int.class);
        installer.hook(notificationBackground.getName(), "onAttachedToWindow", chain -> {
            Object result=chain.proceed(); observe((View)chain.getThisObject()); return result;
        });
        installer.hook(notificationBackground.getName(), "setCustomBackground", chain -> {
            Object result=chain.proceed(); observe((View)chain.getThisObject()); return result;
        },Drawable.class);
        installer.hook(notificationBackground.getName(), "setActualHeight", chain -> {
            Object result=chain.proceed(); observe((View)chain.getThisObject()); return result;
        },int.class);
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
            Object[] original = (Object[])chain.getArg(0);
            Object[] composed = original;
            if (batchCompositor != null && !batchFailed) {
                try { composed = batchCompositor.compose(chain.getThisObject(),original); }
                catch (ReflectiveOperationException | RuntimeException error) {
                    batchFailed = true;
                    log.accept("Cannot batch live tile backdrop regions",error);
                }
            }
            Object result = composed == original ? chain.proceed()
                    : chain.proceed(new Object[]{composed,chain.getArg(1)});
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

    private void installPositionUpdateOptimization(SignalHooks.Installer installer) {
        try {
            Class<?> drawable = trackerNode.getDeclaringClass();
            ClassLoader loader = drawable.getClassLoader();
            Class<?> listener = Class.forName(drawable.getName()+"$1", false, loader);
            Field owner = null;
            for (Field field : listener.getDeclaredFields()) {
                if (field.getType() == drawable) { owner = field; break; }
            }
            if (owner == null) throw new NoSuchFieldException("Flyme effect position listener owner");
            owner.setAccessible(true);
            Field effectId = drawable.getDeclaredField("mFlymeEffectId"); effectId.setAccessible(true);
            Field rectangle = drawable.getDeclaredField("mRect"); rectangle.setAccessible(true);
            Field aggregator = drawable.getDeclaredField("mAggregator"); aggregator.setAccessible(true);
            Method queue = aggregator.getType().getDeclaredMethod("onRenderNodePositionChanged", long.class, Runnable.class);
            queue.setAccessible(true);
            listener.getDeclaredMethod("positionChanged", long.class, int.class, int.class, int.class, int.class);
            listener.getDeclaredMethod("positionLost", long.class);
            final Field listenerOwner = owner;
            installer.hook(listener.getName(), "positionChanged", chain -> {
                Object target = listenerOwner.get(chain.getThisObject());
                if (!liveShaderIds.contains(effectId.getLong(target))) return chain.proceed();
                Rect rect = (Rect) rectangle.get(target);
                int left=(Integer)chain.getArg(1), top=(Integer)chain.getArg(2);
                int right=(Integer)chain.getArg(3), bottom=(Integer)chain.getArg(4);
                // Native FlymeEffectRegion retains this same Rect. Position-only changes need
                // the RT frame transaction, but neither a UI redraw nor uniform reserialization.
                queue.invoke(aggregator.get(target), (Long)chain.getArg(0), (Runnable)()->rect.set(left,top,right,bottom));
                return null;
            }, long.class, int.class, int.class, int.class, int.class);
            installer.hook(listener.getName(), "positionLost", chain -> {
                Object target = listenerOwner.get(chain.getThisObject());
                if (!liveShaderIds.contains(effectId.getLong(target))) return chain.proceed();
                Rect rect = (Rect) rectangle.get(target);
                queue.invoke(aggregator.get(target), (Long)chain.getArg(0), (Runnable)rect::setEmpty);
                return null;
            }, long.class);
            trace.accept("Live tile backdrop position optimization installed: RT-only, no UI redraw or uniform snapshot rebuild");
        } catch (ReflectiveOperationException | RuntimeException error) {
            // Other framework revisions keep the native pipeline and the live color effect.
            log.accept("Cannot optimize live backdrop position updates", error);
        }
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

    void observeDialog(View view) { observe(view, 3); }

    private void observe(View view, int networkRole) {
        if (corners != null && networkRole != 3 && !notificationBackground.isInstance(view)) corners.observe(view, networkRole);
        if (view == null) return;
        WeakReference<State> reference=states.get(view);
        State existing=reference==null?null:reference.get();
        if (existing!=null) {
            if (notificationBackground.isInstance(view)) existing.update();
            return;
        }
        State state = new State(view, networkRole);
        states.put(view, new WeakReference<>(state));
        view.addOnAttachStateChangeListener(state);
        if (notificationBackground.isInstance(view)) view.addOnLayoutChangeListener(state);
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

    private final class State implements View.OnAttachStateChangeListener, View.OnLayoutChangeListener,
            ViewTreeObserver.OnPreDrawListener {
        final WeakReference<View> owner;
        final Outline outline = new Outline();
        final Rect bounds = new Rect();
        final Rect clipRect = new Rect();
        final RectF visibleBounds = new RectF(), ancestorBounds = new RectF();
        final Matrix toAncestor = new Matrix(), fromAncestor = new Matrix();
        final RectF appliedClip = new RectF();
        final RectF clipHole = new RectF(), appliedHole = new RectF();
        final float[] holeRadii = new float[4], appliedHoleRadii = new float[4];
        Drawable effect;
        TileBackdropBatch.Batch batch;
        WeakReference<Object> effectRoot = new WeakReference<>(null);
        final Rect appliedBounds = new Rect();
        ViewTreeObserver observer;
        float previousRadius = -1, previousSmoothness = -1;
        float previousSaturation = -1;
        boolean failed;
        boolean drawableRenderable;
        final int networkRole;
        final boolean slider;
        final boolean notification;
        final float[] appliedNotificationCorners={-1,-1,-1,-1};
        final int backgroundId, radiusId;
        State(View view, int networkRole) {
            owner = new WeakReference<>(view); this.networkRole = networkRole;
            slider = view.getClass().getName().equals(SLIDER_OWNER);
            notification = notificationBackground.isInstance(view);
            backgroundId = view.getResources().getIdentifier("background", "id", "com.android.systemui");
            radiusId = slider ? view.getResources().getIdentifier("qs_corner_radius", "dimen", "com.android.systemui") : 0;
        }
        @Override public void onViewAttachedToWindow(View view) {
            // Background setters may register us inside the native attach callback,
            // before View dispatches its attach listeners. Do not register twice.
            if (observer==view.getViewTreeObserver() && observer.isAlive()) return;
            observer = view.getViewTreeObserver(); observer.addOnPreDrawListener(this);
            update();
        }
        @Override public void onLayoutChange(View view,int left,int top,int right,int bottom,
                int oldLeft,int oldTop,int oldRight,int oldBottom) {
            // Register effect regions while layout is still running, ahead of the
            // aggregator's first pre-draw snapshot and RenderThread frame callback.
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
            appliedClip.setEmpty();
            appliedHole.setEmpty();
            java.util.Arrays.fill(appliedHoleRadii,0f);
            batch = null;
            previousRadius = previousSmoothness = -1;
            previousSaturation = -1;
            drawableRenderable = false;
        }
        void update() {
            View view = owner.get();
            if (view == null) return;
            if (!(notification ? notificationsEnabled : enabled).getAsBoolean() || !view.isAttachedToWindow()) { remove(view); return; }
            if(!notification&&networkRole != 3&&detailShowing) {
                if(effect!=null)setEffectVisibility(false,0);
                return;
            }
            boolean split = networkStyle.getAsInt() != 0;
            if ((networkRole == 1 && split) || (networkRole == 2 && !split)) { remove(view); return; }
            if (failed || view.getWidth() == 0 || view.getHeight() == 0) return;
            try {
                float alpha = view.getAlpha();
                if (notification) alpha *= notificationAlpha.getInt(view) / 255f;
                boolean visible = view.getVisibility() == View.VISIBLE;
                for (android.view.ViewParent parent = view.getParent(); parent instanceof View ancestor;
                     parent = ancestor.getParent()) {
                    alpha *= ancestor.getAlpha();
                    visible &= ancestor.getVisibility() == View.VISIBLE;
                }
                // Hidden panels still receive the window's pre-draw callback. Keep
                // their effect for reuse, but defer geometry and uniforms until visible.
                if (!visible || alpha <= 0f) {
                    if (effect != null) setEffectVisibility(false, 0);
                    return;
                }
                Drawable background = view.getBackground();
                if (notification) background = (Drawable)notificationDrawable.get(view);
                else if (tileBackground.getDeclaringClass().isInstance(view)) background = (Drawable) tileBackground.get(view);
                else if (customTileBackground.getDeclaringClass().isInstance(view))
                    background = (Drawable) customTileBackground.get(view);
                outline.setEmpty();
                if (background instanceof LayerDrawable layers) {
                    Drawable surface = layers.findDrawableByLayerId(backgroundId);
                    if (surface != null) background = surface;
                }
                boolean smooth = smoothDrawable.isInstance(background);
                float radius = 0, smoothing = 0;
                if (notification) {
                    if (background == null) { remove(view); return; }
                    // calculateBackgroundBounds() asserts the new hover-dismiss mode
                    // and reports a synchronous WTF to AMS on this ROM's legacy path.
                    // Match NotificationBackgroundView.draw() without that assertion
                    // or its per-frame Rect allocation.
                    NotificationBackdropGeometry.setBounds(bounds, view.getWidth(),
                            ((Number)notificationActualWidth.invoke(view)).intValue(),
                            ((Number)notificationActualHeight.invoke(view)).intValue(),
                            notificationClipBottom.getInt(view), notificationBottomRounded.getBoolean(view),
                            notificationBottomClips.getBoolean(view), notificationExpanding.getBoolean(view),
                            (Boolean)notificationAlignedRight.invoke(view));
                    radius=notificationRadius.getInt(view);
                } else if (slider) {
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
                updateVisibleBounds(view);
                if (bounds.isEmpty() || visibleBounds.isEmpty()) {
                    if (effect != null) setEffectVisibility(false,0);
                    return;
                }
                if (notification && !notificationExpanding.getBoolean(view))
                    intersectClip(bounds.left,notificationClipTop.getInt(view),bounds.right,
                            ((Number)notificationActualHeight.invoke(view)).intValue()-notificationClipBottom.getInt(view));
                if (!notification && networkRole != 3 && corners != null && corners.isEnabled()) {
                    radius = corners.radius(view, bounds.width(), bounds.height());
                    smoothing = smooth ? corners.smoothing(radius, bounds.width(), bounds.height()) : 0f;
                    smooth &= smoothing > 0f;
                }
                // A different ViewRoot requires a detach, which already clears effectRoot.
                Object root = effectRoot.get();
                if (root == null) root = getRoot.invoke(view);
                if (root == null) return;
                if (effectRoot.get() != root) remove(view);
                if (effect == null) {
                    Long id = effects.get(root);
                    if (id == null) {
                        id = (Long) createEffect.invoke(root,SHADER);
                        if (id == 0) throw new IllegalStateException("Flyme rejected tile backdrop shader");
                        effects.put(root,id); shaderId = id;
                        liveShaderIds.add(id);
                    }
                    effect = (Drawable) createDrawable.invoke(root,id);
                    if (batchCompositor != null) batch = batchCompositor.prepare(root);
                    effectRoot = new WeakReference<>(root); view.getOverlay().add(effect);
                    if (notification) setFloat.invoke(effect,"notificationShape",1f);
                    java.util.Arrays.fill(appliedNotificationCorners,-1f);
                }
                if (notification) {
                    float[] radii=(float[])notificationRadii.get(view);
                    // Blur backgrounds have uniform corners; layered group backgrounds
                    // retain native top/bottom roundness while expanding.
                    boolean uniform=!(notificationDrawable.get(view) instanceof LayerDrawable);
                    float a=uniform?radius:radii[0], b=uniform?radius:radii[2];
                    float c=uniform?radius:radii[4], d=uniform?radius:radii[6];
                    if (appliedNotificationCorners[0]!=a || appliedNotificationCorners[1]!=b
                            || appliedNotificationCorners[2]!=c || appliedNotificationCorners[3]!=d) {
                        setFloat4.invoke(effect,"notificationCorners",a,b,c,d);
                        appliedNotificationCorners[0]=a; appliedNotificationCorners[1]=b;
                        appliedNotificationCorners[2]=c; appliedNotificationCorners[3]=d;
                    }
                }
                if (!appliedBounds.equals(bounds)) {
                    effect.setBounds(bounds);
                    setFloat2.invoke(effect,"shapeSize",(float)bounds.width(),(float)bounds.height());
                    // The framework tracker has no display list on creation. Give RenderThread a
                    // valid empty display list so its position listener can submit nonempty regions.
                    RenderNode tracker = (RenderNode) trackerNode.get(effect);
                    tracker.beginRecording(bounds.width(),bounds.height()); tracker.endRecording();
                    appliedBounds.set(bounds);
                    appliedClip.setEmpty();
                    appliedHole.setEmpty();
                    java.util.Arrays.fill(appliedHoleRadii,Float.NaN);
                }
                if (!appliedClip.equals(visibleBounds)) {
                    // Keep the original shape and corner coordinates; clipping a tile
                    // must not shrink its mask or introduce a new rounded edge.
                    setFloat4.invoke(effect,"shapeClip",visibleBounds.left-bounds.left,
                            visibleBounds.top-bounds.top,visibleBounds.right-bounds.left,
                            visibleBounds.bottom-bounds.top);
                    appliedClip.set(visibleBounds);
                }
                if (!appliedHole.equals(clipHole) || !java.util.Arrays.equals(appliedHoleRadii,holeRadii)) {
                    setFloat4.invoke(effect,"clipHole",clipHole.left-bounds.left,clipHole.top-bounds.top,
                            clipHole.right-bounds.left,clipHole.bottom-bounds.top);
                    setFloat4.invoke(effect,"clipHoleRadii",holeRadii[0],holeRadii[3],0f,0f);
                    appliedHole.set(clipHole);
                    System.arraycopy(holeRadii,0,appliedHoleRadii,0,4);
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
                visible = !visibleBounds.isEmpty();
                float saturation = (float) Math.max(0.0, Math.min(1.0,
                        (notification ? notificationSaturation : saturationAmount).getAsDouble()));
                if (saturation != previousSaturation) {
                    setFloat.invoke(effect,"saturationAmount",saturation);
                    previousSaturation = saturation;
                }
                setEffectVisibility(visible,Math.round(Math.max(0f,Math.min(1f,alpha))*255));
                if (visible && alpha > 0.99f && reportedTypes.add(view.getClass().getName()))
                    trace.accept("Live tile backdrop compositor attached: view=" + view.getClass().getSimpleName()
                            + ", bounds=" + bounds + ", saturation=" + (1f+saturation) + ", brightness=1.1, contrast=1.0");
            } catch (ReflectiveOperationException | RuntimeException error) {
                remove(view); failed = true;
                log.accept("Cannot render live saturated tile backdrop", error);
            }
        }
        private void setEffectVisibility(boolean visible,int alpha) {
            effect.setAlpha(alpha);
            effect.setVisible(visible,false);
            boolean renderable=visible && alpha>0;
            if (renderable && !drawableRenderable) {
                // FlymeEffectDrawable.updatePath() clears its path when bounds
                // arrive while hidden/transparent. Neither setAlpha nor setVisible
                // rebuilds it. Reapply bounds after visibility is restored, even
                // when geometry is unchanged, then record the tracker again.
                effect.setBounds(bounds.left,bounds.top,bounds.right,bounds.bottom);
                effect.invalidateSelf();
            }
            drawableRenderable=renderable;
        }
        private void updateVisibleBounds(View view) throws ReflectiveOperationException {
            visibleBounds.set(bounds);
            clipHole.setEmpty(); java.util.Arrays.fill(holeRadii,0f);
            if (view.getClipBounds(clipRect)) intersectClip(clipRect.left,clipRect.top,clipRect.right,clipRect.bottom);
            toAncestor.reset();
            for (View child=view; child.getParent() instanceof View ancestor; child=ancestor) {
                toAncestor.postConcat(child.getMatrix());
                toAncestor.postTranslate(child.getLeft()-ancestor.getScrollX(),child.getTop()-ancestor.getScrollY());
                if (!toAncestor.invert(fromAncestor)) { visibleBounds.setEmpty(); return; }
                if (notification && notificationOutline.isInstance(ancestor)
                        && (Boolean)rowNeedsClip.invoke(ancestor,child)) {
                    // Canvas.clipPath in ExpandableOutlineView.drawChild does not
                    // clip SurfaceFlinger's effect regions. Match the native swipe
                    // boundary in background-local coordinates and give the remaining
                    // visible width its own rounded mask.
                    Path path=(Path)rowCustomClip.invoke(ancestor,child);
                    if (path==null) path=(Path)rowClip.invoke(ancestor,false);
                    if (path.isEmpty()) {visibleBounds.setEmpty();return;}
                    path.computeBounds(ancestorBounds,true);
                    fromAncestor.mapRect(ancestorBounds);
                    NotificationBackdropGeometry.clipSides(bounds,ancestorBounds.left,ancestorBounds.right);
                    intersectClip(ancestorBounds.left,ancestorBounds.top,ancestorBounds.right,ancestorBounds.bottom);
                    if (bounds.isEmpty()) {visibleBounds.setEmpty();return;}
                }
                if (notification && notificationStack.isInstance(ancestor) && stackClipping.getBoolean(ancestor))
                    mapClip(stackClipLeft.getInt(ancestor),stackClipTop.getInt(ancestor),
                            stackClipRight.getInt(ancestor),stackClipBottom.getInt(ancestor));
                // The QS reveal hook can disable normal child clipping. This
                // container's changing bottom still defines the controls area.
                if (mzQsContainer.isInstance(ancestor)) mapClip(0,0,ancestor.getWidth(),ancestor.getHeight());
                if (qsContainer.isInstance(ancestor)) {
                    Path path = (Path)fancyClipPath.get(ancestor);
                    if (!path.isEmpty()) {
                        path.computeBounds(clipHole,true);
                        // Native dispatchDraw translates by -getTranslationY()
                        // before clipOutPath, then restores it for its children.
                        clipHole.offset(0,-ancestor.getTranslationY());
                        fromAncestor.mapRect(clipHole);
                        float radius = ((float[])fancyClipRadii.get(ancestor))[0];
                        holeRadii[0]=radius; holeRadii[1]=0f;
                        holeRadii[2]=0f; holeRadii[3]=radius;
                        fromAncestor.mapVectors(holeRadii);
                        holeRadii[0]=Math.abs(holeRadii[0]); holeRadii[3]=Math.abs(holeRadii[3]);
                    }
                }
                if (ancestor instanceof ViewGroup group) {
                    if (group.getClipChildren()) mapClip(0,0,ancestor.getWidth(),ancestor.getHeight());
                    if (group.getClipToPadding()) mapClip(ancestor.getPaddingLeft(),ancestor.getPaddingTop(),
                            ancestor.getWidth()-ancestor.getPaddingRight(),ancestor.getHeight()-ancestor.getPaddingBottom());
                }
                if (ancestor.getClipBounds(clipRect)) mapClip(clipRect.left,clipRect.top,clipRect.right,clipRect.bottom);
                if (visibleBounds.isEmpty()) return;
            }
            intersectClip(bounds.left,bounds.top,bounds.right,bounds.bottom);
        }
        private void mapClip(float left,float top,float right,float bottom) {
            ancestorBounds.set(left,top,right,bottom);
            fromAncestor.mapRect(ancestorBounds);
            intersectClip(ancestorBounds.left,ancestorBounds.top,ancestorBounds.right,ancestorBounds.bottom);
        }
        private void intersectClip(float left,float top,float right,float bottom) {
            if (left>=right || top>=bottom || !visibleBounds.intersect(left,top,right,bottom))
                visibleBounds.setEmpty();
        }
    }

}
