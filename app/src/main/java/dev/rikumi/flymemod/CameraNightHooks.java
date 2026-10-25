package dev.rikumi.flymemod;

import android.content.SharedPreferences;
import android.hardware.camera2.CaptureRequest;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

final class CameraNightHooks {
    private final SharedPreferences settings;
    private final BiConsumer<String, Throwable> log;
    private Method mode, setTag, getTag;
    private Object meizu, qualcomm;
    private Method snExposure, evList, priority, priorityExposure;
    private Field defaultFrames, frameCountIndex;
    private final ThreadLocal<Exposure> capture = new ThreadLocal<>();

    private static final class Exposure {
        final long totalMs;
        int frames;
        boolean burst;
        CaptureRequest.Builder builder;
        Exposure(long totalMs, int frames) { this.totalMs = totalMs; this.frames = frames; }
    }

    CameraNightHooks(SharedPreferences settings, BiConsumer<String, Throwable> log) {
        this.settings = settings;
        this.log = log;
    }

    void install(ClassLoader loader, SignalHooks.Installer installer, Consumer<Method> deoptimize) {
        try { installDetection(loader, installer, deoptimize); }
        catch (ReflectiveOperationException | LinkageError error) {
            log.accept("Cannot resolve camera automatic night hooks", error);
        }
        try { installExposure(loader, installer, deoptimize); }
        catch (ReflectiveOperationException | LinkageError error) {
            log.accept("Cannot resolve camera night exposure hooks", error);
        }
    }

    private boolean inMode(String name) throws ReflectiveOperationException {
        return name.equals(((Enum<?>) mode.invoke(null)).name());
    }

    private boolean blockAutoNight() throws ReflectiveOperationException {
        return settings.getBoolean(ModuleSettings.CAMERA_DISABLE_AUTO_NIGHT, false) && inMode("AUTO");
    }

    private void installDetection(ClassLoader loader, SignalHooks.Installer installer,
                                  Consumer<Method> deoptimize) throws ReflectiveOperationException {
        mode = loader.loadClass("com.meizu.media.camera.mode.CameraModeType").getDeclaredMethod("d");
        Class<?> night = loader.loadClass("com.meizu.media.camera.camcontroller.NightSceneUtil");
        Method close = night.getDeclaredMethod("closeNightScene");

        for (Class<?>[] args : new Class<?>[][]{
                {int.class, float.class, float.class, int.class},
                {int.class, int.class, int.class}}) {
            night.getDeclaredMethod("checkNightScene", args);
            installer.hook(night.getName(), "checkNightScene", chain -> {
                if (!blockAutoNight()) return chain.proceed();
                close.invoke(null);
                return null;
            }, args);
        }
        Class<?> ui = loader.loadClass("com.meizu.media.camera.ui.MzCommonUI");
        ui.getDeclaredMethod("handleMetaDataNotify", int.class, int.class);
        installer.hook(ui.getName(), "handleMetaDataNotify", chain -> {
            if (blockAutoNight() && (Integer) chain.getArg(0) == 401)
                return chain.proceed(new Object[]{401, 1});
            return chain.proceed();
        }, int.class, int.class);
        Class<?> metadata = loader.loadClass("com.meizu.media.camera.camcontroller.i0$f1");
        deoptimize.accept(metadata.getDeclaredMethod("x", int.class, float.class, float.class, int.class));
        for (Method method : ui.getDeclaredMethods()) {
            if (java.util.Set.of("onMeizuMetaDataNotify", "onNightDetectionResult", "onNightIconClick")
                    .contains(method.getName())) deoptimize.accept(method);
        }
        log.accept("Camera automatic night hooks registered", null);
    }

