package com.rikumi.flymemod;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.ArrayList;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/** Flyme SystemUI 16260625 connectivity, focus-mode and device-center labels. */
final class SimpleQsTextHooks {
    private static final String NETWORK = "com.flyme.systemui.qs.tileimpl.ConnectivityQSTileViewImpl";
    private static final String DEVICE = "com.flyme.systemui.qs.tileimpl.DeviceCenterQSTileViewImpl";
    private static final String TILE = "com.android.systemui.qs.tileimpl.QSTileViewImpl";
    private final Consumer<Context> settings;
    private final BooleanSupplier optimize;
    private final BiConsumer<String, Throwable> log;
    private final IntSupplier networkStyle;
    private final Map<View, Original> originals = new WeakHashMap<>();
    private final Map<View, Boolean> tiles = new WeakHashMap<>();

    SimpleQsTextHooks(ClassLoader loader, Consumer<Context> settings,
                      BooleanSupplier optimize, IntSupplier networkStyle,
                      BiConsumer<String, Throwable> log)
            throws ReflectiveOperationException {
        this.settings = settings;
        this.optimize = optimize;
        this.networkStyle = networkStyle;
        this.log = log;
        // Verify the actual target fields before installing any of the hooks.
        Class<?> network = loader.loadClass(NETWORK);
        for (String prefix : new String[]{"wifi", "mobile", "bluetooth"}) {
            network.getField(prefix + "Title");
            network.getField(prefix + "Subtitle");
            network.getField(prefix + "TextContainer");
        }
        loader.loadClass(DEVICE).getField("deviceCenterText");
        Class<?> tile = loader.loadClass(TILE);
        for (String field : new String[]{"tileSpec", "label", "secondaryLabel", "labelContainer"}) tile.getField(field);

    }

    void install(ClassLoader loader, SignalHooks.Installer installer) throws ClassNotFoundException {
        Class<?> state = loader.loadClass("com.android.systemui.plugins.qs.QSTile$State");
        for (String type : new String[]{NETWORK, DEVICE, TILE}) {
            installer.hook(type, "handleStateChanged", chain -> {
                Object result = chain.proceed();
                apply((View) chain.getThisObject());
                return result;
            }, state);
            installer.hook(type, "updateResources", chain -> {
                restoreTile((View) chain.getThisObject());
                Object result = chain.proceed();
                apply((View) chain.getThisObject());
                return result;
            });
        }
        installer.hook(NETWORK, "setupLayout", chain -> {
            Object result = chain.proceed();
            apply((View) chain.getThisObject());
            return result;
        });
        installer.hook(DEVICE, "initViews", chain -> {
            Object result = chain.proceed();
            apply((View) chain.getThisObject());
            return result;
        });

    }

    private void apply(View tile) throws ReflectiveOperationException {
        tiles.put(tile, true);
        settings.accept(tile.getContext());
        boolean optimizeText = optimize.getAsBoolean();
        String type = tile.getClass().getName();
        if (NETWORK.equals(type)) {
            for (String prefix : new String[]{"wifi", "mobile", "bluetooth"}) {
                pair((TextView) field(tile, prefix + "Title"), (TextView) field(tile, prefix + "Subtitle"),
                        (LinearLayout) field(tile, prefix + "TextContainer"),
                        optimizeText && networkStyle.getAsInt() != 0);
                Object snapshot = field(tile, prefix.equals("bluetooth") ? "lastBluetoothSnapshot"
                        : prefix.equals("wifi") ? "lastWifiSnapshot" : "lastMobileSnapshot");
                View arrow = (View) field(tile, prefix + "Chevron");
                if (snapshot != null && arrow != null) {
                    boolean hidden = networkStyle.getAsInt() != 0 && (optimizeText || networkStyle.getAsInt() == 2);
                    boolean show = snapshot.getClass().getField("showSideView").getBoolean(snapshot);
                    arrow.setVisibility(!hidden && show ? View.VISIBLE : View.GONE);
                }
            }
        } else if (DEVICE.equals(type)) {
            TextView title = (TextView) field(tile, "deviceCenterText");
            if (title != null) text(title, optimizeText, 0f, optimizeText);
        } else {
            boolean horizontal = "dnd".equals(field(tile, "tileSpec"));
            pair((TextView) field(tile, "label"), (TextView) field(tile, "secondaryLabel"),
                    (LinearLayout) field(tile, "labelContainer"), optimizeText && horizontal);
            if (horizontal && optimizeText) {
                shiftText((TextView) field(tile, "label"), 7f);
                shiftText((TextView) field(tile, "secondaryLabel"), 7f);
            }
            View arrow = (View) field(tile, "chevronView");
            if (arrow != null) {
                if (optimizeText && horizontal) { remember(arrow).titleHidden = true; arrow.setVisibility(View.GONE); }
                else restore(arrow);
            }
        }
    }

