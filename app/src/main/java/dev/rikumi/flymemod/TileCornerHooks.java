package dev.rikumi.flymemod;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Outline;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.drawable.*;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

final class TileCornerHooks {
    private final Consumer<Context> settings;
    private final BooleanSupplier enabled, circleSmallTiles;
    private final IntSupplier percentage, networkStyle;
    private final BiConsumer<String, Throwable> log;
    private final Class<?> tileClass, squareClass, customClass, smoothClass;
    private final Field tileBackground, customBackground, smoothRadius, smoothness, smoothPath, press, cellWidth, albumArt;
    private final Method updateSmooth, updateBitmap, generatePath;
    private final int qsIconSize;
    private Resources systemUiResources;
    private final Map<View, WeakReference<State>> states = new WeakHashMap<>();
    private final LayoutScopedViewLookup grids;
    private final Map<Drawable, Shape> originals = new WeakHashMap<>();

    TileCornerHooks(ClassLoader loader, Consumer<Context> settings, BooleanSupplier enabled,
                    IntSupplier percentage, IntSupplier networkStyle, BooleanSupplier circleSmallTiles,
                    BiConsumer<String, Throwable> log) throws ReflectiveOperationException {
        this.settings = settings; this.enabled = enabled; this.percentage = percentage; this.networkStyle = networkStyle; this.log = log;
        this.circleSmallTiles = circleSmallTiles;
        qsIconSize = loader.loadClass("com.android.systemui.res.R$dimen").getField("qs_icon_size").getInt(null);
        tileClass = loader.loadClass("com.android.systemui.qs.tileimpl.QSTileViewImpl");
        squareClass = loader.loadClass("com.flyme.systemui.qs.tileimpl.SquareQSTileViewImpl");
        customClass = loader.loadClass("com.flyme.systemui.qs.tileimpl.FlymeCustomQSTileView");
        press = tileClass.getField("drawableForDisplayPressState");
        cellWidth = loader.loadClass("com.flyme.systemui.controlcenter.qs.UnifiedTileLayout").getField("mCellWidth");
        grids = new LayoutScopedViewLookup(cellWidth.getDeclaringClass());
        albumArt = loader.loadClass("com.flyme.systemui.qs.media.QsMediaView").getField("mAlbumArt");
        tileBackground = tileClass.getField("backgroundDrawable");
        customBackground = customClass.getField("backgroundDrawable");
        smoothClass = loader.loadClass("com.android.systemui.qs.CustomSmoothCornerDrawable");
        smoothRadius = smoothClass.getField("radius"); smoothness = smoothClass.getField("smoothness");
        smoothPath = smoothClass.getField("path");
        updateSmooth = smoothClass.getMethod("updatePathAndBitmap");
        updateBitmap = smoothClass.getMethod("updateCachedBitmap");
        generatePath = loader.loadClass("com.meizu.common.util.SmoothCornerPathGenerator")
                .getMethod("genSmoothCornerPath", float.class, float.class, float.class, float.class,
                        float.class, float.class, boolean.class);
    }

