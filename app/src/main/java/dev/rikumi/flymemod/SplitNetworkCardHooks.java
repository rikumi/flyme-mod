package dev.rikumi.flymemod;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.LinearLayout;
import android.widget.ImageView;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/** Retain ConnectivityTile's behavior while presenting Wi-Fi and Bluetooth as 2x1 rows. */
final class SplitNetworkCardHooks {
    private static final String NETWORK = "com.flyme.systemui.qs.tileimpl.ConnectivityQSTileViewImpl";
    private final Consumer<Context> settings;
    private final BooleanSupplier whiteActive, optimizeText;
    private final IntSupplier style;
    private final IntSupplier activeColor;
    interface IconTint { void apply(ImageView icon, int color) throws ReflectiveOperationException; }
    private final IconTint iconTint;
    private final BiConsumer<String, Throwable> log;
    private final Class<?> stateClass, tileClass, expandableClass;
    private final Method fromView, backgroundColor, circleColor, circleTint, iconColor;
    private final Object companion;
    private final java.lang.reflect.Constructor<?> smoothDrawable;
    private final Method smoothRadius, smoothOutline;
    private Consumer<View> contourListener;
    void setContourListener(Consumer<View> listener) { contourListener = listener; }
    private void updateContours(View card) { if (contourListener != null) contourListener.accept(card); }

    private final Map<View, Saved> originals = new WeakHashMap<>();
    private final Map<View, Boolean> cards = new WeakHashMap<>();
    private final Map<View, Boolean> horizontalCards = new WeakHashMap<>();

    void setHorizontal(View card, boolean horizontal) throws ReflectiveOperationException {
        boolean previous = horizontalCards.containsKey(card);
        if (horizontal) horizontalCards.put(card, true); else horizontalCards.remove(card);
        if (previous != horizontal) apply(card);
    }


    SplitNetworkCardHooks(ClassLoader loader, Consumer<Context> settings, IntSupplier style,
                         BooleanSupplier whiteActive, BooleanSupplier optimizeText, IntSupplier activeColor, IconTint iconTint, BiConsumer<String, Throwable> log) throws ReflectiveOperationException {
        this.settings = settings; this.style = style; this.whiteActive = whiteActive; this.optimizeText = optimizeText; this.log = log;
        this.activeColor = activeColor; this.iconTint = iconTint;
        Class<?> network = loader.loadClass(NETWORK);
        for (String field : new String[]{"wifiContainer", "mobileContainer", "bluetoothContainer",
                "wifiIcon", "bluetoothIcon", "wifiChevron", "bluetoothChevron", "lastWifiSnapshot", "lastBluetoothSnapshot"}) network.getField(field);
        stateClass = loader.loadClass("com.android.systemui.plugins.qs.QSTile$State");
        tileClass = loader.loadClass("com.android.systemui.plugins.qs.QSTile");
        expandableClass = loader.loadClass("com.android.systemui.animation.Expandable");
        companion = expandableClass.getField("Companion").get(null);
        fromView = companion.getClass().getMethod("fromView", View.class);
        backgroundColor = network.getMethod("getBackgroundColorForState", int.class);
        Class<?> icon = loader.loadClass("com.android.systemui.qs.tileimpl.QSIconViewImpl");
        circleColor = icon.getMethod("getCircleIconBgColor", String.class, int.class);
        circleTint = icon.getMethod("setCircleIconBg", int.class);
        iconColor = icon.getMethod("getIconColorForState", int.class);
        Class<?> smooth = loader.loadClass("com.android.systemui.qs.CustomSmoothCornerDrawable");
        smoothDrawable = smooth.getConstructor();
        smoothRadius = smooth.getMethod("setRadius", float.class);
        smoothOutline = loader.loadClass("com.flyme.systemui.utils.SystemUICommonUtils")
                .getMethod("setViewSmoothCorner", View.class, float.class);
    }

