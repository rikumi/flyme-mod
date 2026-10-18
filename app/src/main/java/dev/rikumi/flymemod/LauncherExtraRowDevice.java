package dev.rikumi.flymemod;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.view.Display;

final class LauncherExtraRowDevice {
    static boolean isSupported(Context context) {
        DisplayManager manager = context.getSystemService(DisplayManager.class);
        Display display = manager == null ? null : manager.getDisplay(Display.DEFAULT_DISPLAY);
        if (display == null) return false;
        Display.Mode mode = display.getMode();
        return LauncherExtraRowPolicy.isSupportedSize(mode.getPhysicalWidth(), mode.getPhysicalHeight());
    }
}