    boolean isEnabled() { return enabled.getAsBoolean(); }
    float radius(View view, float width, float height) {
        if (circleSmallTiles.getAsBoolean() && squareClass.isInstance(view)) return Math.min(width, height)/2f;
        return Math.min(Math.min(width,height)/2f, cellSide(view) * Math.max(ModuleSettings.TILE_CORNER_PERCENT_MIN,Math.min(ModuleSettings.TILE_CORNER_PERCENT_MAX,percentage.getAsInt()))/100f);
    }
    float cellSide(View view) {
        float side = 0;
        View grid=grids.find(view);
        if (grid != null) {
            try { side = cellWidth.getInt(grid); }
            catch (ReflectiveOperationException error) { log.accept("Cannot read QS grid cell size", error); }
        }
        if (side <= 0) {

            if (systemUiResources == null) {
                try {
                    systemUiResources = view.getContext().getPackageManager()
                            .getResourcesForApplication("com.android.systemui");
                } catch (PackageManager.NameNotFoundException error) {
                    throw new IllegalStateException("SystemUI resources unavailable", error);
                }
            }
            // Native SquareQSTileViewImpl's initial 1x1 size, before the grid is measured.
            side = systemUiResources.getDimensionPixelSize(qsIconSize) * 3f;
        }
        return side;
    }
    float smoothing(float radius, float width, float height) {
        int percent = Math.max(ModuleSettings.TILE_CORNER_PERCENT_MIN, Math.min(ModuleSettings.TILE_CORNER_PERCENT_MAX, percentage.getAsInt()));
        return radius <= 0f || percent == 0 ? 0f : Math.max(0f, Math.min(.2f,
                Math.min(50f / percent - 1f, Math.min(width, height) / (2f*radius) - 1f)));
    }
    void observe(View view) { observe(view, 0); }
    void observe(View view, int networkRole) { observe(view, null, networkRole); }
    private void observe(View view, View media, int networkRole) {
        if (view == null) return;
        if (networkRole == 0 && view.getClass().getName().equals("com.flyme.systemui.qs.tileimpl.ConnectivityQSTileViewImpl")) networkRole = 1;
        WeakReference<State> existing = states.get(view);
        if (existing != null && existing.get() != null) {
            if (networkRole != 0) existing.get().networkRole = networkRole;
            return;
        }
        settings.accept(view.getContext());
        State state = new State(view, media, networkRole);
        states.put(view, new WeakReference<>(state));
        view.addOnAttachStateChangeListener(state);
        if (view.isAttachedToWindow()) state.onViewAttachedToWindow(view);
    }
    void observePanel(View panel) throws ReflectiveOperationException {
        for (String field : new String[]{"mConnectivityTilesWrapper", "mBrightnessSlider", "mVolumeSlider"})
            observe((View) panel.getClass().getField(field).get(panel));
    }
    void observeNetwork(View card) {
        observe(card, 1);
        try {
            for (String name : new String[]{"wifiContainer", "bluetoothContainer"})
                observe((View) card.getClass().getField(name).get(card), 2);
        } catch (ReflectiveOperationException error) { log.accept("Cannot bind network tile corners", error); }
    }
    void refresh() {
        for (WeakReference<State> reference : new ArrayList<>(states.values())) {
            State state = reference.get(); if (state != null) state.update();
        }
    }
    private void shape(Drawable drawable, float radius, float smoothing, boolean active) throws ReflectiveOperationException {
        if (drawable == null) return;
        if (drawable instanceof LayerDrawable layers) {
            for (int i=0; i<layers.getNumberOfLayers(); i++) shape(layers.getDrawable(i), radius, smoothing, active);
        } else if (drawable instanceof StateListDrawable list) {
            for (int i=0; i<list.getStateCount(); i++) shape(list.getStateDrawable(i), radius, smoothing, active);
        } else if (drawable instanceof DrawableWrapper wrapper) shape(wrapper.getDrawable(), radius, smoothing, active);
        else if (drawable instanceof GradientDrawable gradient && gradient.getShape() == GradientDrawable.RECTANGLE) {
            if (active) {
                if (!originals.containsKey(drawable)) originals.put(drawable, new Shape(gradient.getCornerRadius(), 0, gradient.getCornerRadii()));
                if (gradient.getCornerRadii() != null || gradient.getCornerRadius() != radius) {
                    gradient.mutate(); gradient.setCornerRadius(radius);
                }
            } else {
                Shape saved = originals.remove(drawable);
                if (saved != null) {
                    if (saved.corners == null) gradient.setCornerRadius(saved.radius);
                    else gradient.setCornerRadii(saved.corners);
                }
            }
        } else if (smoothClass.isInstance(drawable)) {
            Shape saved = originals.get(drawable);
            if (active && saved == null) {
                saved = new Shape(smoothRadius.getFloat(drawable), smoothness.getFloat(drawable), null);
                originals.put(drawable, saved);
            }
            if (saved == null) return;
            float r = active ? radius : saved.radius, s = active ? smoothing : saved.smoothing;
            if (smoothRadius.getFloat(drawable) != r || smoothness.getFloat(drawable) != s) {
                smoothRadius.setFloat(drawable, r); smoothness.setFloat(drawable, s);
                updateSmooth.invoke(drawable);
                drawable.invalidateSelf();
            }
            // Bounds changes can regenerate the native cubic path without changing radius.
            if (active && s == 0f && r > 0f && !drawable.getBounds().isEmpty()
                    && smoothPath.get(drawable) != saved.appliedPath) {
                Rect b=drawable.getBounds(); Path path=new Path();
                path.addRoundRect(0,0,b.width(),b.height(),r,r,Path.Direction.CW);
                smoothPath.set(drawable,path); saved.appliedPath=path;
                updateBitmap.invoke(drawable); drawable.invalidateSelf();
            }
            if (!active) originals.remove(drawable);
        }
    }
    private final class State implements View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
        final WeakReference<View> owner, mediaOwner;
        int networkRole;
        final int backgroundId;
        final Path path = new Path();
        final ViewOutlineProvider provider = new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                if (smooth && smoothing > 0f) outline.setConvexPath(path);
                else outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        };
        ViewOutlineProvider originalProvider;
        boolean originalClip, applied, smooth, failed;
        float radius = -1, smoothing = -1;
        int width, height;
        ViewTreeObserver observer;
        State(View view, View media, int networkRole) {
            owner = new WeakReference<>(view); mediaOwner = new WeakReference<>(media); this.networkRole = networkRole;
            backgroundId = view.getResources().getIdentifier("background", "id", "com.android.systemui");
        }
        @Override public void onViewAttachedToWindow(View view) {
            if(observer==view.getViewTreeObserver() && observer.isAlive())return;
            observer = view.getViewTreeObserver(); observer.addOnPreDrawListener(this); update();
        }
        @Override public void onViewDetachedFromWindow(View view) {
            if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(this);
            observer = null; failed = false;
        }
        @Override public boolean onPreDraw() { update(); return true; }
        void update() {
            View view = owner.get(); if (view == null || failed) return;
            boolean split = networkStyle.getAsInt() != 0;
            boolean active = isEnabled() && !(circleSmallTiles.getAsBoolean() && squareClass.isInstance(view))
                    && !(networkRole == 1 && split) && !(networkRole == 2 && !split);
            if (!active && !applied) return;
            if (view.getWidth() == 0 || view.getHeight() == 0) return;
            try {
                Drawable fill = tileClass.isInstance(view) ? (Drawable) tileBackground.get(view)
                        : customClass.isInstance(view) ? (Drawable) customBackground.get(view) : view.getBackground();
                boolean natural = smoothClass.isInstance(fill)
                        || view.getClass().getName().equals("com.android.systemui.settings.brightness.BrightnessSliderView");
                if (fill instanceof LayerDrawable layers) {
                    natural |= smoothClass.isInstance(layers.findDrawableByLayerId(backgroundId));
                }
                View media = mediaOwner.get();
                float r = radius(view, view.getWidth(), view.getHeight());
                if (media != null) {
                    float left=0, top=0;
                    for (View child=view; child!=media; child=child.getParent() instanceof View parent ? parent : media) {
                        left+=child.getLeft(); top+=child.getTop();
                    }
                    r=Math.min(Math.min(view.getWidth(),view.getHeight())/2f,
                            Math.max(0f,radius(media,media.getWidth(),media.getHeight())-Math.max(0f,Math.min(left,top))));
                    natural = true;
                }
                float s = natural ? smoothing(r, view.getWidth(), view.getHeight()) : 0f;
                Drawable background = view.getBackground();
                shape(background, r, s, active);
                if (fill != background) shape(fill, r, s, active);
                // QS press-state drawables are separate from the normal fill.
                if (tileClass.isInstance(view))
                    shape((Drawable) press.get(view), r, s, active);
                if (active) {
                    if (!applied || view.getOutlineProvider() != provider) {
                        originalProvider = view.getOutlineProvider(); originalClip = view.getClipToOutline();
                    }
                    if (radius != r || smoothing != s || width != view.getWidth() || height != view.getHeight() || smooth != natural) {
                        radius=r; smoothing=s; width=view.getWidth(); height=view.getHeight(); smooth=natural;
                        path.reset();
                        if (natural && s > 0f) path.set((Path) generatePath.invoke(null, 0f, 0f,
                                (float)width, (float)height, s, r, false));
                        view.invalidateOutline();
                    }
                    if (view.getOutlineProvider() != provider) view.setOutlineProvider(provider);
                    if (!view.getClipToOutline()) view.setClipToOutline(true);
                    applied = true;
                    if (albumArt.getDeclaringClass().isInstance(view)) observe((View) albumArt.get(view), view, 0);
                } else {
                    if (view.getOutlineProvider() == provider) {
                        view.setOutlineProvider(originalProvider); view.setClipToOutline(originalClip);
                    }
                    applied = false; radius = smoothing = -1;
                    view.invalidateOutline();
                }
            } catch (ReflectiveOperationException | RuntimeException error) {
                failed = true; log.accept("Cannot apply control center tile corners", error);
            }
        }
    }
    private static final class Shape {
        final float radius, smoothing; final float[] corners;
        Path appliedPath;
        Shape(float radius, float smoothing, float[] corners) {
            this.radius=radius; this.smoothing=smoothing; this.corners=corners == null ? null : corners.clone();
        }
    }
}
