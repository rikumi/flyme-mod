package dev.rikumi.flymemod;

import android.content.Context;
import android.view.View;
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

/** Keep Flyme's shrinking removal animation below the remaining notification rows. */
final class NotificationRemovalLayerHooks {
    private static final String ACTIVATABLE = "com.android.systemui.statusbar.notification.row.ActivatableNotificationView";
    private static final String ROW = "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow";
    private final Class<?> rowClass;
    private final Field customEnabled, customZ, originZ;
    private final Method setCustomEnabled, setCustomZ, groupContainer, summary;
    private final Consumer<Context> settings;
    private final BooleanSupplier enabled;
    private final BiConsumer<String, Throwable> log;
    private final Map<View, State> states = new WeakHashMap<>();

    NotificationRemovalLayerHooks(ClassLoader loader, Consumer<Context> settings, BooleanSupplier enabled,
                                  BiConsumer<String, Throwable> log) throws ReflectiveOperationException {
        this.settings = settings; this.enabled = enabled; this.log = log;
        rowClass = loader.loadClass(ROW);
        Class<?> activatable = loader.loadClass(ACTIVATABLE);
        activatable.getDeclaredMethod("updateAppearAnimationScale", boolean.class);
        activatable.getDeclaredMethod("cancelAppearAnimation");
        activatable.getDeclaredMethod("onDetachedFromWindow");
        rowClass.getDeclaredMethod("onAppearAnimationFinished", boolean.class, boolean.class);
        customEnabled = rowClass.getField("mIsCustomTranslationZ");
        customZ = rowClass.getField("mCustomTranslationZ");
        originZ = rowClass.getField("mOriginTranslationZ");
        setCustomEnabled = rowClass.getMethod("setIsCustomTranslationZ", boolean.class);
        setCustomZ = rowClass.getMethod("setCustomTranslationZ", float.class);
        groupContainer = rowClass.getMethod("getGroupCollapseContainer");
        summary = rowClass.getMethod("isSummaryWithChildren");
    }

    void install(SignalHooks.Installer installer) {
        installer.hook(ACTIVATABLE, "updateAppearAnimationScale", chain -> {
            View row = (View) chain.getThisObject();
            if (rowClass.isInstance(row)) {
                settings.accept(row.getContext());
                if (!(Boolean) chain.getArg(0) && enabled.getAsBoolean()
                        && (groupContainer.invoke(row) == null || (Boolean) summary.invoke(row))) begin(row);
                else finish(row);
            }
            try { return chain.proceed(); }
            catch (Throwable error) { finish(row); throw error; }
        }, boolean.class);
        installer.hook(ROW, "onAppearAnimationFinished", chain -> {
            try { return chain.proceed(); }
            finally { finish((View) chain.getThisObject()); }
        }, boolean.class, boolean.class);
        for (String method : new String[]{"cancelAppearAnimation", "onDetachedFromWindow"}) {
            installer.hook(ACTIVATABLE, method, chain -> {
                try { return chain.proceed(); }
                finally { finish((View) chain.getThisObject()); }
            });
        }
        // Native live-notification updates may change the custom layer while removal is running.
        // Preserve their latest values for restoration without allowing the row to rise again.
        installer.hook(ROW, "setCustomTranslationZ", chain -> {
            View row = (View) chain.getThisObject();
            State state = states.get(row);
            if (state == null || state.applying) return chain.proceed();
            state.restoreCustomZ = (Float) chain.getArg(0);
            return chain.proceed(new Object[]{belowSiblings(row)});
        }, float.class);
        installer.hook(ROW, "setIsCustomTranslationZ", chain -> {
            View row = (View) chain.getThisObject();
            State state = states.get(row);
            if (state == null || state.applying) return chain.proceed();
            state.restoreCustomEnabled = (Boolean) chain.getArg(0);
            float origin = originZ.getFloat(row);
            try { return chain.proceed(new Object[]{true}); }
            finally { originZ.setFloat(row, origin); }
        }, boolean.class);
    }

    void refresh() {
        if (!enabled.getAsBoolean()) for (View row : new ArrayList<>(states.keySet())) finish(row);
    }

    private void begin(View row) {
        finish(row);
        State state = null;
        try {
            state = new State(row, customEnabled.getBoolean(row), customZ.getFloat(row));
            states.put(row, state);
            state.applying = true;
            float origin = originZ.getFloat(row);
            setCustomZ.invoke(row, belowSiblings(row));
            setCustomEnabled.invoke(row, true);
            originZ.setFloat(row, origin);
            state.applying = false;
            state.observer = row.getViewTreeObserver();
            state.observer.addOnPreDrawListener(state);
        } catch (ReflectiveOperationException | RuntimeException error) {
            finish(row);
            log.accept("Cannot lower shrinking notification", error);
        }
    }

    private float belowSiblings(View row) {
        float minimum = 0f;
        if (row.getParent() instanceof ViewGroup parent) {
            for (int i = 0; i < parent.getChildCount(); i++) {
                View sibling = parent.getChildAt(i);
                if (sibling != row && rowClass.isInstance(sibling) && !states.containsKey(sibling)
                        && sibling.getVisibility() == View.VISIBLE) minimum = Math.min(minimum, sibling.getZ());
            }
        }
        // Compare total Z, including elevation; one dp ensures strict ordering even for equal native layers.
        return minimum - row.getResources().getDisplayMetrics().density - row.getElevation();
    }

    private void finish(View row) {
        State state = states.remove(row);
        if (state == null) return;
        if (state.observer != null && state.observer.isAlive()) state.observer.removeOnPreDrawListener(state);
        try {
            float origin = originZ.getFloat(row);
            setCustomZ.invoke(row, state.restoreCustomZ);
            setCustomEnabled.invoke(row, state.restoreCustomEnabled);
            originZ.setFloat(row, origin);
        } catch (ReflectiveOperationException | RuntimeException error) {
            log.accept("Cannot restore notification animation layer", error);
        }
    }

    private final class State implements ViewTreeObserver.OnPreDrawListener {
        final WeakReference<View> owner;
        boolean restoreCustomEnabled, applying;
        float restoreCustomZ;
        ViewTreeObserver observer;
        State(View row, boolean custom, float z) {
            owner = new WeakReference<>(row); restoreCustomEnabled = custom; restoreCustomZ = z;
        }
        @Override public boolean onPreDraw() {
            View row = owner.get();
            if (row == null) return true;
            if (!enabled.getAsBoolean()) { finish(row); return true; }
            try {
                float z = belowSiblings(row);
                if (customZ.getFloat(row) != z) {
                    applying = true;
                    setCustomZ.invoke(row, z);
                }
            } catch (ReflectiveOperationException | RuntimeException error) {
                finish(row);
                log.accept("Cannot update shrinking notification layer", error);
            } finally { applying = false; }
            return true;
        }
    }
}
