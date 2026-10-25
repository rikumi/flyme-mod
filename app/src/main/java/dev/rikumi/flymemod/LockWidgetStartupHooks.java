package dev.rikumi.flymemod;

import android.content.Context;
import android.view.ViewGroup;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

final class LockWidgetStartupHooks {
    private static final String LAYOUT = "com.flyme.keyguard.plugin.PluginLayout";
    private final Consumer<Context> settings;
    private final BooleanSupplier enabled;

    LockWidgetStartupHooks(ClassLoader loader, Consumer<Context> settings, BooleanSupplier enabled)
            throws ReflectiveOperationException {
        Class<?> layout = loader.loadClass(LAYOUT);
        layout.getDeclaredMethod("onFinishInflate");
        layout.getDeclaredMethod("updatePluginLayout", java.util.List.class);
        if (!ViewGroup.class.isAssignableFrom(layout)) throw new NoSuchMethodException(LAYOUT + " is not a ViewGroup");
        this.settings = settings;
        this.enabled = enabled;
    }

    void install(SignalHooks.Installer installer) {
        installer.hook(LAYOUT, "onFinishInflate", chain -> {
            Object result = chain.proceed();
            ViewGroup layout = (ViewGroup) chain.getThisObject();
            settings.accept(layout.getContext());

            if (enabled.getAsBoolean()) layout.removeAllViews();
            return result;
        });
    }
}
