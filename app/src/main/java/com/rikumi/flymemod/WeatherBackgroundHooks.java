package com.rikumi.flymemod;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.RenderEffect;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;

/** Use one GPU filter for video and static frames, independent of window focus. */
final class WeatherBackgroundHooks {
    private static final String BACKGROUND = "com.hy.weather.mz.modules.home.weatherVideo.WeatherBackGroundView";
    private final Map<View, Boolean> backgrounds = new WeakHashMap<>();
    private final Map<SurfaceView, Video> videos = new WeakHashMap<>();
    private final Map<MediaPlayer, Video> players = new WeakHashMap<>();
    private final BiConsumer<String, Throwable> log;
    private final RenderEffect effect;
    private boolean enabled;
    private boolean errorLogged;
    private ContentObserver observer;
    private Context observerContext;
    private Field playerField;

    WeatherBackgroundHooks(BiConsumer<String, Throwable> log) {
        this.log = log;
        ColorMatrix color = new ColorMatrix();
        color.setSaturation(1.25f);
        float brightness = .68f;
        float contrast = 1.12f;
        float scale = brightness * contrast;
        float offset = 127.5f * (1f - contrast) * brightness;
        color.postConcat(new ColorMatrix(new float[]{
                scale, 0, 0, 0, offset, 0, scale, 0, 0, offset,
                0, 0, scale, 0, offset, 0, 0, 0, 1, 0}));
        effect = RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(color));
    }

    void install(SignalHooks.Installer installer) throws ReflectiveOperationException {
        installer.hook(BACKGROUND, "onAttachedToWindow", chain -> {
            Object result = chain.proceed();
            View root = (View) chain.getThisObject();
            if (!isHome(root)) return result;
            if (playerField == null) {
                playerField = root.getClass().getDeclaredField("c");
                playerField.setAccessible(true);
            }
            read(root.getContext());
            boolean tracked = backgrounds.containsKey(root);
            backgrounds.put(root, enabled);
            apply(root);
            if (!tracked) {
                root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                    @Override public void onViewAttachedToWindow(View view) {}
                    @Override public void onViewDetachedFromWindow(View view) {
                        releaseVideo(view);
                        view.removeOnAttachStateChangeListener(this);
                        backgrounds.remove(view);
                    }
                });
            }
            if (observer == null) {
                Context context = root.getContext().getApplicationContext();
                observerContext = context;
                observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
                    @Override public void onChange(boolean selfChange) {
                        read(context);
                        for (View view : new ArrayList<>(backgrounds.keySet())) {
                            if (view != null && view.isAttachedToWindow()) apply(view);
                        }
                    }
                };
                context.getContentResolver().registerContentObserver(ModuleSettings.URI, false, observer);
            }
            return result;
        });
        installer.hook("android.media.MediaPlayer", "setDisplay", chain -> {
            MediaPlayer player = (MediaPlayer) chain.getThisObject();
            SurfaceHolder holder = (SurfaceHolder) chain.getArg(0);
            Video video = null;
            if (holder != null) {
                synchronized (videos) {
                    for (Video candidate : videos.values()) {
                        if (candidate.original.getHolder() == holder) { video = candidate; break; }
                    }
                }
            } else {
                synchronized (players) { video = players.remove(player); }
                if (video != null) {
                    if (video.player != null && video.player.get() == player) video.player = null;
                    player.setSurface(null);
                }
                return chain.proceed();
            }
            if (video == null) return chain.proceed();
            synchronized (players) { players.put(player, video); }
            video.bind(player);
            return null;
        }, SurfaceHolder.class);
        installer.hook("android.media.MediaPlayer", "release", chain -> {
            MediaPlayer player = (MediaPlayer) chain.getThisObject();
            Video video;
            synchronized (players) { video = players.remove(player); }
            if (video != null && video.player != null && video.player.get() == player) video.player = null;
            return chain.proceed();
        });
        installer.hook("android.view.SurfaceView", "setAlpha", chain -> {
            Object result = chain.proceed();
            Video video = video((SurfaceView) chain.getThisObject());
            if (video != null) video.texture.setAlpha(video.original.getAlpha());
            return result;
        }, float.class);
        installer.hook("android.view.SurfaceView", "updateSurface", chain -> {
            Object result = chain.proceed();
            SurfaceView view = (SurfaceView) chain.getThisObject();
            if (video(view) != null) hideOriginal(view);
            return result;
        });
        // Retain SurfaceHolder callbacks for the stock player lifecycle, but do not
        // punch a transparent hole for the unused native video layer.
        for (String method : new String[]{"draw", "dispatchDraw"}) {
            installer.hook("android.view.SurfaceView", method, chain ->
                    video((SurfaceView) chain.getThisObject()) != null ? null : chain.proceed(), Canvas.class);
        }
    }

    private Video video(SurfaceView view) {
        synchronized (videos) { return videos.get(view); }
    }

    private boolean isHome(View view) {
        Context context = view.getContext();
        while (context instanceof ContextWrapper wrapper && !(context instanceof Activity)) {
            Context base = wrapper.getBaseContext();
            if (base == context) break;
            context = base;
        }
        return "com.hy.weather.mz.modules.home.WeatherMainActivity".equals(context.getClass().getName());
    }

    private void apply(View root) {
        applyFrames(root, root);
    }

    private void applyFrames(View view, View root) {
        if (view instanceof SurfaceView original) {
            Video video = video(original);
            if (enabled && video == null && original.getParent() instanceof ViewGroup parent) {
                video = new Video(original);
                synchronized (videos) { videos.put(original, video); }
                // Insert below the existing transition masks, preserving their order.
                ViewGroup.LayoutParams layout = original.getLayoutParams();
                parent.addView(video.texture, parent.indexOfChild(original) + 1,
                        new ViewGroup.LayoutParams(layout.width, layout.height));
                original.addOnLayoutChangeListener(video.layout);
                try {
                    MediaPlayer player = (MediaPlayer) playerField.get(root);
                    if (player != null) {
                        synchronized (players) { players.put(player, video); }
                        video.bind(player);
                    }
                } catch (ReflectiveOperationException | RuntimeException e) {
                    report("Cannot redirect weather video", e);
                }
                hideOriginal(original);
            }
            if (video != null) video.texture.setRenderEffect(enabled ? effect : null);
        } else if (view instanceof TextureView) {
            // The video texture was configured above, including when disabling.
        } else if (view instanceof ViewGroup group) {
            for (View child : children(group)) applyFrames(child, root);
        } else {
            view.setRenderEffect(enabled ? effect : null);
        }
    }

    private static ArrayList<View> children(ViewGroup group) {
        ArrayList<View> children = new ArrayList<>();
        for (int i = 0; i < group.getChildCount(); i++) children.add(group.getChildAt(i));
        return children;
    }

    private void hideOriginal(SurfaceView view) {
        SurfaceControl surface = view.getSurfaceControl();
        if (surface == null || !surface.isValid()) return;
        try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
            transaction.setVisibility(surface, false).apply();
        } catch (RuntimeException e) { report("Cannot hide redirected weather Surface", e); }
    }

    private void releaseVideo(View view) {
        if (view instanceof SurfaceView original) {
            Video video;
            synchronized (videos) { video = videos.remove(original); }
            if (video != null) video.close();
        } else if (view instanceof ViewGroup group) {
            for (View child : children(group)) releaseVideo(child);
        }
    }

    private final class Video implements TextureView.SurfaceTextureListener {
        final SurfaceView original;
        final TextureView texture;
        volatile WeakReference<MediaPlayer> player;
        Surface output;
        final View.OnLayoutChangeListener layout;

        Video(SurfaceView original) {
            this.original = original;
            texture = new TextureView(original.getContext());
            texture.setOpaque(false);
            texture.setAlpha(original.getAlpha());
            texture.setRenderEffect(effect);
            texture.setSurfaceTextureListener(this);
            layout = (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                ViewGroup.LayoutParams params = texture.getLayoutParams();
                int width = right - left, height = bottom - top;
                if (params != null && width > 0 && height > 0
                        && (params.width != width || params.height != height)) {
                    params.width = width;
                    params.height = height;
                    texture.setLayoutParams(params);
                }
            };
        }

        synchronized void bind(MediaPlayer target) {
            MediaPlayer previous = player == null ? null : player.get();
            if (previous != null && previous != target) {
                synchronized (players) { players.remove(previous); }
            }
            player = new WeakReference<>(target);
            // Null until TextureView is available: never display an unfiltered frame.
            target.setSurface(output);
        }

        @Override public synchronized void onSurfaceTextureAvailable(SurfaceTexture source, int width, int height) {
            if (output != null) output.release();
            output = new Surface(source);
            MediaPlayer target = player == null ? null : player.get();
            if (target != null) {
                try { target.setSurface(output); }
                catch (RuntimeException e) { report("Cannot attach weather video texture", e); }
            }
        }
        @Override public void onSurfaceTextureSizeChanged(SurfaceTexture source, int width, int height) {}
        @Override public void onSurfaceTextureUpdated(SurfaceTexture source) {}
        @Override public synchronized boolean onSurfaceTextureDestroyed(SurfaceTexture source) {
            detach();
            return true;
        }
        private void detach() {
            MediaPlayer target = player == null ? null : player.get();
            if (target != null) {
                try { target.setSurface(null); }
                catch (RuntimeException e) { report("Cannot detach weather video texture", e); }
            }
            if (output != null) { output.release(); output = null; }
        }
        synchronized void close() {
            original.removeOnLayoutChangeListener(layout);
            MediaPlayer target = player == null ? null : player.get();
            if (target != null) synchronized (players) { players.remove(target); }
            detach();
            player = null;
            texture.setSurfaceTextureListener(null);
            if (texture.getParent() instanceof ViewGroup parent) parent.removeView(texture);
        }
    }

    private void read(Context context) {
        try (Cursor cursor = context.getContentResolver().query(ModuleSettings.URI, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(ModuleSettings.WEATHER_DARK_BACKGROUND);
                enabled = column >= 0 && cursor.getInt(column) != 0;
            }
        } catch (RuntimeException e) { report("Cannot read weather background preferences", e); }
    }

    private void report(String message, Throwable error) {
        if (!errorLogged) {
            errorLogged = true;
            log.accept(message, error);
        }
    }
}
