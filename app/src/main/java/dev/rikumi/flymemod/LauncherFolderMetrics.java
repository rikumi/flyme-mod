package dev.rikumi.flymemod;

import android.content.Context;
import android.os.Bundle;

/** Snapshot the actual folder profile for the module's hidden-app grid. */
final class LauncherFolderMetrics {
    static final String METHOD = "save_launcher_folder_metrics";
    static final String PREFIX = "hidden_folder_metric_";
    static final String[] FIELDS = {"folderChildIconSizePx", "folderChildTextSizePx",
            "folderChildDrawablePaddingPx", "folderCellWidthPx", "folderCellHeightPx"};

    static void publish(Context launcher, ClassLoader loader) throws ReflectiveOperationException {
        Object profile = loader.loadClass("com.android.launcher3.Launcher")
                .getMethod("getDeviceProfile").invoke(launcher);
        Class<?> type = loader.loadClass("com.android.launcher3.DeviceProfile");
        float density = launcher.getResources().getDisplayMetrics().density;
        Bundle metrics = new Bundle();
        for (String name : FIELDS) metrics.putFloat(name, type.getField(name).getInt(profile) / density);
        int top = (Integer) loader.loadClass("com.meizu.flyme.launcher.deviceprofile.FlymeDeviceProfile")
                .getMethod("calcFolderAppPaddingTop", Context.class).invoke(null, launcher);
        metrics.putFloat("top", top / density);
        metrics.putFloat("side", type.getField("edgeMarginPx").getInt(profile) / density / 2f);
        launcher.getContentResolver().call(ModuleSettings.URI, METHOD, null, metrics);
    }
}
