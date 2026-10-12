package dev.rikumi.flymemod;

import android.content.SharedPreferences;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Camera 12.7.2: prepend cached frames before the native 1.6 s replay window. */
final class CameraLivePhotoHooks {
    private static final String LIVE = "com.meizu.media.camera.livephoto.";
    private static final long NATIVE_WINDOW_NS = 1_600_000_000L;
    private final SharedPreferences settings;
    private final BiConsumer<String, Throwable> log;
    private Class<?> plain, picker, preview, video, record, buffer, device;
    private Method recordImage;
    private Object flush;
    private final Map<Object, State> states = Collections.synchronizedMap(new WeakHashMap<>());
    private static final class State {
        final int ms;
        int preFrames;
        State(int ms) { this.ms = ms; }
    }

    CameraLivePhotoHooks(SharedPreferences settings, BiConsumer<String, Throwable> log) {
        this.settings = settings;
        this.log = log;
    }

    private int extraMs() {
        return settings.getBoolean(ModuleSettings.CAMERA_LIVE_PREROLL_ENABLED, false)
                ? ModuleSettings.cameraLivePrerollMs(settings.getInt(ModuleSettings.CAMERA_LIVE_PREROLL_MS,
                        ModuleSettings.CAMERA_LIVE_PREROLL_DEFAULT)) : 0;
    }

