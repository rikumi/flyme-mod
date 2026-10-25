package dev.rikumi.flymemod;

import android.content.Context;
import android.os.Bundle;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

final class LauncherFolderMetrics {
    static final String METHOD = "save_launcher_folder_metrics";
    static final String PREFIX = "hidden_folder_metric_";
    static final String[] FIELDS = {"folderChildIconSizePx", "folderChildTextSizePx",
            "folderChildDrawablePaddingPx", "folderCellWidthPx", "folderCellHeightPx"};
    private static final Executor WRITER = Executors.newSingleThreadExecutor();
    private static final AtomicLong REVISION = new AtomicLong();
    private static Bundle published;

    static void publish(Context launcher, ClassLoader loader) throws ReflectiveOperationException {
        Bundle metrics = snapshot(launcher, loader);
        publishSnapshot(launcher, metrics, REVISION.incrementAndGet());
    }

    static void publishAsync(Context launcher, ClassLoader loader, BiConsumer<String, Throwable> log)
            throws ReflectiveOperationException {

        Bundle metrics = snapshot(launcher, loader);
        long revision = REVISION.incrementAndGet();
        Context application = launcher.getApplicationContext();
        WRITER.execute(() -> {
            try { publishSnapshot(application, metrics, revision); }
            catch (RuntimeException error) { log.accept("Cannot save launcher folder layout", error); }
        });
    }

    private static Bundle snapshot(Context launcher, ClassLoader loader) throws ReflectiveOperationException {
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
        android.graphics.Point cell=(android.graphics.Point)type.getMethod("getCellSize").invoke(profile);
        Object flymeProfile=type.getMethod("getFlymeDp").invoke(profile);
        int horizontalPadding=(Integer)flymeProfile.getClass().getMethod("getMzGridCellPaddingLeftRight").invoke(flymeProfile);
        metrics.putFloat("textWidth",Math.max(1,cell.x-2*horizontalPadding)/density);
        return metrics;
    }

    private static synchronized void publishSnapshot(Context context, Bundle metrics, long revision) {
        // An explicit hidden-apps launch may publish newer metrics before a queued resume.
        if (revision != REVISION.get()) return;
        if (published != null) {
            boolean unchanged = true;
            for (String key : metrics.keySet()) {
                if (Float.compare(metrics.getFloat(key), published.getFloat(key)) != 0) {
                    unchanged = false;
                    break;
                }
            }
            if (unchanged) return;
        }
        context.getContentResolver().call(ModuleSettings.URI, METHOD, null, metrics);
        // A failed call must remain retryable on the next resume.
        published = metrics;
    }
}
