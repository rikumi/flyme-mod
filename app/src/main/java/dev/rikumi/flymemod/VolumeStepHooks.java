package dev.rikumi.flymemod;

import android.content.Context;
import android.media.AudioManager;
import java.lang.reflect.Field;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/** Flyme physical-key callbacks, before VolumePanel reads the resulting level for display. */
final class VolumeStepHooks {
    private static final String PANEL = "com.android.systemui.volume.VolumePanel";
    // Verified in the captured Flyme framework AudioManager, not generic Android FLAG_FROM_KEY.
    private static final int KEY_UP = 4194304, KEY_DOWN = 8388608;
    private final Consumer<Context> settings;
    private final IntSupplier mode;
    private final Field context, audio;

    VolumeStepHooks(ClassLoader loader, Consumer<Context> settings, IntSupplier mode)
            throws ReflectiveOperationException {
        this.settings = settings;
        this.mode = mode;
        Class<?> panel = loader.loadClass(PANEL);
        context = panel.getField("mContext");
        audio = panel.getField("mAudioManager");
    }

    void install(SignalHooks.Installer installer) {
        installer.hook(PANEL, "postVolumeChanged", chain -> {
            Object panel = chain.getThisObject();
            settings.accept((Context) context.get(panel));
            int flags = (Integer) chain.getArg(1);
            int stream = (Integer) chain.getArg(0);
            int direction = (flags & KEY_UP) != 0 ? 1 : (flags & KEY_DOWN) != 0 ? -1 : 0;
            int selected = mode.getAsInt();
            if (selected != 0 && direction != 0 && stream >= 0 && stream <= 11) {
                AudioManager manager = (AudioManager) audio.get(panel);
                // Voice-call streams with a nonzero minimum, remote playback and per-app volume
                // have different ranges. Keep their original behavior.
                if (manager.getStreamMinVolume(stream) == 0 && manager.getStreamMaxVolume(stream) >= 4) {
                    int current = manager.getStreamVolume(stream);
                    int target = mergedLevel(current, direction, selected);
                    if (target != current) {
                        // Do not carry key flags into this correction: its callback must not
                        // generate another key step. Original callback still updates the panel.
                        manager.setStreamVolume(stream, target, 0);
                    }
                }
            }
            return chain.proceed();
        }, int.class, int.class);
    }

    static int mergedLevel(int current, int direction, int mode) {
        if (current <= 0 || current >= 4 || mode == 0) return current;
        int step = mode == 1 ? 2 : 4;
        return direction > 0 ? ((current + step - 1) / step) * step : (current / step) * step;
    }
}
