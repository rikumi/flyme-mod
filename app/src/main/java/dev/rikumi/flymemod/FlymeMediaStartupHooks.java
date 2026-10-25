package dev.rikumi.flymemod;

import android.content.SharedPreferences;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

final class FlymeMediaStartupHooks {
    private static final String QS = "com.flyme.systemui.qs.media.QsMediaController";
    private final XposedInit module;
    private final SharedPreferences prefs;
    private final PlayedMediaHistory history = new PlayedMediaHistory();
    private final Map<Object, Object> owners = new WeakHashMap<>();
    private final Map<Object, Boolean> hidden = new WeakHashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Field current;
    private Method getView, clearView, refresh;
    private final SharedPreferences.OnSharedPreferenceChangeListener changes = (p, key) -> {
        if (key == null || ModuleSettings.FIX_MEDIA_STARTUP.equals(key)) main.post(this::refreshAll);
    };
    FlymeMediaStartupHooks(XposedInit module) {
        this.module = module;
        prefs = module.getRemotePreferences(CameraSettingsBridge.REMOTE_FILE);
    }
    private boolean enabled() { return prefs.getBoolean(ModuleSettings.FIX_MEDIA_STARTUP, false); }

    void install(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> qs = loader.loadClass(QS);
        Class<?> view = loader.loadClass("com.flyme.systemui.qs.media.QsMediaView");
        Class<?> data = loader.loadClass(QS + "$QsMediaData");
        current = qs.getDeclaredField("mMediaController"); current.setAccessible(true);
        getView = qs.getDeclaredMethod("getView");
        clearView = view.getDeclaredMethod("setMediaDataRemoved");
        refresh = qs.getDeclaredMethod("refreshUI", String.class);
        Method init = qs.getDeclaredMethod("initMedia", MediaController.class, String.class);
        Method getData = qs.getDeclaredMethod("getQsMediaData", boolean.class);
        Method render = view.getDeclaredMethod("refreshData", data, String.class);
        Method buttons = qs.getDeclaredMethod("updateButtonColor", boolean.class);
        Method playingIcon = qs.getDeclaredMethod("updatePlayingIcon", boolean.class);
        module.hook(init).intercept(chain -> {
            Object owner = chain.getThisObject();
            Object surface = getView.invoke(owner);
            if (surface != null) owners.put(surface, owner);
            return chain.proceed(); // Keep metadata and playback callbacks registered.
        });
        module.hook(getData).intercept(chain -> {
            Object owner = chain.getThisObject();
            Object surface = getView.invoke(owner);
            if (surface != null) owners.put(surface, owner);
            if (!allowed(owner)) {
                hide(owner, surface);
                return null;
            }
            hidden.remove(owner);
            return chain.proceed();
        });
        // The view entry also covers compiled/inlined refresh runnables.
        module.hook(render).intercept(chain -> {
            Object surface = chain.getThisObject(), owner = owners.get(surface);
            if (owner != null && !allowed(owner)) {
                hide(owner, surface);
                return null;
            }
            return chain.proceed();
        });
        for (Method method : new Method[]{buttons, playingIcon}) {
            module.hook(method).intercept(chain -> {
                if (enabled() && !allowed(chain.getThisObject())) return chain.proceed(new Object[]{false});
                return chain.proceed();
            });
        }
        for (Method method : new Method[]{init, getData, render, buttons, playingIcon}) module.deoptimize(method);
        prefs.registerOnSharedPreferenceChangeListener(changes);
        module.log(Log.INFO, "FlymeMod", "Flyme QS direct-session media guard installed", null);
    }
    private boolean allowed(Object owner) throws IllegalAccessException {
        MediaController controller = (MediaController) current.get(owner);
        if (controller == null) return !enabled();
        try {
            MediaMetadata metadata = controller.getMetadata();
            PlaybackState state = controller.getPlaybackState();
            String title = "", artist = "", id = "";
            long duration = 0;
            if (metadata != null) {
                title = Objects.toString(metadata.getString(MediaMetadata.METADATA_KEY_TITLE), "");
                artist = Objects.toString(metadata.getString(MediaMetadata.METADATA_KEY_ARTIST), "");
                id = Objects.toString(metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID), "");
                duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
            }
            List<?> track = Arrays.asList(controller.getPackageName(), controller.getSessionToken(), title, artist, id, duration);
            return history.allow(track, state != null && state.getState() == PlaybackState.STATE_PLAYING, enabled());
        } catch (RuntimeException error) {
            return !enabled(); // Dead sessions cannot establish playback history.
        }
    }
    private void hide(Object owner, Object surface) throws ReflectiveOperationException {
        // Clearing the view alone preserves session callbacks; resetCurrentController would lose them.
        if (surface != null) clearView.invoke(surface);
        if (hidden.put(owner, true) == null) {
            module.log(Log.INFO, "FlymeMod", "Hidden unplayed Flyme QS session metadata", null);
        }
    }
    private void refreshAll() {
        try {
            for (Object owner : new HashSet<>(owners.values())) {
                Object surface = getView.invoke(owner);
                if (!allowed(owner)) hide(owner, surface);
                refresh.invoke(owner, "module media visibility changed");
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            module.log(Log.ERROR, "FlymeMod", "Cannot refresh direct-session media visibility", error);
        }
    }
}
