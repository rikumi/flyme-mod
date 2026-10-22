package dev.rikumi.flymemod;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

/** SystemUI 16260625: disable browser-driven discovery; keep passive session playback updates. */
final class MediaStartupHooks {
    private static final String PIPE = "com.android.systemui.media.controls.domain.pipeline.";
    private static final String RESUME = "com.android.systemui.media.controls.domain.resume.";
    private final XposedInit module;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final PlayedMediaHistory history = new PlayedMediaHistory();
    private final Map<Object, Map<String, Object[]>> entries = new WeakHashMap<>();
    private final Set<Object> browsers = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private Context context;
    private Method user, pkg, token, song, artist;
    private boolean internalRemoval;
    private Method disconnect;
    private final SharedPreferences.OnSharedPreferenceChangeListener changes = (preferences, key) -> {
        if (key == null || ModuleSettings.FIX_MEDIA_STARTUP.equals(key)) main.post(this::refresh);
    };

    MediaStartupHooks(XposedInit module) {
        this.module = module;
        prefs = module.getRemotePreferences(CameraSettingsBridge.REMOTE_FILE);
    }
    private boolean enabled() { return prefs.getBoolean(ModuleSettings.FIX_MEDIA_STARTUP, false); }

    void install(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> data = loader.loadClass("com.android.systemui.media.controls.shared.model.MediaData");
        user = data.getMethod("getUserId"); pkg = data.getMethod("getPackageName");
        token = data.getMethod("getToken"); song = data.getMethod("getSong"); artist = data.getMethod("getArtist");
        Class<?> browser = loader.loadClass(RESUME + "ResumeMediaBrowser");
        disconnect = browser.getDeclaredMethod("disconnect");
        Method connect = browser.getDeclaredMethod("connectBrowser", MediaBrowser.class, String.class);
        module.hook(connect).intercept(chain -> {
            browsers.add(chain.getThisObject());
            if (enabled()) { disconnect.invoke(chain.getThisObject()); return null; }
            return chain.proceed();
        });
        module.deoptimize(connect);
        Class<?> resume = loader.loadClass(RESUME + "MediaResumeListener");
        block(resume.getDeclaredMethod("loadMediaResumptionControls"));
        block(resume.getDeclaredMethod("onMediaDataLoaded", String.class, String.class,
                data, boolean.class, int.class, boolean.class));
        block(resume.getDeclaredMethod("tryUpdateResumptionList", String.class, ComponentName.class));
        // Also block callbacks already queued before the switch changed.
        for (String name : new String[]{"LegacyMediaDataManagerImpl", "MediaDataProcessor"}) {
            Class<?> processor = loader.loadClass(PIPE + name);
            block(processor.getDeclaredMethod("addResumptionControls", int.class, MediaDescription.class,
                    Runnable.class, MediaSession.Token.class, String.class, PendingIntent.class, String.class));
            Field ctx = processor.getDeclaredField("context"); ctx.setAccessible(true);
            Method notify = processor.getDeclaredMethod("notifyMediaDataLoaded", String.class, String.class, data);
            module.hook(notify).intercept(chain -> {
                context = (Context) ctx.get(chain.getThisObject());
                // Record before downstream filters, including when the option is currently off.
                allowed(chain.getArg(2));
                return chain.proceed();
            });
            module.deoptimize(notify);
        }
        for (String name : new String[]{"LegacyMediaDataFilterImpl", "MediaDataFilterImpl"}) {
            Class<?> filter = loader.loadClass(PIPE + name);
            Method loaded = filter.getDeclaredMethod("onMediaDataLoaded", String.class, String.class,
                    data, boolean.class, int.class, boolean.class);
            Method removed = filter.getDeclaredMethod("onMediaDataRemoved", String.class, boolean.class);
            module.hook(loaded).intercept(chain -> {
                Object owner = chain.getThisObject();
                String key = (String) chain.getArg(0), old = (String) chain.getArg(1);
                Map<String, Object[]> current = entries.computeIfAbsent(owner, ignored -> new LinkedHashMap<>());
                if (old != null && !old.equals(key)) current.remove(old);
                Object[] args = new Object[]{key, old, chain.getArg(2), chain.getArg(3), chain.getArg(4), chain.getArg(5)};
                current.put(key, args);
                if (allowed(args[2])) return chain.proceed();
                internalRemoval = true;
                try {
                    if (old != null && !old.equals(key)) removed.invoke(owner, old, false);
                    removed.invoke(owner, key, false);
                } finally { internalRemoval = false; }
                return null;
            });
            module.hook(removed).intercept(chain -> {
                if (!internalRemoval) {
                    Map<String, Object[]> current = entries.get(chain.getThisObject());
                    if (current != null) current.remove((String) chain.getArg(0));
                }
                return chain.proceed();
            });
            module.deoptimize(loaded); module.deoptimize(removed);
        }
        prefs.registerOnSharedPreferenceChangeListener(changes);
        module.log(Log.INFO, "FlymeMod", "Media startup query guards installed", null);
    }
    private void block(Method method) {
        module.hook(method).intercept(chain -> enabled() ? null : chain.proceed());
        module.deoptimize(method);
    }
    private boolean allowed(Object data) throws ReflectiveOperationException {
        MediaSession.Token session = (MediaSession.Token) token.invoke(data);
        String title = Objects.toString(song.invoke(data), ""), author = Objects.toString(artist.invoke(data), "");
        String mediaId = "";
        long duration = 0;
        boolean playing = false;
        if (session != null && context != null) {
            try {
                MediaController controller = new MediaController(context, session);
                PlaybackState state = controller.getPlaybackState();
                MediaMetadata metadata = controller.getMetadata();
                CharSequence currentTitle = metadata == null ? null : metadata.getText(MediaMetadata.METADATA_KEY_TITLE);
                if (metadata != null) {
                    mediaId = Objects.toString(metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID), "");
                    duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
                }
                // Never credit a stale displayed track using another track's PLAYING state.
                playing = state != null && state.getState() == PlaybackState.STATE_PLAYING
                        && (currentTitle == null || title.contentEquals(currentTitle));
            } catch (RuntimeException ignored) { /* A dead session supplies no playback evidence. */ }
        }
        List<?> track = Arrays.asList(user.invoke(data), pkg.invoke(data), session, title, author, mediaId, duration);
        return history.allow(track, playing, enabled());
    }
    private void refresh() {
        try {
            if (enabled()) {
                List<Object> snapshot;
                synchronized (browsers) { snapshot = new ArrayList<>(browsers); }
                for (Object browser : snapshot) disconnect.invoke(browser);
            }
            // Re-evaluate existing cards immediately, retaining upstream session/timeout listeners.
            for (Map.Entry<Object, Map<String, Object[]>> entry : new ArrayList<>(entries.entrySet())) {
                Object filter = entry.getKey();
                if (filter == null) continue;
                for (Object[] args : new ArrayList<>(entry.getValue().values())) {
                    Method loaded = filter.getClass().getMethod("onMediaDataLoaded", String.class, String.class,
                            args[2].getClass(), boolean.class, int.class, boolean.class);
                    loaded.invoke(filter, args);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            module.log(Log.ERROR, "FlymeMod", "Cannot refresh media startup filtering", error);
        }
    }
}
