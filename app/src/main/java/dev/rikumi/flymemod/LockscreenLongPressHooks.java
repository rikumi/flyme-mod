package dev.rikumi.flymemod;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

final class LockscreenLongPressHooks {
    private static final String PANEL = "com.android.systemui.shade.NotificationPanelViewController";
    private final Field view;
    private final Method isKeyguardShowing;
    private final Consumer<Context> settings;
    private final BooleanSupplier enabled;

    LockscreenLongPressHooks(ClassLoader loader, Consumer<Context> settings, BooleanSupplier enabled)
            throws ReflectiveOperationException {
        Class<?> panel = loader.loadClass(PANEL);
        panel.getDeclaredMethod("onLongPress", MotionEvent.class);
        view = panel.getField("mView");
        isKeyguardShowing = panel.getDeclaredMethod("isKeyguardShowing");
        this.settings = settings;
        this.enabled = enabled;
    }

    void install(SignalHooks.Installer installer) {
        installer.hook(PANEL, "onLongPress", chain -> {
            Object panel = chain.getThisObject();
            settings.accept(((View) view.get(panel)).getContext());
            if (enabled.getAsBoolean() && (boolean) isKeyguardShowing.invoke(panel)) return null;
            return chain.proceed();
        }, MotionEvent.class);
    }
}
