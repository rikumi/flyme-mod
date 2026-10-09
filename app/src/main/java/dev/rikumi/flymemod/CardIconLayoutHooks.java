package dev.rikumi.flymemod;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Icon layout optimization for Flyme's native focus-mode and device-center 2x1 cards. */
final class CardIconLayoutHooks {
    private static final String TILE = "com.android.systemui.qs.tileimpl.QSTileViewImpl";
    private static final String DEVICE = "com.flyme.systemui.qs.tileimpl.DeviceCenterQSTileViewImpl";
    private static final String ICON = "com.android.systemui.qs.tileimpl.QSIconViewImpl";
    private final Consumer<Context> settings;
    private final BooleanSupplier optimize;
    private final BiConsumer<String, Throwable> log;
    private final Class<?> stateClass, tileClass;
    private final Field iconState, glyphField;
    private final Method circleTint, circleColor;
    private final CardIconOverflow overflow = new CardIconOverflow();
    private final Map<View, Boolean> cards = new WeakHashMap<>();
    private final Map<View, Saved> originals = new WeakHashMap<>();

    CardIconLayoutHooks(ClassLoader loader, Consumer<Context> settings, BooleanSupplier optimize,
                   BiConsumer<String, Throwable> log) throws ReflectiveOperationException {
        this.settings = settings; this.optimize = optimize; this.log = log;
        stateClass = loader.loadClass("com.android.systemui.plugins.qs.QSTile$State");
        tileClass = loader.loadClass("com.android.systemui.plugins.qs.QSTile");
        Class<?> icon = loader.loadClass(ICON);
        iconState = icon.getField("mIconState"); glyphField = icon.getField("mIcon");
        circleTint = icon.getMethod("setCircleIconBg", int.class);
        circleColor = icon.getMethod("getCircleIconBgColor", String.class, int.class);
        Class<?> tile = loader.loadClass(TILE);
        for (String name : new String[]{"tileSpec", "labelContainer"}) tile.getField(name);
        Class<?> device = loader.loadClass(DEVICE);
        for (String name : new String[]{"container", "deviceCenterText", "tileIcon", "secondIcon", "thirdIcon"}) device.getField(name);
    }

    void install(SignalHooks.Installer installer) {
        installer.hook(TILE, "init", chain -> {
            View tile = (View) chain.getThisObject();
            Object spec = tileClass.getMethod("getTileSpec").invoke(chain.getArg(0));
            if ("dnd".equals(spec)) cards.put(tile, true);
            return chain.proceed();
        }, tileClass);
        for (String type : new String[]{TILE, DEVICE}) {
            installer.hook(type, "handleStateChanged", chain -> {
                View card = (View) chain.getThisObject();
                if ("dnd".equals(stateClass.getField("spec").get(chain.getArg(0)))) cards.put(card, true);
                Object result = chain.proceed(); apply(card); return result;
            }, stateClass);
            installer.hook(type, "updateResources", chain -> {
                Object result = chain.proceed(); apply((View) chain.getThisObject()); return result;
            });
        }
        installer.hook(TILE, "applyConstraints", chain -> {
            Object result = chain.proceed(); apply((View) chain.getThisObject()); return result;
        });
        installer.hook(TILE, "setupPadding", chain -> {
            Object result = chain.proceed(); apply((View) chain.getThisObject()); return result;
        });
        installer.hook(DEVICE, "initViews", chain -> {
            Object result = chain.proceed(); apply((View) chain.getThisObject()); return result;
        });
        for (String method : new String[]{"onAttachedToWindow", "setIconForUiModelChange"}) {
            installer.hook(ICON, method, chain -> {
                Object result = chain.proceed();
                View card = ancestor((View) chain.getThisObject());
                if (card != null) apply(card);
                return result;
            });
        }
    }

    void refresh() {
        for (View card : new ArrayList<>(cards.keySet())) if (card != null) {
            try { card.getClass().getMethod("updateResources").invoke(card); }
            catch (ReflectiveOperationException | RuntimeException error) { log.accept("Cannot refresh 2x1 card icon layout", error); }
        }
    }

    private boolean target(View view) {
        if (DEVICE.equals(view.getClass().getName()) || cards.containsKey(view)) return true;
        if (!TILE.equals(view.getClass().getName())) return false;
        try { return "dnd".equals(view.getClass().getField("tileSpec").get(view)); }
        catch (ReflectiveOperationException ignored) { return false; }
    }

    private View ancestor(View view) {
        for (View current = view; current != null;
             current = current.getParent() instanceof View parent ? parent : null) if (target(current)) return current;
        return null;
    }