    void refresh() {
        for (View tile : new ArrayList<>(tiles.keySet())) {
            if (tile == null) continue;
            try { tile.getClass().getMethod("updateResources").invoke(tile); }
            catch (ReflectiveOperationException | RuntimeException error) {
                log.accept("Cannot refresh simplified control center text", error);
            }
        }
    }

    private void restoreTile(View tile) {
        for (View view : new ArrayList<>(originals.keySet())) {
            if (view == null) continue;
            android.view.ViewParent parent = view.getParent();
            while (parent instanceof View ancestor && ancestor != tile) parent = ancestor.getParent();
            if (parent == tile) restore(view);
        }
    }

    private Object field(Object target, String name) throws ReflectiveOperationException {
        return target.getClass().getField(name).get(target);
    }

    private void pair(TextView title, TextView subtitle, LinearLayout group, boolean optimizeText) {
        if (title == null || subtitle == null || group == null) return;
        if (!optimizeText) { restore(title); restore(subtitle); restore(group); return; }
        text(title, true, 0f, true);
        text(subtitle, true, 0f, true);
        Original second = remember(subtitle);
        if (subtitle.getLayoutParams() instanceof ViewGroup.MarginLayoutParams params) {
            int margin = second.topMargin + Math.round(2f * subtitle.getResources().getDisplayMetrics().density);
            if (params.topMargin != margin) { params.topMargin = margin; subtitle.setLayoutParams(params); }
        }
    }

    private void text(TextView text, boolean adjust, float baseSize, boolean optimizeText) {
        if (!adjust) { restore(text); return; }
        Original original = remember(text);
        float size = (baseSize > 0f ? baseSize : original.size) * (optimizeText ? 1.15f : 1f);
        if (Math.abs(text.getTextSize() - size) > .01f) text.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
        shiftText(text, optimizeText ? 5f : 0f);
    }

    private void shiftText(TextView text, float dp) {
        if (text == null) return;
        Original original = remember(text);
        float shift = dp * text.getResources().getDisplayMetrics().density;
        float x = original.x + (text.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? -shift : shift);
        if (Math.abs(text.getTranslationX() - x) > .01f) text.setTranslationX(x);
    }

    private Original remember(View view) {
        Original original = originals.get(view);
        if (original == null) {
            original = new Original(view);
            originals.put(view, original);
        }
        return original;
    }

    private void restore(View view) {
        Original original = originals.remove(view);
        if (original == null) return;
        if (view instanceof TextView text) {
            text.setTextSize(TypedValue.COMPLEX_UNIT_PX, original.size);
            text.setTypeface(original.typeface);
            text.setGravity(original.gravity);
            // Only titles have visibility overridden. Subtitle state stays native.
            if (original.titleHidden) text.setVisibility(original.visibility);
        } else if (view instanceof LinearLayout group) group.setGravity(original.gravity);
        if (!(view instanceof TextView) && original.titleHidden) view.setVisibility(original.visibility);
        view.setTranslationX(original.x);
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params instanceof ViewGroup.MarginLayoutParams margins && margins.topMargin != original.topMargin) {
            margins.topMargin = original.topMargin;
            view.setLayoutParams(params);
        }
        if (params != null && params.height != original.height) {
            params.height = original.height;
            view.setLayoutParams(params);
        }
    }

    private static final class Original {
        final float size, x;
        final int gravity, height, visibility, topMargin;
        final Typeface typeface;
        boolean titleHidden;
        Original(View view) {
            size = view instanceof TextView text ? text.getTextSize() : 0f;
            typeface = view instanceof TextView text ? text.getTypeface() : null;
            gravity = view instanceof TextView text ? text.getGravity()
                    : view instanceof LinearLayout group ? group.getGravity() : 0;
            x = view.getTranslationX();
            height = view.getLayoutParams() == null ? ViewGroup.LayoutParams.WRAP_CONTENT : view.getLayoutParams().height;
            visibility = view.getVisibility();
            topMargin = view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams params ? params.topMargin : 0;
        }
    }
}