    void install(SignalHooks.Installer installer) {
        for (String method : new String[]{"setupLayout", "updateResources"}) {
            installer.hook(NETWORK, method, chain -> {
                Object result = chain.proceed(); apply((View) chain.getThisObject()); return result;
            });
        }
        installer.hook(NETWORK, "handleStateChanged", chain -> {
            Object result = chain.proceed(); apply((View) chain.getThisObject()); return result;
        }, stateClass);
        installer.hook(NETWORK, "init", chain -> {
            Object result = chain.proceed(); apply((View) chain.getThisObject()); return result;
        }, tileClass);
        installer.hook("com.android.systemui.qs.tileimpl.QSIconViewImpl", "setCircleIconBg", chain -> {
            View icon = (View) chain.getThisObject();
            View card = cardAncestor(icon);
            if (card == null) return chain.proceed();
            settings.accept(card.getContext());
            return style.getAsInt() == 2 ? chain.proceed(new Object[]{Color.TRANSPARENT}) : chain.proceed();
        }, int.class);
        for (String method : new String[]{"onAttachedToWindow", "setIconForUiModelChange"}) {
            installer.hook("com.android.systemui.qs.tileimpl.QSIconViewImpl", method, chain -> {
                Object result = chain.proceed();
                View icon = (View) chain.getThisObject();
                View card = cardAncestor(icon);
                if (card != null) {
                    settings.accept(card.getContext());
                    if (style.getAsInt() == 2) {
                        circleTint.invoke(icon, Color.TRANSPARENT);
                    }
                    if (style.getAsInt() != 0) enlargeIcon(icon);
                }
                return result;
            });
        }
        installer.hook(NETWORK, "updateAllColors", chain -> {
            View card = (View) chain.getThisObject();
            settings.accept(card.getContext());
            String spec = (String) chain.getArg(0);
            String prefix = "wifi".equals(spec) ? "wifi" : "bt".equals(spec) ? "bluetooth" : null;
            if (style.getAsInt() == 0 || prefix == null) return chain.proceed();
            Object[] args = chain.getArgs().toArray();
            if (style.getAsInt() == 1) {
                args[1] = card.getClass().getMethod("getLabelColorForState", int.class).invoke(card, 1);
                args[2] = card.getClass().getMethod("getSecondaryLabelColorForState", int.class).invoke(card, 1);
            } else if (whiteActive.getAsBoolean() && state(card, prefix) == 2) {
                args[1] = 0xE6000000; args[2] = 0xE6000000;
            }
            Object result = chain.proceed(args);
            tintRow(card, prefix);
            return result;
        }, String.class, int.class, int.class);
        // Keep listeners intact, but select the individual row as the expandable
        // source at click time, including changes to this setting after inflation.
        for (String method : new String[]{"getTileClickListener", "getTileIconClickListener", "getTileLongClickListener"}) {
            installer.hook(NETWORK, method, chain -> {
                View card = (View) chain.getThisObject();
                String tag = (String) chain.getArg(2);
                String prefix = tag.startsWith("wifi_") ? "wifi" : tag.startsWith("bluetooth_") ? "bluetooth" : null;
                if (prefix == null) return chain.proceed();
                Object original = chain.getArg(1);
                Object proxy = Proxy.newProxyInstance(expandableClass.getClassLoader(), new Class<?>[]{expandableClass},
                        (instance, invoked, args) -> {
                            settings.accept(card.getContext());
                            Object delegate = style.getAsInt() != 0
                                    ? fromView.invoke(companion, field(card, prefix + "Container")) : original;
                            try { return invoked.invoke(delegate, args); }
                            catch (java.lang.reflect.InvocationTargetException error) { throw error.getCause(); }
                        });
                Object[] args = chain.getArgs().toArray(); args[1] = proxy;
                Object listener = chain.proceed(args);
                if (!method.equals("getTileClickListener")) return listener;
                Object tile = chain.getArg(0);
                return (View.OnClickListener) tapped -> {
                    settings.accept(card.getContext());
                    if (style.getAsInt() != 2) {
                        ((View.OnClickListener) listener).onClick(tapped);
                        return;
                    }
                    try {
                        ((View) field(card, prefix + "Container")).setTag(prefix + "_icon");
                        tileClass.getMethod("click", expandableClass, Consumer.class).invoke(tile, proxy, null);
                    } catch (ReflectiveOperationException | RuntimeException error) {
                        log.accept("Cannot toggle split network tile", error);
                    }
                };
            }, tileClass, expandableClass, String.class);
        }
    }

    void refresh() {
        for (View card : new ArrayList<>(cards.keySet())) {
            if (card == null) continue;
            try {
                // Native resource refresh also restores the foreground after disabling.
                card.getClass().getMethod("updateResources").invoke(card);
            } catch (ReflectiveOperationException | RuntimeException error) {
                log.accept("Cannot refresh split network card", error);
            }
        }
    }