    private void apply(View card) throws ReflectiveOperationException {
        if (!target(card)) return;
        cards.put(card, true); settings.accept(card.getContext());
        boolean device = DEVICE.equals(card.getClass().getName());
        View padding = device ? (View) field(card, "container") : card;
        View label = device ? (View) field(card, "deviceCenterText") : (View) field(card, "labelContainer");
        if (padding == null || label == null) return;
        restore(padding); restore(label);
        if (!device && label.getLayoutParams() instanceof ViewGroup.MarginLayoutParams params) {
            // Native horizontal constraints reserve the circle width plus the label gap.
            // Init/state callbacks can precede applyConstraints, so a saved margin may be zero.
            int circleId = card.getResources().getIdentifier("circle_qs_icon_size", "dimen", "com.android.systemui");
            if (circleId != 0) {
                int gap = labelGap(card);
                int margin = card.getResources().getDimensionPixelSize(circleId) + gap;
                if (params.getMarginStart() != margin) { params.setMarginStart(margin); label.setLayoutParams(params); }
            }
        }
        for (String name : device ? new String[]{"tileIcon", "secondIcon", "thirdIcon"} : new String[]{"icon"}) {
            View icon = (View) field(card, name);
            if (icon == null) continue;
            View glyph = (View) glyphField.get(icon);
            if (glyph == null) continue;
            boolean optimized = optimize.getAsBoolean();
            overflow.apply(icon, card, optimized);
            View circle = (View) field(icon, "mCircleIconBg");
            if (optimized && circle != null) {
                Saved background = remember(circle);
                circle.setScaleX(background.scaleX * 1.1f);
                circle.setScaleY(background.scaleY * 1.1f);
            } else if (circle != null) restore(circle);
            if (optimized) {
                Saved saved = remember(glyph);
                float scale = 1.2f;
                glyph.setScaleX(saved.scaleX * scale); glyph.setScaleY(saved.scaleY * scale);
                glyph.setTranslationX(saved.translationX);
                for (View current = glyph.getParent() instanceof View parent ? parent : null;
                     current != null; current = current.getParent() instanceof View parent ? parent : null) {
                    if (current instanceof ViewGroup group) { remember(group); group.setClipChildren(false); group.setClipToPadding(false); }
                    if (current == icon) break;
                }
                restoreCircle(icon);
            } else {
                restore(glyph);
                for (View current = glyph.getParent() instanceof View parent ? parent : null;
                     current != null; current = current.getParent() instanceof View parent ? parent : null) {
                    restore(current); if (current == icon) break;
                }
                restoreCircle(icon);
            }
        }
    }

    private void restoreCircle(View icon) throws ReflectiveOperationException {
        Object state = iconState.get(icon);
        if (state != null) circleTint.invoke(icon, circleColor.invoke(icon,
                stateClass.getField("spec").get(state), stateClass.getField("state").getInt(state)));
    }

    private int labelGap(View card) {
        int id = card.getResources().getIdentifier("qs_label_container_margin", "dimen", "com.android.systemui");
        return id == 0 ? 0 : card.getResources().getDimensionPixelSize(id);
    }
    private Object field(Object object, String name) throws ReflectiveOperationException { return object.getClass().getField(name).get(object); }
    private Saved remember(View view) { return originals.computeIfAbsent(view, Saved::new); }
    private void restore(View view) {
        Saved saved = originals.remove(view); if (saved == null) return;
        view.setPadding(saved.left, saved.top, saved.right, saved.bottom);
        view.setScaleX(saved.scaleX); view.setScaleY(saved.scaleY); view.setTranslationX(saved.translationX);
        if (view instanceof ViewGroup group) { group.setClipChildren(saved.clipChildren); group.setClipToPadding(saved.clipPadding); }
        if (view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams params && params.getMarginStart() != saved.marginStart) {
            params.setMarginStart(saved.marginStart); view.setLayoutParams(params);
        }
    }
    private static final class Saved {
        final int left, top, right, bottom, marginStart;
        final float scaleX, scaleY, translationX;
        final boolean clipChildren, clipPadding;
        Saved(View view) {
            left = view.getPaddingLeft(); top = view.getPaddingTop(); right = view.getPaddingRight(); bottom = view.getPaddingBottom();
            marginStart = view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams params ? params.getMarginStart() : 0;
            scaleX = view.getScaleX(); scaleY = view.getScaleY(); translationX = view.getTranslationX();
            clipChildren = view instanceof ViewGroup group && group.getClipChildren();
            clipPadding = view instanceof ViewGroup group && group.getClipToPadding();
        }
    }
}
