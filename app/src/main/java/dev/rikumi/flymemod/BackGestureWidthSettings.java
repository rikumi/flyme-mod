package dev.rikumi.flymemod;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.provider.Settings;

/** Freeze the old effective 1x width in dp once, before publishing settings to either process. */
final class BackGestureWidthSettings {
    static synchronized void initialize(Context context, SharedPreferences preferences) {
        if (preferences.contains(ModuleSettings.BACK_GESTURE_WIDTH_DP)
                && preferences.contains(ModuleSettings.BACK_GESTURE_WIDTH_MAX_DP)) return;
        if (preferences.contains("back_gesture_width_dp_tenths")
                && preferences.contains("back_gesture_width_max_dp_tenths")) {
            int maximum = Math.max(1, Math.round(preferences.getInt("back_gesture_width_max_dp_tenths", 0) / 10f));
            int value = Math.max(0, Math.min(maximum,
                    Math.round(preferences.getInt("back_gesture_width_dp_tenths", 0) / 10f)));
            preferences.edit().putInt(ModuleSettings.BACK_GESTURE_WIDTH_DP, value)
                    .putInt(ModuleSettings.BACK_GESTURE_WIDTH_MAX_DP, maximum)
                    .remove("back_gesture_width_dp_tenths")
                    .remove("back_gesture_width_max_dp_tenths").commit();
            return;
        }
        Resources resources = context.getResources();
        float density = resources.getDisplayMetrics().density;
        // IDs and names verified against the target Flyme framework.
        int servicePixels = resources.getDimensionPixelSize(dimension(resources,
                "left_right_swip_gesture_valid_offset", 0x01050404));
        float insetDp = resources.getDimension(dimension(resources,
                "config_backGestureInset", 0x010500db)) / density;
        if (insetDp > 0) insetDp = deviceConfigWidth(insetDp);
        int left = (int) (insetDp * density * sensitivity(context, "left"));
        int right = (int) (insetDp * density * sensitivity(context, "right"));
        float nativePixels = Math.max(0, Math.min(servicePixels, Math.min(left, right)));
        float base = nativePixels / density;
        int maximum = Math.max(1, Math.round(base * 2));
        int oldScale = Math.max(0, Math.min(20,
                preferences.getInt("back_gesture_width_scale_tenths", 10)));
        int migrated = Math.min(maximum, Math.round(base * oldScale / 10f));
        preferences.edit()
                .putInt(ModuleSettings.BACK_GESTURE_WIDTH_DP, migrated)
                .putInt(ModuleSettings.BACK_GESTURE_WIDTH_MAX_DP, maximum)
                .remove("back_gesture_width_scale_tenths")
                .commit();
    }

    private static int dimension(Resources resources, String name, int fallbackId) {
        int id = resources.getIdentifier(name, "dimen", "android");
        return id != 0 ? id : fallbackId;
    }

    private static float sensitivity(Context context, String side) {
        return Settings.Secure.getFloat(context.getContentResolver(), "back_gesture_inset_scale_" + side, 1f);
    }

    private static float deviceConfigWidth(float fallback) {
        try {
            return (Float) Class.forName("android.provider.DeviceConfig")
                    .getMethod("getFloat", String.class, String.class, float.class)
                    .invoke(null, "systemui", "back_gesture_edge_width", fallback);
        } catch (ReflectiveOperationException | SecurityException error) {
            return fallback;
        }
    }
}
