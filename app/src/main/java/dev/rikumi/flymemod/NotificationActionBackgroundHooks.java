package dev.rikumi.flymemod;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.service.notification.StatusBarNotification;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Remove the action bar's fill, preserving individual buttons and their interaction. */
final class NotificationActionBackgroundHooks {
    private final Consumer<Context> settings;
    private final BooleanSupplier enabled;
    private final Field actions;
    private final Map<View, Boolean> containers = new WeakHashMap<>();
    private final Map<View, Drawable> originals = new WeakHashMap<>();

    NotificationActionBackgroundHooks(ClassLoader loader, Consumer<Context> settings,
            BooleanSupplier enabled) throws ReflectiveOperationException {
        this.settings=settings; this.enabled=enabled;
        Class<?> wrapper=loader.loadClass("com.android.systemui.statusbar.notification.row.wrapper.NotificationTemplateViewWrapper");
        actions=wrapper.getField("mActionsContainer");
        wrapper.getDeclaredMethod("resolveTemplateViews",StatusBarNotification.class);
    }

    void install(SignalHooks.Installer installer) {
        installer.hook(actions.getDeclaringClass().getName(), "resolveTemplateViews", chain -> {
            Object result=chain.proceed();
            if (actions.get(chain.getThisObject()) instanceof View view) {
                settings.accept(view.getContext());
                containers.put(view,Boolean.TRUE);
                apply(view);
            }
            return result;
        },StatusBarNotification.class);
    }

    private void apply(View view) {
        Drawable background=view.getBackground();
        if (enabled.getAsBoolean()) {
            if (background!=null) {
                // RemoteViews reapplication can replace the background while enabled.
                // Save its latest native value rather than the first inflated value.
                originals.put(view,background);
                view.setBackground(null);
            }
        } else {
            Drawable original=originals.remove(view);
            if (original!=null && background==null) view.setBackground(original);
        }
    }

    void refresh() {
        for (View view:new ArrayList<>(containers.keySet())) if (view!=null) apply(view);
    }
}