    private void apply(View card) throws ReflectiveOperationException {
        cards.put(card, true);
        settings.accept(card.getContext());
        LinearLayout wifi = (LinearLayout) field(card, "wifiContainer");
        LinearLayout mobile = (LinearLayout) field(card, "mobileContainer");
        LinearLayout bt = (LinearLayout) field(card, "bluetoothContainer");
        if (wifi == null || mobile == null || bt == null || !(wifi.getParent() instanceof View wrapper)) return;
        if (style.getAsInt() == 0) {
            restore(card); restore(wrapper); restore(wifi); restore(bt); restore(mobile);
            horizontalCards.remove(card);
            for (String prefix : new String[]{"wifi", "bluetooth"}) {
                Object icon = field(card, prefix + "Icon");
                restore((View) field(card, prefix + "TextContainer"));
                View chevron = (View) field(card, prefix + "Chevron");
                restore(chevron);
                Object snapshot = field(card, prefix.equals("wifi") ? "lastWifiSnapshot" : "lastBluetoothSnapshot");
                if (snapshot != null) chevron.setVisibility(snapshot.getClass().getField("showSideView").getBoolean(snapshot) ? View.VISIBLE : View.GONE);
                restoreIcon((View) icon);
                circleTint.invoke(icon, circleColor.invoke(icon, prefix.equals("wifi") ? "wifi" : "bt", state(card, prefix)));
                restoreIconColor((View) icon, state(card, prefix));
            }
            updateContours(card);
            return;
        }
        boolean solid = style.getAsInt() == 2;
        remember(card); remember(wrapper); remember(wifi); remember(bt); remember(mobile);
        if (!(card.getBackground() instanceof ColorDrawable drawable) || drawable.getColor() != Color.TRANSPARENT) {
            card.setBackground(new ColorDrawable(Color.TRANSPARENT));
        }
        if (wrapper.getPaddingTop() != 0 || wrapper.getPaddingBottom() != 0) {
            wrapper.setPadding(wrapper.getPaddingLeft(), 0, wrapper.getPaddingRight(), 0);
        }
        if (mobile.getVisibility() != View.GONE) mobile.setVisibility(View.GONE);
        int gapId = card.getResources().getIdentifier("qs_tile_margin_vertical", "dimen", "com.android.systemui");
        boolean horizontal = horizontalCards.containsKey(card);
        if (wrapper instanceof LinearLayout linear) {
            int orientation = horizontal ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL;
            if (linear.getOrientation() != orientation) linear.setOrientation(orientation);
        }
        int gap = gapId == 0 ? Math.round(16f * card.getResources().getDisplayMetrics().density)
                : card.getResources().getDimensionPixelSize(gapId);
        for (String prefix : new String[]{"wifi", "bluetooth"}) {
            LinearLayout row = prefix.equals("wifi") ? wifi : bt;
            View chevron = (View) field(card, prefix + "Chevron");
            remember(chevron);
            Object snapshot = field(card, prefix.equals("wifi") ? "lastWifiSnapshot" : "lastBluetoothSnapshot");
            boolean showChevron = !solid && !optimizeText.getAsBoolean() && snapshot != null
                    && snapshot.getClass().getField("showSideView").getBoolean(snapshot);
            chevron.setVisibility(showChevron ? View.VISIBLE : View.GONE);
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) row.getLayoutParams();
            int top = !horizontal && prefix.equals("bluetooth") ? gap : 0;
            int start = horizontal && prefix.equals("bluetooth") ? gap : 0;
            int width = horizontal ? 0 : ViewGroup.LayoutParams.MATCH_PARENT;
            int height = horizontal ? ViewGroup.LayoutParams.MATCH_PARENT : 0;
            if (params.width != width || params.height != height || params.weight != 1f
                    || params.topMargin != top || params.bottomMargin != 0 || params.getMarginStart() != start) {
                params.width = width; params.height = height; params.weight = 1f;
                params.topMargin = top; params.bottomMargin = 0; params.setMarginStart(start);
                row.setLayoutParams(params);
            }
            tintRow(card, prefix);
            Saved rowOriginal = originals.get(row);
            boolean rtl = row.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            int startPadding = (rtl ? rowOriginal.right : rowOriginal.left) / (solid ? 2 : 1);
            if (row.getPaddingStart() != startPadding) {
                row.setPaddingRelative(startPadding, rowOriginal.top,
                        rtl ? rowOriginal.left : rowOriginal.right, rowOriginal.bottom);
            }
            View textGroup = (View) field(card, prefix + "TextContainer");
            remember(textGroup);
            LinearLayout.LayoutParams textParams = (LinearLayout.LayoutParams) textGroup.getLayoutParams();
            int textMargin = originals.get(textGroup).params.getMarginStart() / (solid ? 2 : 1);
            if (textParams.getMarginStart() != textMargin) {
                textParams.setMarginStart(textMargin);
                textGroup.setLayoutParams(textParams);
            }
            View icon = (View) field(card, prefix + "Icon");
            if (solid) {
                circleTint.invoke(icon, Color.TRANSPARENT);
                enlargeIcon(icon);
            } else {
                restoreIcon(icon);
                circleTint.invoke(icon, circleColor.invoke(icon, prefix.equals("wifi") ? "wifi" : "bt", state(card, prefix)));
                restoreIconColor(icon, state(card, prefix));
                if (optimizeText.getAsBoolean()) enlargeIcon(icon);
            }
            int currentState = solid ? state(card, prefix) : 1;
            int label = (Integer) card.getClass().getMethod("getLabelColorForState", int.class).invoke(card, currentState);
            int secondary = (Integer) card.getClass().getMethod("getSecondaryLabelColorForState", int.class).invoke(card, currentState);
            card.getClass().getMethod("updateAllColors", String.class, int.class, int.class)
                    .invoke(card, prefix.equals("wifi") ? "wifi" : "bt", label, secondary);
        }
        updateContours(card);
    }

    private final CardIconOverflow overflow = new CardIconOverflow();

    private void enlargeIcon(View icon) throws ReflectiveOperationException {
        View glyph = (View) field(icon, "mIcon");
        if (glyph == null) return;
        remember(glyph);
        Saved saved = originals.get(glyph);
        boolean solid = style.getAsInt() == 2;
        overflow.apply(icon, cardAncestor(icon), solid || optimizeText.getAsBoolean());
        float scale = optimizeText.getAsBoolean() ? 1.2f : solid ? 1.5f : 1f;
        if (solid) scale *= 1.2f;
        glyph.setScaleX(saved.scaleX * scale);
        glyph.setScaleY(saved.scaleY * scale);
        View circle = (View) field(icon, "mCircleIconBg");
        if (circle != null) {
            remember(circle);
            Saved background = originals.get(circle);
            float circleScale = optimizeText.getAsBoolean() ? 1.1f : 1f;
            circle.setScaleX(background.scaleX * circleScale);
            circle.setScaleY(background.scaleY * circleScale);
        }
        float towardText = solid ? 4f * icon.getResources().getDisplayMetrics().density : 0f;
        glyph.setTranslationX(saved.translationX
                + (icon.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? -towardText : towardText));
        // Shift only the glyph toward the text, preserving the text and icon slot.
        // Let the enlarged glyph draw beyond the invisible circle frame.
        for (View current = glyph.getParent() instanceof View parent ? parent : null;
             current != null; current = current.getParent() instanceof View parent ? parent : null) {
            if (current instanceof ViewGroup group) {
                remember(group);
                if (group.getClipChildren()) group.setClipChildren(false);
                if (group.getClipToPadding()) group.setClipToPadding(false);
            }
            if (current == icon) break;
        }
    }

    private void restoreIcon(View icon) throws ReflectiveOperationException {
        overflow.apply(icon, cardAncestor(icon), false);
        View glyph = (View) field(icon, "mIcon");
        if (glyph == null) return;
        restore(glyph);
        View circle = (View) field(icon, "mCircleIconBg");
        if (circle != null) restore(circle);
        for (View current = glyph.getParent() instanceof View parent ? parent : null;
             current != null; current = current.getParent() instanceof View parent ? parent : null) {
            restore(current);
            if (current == icon) break;
        }
    }

    private void restoreIconColor(View icon, int state) throws ReflectiveOperationException {
        ImageView glyph = (ImageView) field(icon, "mIcon");
        if (glyph != null) iconTint.apply(glyph, (Integer) iconColor.invoke(icon, state));
    }

    private void tintRow(View card, String prefix) throws ReflectiveOperationException {
        View row = (View) field(card, prefix + "Container");
        if (!originals.containsKey(row)) return;
        int currentState = state(card, prefix);
        boolean solid = style.getAsInt() == 2;
        if (solid && whiteActive.getAsBoolean() && currentState == 2) {
            ImageView glyph = (ImageView) field(field(card, prefix + "Icon"), "mIcon");
            if (glyph != null) iconTint.apply(glyph, activeColor.getAsInt() & 0xFF000000);
        }
        int color = solid && whiteActive.getAsBoolean() && currentState == 2 ? activeColor.getAsInt()
                : (Integer) backgroundColor.invoke(card, solid ? currentState : 1);
        if (!(row.getBackground() instanceof LayerDrawable)) {
            int id = card.getResources().getIdentifier("qs_tile_background", "drawable", "com.android.systemui");
            if (id != 0) {
                LayerDrawable layers = (LayerDrawable) card.getContext().getDrawable(id).mutate();
                int backgroundId = card.getResources().getIdentifier("background", "id", "com.android.systemui");
                int radiusId = card.getResources().getIdentifier("qs_corner_radius", "dimen", "com.android.systemui");
                float radius = card.getResources().getDimensionPixelSize(radiusId);
                Drawable surface = (Drawable) smoothDrawable.newInstance();
                smoothRadius.invoke(surface, radius);
                layers.setDrawableByLayerId(backgroundId, surface);
                row.setBackground(layers);
                smoothOutline.invoke(null, row, radius);
            }
        }
        if (row.getBackground() instanceof LayerDrawable layers) {
            int id = card.getResources().getIdentifier("background", "id", "com.android.systemui");
            Drawable surface = layers.findDrawableByLayerId(id);
            if (surface != null) surface.setTint(color);
        }
    }

    private int state(View card, String prefix) throws ReflectiveOperationException {
        Object state = field(card, prefix.equals("wifi") ? "lastWifiSnapshot" : "lastBluetoothSnapshot");
        return state == null ? 1 : state.getClass().getField("state").getInt(state);
    }
    private Object field(Object target, String name) throws ReflectiveOperationException {
        return target.getClass().getField(name).get(target);
    }
    private View cardAncestor(View view) {
        for (View current = view; current != null; current = current.getParent() instanceof View parent ? parent : null) {
            if (NETWORK.equals(current.getClass().getName())) return current;
        }
        return null;
    }
    private void remember(View view) { originals.computeIfAbsent(view, Saved::new); }
    private void restore(View view) {
        Saved saved = originals.remove(view);
        if (saved == null) return;
        view.setBackground(saved.background);
        view.setPadding(saved.left, saved.top, saved.right, saved.bottom);
        view.setVisibility(saved.visibility);
        view.setScaleX(saved.scaleX);
        view.setScaleY(saved.scaleY);
        view.setTranslationX(saved.translationX);
        if (view instanceof LinearLayout linear) linear.setOrientation(saved.orientation);
        if (view instanceof ViewGroup group) {
            group.setClipChildren(saved.clipChildren);
            group.setClipToPadding(saved.clipPadding);
        }
        view.setOutlineProvider(saved.outline);
        view.setClipToOutline(saved.clipOutline);
        if (saved.params != null) view.setLayoutParams(new LinearLayout.LayoutParams(saved.params));
    }
    private static final class Saved {
        final Drawable background;
        final int left, top, right, bottom, visibility, orientation;
        final LinearLayout.LayoutParams params;
        final float scaleX, scaleY, translationX;
        final boolean clipChildren, clipPadding;
        final boolean clipOutline;
        final ViewOutlineProvider outline;
        Saved(View view) {
            background = view.getBackground(); visibility = view.getVisibility();
            orientation = view instanceof LinearLayout linear ? linear.getOrientation() : 0;
            left = view.getPaddingLeft(); top = view.getPaddingTop(); right = view.getPaddingRight(); bottom = view.getPaddingBottom();
            params = view.getLayoutParams() instanceof LinearLayout.LayoutParams p ? new LinearLayout.LayoutParams(p) : null;
            scaleX = view.getScaleX(); scaleY = view.getScaleY();
            translationX = view.getTranslationX();
            clipChildren = view instanceof ViewGroup group && group.getClipChildren();
            clipPadding = view instanceof ViewGroup group && group.getClipToPadding();
            clipOutline = view.getClipToOutline(); outline = view.getOutlineProvider();
        }
    }
}
