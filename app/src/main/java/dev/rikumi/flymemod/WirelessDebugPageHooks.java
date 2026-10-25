package dev.rikumi.flymemod;

import android.content.SharedPreferences;
import android.view.View;
import android.widget.ImageView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.lang.reflect.Executable;

final class WirelessDebugPageHooks {
    private final SharedPreferences preferences;
    private final BiConsumer<String, Throwable> log;
    private final Map<ImageView, Boolean> icons = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<View, Integer> pairedRows = Collections.synchronizedMap(new WeakHashMap<>());
    private volatile boolean enabled;
    private final SharedPreferences.OnSharedPreferenceChangeListener changes = (prefs, key) -> {
        if (key == null || ModuleSettings.WIRELESS_DEBUG_THEME_FIX.equals(key)) read();
    };

    WirelessDebugPageHooks(SharedPreferences preferences, BiConsumer<String, Throwable> log) {
        this.preferences = preferences;
        this.log = log;
    }

    private void read() {
        enabled = preferences.getBoolean(ModuleSettings.WIRELESS_DEBUG_THEME_FIX, false);
        synchronized (icons) {
            for (ImageView icon : icons.keySet()) icon.post(icon::invalidate);
        }
        synchronized (pairedRows) {
            for (View row : pairedRows.keySet()) row.post(() -> updateSpacing(row));
        }
    }

    void install(ClassLoader loader, ThemeColorHooks.Installer installer, Consumer<Executable> deoptimizer) {
        read();
        preferences.registerOnSharedPreferenceChangeListener(changes);
        try {
            Class<?> preference = Class.forName("androidx.preference.Preference", false, loader);
            Class<?> holder = Class.forName("androidx.preference.PreferenceViewHolder", false, loader);
            Field itemView = holder.getField("itemView");
            Field key = preference.getField("mKey");
            installer.hook(preference.getDeclaredMethod("onBindViewHolder", holder), chain -> {
                Object result = chain.proceed();
                View row = (View) itemView.get(chain.getArg(0));
                // A RecyclerView row may next represent an unrelated preference.
                clear(row);
                String name = (String) key.get(chain.getThisObject());
                if (WirelessDebugTextPolicy.pageIconPreference(name)) mark(row.findViewById(android.R.id.icon));
                return result;
            });
            Class<?> paired = Class.forName("com.android.settings.development.AdbPairedDevicePreference", false, loader);
            installer.hook(paired.getDeclaredMethod("onBindViewHolder", holder), chain -> {
                Object result = chain.proceed();
                View row = (View) itemView.get(chain.getArg(0));
                int id = row.getResources().getIdentifier("settings_button_no_background", "id", "com.android.settings");
                if (id != 0) mark(row.findViewById(id));
                pairedRows.putIfAbsent(row, row.getPaddingEnd());
                updateSpacing(row);
                return result;
            });

            deoptimizer.accept(paired.getDeclaredMethod("onBindViewHolder", holder));
            deoptimizer.accept(Class.forName("androidx.preference.PreferenceGroupAdapter", false, loader)
                    .getDeclaredMethod("onBindViewHolder", holder, int.class));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log.accept("Cannot resolve wireless debugging preference icons", error);
        }
    }

    private void mark(View view) {
        if (view instanceof ImageView icon) icons.put(icon, Boolean.TRUE);
    }

    private void clear(View row) {
        synchronized (icons) {
            icons.keySet().removeIf(icon -> {
                for (View view = icon; view != null; view = view.getParent() instanceof View parent ? parent : null)
                    if (view == row) return true;
                return false;
            });
        }
        Integer original = pairedRows.remove(row);
        if (original != null) row.setPaddingRelative(row.getPaddingStart(), row.getPaddingTop(), original, row.getPaddingBottom());
    }

    private void updateSpacing(View row) {
        Integer original = pairedRows.get(row);
        if (original == null) return;
        int end = enabled ? Math.min(original, Math.round(16 * row.getResources().getDisplayMetrics().density)) : original;
        if (row.getPaddingEnd() != end)
            row.setPaddingRelative(row.getPaddingStart(), row.getPaddingTop(), end, row.getPaddingBottom());
    }

    android.graphics.Paint iconPaint(ImageView view) { return null; }
}
