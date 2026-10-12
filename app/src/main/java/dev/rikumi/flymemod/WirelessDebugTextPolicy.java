package dev.rikumi.flymemod;

/** Only neutral, unreadably dark text in the verified wireless-debugging windows. */
final class WirelessDebugTextPolicy {
    static boolean pageIconPreference(String key) {
        return "adb_pair_method_qrcode_pref".equals(key) || "adb_pair_method_code_pref".equals(key);
    }
    static boolean pairingDialog(String packageName, String className) {
        return "com.android.settings".equals(packageName)
                && "com.android.settings.development.AdbWirelessDialog".equals(className);
    }

    static boolean authorizationActivity(String packageName, String className) {
        return "com.android.systemui".equals(packageName)
                && ("com.android.systemui.wifi.WifiDebuggingActivity".equals(className)
                || "com.android.systemui.wifi.WifiDebuggingSecondaryUserActivity".equals(className));
    }

    static int readable(int color, int foreground) {
        int r = color >> 16 & 255, g = color >> 8 & 255, b = color & 255;
        int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        // Keep alpha (including disabled states), colored errors/accents and already light text.
        if ((color >>> 24) == 0 || max > 120 || max - min > 12) return color;
        return (color & 0xff000000) | (foreground & 0xffffff);
    }
}
