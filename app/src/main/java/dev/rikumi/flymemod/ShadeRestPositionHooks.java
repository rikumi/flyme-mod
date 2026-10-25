package dev.rikumi.flymemod;

import android.content.Context;
import android.view.View;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

final class ShadeRestPositionHooks {
    private final Consumer<Context> settings;
    private final BooleanSupplier enabled;
    private final Field statusBar, editing;
    private final int contentId, batteryId, iconsId;
    private final Map<View, Boolean> phoneBars = new WeakHashMap<>();
    private final Map<View, Pose> panels = new WeakHashMap<>();

    ShadeRestPositionHooks(ClassLoader loader, Consumer<Context> settings, BooleanSupplier enabled)
            throws ReflectiveOperationException {
        this.settings = settings;
        this.enabled = enabled;
        Class<?> panel = loader.loadClass("com.flyme.systemui.controlcenter.phone.ControlCenterPanelView");
        panel.getDeclaredMethod("onFinishInflate");
        panel.getDeclaredMethod("onLayout", boolean.class, int.class, int.class, int.class, int.class);
        loader.loadClass("com.flyme.systemui.controlcenter.phone.MzQSPanel")
                .getDeclaredMethod("setFooterEditModeTopMargin", int.class);
        loader.loadClass("com.android.systemui.statusbar.phone.PhoneStatusBarView")
                .getDeclaredMethod("onFinishInflate");
        statusBar = panel.getField("mStatusBar");
        editing = panel.getField("mIsQSEditing");
        Class<?> ids = loader.loadClass("com.android.systemui.res.R$id");
        contentId = ids.getField("center_content").getInt(null);
        batteryId = ids.getField("battery").getInt(null);
        iconsId = ids.getField("statusIcons").getInt(null);
    }

