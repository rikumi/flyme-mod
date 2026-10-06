package com.rikumi.flymemod;

import android.content.Context;
import android.content.res.Resources;
import android.text.SpannableStringBuilder;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Replace SystemUI Wi-Fi labels at their resource source, never user SSIDs. */
final class WifiLabelHooks {
    private static final Set<String> LABELS = Set.of("quick_settings_wifi_label", "status_bar_settings_wlan",
            "wifi_is_off", "quick_settings_wifi_detail_empty_text");
    private final Consumer<Context> settings;
    private final BooleanSupplier enabled;
    private final Method application;

    WifiLabelHooks(ClassLoader loader, Consumer<Context> settings, BooleanSupplier enabled)
            throws ReflectiveOperationException {
        this.settings = settings; this.enabled = enabled;
        application = loader.loadClass("android.app.ActivityThread").getDeclaredMethod("currentApplication");
        Resources.class.getDeclaredMethod("getText", int.class);
    }

    void install(SignalHooks.Installer installer) {
        installer.hook(Resources.class.getName(), "getText", chain -> {
            CharSequence result = (CharSequence) chain.proceed();
            if (result == null || !result.toString().contains("无线网络")) return result;
            Resources resources = (Resources) chain.getThisObject();
            int id = (Integer) chain.getArg(0);
            if (!"com.android.systemui".equals(resources.getResourcePackageName(id))
                    || !LABELS.contains(resources.getResourceEntryName(id))) return result;
            Context context = (Context) application.invoke(null);
            if (context != null) settings.accept(context);
            if (!enabled.getAsBoolean()) return result;
            SpannableStringBuilder text = new SpannableStringBuilder(result);
            int start;
            while ((start = text.toString().indexOf("无线网络")) >= 0) text.replace(start, start + 4, "Wi-Fi");
            return text;
        }, int.class);
    }
}
