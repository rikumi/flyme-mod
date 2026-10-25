package dev.rikumi.flymemod;

import java.util.Set;

final class NightModeSpecialPackagePolicy {
    private static final Set<String> NATIVE_DARK = Set.of(
            "com.android.dialer", "com.meizu.mznfcpay", "com.android.vending",
            "com.android.settings", "com.android.alarmclock", "com.meizu.media.gallery",
            "com.meizu.filemanager", "com.meizu.media.camera", "com.android.mms",
            "com.android.traceur", "com.meizu.mstore");

    static boolean nativeDark(String pkg) { return NATIVE_DARK.contains(pkg); }
}