    void install(ClassLoader loader, SignalHooks.Installer installer, Consumer<Method> deoptimize) {
        try {
            plain = loader.loadClass(LIVE + "MzLivePhotoHandler");
            video = loader.loadClass(LIVE + "LiveVideoProcessor");
            record = loader.loadClass(LIVE + "RecordBytes");
            preview = loader.loadClass(LIVE + "PreviewLivePhotoProcess");
            picker = loader.loadClass("com.meizu.camera.effectlib.reprocess.PreviewDataPicker");
            buffer = loader.loadClass("com.meizu.camera.effectlib.reprocess.entity.RecyclerHardwareBuffer");
            device = loader.loadClass("com.meizu.media.camera.util.DeviceHelper");
            Class<?> filter = loader.loadClass("com.meizu.camera.effectlib.effects.views.g$b");
            Class<?> function = loader.loadClass("kotlin.jvm.functions.Function0");
            Object unit = loader.loadClass("kotlin.Unit").getField("INSTANCE").get(null);
            flush = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{function},
                    (proxy, method, args) -> "invoke".equals(method.getName()) ? unit : null);
            recordImage = video.getDeclaredMethod("recordImage", record, boolean.class, boolean.class, function);
            // Resolve all fields before installing hooks; raw DEX names are retained here.
            for (String field : new String[]{"mVideoByteQueue", "mLiveVideoProcessorList", "filterRender",
                    "mVideoWidth", "mVideoHeight", "mIsMirrorChanged"}) field(plain, field);
            for (String field : new String[]{"mPreviewBufferQueue", "processorList", "filterRender"}) field(picker, field);
            field(preview, "videoProcessor");
            field(video, "mAfterCaptureSavedFrameCount");
            field(device, "P3");
            installCapacity(plain, record, installer, deoptimize, true);
            installCapacity(picker, buffer, installer, deoptimize, true);
            installCapacity(loader.loadClass("com.meizu.media.camera.previewrecord.AudioDataPicker"),
                    record, installer, deoptimize, false);
            hook(video, "start", installer, chain -> {
                // Freeze the setting for each photo, including concurrent captures.
                states.put(chain.getThisObject(), new State(extraMs()));
                return chain.proceed();
            }, String.class, int.class, int.class, int.class, int.class, int.class);
            hook(plain, "onLivePhotoTaken", installer, chain -> {
                try {
                    if (Objects.equals(get(plain, "filterRender", null), chain.getArg(5))
                            && ((Integer) get(plain, "mVideoWidth", null)).intValue() == (Integer) chain.getArg(3)
                            && ((Integer) get(plain, "mVideoHeight", null)).intValue() == (Integer) chain.getArg(4)
                            && !((AtomicBoolean) get(plain, "mIsMirrorChanged", null)).get()) {
                        replayPlain();
                    }
                } catch (ReflectiveOperationException | RuntimeException error) {
                    log.accept("Cannot prepend live photo frames", error);
                }
                return chain.proceed();
            }, byte[].class, long.class, long.class, int.class, int.class, filter);
            hook(picker, "onPreviewDataCapture", installer, chain -> {
                try {
                    if (Objects.equals(get(picker, "filterRender", null), chain.getArg(1))) replayPreview();
                } catch (ReflectiveOperationException | RuntimeException error) {
                    log.accept("Cannot prepend watermarked live photo frames", error);
                }
                return chain.proceed();
            }, buffer, filter);
            installer.hook(video.getName(), "recordImage", chain -> {
                State state = states.get(chain.getThisObject());
                if (state == null || state.ms == 0) return chain.proceed();
                if (!(Boolean) chain.getArg(1)) {
                    state.preFrames++;
                    return chain.proceed();
                }
                int totalFrames = (Integer) get(device, "P3", null);
                int afterFrames = ((AtomicInteger) get(video, "mAfterCaptureSavedFrameCount",
                        chain.getThisObject())).get() + 1;
                // Both pipelines count real cached frames. Keep the native total budget
                // and shorten the tail by the extra frames actually recorded.
                int tailFrames = tailFrames(totalFrames, state.preFrames);
                if (afterFrames >= tailFrames)
                    return chain.proceed(new Object[]{chain.getArg(0), true, true, chain.getArg(3)});
                return chain.proceed();
            }, recordImage.getParameterTypes());
            deoptimize.accept(loader.loadClass(LIVE + "PreviewLivePhotoProcess$previewWatermarkManager$2$1$1")
                    .getDeclaredMethod("onFrameAvailable", byte[].class, int.class, int.class,
                            long.class, long.class, boolean.class));
            for (Class<?> cls : new Class<?>[]{plain, picker, video, preview}) {
                for (Method method : cls.getDeclaredMethods()) deoptimize.accept(method);
            }
            log.accept("Camera live photo preroll hooks registered", null);
        } catch (ReflectiveOperationException | LinkageError error) {
            log.accept("Cannot resolve camera live photo preroll hooks", error);
        }
    }

    private void installCapacity(Class<?> cls, Class<?> data, SignalHooks.Installer installer,
                                 Consumer<Method> deoptimize, boolean videoQueue) throws ReflectiveOperationException {
        Method add = cls.getDeclaredMethod("addDataToQueue", Queue.class, data, int.class, boolean.class);
        installer.hook(cls.getName(), add.getName(), chain -> {
            int ms = extraMs();
            if (ms == 0) return chain.proceed();
            // Native caches are 30 fps video and 100 audio blocks (~2 s).
            int extra = videoQueue && (Boolean) chain.getArg(3) ? ms * 30 / 1000 : (ms + 19) / 20;
            return chain.proceed(new Object[]{chain.getArg(0), chain.getArg(1),
                    (Integer) chain.getArg(2) + extra, chain.getArg(3)});
        }, add.getParameterTypes());
        deoptimize.accept(add);
    }

    private void replayPlain() throws ReflectiveOperationException {
        Queue<?> queue = (Queue<?>) get(plain, "mVideoByteQueue", null);
        for (Object target : new ArrayList<>((java.util.Collection<?>) get(plain, "mLiveVideoProcessorList", null))) {
            State state = states.get(target);
            if (state == null || state.ms == 0 || !((AtomicBoolean) call(target, "getMIsRecording")).get()
                    || !((AtomicBoolean) call(target, "getMShouldSaveVideoQueueData")).get()) continue;
            for (Object frame : olderFrames(queue, state.ms)) recordImage.invoke(target, frame, false, false, flush);
        }
    }

    private void replayPreview() throws ReflectiveOperationException {
        Queue<?> queue = (Queue<?>) get(picker, "mPreviewBufferQueue", null);
        for (Object target : new ArrayList<>((java.util.Collection<?>) get(picker, "processorList", null))) {
            if (!preview.isInstance(target)) continue;
            State state = states.computeIfAbsent(target, ignored -> new State(extraMs()));
            if (state.ms == 0 || !(Boolean) call(target, "isNeedCacheData")
                    || (Boolean) call(target, "getIsProcessDone")
                    || !((AtomicBoolean) call(target, "getMShouldSaveVideoQueueData")).get()) continue;
            for (Object frame : olderFrames(queue, state.ms)) {
                buffer.getMethod("setAfterCapture", boolean.class).invoke(frame, false);
                if (!(Boolean) preview.getMethod("onPreviewDataTaken", Object.class).invoke(target, frame)) break;
            }
        }
    }

    private java.util.List<Object> olderFrames(Queue<?> queue, int ms) throws ReflectiveOperationException {
        ArrayList<Object> frames = new ArrayList<>(queue);
        if (frames.isEmpty()) return frames;
        long newest = (Long) call(frames.get(frames.size() - 1), "getNanoTime");
        ArrayList<Object> selected = new ArrayList<>();
        for (Object frame : frames) {
            long age = newest - (Long) call(frame, "getNanoTime");
            if (age > NATIVE_WINDOW_NS && age <= NATIVE_WINDOW_NS + ms * 1_000_000L) selected.add(frame);
        }
        return selected;
    }

    static int tailFrames(int totalFrames, int preFrames) {
        return Math.min(totalFrames - 45, Math.max(1, totalFrames - preFrames));
    }

    private static Field field(Class<?> cls, String name) throws NoSuchFieldException {
        Field field = cls.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
    private static Object get(Class<?> cls, String name, Object target) throws ReflectiveOperationException {
        return field(cls, name).get(target);
    }
    private static Object call(Object target, String name) throws ReflectiveOperationException {
        Class<?> cls = target.getClass();
        Method method;
        try { method = cls.getMethod(name); }
        catch (NoSuchMethodException missing) { method = cls.getDeclaredMethod(name); }
        method.setAccessible(true);
        return method.invoke(target);
    }
    private static void hook(Class<?> cls, String name, SignalHooks.Installer installer,
                             io.github.libxposed.api.XposedInterface.Hooker hook, Class<?>... args)
            throws ReflectiveOperationException {
        cls.getDeclaredMethod(name, args);
        installer.hook(cls.getName(), name, hook, args);
    }
}