    private void installExposure(ClassLoader loader, SignalHooks.Installer installer,
                                 Consumer<Method> deoptimize) throws ReflectiveOperationException {
        Class<?> night = loader.loadClass("com.meizu.media.camera.camcontroller.NightSceneUtil");
        Class<?> controller = loader.loadClass("com.meizu.media.camera.camcontroller.i0");
        Class<?> param = loader.loadClass("com.meizu.media.camera.data.ParamData");
        Class<?> code = loader.loadClass("com.meizu.media.camera.util.Contants$CameraService$RequestCode");
        defaultFrames = loader.loadClass("com.meizu.media.camera.util.DeviceHelper").getDeclaredField("L8");
        defaultFrames.setAccessible(true);
        frameCountIndex = defaultFrames.getDeclaringClass().getDeclaredField("T8");
        frameCountIndex.setAccessible(true);
        Class<?> mz = loader.loadClass("com.meizu.camera.customtag.MeizuTag");
        Class<?> qc = loader.loadClass("com.meizu.camera.customtag.QualCommTag");
        meizu = mz.getField("INSTANCE").get(null);
        qualcomm = qc.getField("INSTANCE").get(null);
        snExposure = mz.getMethod("getSn_exposure_time_request");
        evList = mz.getMethod("getCapture_ev_list_request");
        priority = qc.getMethod("getIso_exp_priority_select_request");
        priorityExposure = qc.getMethod("getIso_exp_priority_use_iso_exp_request");
        setTag = loader.loadClass("com.meizu.media.camera.util.CameraKeyExKt").getDeclaredMethod(
                "setIgnoreExc", CaptureRequest.Builder.class, CaptureRequest.Key.class, Object.class);
        getTag = setTag.getDeclaringClass().getDeclaredMethod("getIgnoreExc",
                CaptureRequest.Builder.class, CaptureRequest.Key.class);
        Class<?>[] args = {param, int.class, int.class, float.class, int.class,
                long.class, UUID.class, code};
        Method makeCapture = controller.getDeclaredMethod("Ma", args);
        Method makeBurst = controller.getDeclaredMethod("I8", CaptureRequest.Builder.class,
                int.class, long.class, int.class, float.class, float[].class, boolean.class);
        night.getDeclaredMethod("getTotalExposureTime", int.class);
        installer.hook(controller.getName(), "Ma", chain -> {
            if (!exposureEnabled()) return chain.proceed();
            Exposure previous = capture.get();
            capture.set(new Exposure(exposureMs(), Math.max(1, defaultFrames.getInt(null))));
            try { return chain.proceed(); }
            finally { if (previous == null) capture.remove(); else capture.set(previous); }
        }, args);
        installer.hook(controller.getName(), "I8", chain -> {
            Exposure state = capture.get();
            if (state == null) return chain.proceed();
            int frames = state.frames;
            boolean burst = state.burst;
            state.frames = Math.max(1, (Integer) chain.getArg(3));
            state.burst = true;
            try { return chain.proceed(); }
            finally { state.frames = frames; state.burst = burst; }
        }, makeBurst.getParameterTypes());
        Method nightRequest = controller.getDeclaredMethod("R8", CaptureRequest.Builder.class,
                boolean.class, boolean.class);
        installer.hook(controller.getName(), "R8", chain -> {
            Object result = chain.proceed();
            Exposure state = capture.get();
            CaptureRequest.Builder builder = (CaptureRequest.Builder) chain.getArg(0);
            if (state != null && builder != null && Integer.valueOf(CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE)
                    .equals(builder.get(CaptureRequest.CONTROL_CAPTURE_INTENT))) state.builder = builder;
            return result;
        }, nightRequest.getParameterTypes());
        installer.hook(CaptureRequest.Builder.class.getName(), "build", chain -> {
            Exposure state = capture.get();
            if (state != null && state.builder == chain.getThisObject()
                    && Integer.valueOf(CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE)
                    .equals(state.builder.get(CaptureRequest.CONTROL_CAPTURE_INTENT))) {
                try { applyExposure((CaptureRequest.Builder) chain.getThisObject(), state); }
                catch (ReflectiveOperationException | RuntimeException error) {
                    log.accept("Cannot apply camera night exposure", error);
                }
            }
            return chain.proceed();
        });
        installer.hook(night.getName(), "getTotalExposureTime", chain ->
                exposureEnabled() ? (long) exposureMs() : chain.proceed(), int.class);
        deoptimize.accept(loader.loadClass("com.meizu.media.camera.camcontroller.g").getDeclaredMethod("run"));
        deoptimize.accept(makeCapture);
        deoptimize.accept(makeBurst);
        for (Method method : night.getDeclaredMethods()) deoptimize.accept(method);
        // Detection gates and request helpers may have been inlined into these callers.
        for (Method method : controller.getDeclaredMethods()) {
            if (java.util.Set.of("v8", "R8", "Fb", "G8", "U9").contains(method.getName()))
                deoptimize.accept(method);
        }
        log.accept("Camera night exposure hooks registered", null);
    }

    private boolean exposureEnabled() throws ReflectiveOperationException {
        return settings.getBoolean(ModuleSettings.CAMERA_NIGHT_EXPOSURE_ENABLED, false)
                && inMode("SUPER_NIGHT");
    }

    private int exposureMs() {
        return ModuleSettings.cameraNightExposureMs(settings.getInt(
                ModuleSettings.CAMERA_NIGHT_EXPOSURE_MS, ModuleSettings.CAMERA_NIGHT_EXPOSURE_DEFAULT));
    }

    @SuppressWarnings("unchecked")
    private void applyExposure(CaptureRequest.Builder builder, Exposure state)
            throws ReflectiveOperationException {
        int frames = state.frames;
        if (!state.burst) {
            CaptureRequest.Key<float[]> key = (CaptureRequest.Key<float[]>) evList.invoke(meizu);
            float[] ev = key == null ? null : (float[]) getTag.invoke(null, builder, key);

            if (ev != null) {
                int index = frameCountIndex.getInt(null) - 1;
                if (index >= 0 && index < ev.length && ev[index] > 0)
                    frames = Math.max(1, (int) ev[index]);
            }
        }
        long ns = state.totalMs * 1_000_000L / frames;
        if (state.burst) {
            Long oldTime = builder.get(CaptureRequest.SENSOR_EXPOSURE_TIME);
            Integer iso = builder.get(CaptureRequest.SENSOR_SENSITIVITY);
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, ns);
            // Preserve each bracket's original exposure product where possible.
            if (oldTime != null && oldTime > 0 && iso != null)
                builder.set(CaptureRequest.SENSOR_SENSITIVITY,
                        (int) Math.max(1, Math.min(Integer.MAX_VALUE, Math.round(iso * (double) oldTime / ns))));
        } else {
            CaptureRequest.Key<Integer> select = (CaptureRequest.Key<Integer>) priority.invoke(qualcomm);
            if (select != null) {
                setTag.invoke(null, builder, CaptureRequest.SENSOR_EXPOSURE_TIME, ns);
                setTag.invoke(null, builder, select, 1);
                setTag.invoke(null, builder, priorityExposure.invoke(qualcomm), ns);
            } else {
                setTag.invoke(null, builder, snExposure.invoke(meizu), ns);
            }
        }
    }
}