    void install(SignalHooks.Installer installer) {
        installer.hook("com.flyme.systemui.controlcenter.phone.MzQSPanel", "setFooterEditModeTopMargin", chain -> {
            View panel = (View) chain.getThisObject();
            for (View parent = panel; parent != null;
                 parent = parent.getParent() instanceof View view ? view : null) {
                if (!parent.getClass().getName().equals("com.flyme.systemui.controlcenter.phone.ControlCenterPanelView")) continue;
                int id = panel.getResources().getIdentifier("qs_tile_margin_vertical", "dimen", "com.android.systemui");
                // Place the menu and add button one grid gap below the last tile row.
                if (id != 0) return chain.proceed(new Object[]{Math.min((Integer) chain.getArg(0),
                        panel.getResources().getDimensionPixelSize(id))});
                break;
            }
            return chain.proceed();
        }, int.class);
        installer.hook("com.android.systemui.statusbar.phone.PhoneStatusBarView", "onFinishInflate", chain -> {
            Object result = chain.proceed();
            View phone = (View) chain.getThisObject();
            phoneBars.put(phone, true);
            watch(phone.findViewById(batteryId), phone);
            watch(phone.findViewById(iconsId), phone);
            phone.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View view) { dirty(); }
                @Override public void onViewDetachedFromWindow(View view) { dirty(); }
            });
            dirty();
            return result;
        });
        installer.hook("com.flyme.systemui.controlcenter.phone.ControlCenterPanelView", "onFinishInflate", chain -> {
            Object result = chain.proceed();
            View panel = (View) chain.getThisObject();
            View content = panel.findViewById(contentId);
            if (content != null) {
                Pose pose = new Pose(content);
                panels.put(panel, pose);
                WeakReference<View> owner = new WeakReference<>(panel);
                panel.getViewTreeObserver().addOnPreDrawListener(() -> {
                    View root = owner.get();
                    if (root != null) {
                        if (pose.dirty || pose.active != eligible(root)) update(root, pose);
                        clampStatusBar(root, pose);
                    }
                    return true;
                });
            }
            return result;
        });
        installer.hook("com.flyme.systemui.controlcenter.phone.ControlCenterPanelView", "onLayout", chain -> {
            Object result = chain.proceed();
            View panel = (View) chain.getThisObject();
            Pose pose = panels.get(panel);
            if (pose != null) {
                settings.accept(panel.getContext());
                update(panel, pose);
            }
            return result;
        }, boolean.class, int.class, int.class, int.class, int.class);
    }

    private void watch(View icon, View phone) {
        for (View current = icon; current != null;
                current = current.getParent() instanceof View parent ? parent : null) {
            current.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> dirty());
            if (current == phone) break;
        }
    }

    private void dirty() { for (Pose pose : panels.values()) pose.dirty = true; }

    private boolean eligible(View panel) {
        try {
            View bar = (View) statusBar.get(panel);
            return enabled.getAsBoolean() && bar != null;
        } catch (IllegalAccessException error) { throw new IllegalStateException(error); }
    }

    private void update(View panel, Pose pose) {
        View content = pose.content.get();
        if (content == null) return;
        pose.dirty = false;
        pose.active = eligible(panel);
        try {
            View bar = (View) statusBar.get(panel);
            if (!pose.active) {
                restoreStatusBar(bar, pose);
                set(content, pose.original);
                return;
            }

            if (editing.getBoolean(panel) || bar.getVisibility() != View.VISIBLE) {
                restoreStatusBar(bar, pose);
                set(content, pose.offset);
                return;
            }
            for (View phone : phoneBars.keySet()) {
                if (!phone.isAttachedToWindow() || phone.getDisplay() == null || panel.getDisplay() == null
                        || phone.getDisplay().getDisplayId() != panel.getDisplay().getDisplayId()) continue;
                for (int id : new int[]{batteryId, iconsId}) {
                    View source = bar.findViewById(id), target = phone.findViewById(id);
                    if (!measured(source) || !measured(target)) continue;
                    pose.source = new WeakReference<>(source);
                    pose.target = new WeakReference<>(target);
                    pose.offset = layoutCenterOnScreen(target) - layoutCenterOnScreen(source);
                    set(content, pose.offset);
                    return;
                }
            }

        } catch (IllegalAccessException error) {
            throw new IllegalStateException(error);
        }
    }

    private void clampStatusBar(View panel, Pose pose) {
        if (!pose.active) return;
        View source = pose.source.get(), target = pose.target.get();
        if (!measured(source) || !measured(target)) return;
        if (!target.isAttachedToWindow() || source.getDisplay() == null || target.getDisplay() == null
                || source.getDisplay().getDisplayId() != target.getDisplay().getDisplayId()) return;
        try {
            View bar = (View) statusBar.get(panel);
            if (bar == null || editing.getBoolean(panel) || bar.getVisibility() != View.VISIBLE) return;
            float parentScale = screenScaleY(bar.getParent() instanceof View parent ? parent : null);
            if (parentScale <= 0f) return;
            float current = bar.getTranslationY();

            float applied = current == pose.lastBarTranslation ? pose.barCorrection : 0f;
            float actualCenter = animatedCenterOnScreen(source, pose.location);
            float minimumCenter = layoutCenterOnScreen(target);
            float corrected = boundedStatusTranslation(current, applied, actualCenter, minimumCenter, parentScale);
            pose.barCorrection = corrected - (current - applied);
            pose.lastBarTranslation = corrected;
            set(bar, corrected);
        } catch (IllegalAccessException error) { throw new IllegalStateException(error); }
    }

    private static float animatedCenterOnScreen(View view, int[] location) {
        View root = view.getRootView();
        float center = view.getHeight() * .5f;
        for (View current = view; current != root;) {
            center = current.getTop() + current.getTranslationY() + current.getPivotY()
                    + (center - current.getPivotY()) * current.getScaleY();
            if (!(current.getParent() instanceof View parent)) break;
            center -= parent.getScrollY();
            current = parent;
        }
        root.getLocationOnScreen(location);

        return center + location[1];
    }

    static float boundedStatusTranslation(float current, float appliedCorrection, float actualCenter,
                                          float minimumCenter, float parentScale) {
        return current - appliedCorrection
                + Math.max(0f, appliedCorrection + (minimumCenter - actualCenter) / parentScale);
    }

    private static float screenScaleY(View view) {
        float scale = 1f;
        for (View current = view; current != null;
             current = current.getParent() instanceof View parent ? parent : null) scale *= current.getScaleY();
        return scale;
    }

    private static void restoreStatusBar(View bar, Pose pose) {
        if (bar != null && bar.getTranslationY() == pose.lastBarTranslation)
            set(bar, pose.lastBarTranslation - pose.barCorrection);
        pose.barCorrection = 0f;
        pose.lastBarTranslation = Float.NaN;
    }

    private static boolean measured(View view) {
        return view != null && view.isLaidOut() && view.getHeight() > 0 && view.getVisibility() != View.GONE;
    }

    static float layoutCenterOnScreen(View view) {
        View root = view.getRootView();
        float center = view.getHeight() * .5f;
        for (View current = view; current != root;) {
            center += current.getTop();
            if (!(current.getParent() instanceof View parent)) break;
            center -= parent.getScrollY();
            current = parent;
        }
        int[] location = new int[2];
        root.getLocationOnScreen(location);
        // Child translations/scales belong to the gesture, not the resting layout.
        return center + location[1] - root.getTranslationY();
    }

    private static void set(View content, float offset) {
        if (content.getTranslationY() != offset) content.setTranslationY(offset);
    }

    private static final class Pose {
        final WeakReference<View> content;
        final float original;
        final int[] location = new int[2];
        WeakReference<View> source = new WeakReference<>(null), target = new WeakReference<>(null);
        float offset, barCorrection, lastBarTranslation = Float.NaN;
        boolean dirty = true, active;
        Pose(View content) { this.content = new WeakReference<>(content); original = offset = content.getTranslationY(); }
    }
}
