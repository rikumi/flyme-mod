package dev.rikumi.flymemod;

import android.content.SharedPreferences;

/** Notification percentages are actual alpha values, independent of the active theme. */
final class NotificationBackgroundOpacity {
    static final String LIGHT = "notification_light_background_opacity_light";
    static final String DARK = "notification_light_background_opacity_dark";
    static final int LIGHT_DEFAULT = 72, DARK_DEFAULT = 12;
    static int clamp(int value) { return Math.max(0, Math.min(100, value)); }
    static int effective(int light, int dark, boolean night) { return clamp(night ? dark : light); }
    static void initialize(SharedPreferences preferences) {
        if (preferences.contains(LIGHT) && preferences.contains(DARK)) return;
        int legacy = preferences.getInt("notification_light_background_opacity", 12);
        SharedPreferences.Editor editor = preferences.edit();
        // Preserve each theme's exact previous appearance, even values between slider steps.
        if (!preferences.contains(LIGHT)) editor.putInt(LIGHT, LightBackgroundOpacity.effective(legacy, false));
        if (!preferences.contains(DARK)) editor.putInt(DARK, LightBackgroundOpacity.effective(legacy, true));
        editor.commit();
    }
    private NotificationBackgroundOpacity() {}
}
