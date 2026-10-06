package com.rikumi.flymemod;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Flyme SystemUI 16260625 square tiles; retain native colors, grid and text. */
final class CircleTileHooks {
    private static final String TILE = "com.android.systemui.qs.tileimpl.QSTileViewImpl";
    private final Consumer<Context> settings;
    private final BooleanSupplier enabled;
    private final BiConsumer<String, Throwable> log;
    private final Class<?> squareClass, stateClass, smoothClass;
    private final Field background, ripple, press, radius, smoothness;
    private final Method setRadius, updatePath;
    private final Map<View, Boolean> tiles = new WeakHashMap<>();
    private final Map<Drawable, Shape> originals = new WeakHashMap<>();

    CircleTileHooks(ClassLoader loader, Consumer<Context> settings, BooleanSupplier enabled,
                    BiConsumer<String, Throwable> log) throws ReflectiveOperationException {
        this.settings = settings; this.enabled = enabled; this.log = log;
        squareClass = loader.loadClass("com.flyme.systemui.qs.tileimpl.SquareQSTileViewImpl");
        stateClass = loader.loadClass("com.android.systemui.plugins.qs.QSTile$State");
        Class<?> tile = loader.loadClass(TILE);
        background = tile.getField("backgroundDrawable");
        ripple = tile.getField("qsTileBackground");
        press = tile.getField("drawableForDisplayPressState");
        smoothClass = loader.loadClass("com.android.systemui.qs.CustomSmoothCornerDrawable");
        radius = smoothClass.getField("radius");
        smoothness = smoothClass.getField("smoothness");
        setRadius = smoothClass.getMethod("setRadius", float.class);
        updatePath = smoothClass.getMethod("updatePathAndBitmap");
    }

    void install(SignalHooks.Installer installer) {
        installer.hook(TILE, "updateResources", chain -> {
            Object result = chain.proceed(); apply((View) chain.getThisObject()); return result;
        });
        installer.hook(TILE, "handleStateChanged", chain -> {
            Object result = chain.proceed(); apply((View) chain.getThisObject()); return result;
        }, stateClass);
        installer.hook(TILE, "onLayout", chain -> {
            Object result = chain.proceed(); apply((View) chain.getThisObject()); return result;
        }, boolean.class, int.class, int.class, int.class, int.class);
    }

    void refresh() {
        for (View tile : new ArrayList<>(tiles.keySet())) {
            if (tile == null) continue;
            try { apply(tile); }
            catch (ReflectiveOperationException | RuntimeException error) {
                log.accept("Cannot refresh circular small tiles", error);
            }
        }
    }

    private void apply(View tile) throws ReflectiveOperationException {
        if (!squareClass.isInstance(tile)) return;
        tiles.put(tile, true);
        settings.accept(tile.getContext());
        float size = Math.min(tile.getWidth(), tile.getHeight());
        if (size <= 0) return;
        boolean circle = enabled.getAsBoolean();
        for (Drawable drawable : new Drawable[]{tile.getBackground(), (Drawable) background.get(tile),
                (Drawable) ripple.get(tile), (Drawable) press.get(tile)}) {
            shape(drawable, size / 2f, circle);
        }
        tile.invalidateOutline();
    }

    private void shape(Drawable drawable, float target, boolean circle) throws ReflectiveOperationException {
        if (drawable == null) return;
        if (drawable instanceof LayerDrawable layers) {
            for (int i = 0; i < layers.getNumberOfLayers(); i++) shape(layers.getDrawable(i), target, circle);
        } else if (drawable instanceof StateListDrawable states) {
            for (int i = 0; i < states.getStateCount(); i++) shape(states.getStateDrawable(i), target, circle);
        } else if (drawable instanceof DrawableWrapper wrapper) {
            shape(wrapper.getDrawable(), target, circle);
        } else if (drawable instanceof GradientDrawable gradient) {
            if (circle) {
                if (!originals.containsKey(drawable)) {
                    float[] corners = gradient.getCornerRadii();
                    originals.put(drawable, new Shape(gradient.getCornerRadius(), 0f,
                            corners == null ? null : corners.clone()));
                }
                if (gradient.getCornerRadii() != null || gradient.getCornerRadius() != target) {
                    gradient.mutate(); gradient.setCornerRadius(target);
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
            if (circle && saved == null) {
                saved = new Shape(radius.getFloat(drawable), smoothness.getFloat(drawable), null);
                originals.put(drawable, saved);
            }
            if (saved == null) return;
            float desiredRadius = circle ? target : saved.radius;
            float desiredSmoothness = circle ? 0f : saved.smoothness;
            if (radius.getFloat(drawable) != desiredRadius || smoothness.getFloat(drawable) != desiredSmoothness) {
                smoothness.setFloat(drawable, desiredSmoothness);
                setRadius.invoke(drawable, desiredRadius);
                updatePath.invoke(drawable);
                drawable.invalidateSelf();
            }
            if (!circle) originals.remove(drawable);
        }
    }

    private static final class Shape {
        final float radius, smoothness;
        final float[] corners;
        Shape(float radius, float smoothness, float[] corners) {
            this.radius = radius; this.smoothness = smoothness; this.corners = corners;
        }
    }
}
