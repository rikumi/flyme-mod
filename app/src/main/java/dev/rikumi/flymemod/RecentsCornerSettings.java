package dev.rikumi.flymemod;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.RoundedCorner;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;

final class RecentsCornerSettings {
    static int readHardwareDp(Context context) {
        try {
            WindowManager manager = context.getSystemService(WindowManager.class);
            if (manager == null) return 0;
            WindowMetrics metrics = manager.getMaximumWindowMetrics();
            WindowInsets insets = metrics.getWindowInsets();
            int radius = Integer.MAX_VALUE;
            for (int position = 0; position < 4; position++) {
                RoundedCorner corner = insets.getRoundedCorner(position);
                if (corner != null && corner.getRadius() > 0) radius = Math.min(radius, corner.getRadius());
            }

            return radius == Integer.MAX_VALUE ? 0
                    : RecentsCornerGeometry.hardwareDefault(radius, metrics.getDensity());
        } catch (RuntimeException error) { return 0; }
    }
    static synchronized void initialize(Context context, SharedPreferences preferences) {
        if (preferences.contains(ModuleSettings.RECENTS_CORNER_DP)) return;
        int value = readHardwareDp(context);
        // Do not permanently replace missing system information with a claimed hardware default.
        if (value > 0) preferences.edit().putInt(ModuleSettings.RECENTS_CORNER_DP, value).commit();
    }
    static int radiusDp(Context context, SharedPreferences preferences) {
        initialize(context, preferences);
        // Missing hardware data temporarily uses the slider's lower bound, and is retried next time.
        return RecentsCornerGeometry.clamp(preferences.getInt(ModuleSettings.RECENTS_CORNER_DP,
                RecentsCornerGeometry.MIN_DP));
    }
}
