package dev.rikumi.flymemod;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.SharedPreferences;
import android.view.ViewGroup;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.Button;
import android.widget.TextView;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

final class WirelessDebugTextHooks {
    private final String packageName;
    private final SharedPreferences preferences;
    private final BiConsumer<String, Throwable> log;
    private final Map<View, Boolean> roots = Collections.synchronizedMap(new WeakHashMap<>());
    private final SharedPreferences.OnSharedPreferenceChangeListener changes = (prefs, key) -> {
        if (key == null || ModuleSettings.WIRELESS_DEBUG_THEME_FIX.equals(key)) {
            synchronized (roots) {
                for (View root : roots.keySet()) root.post(() -> invalidateText(root));
            }
        }
    };
    private final AtomicBoolean failure = new AtomicBoolean();
    private Method states, colors;

    WirelessDebugTextHooks(String packageName, SharedPreferences preferences, BiConsumer<String, Throwable> log) {
        this.packageName = packageName;
        this.preferences = preferences;
        this.log = log;
    }

    void install(ClassLoader loader, ThemeColorHooks.Installer installer) {
        if (!"com.android.settings".equals(packageName) && !"com.android.systemui".equals(packageName)) return;
        preferences.registerOnSharedPreferenceChangeListener(changes);
        try {
            states = ColorStateList.class.getDeclaredMethod("getStates");
            colors = ColorStateList.class.getDeclaredMethod("getColors");
            if ("com.android.systemui".equals(packageName)) {
                for (String owner : new String[]{"com.android.systemui.wifi.WifiDebuggingActivity",
                        "com.android.systemui.wifi.WifiDebuggingSecondaryUserActivity"}) {
                    Executable method = Class.forName(owner, false, loader).getDeclaredMethod("onCreate", Bundle.class);
                    installer.hook(method, chain -> {
                        Object result = chain.proceed();
                        Activity activity = (Activity) chain.getThisObject();
                        if (WirelessDebugTextPolicy.authorizationActivity(packageName, activity.getClass().getName()))
                            roots.put(activity.getWindow().getDecorView(), Boolean.TRUE);
                        return result;
                    });
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log.accept("Cannot resolve native wireless debugging text", error);
        }
    }

    void shown(Dialog dialog) {
        if (WirelessDebugTextPolicy.pairingDialog(packageName, dialog.getClass().getName()) && dialog.getWindow() != null)
            roots.put(dialog.getWindow().getDecorView(), Boolean.TRUE);
    }

    ColorStateList textColors(TextView view, Integer customAccent) {
        if (!preferences.getBoolean(ModuleSettings.WIRELESS_DEBUG_THEME_FIX, false)
                || states == null || colors == null || view instanceof EditText || view.getError() != null
                || (view.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                != Configuration.UI_MODE_NIGHT_YES || !roots.containsKey(view.getRootView())) return null;
        try {
            // Flyme's real night on-surface foreground is #eeeeee / #e5e5e5.
            int foreground = 0xffeeeeee;
            int id = view.getResources().getIdentifier("fd_sys_color_on_surface_default", "color", packageName);
            if (id != 0) {
                int nativeColor = view.getResources().getColor(id, view.getContext().getTheme());
                if ((nativeColor & 0xffffff) >= 0xaaaaaa) foreground = nativeColor;
            }
            ColorStateList original = view.getTextColors();
            int[] nativeColors = (int[]) colors.invoke(original), mapped = null;
            for (int i = 0; i < nativeColors.length; i++) {
                // A selected gray/black accent is still an intentional button color.
                if (view instanceof Button && customAccent != null
                        && (nativeColors[i] & 0xffffff) == (customAccent & 0xffffff)) continue;
                int next = WirelessDebugTextPolicy.readable(nativeColors[i], foreground);
                if (next != nativeColors[i]) {
                    if (mapped == null) mapped = nativeColors.clone();
                    mapped[i] = next;
                }
            }
            return mapped == null ? null : new ColorStateList((int[][]) states.invoke(original), mapped);
        } catch (ReflectiveOperationException | RuntimeException error) {
            if (failure.compareAndSet(false, true)) log.accept("Cannot adapt wireless debugging text states", error);
            return null;
        }
    }

    private static void invalidateText(View view) {
        if (view instanceof TextView) view.invalidate();
        if (view instanceof ViewGroup group)
            for (int i = 0; i < group.getChildCount(); i++) invalidateText(group.getChildAt(i));
    }
}
