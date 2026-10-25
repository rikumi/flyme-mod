package dev.rikumi.flymemod;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.hardware.HardwareBuffer;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.View;
import android.view.SurfaceControl;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;
import java.util.function.ObjIntConsumer;

final class AppNavigationHooks {
    private static final String NAV = "com.android.systemui.navigationbar.views.NavigationBarView";
    private static final String TRANSITIONS = "com.android.systemui.navigationbar.views.NavigationBarTransitions";
    private static final String SHADE_WINDOW = "com.android.systemui.shade.NotificationShadeWindowControllerImpl";
    private static final long SAMPLE_INTERVAL_MS = 50;
    private static final long RECENTS_FOCUS_SETTLE_MS = 500;
    private final SharedPreferences preferences;
    private final BiConsumer<String, Throwable> log;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Handler worker, captureWorker;
    private final Map<View, State> states = new WeakHashMap<>();
    private Constructor<?> argsBuilder, listenerConstructor;
    private Method setCrop, setExcludeLayers, build, captureLayers, asBitmap, getHardwareBuffer, applyIntensity, reapplyIntensity;
    private AppNavigationTaskSurface taskSurfaces;
    private boolean shadeVisible;
    private volatile boolean recentsRunning, recentsCached;
    private final SharedPreferences.OnSharedPreferenceChangeListener changes = (prefs, key) -> {
        if (key != null && !ModuleSettings.APP_IMMERSIVE_NAVIGATION.equals(key)
                && !ModuleSettings.APP_IMMERSIVE_NAVIGATION_PACKAGES.equals(key)) return;
        main.post(() -> {
            for (State state : states.values()) { state.reset(); state.tick(); }
        });
    };

    AppNavigationHooks(SharedPreferences preferences, BiConsumer<String, Throwable> log) {
        this.preferences = preferences;
        this.log = log;
    }

    void install(ClassLoader loader, SignalHooks.Installer installer) throws ReflectiveOperationException {
        Class<?> nav = loader.loadClass(NAV);
        nav.getDeclaredMethod("onAttachedToWindow");
        nav.getDeclaredMethod("onDetachedFromWindow");
        nav.getField("mBarTransitions");
        nav.getField("mIsVertical");
        Class<?> transitions = loader.loadClass(TRANSITIONS);
        transitions.getField("mView");
        applyIntensity = transitions.getMethod("applyDarkIntensity", float.class);
        reapplyIntensity = transitions.getMethod("reapplyDarkIntensity");
        Class<?> shadeState = loader.loadClass("com.android.systemui.shade.NotificationShadeWindowState");
        loader.loadClass(SHADE_WINDOW).getDeclaredMethod("apply", shadeState);
        Field panelVisible = shadeState.getField("panelVisible");
        Field centerVisible = shadeState.getField("centerControllerVisible");
        Field expanded = shadeState.getField("shadeOrQsExpanded");
        Field blurRadius = shadeState.getField("backgroundBlurRadius");
        Field scrimsVisibility = shadeState.getField("scrimsVisibility");
        Field keyguardShowing = shadeState.getField("keyguardShowing");
        Field dozing = shadeState.getField("dozing");
        Class<?> builder = loader.loadClass("android.window.ScreenCapture$LayerCaptureArgs$Builder");
        Class<?> args = loader.loadClass("android.window.ScreenCapture$LayerCaptureArgs");
        Class<?> listener = loader.loadClass("android.window.ScreenCapture$ScreenCaptureListener");
        Class<?> buffer = loader.loadClass("android.window.ScreenCapture$ScreenshotHardwareBuffer");
        argsBuilder = builder.getConstructor(SurfaceControl.class);
        setCrop = builder.getMethod("setSourceCrop", Rect.class);
        setExcludeLayers = builder.getMethod("setExcludeLayers", SurfaceControl[].class);
        build = builder.getMethod("build");
        listenerConstructor = listener.getConstructor(ObjIntConsumer.class);
        asBitmap = buffer.getMethod("asBitmap");
        getHardwareBuffer = buffer.getMethod("getHardwareBuffer");
        captureLayers = loader.loadClass("android.window.ScreenCapture").getMethod("captureLayers", args, listener);
        taskSurfaces = new AppNavigationTaskSurface(loader);
        taskSurfaces.install(installer, id -> main.post(() -> {
            for (State state : states.values()) if (state.task != null && state.task.id == id) state.reset();
        }), info -> main.post(() -> focusedTaskChanged(info)));
        new AppNavigationRecentsHooks(loader).install(installer, this::recentsAnimationChanged);
        HandlerThread thread = new HandlerThread("FlymeMod-NavColor", android.os.Process.THREAD_PRIORITY_BACKGROUND);
        thread.start(); worker = new Handler(thread.getLooper());
        HandlerThread captureThread = new HandlerThread("FlymeMod-NavCapture", android.os.Process.THREAD_PRIORITY_BACKGROUND);
        captureThread.start(); captureWorker = new Handler(captureThread.getLooper());
        installer.hook(NAV, "onAttachedToWindow", chain -> {
            Object result = chain.proceed();
            View view = (View) chain.getThisObject();
            State state = new State(view);
            State previous = states.put(view, state);
            if (previous != null) previous.close();
            state.tick();
            return result;
        });
        installer.hook(NAV, "onDetachedFromWindow", chain -> {
            State state = states.remove(chain.getThisObject());
            if (state != null) state.close();
            return chain.proceed();
        });
        installer.hook(SHADE_WINDOW, "apply", chain -> {
            Object state = chain.getArg(0);
            if (recentsCached && (keyguardShowing.getBoolean(state) || dozing.getBoolean(state))) {
                recentsCached = false; recentsRunning = false;
                for (State navigation : states.values()) {
                    navigation.reset(); navigation.scheduleSample();
                }
            }
            boolean visible = panelVisible.getBoolean(state) || centerVisible.getBoolean(state)
                    || expanded.getBoolean(state) || blurRadius.getInt(state) > 0
                    || scrimsVisibility.getInt(state) != 0;
            if (shadeVisible != visible) {
                shadeVisible = visible;
                for (State navigation : states.values()) {

                    try { reapplyIntensity.invoke(navigation.transitions()); }
                    catch (ReflectiveOperationException error) { navigation.report(error); }
                }
            }
            return chain.proceed();
        }, shadeState);
        installer.hook(TRANSITIONS, "applyDarkIntensity", chain -> {
            View view = (View) chain.getThisObject().getClass().getField("mView").get(chain.getThisObject());
            State state = states.get(view);
            if (state != null && state.color != null && enabled() && !shadeVisible && !recentsCached) {
                return chain.proceed(new Object[]{Color.luminance(state.color) > .5f ? 1f : 0f});
            }
            return chain.proceed();
        }, float.class);
        preferences.registerOnSharedPreferenceChangeListener(changes);
        log.accept("SystemUI application task navigation surface hooks registered", null);
    }

    private boolean enabled() {
        return preferences.getBoolean(ModuleSettings.APP_IMMERSIVE_NAVIGATION, false);
    }

    private void recentsAnimationChanged(boolean running) {
        recentsRunning = running;
        if (running) {

            recentsCached = true;
            main.post(this::cacheForRecents);
        } else main.postDelayed(() -> {
            try {
                ActivityManager.RunningTaskInfo info = taskSurfaces.focusedTask();
                if (info != null) focusedTaskChanged(info);
            } catch (ReflectiveOperationException | RuntimeException error) {
                log.accept("Cannot resolve focused task after recents animation", error);
            }
        }, RECENTS_FOCUS_SETTLE_MS);
    }

    private void cacheForRecents() {
        if (!recentsCached) return;
        for (State state : states.values()) {
            main.removeCallbacks(state.tick);
            state.cancelRequests();
            state.restoreNavigationBackground();
            try { reapplyIntensity.invoke(state.transitions()); }
            catch (ReflectiveOperationException error) { state.report(error); }
        }
    }

    private void focusedTaskChanged(ActivityManager.RunningTaskInfo info) {
        if (recentsRunning) return;
        try {
            if (taskSurfaces.isOverviewHost(info)) {
                if (!recentsCached) { recentsCached = true; cacheForRecents(); }
                return;
            }
            if (!recentsCached) return;
            recentsCached = false;
            for (State state : states.values()) {
                if (state.task != null && state.task.id == info.taskId
                        && state.task.surface.isValid() && enabled()
                        && info.topActivity != null && ModuleSettings.navigationApplications(preferences)
                                .contains(info.topActivity.getPackageName())) {
                    if (state.backing != null && state.color != null) state.clearNavigationBackground();
                    reapplyIntensity.invoke(state.transitions());
                } else state.reset();
                state.scheduleSample();
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            log.accept("Cannot restore application navigation after recents", error);
        }
    }

    private final class State {
        final View view;
        final Runnable tick = this::tick;
        volatile boolean attached = true;
        boolean pending, checkingTask, errorLogged;
        Integer color;
        volatile int generation;
        volatile long activeCapture;
        volatile long activeTask;
        Runnable captureTimeout, taskTimeout;
        // Retain the native listener until its asynchronous capture completes.
        Object captureListener;
        SurfaceControl backing;
        Integer backingColor;
        Rect backingFrame, backingTaskBounds;
        NavigationSampling sampling;
        AppNavigationTaskSurface.Target task;
        Drawable originalBackground;
        boolean backgroundCleared;
        State(View view) { this.view = view; }

        void tick() {
            if (!attached) return;
            main.removeCallbacks(tick);
            if (!enabled()) { reset(); return; }
            if (recentsCached) return;
            try {
                KeyguardManager keyguard = view.getContext().getSystemService(KeyguardManager.class);
                boolean vertical = view.getClass().getField("mIsVertical").getBoolean(view);
                if (!enabled() || !view.isShown() || vertical || view.getDisplay() == null
                        || (keyguard != null && keyguard.isKeyguardLocked())) {
                    reset();
                } else if (!checkingTask) {
                    int[] position = new int[2]; view.getLocationOnScreen(position);
                    if (position[1] > 1 && view.getWidth() > 0 && view.getHeight() > 0) {
                        if (sampling == null || !sampling.matches(position[0], position[1], view.getWidth(), view.getHeight())) {
                            sampling = new NavigationSampling(position[0], position[1], view.getWidth(), view.getHeight());
                        }
                        NavigationSampling points = sampling;
                        int display = view.getDisplay().getDisplayId(), token = generation;
                        checkingTask = true;
                        long taskRequest = ++activeTask;
                        taskTimeout = () -> { if (checkingTask && taskRequest == activeTask && !recentsCached) reset(); };
                        main.postDelayed(taskTimeout, 750);
                        worker.post(() -> checkTask(points, display, token, taskRequest));
                    } else reset();
                }
            } catch (ReflectiveOperationException | RuntimeException error) { report(error); reset(); }
            scheduleSample();
        }

        void scheduleSample() {
            main.removeCallbacks(tick);
            if (attached && enabled() && !recentsCached) main.postDelayed(tick, SAMPLE_INTERVAL_MS);
        }

        void checkTask(NavigationSampling points, int display, int token, long taskRequest) {
            if (!attached || token != generation || taskRequest != activeTask || recentsCached) return;
            try {
                ActivityManager manager = view.getContext().getSystemService(ActivityManager.class);
                List<ActivityManager.RunningTaskInfo> tasks = manager == null ? null : manager.getRunningTasks(1);
                ActivityManager.RunningTaskInfo task = tasks == null || tasks.isEmpty() ? null : tasks.get(0);
                boolean selected = task != null && task.topActivity != null
                        && task.getClass().getField("displayId").getInt(task) == display
                        && ModuleSettings.navigationApplications(preferences).contains(task.topActivity.getPackageName());
                Rect capture = new Rect(points.left, points.captureTop,
                        points.left + points.width, points.captureTop + points.captureHeight);
                AppNavigationTaskSurface.Target target = selected ? taskSurfaces.find(task, display, capture) : null;
                main.post(() -> {
                    if (taskRequest != activeTask) { if (target != null) target.close(); return; }
                    if (taskTimeout != null) main.removeCallbacks(taskTimeout);
                    taskTimeout = null;
                    checkingTask = false;
                    if (!attached || token != generation || !enabled() || recentsCached) { if (target != null) target.close(); return; }
                    if (target == null) { reset(); return; }
                    boolean adopted = false;
                    try {
                        boolean same = taskSurfaces.same(this.task, target);
                        if (pending) {
                            if (!same) reset();
                            return;
                        }
                        if (!same) hideBacking();
                        if (this.task != null) this.task.close();
                        this.task = target; adopted = true;
                        startCapture(points, token);
                    } catch (ReflectiveOperationException | RuntimeException error) { report(error); reset(); }
                    finally { if (!adopted) target.close(); }
                });
            } catch (ReflectiveOperationException | RuntimeException error) {
                report(error); main.post(() -> { if (taskRequest == activeTask && !recentsCached) reset(); });
            }
        }

        void startCapture(NavigationSampling points, int token) throws ReflectiveOperationException {
            SurfaceControl root = taskSurfaces.copy(task.surface);
            SurfaceControl exclude = null;
            try {
                if (backing != null) exclude = taskSurfaces.copy(backing);
            } catch (ReflectiveOperationException | RuntimeException error) { root.release(); throw error; }
            Rect local = new Rect(points.left, points.captureTop,
                    points.left + points.width, points.captureTop + points.captureHeight);
            local.offset(-task.bounds.left, -task.bounds.top);
            SurfaceControl excluded = exclude;
            pending = true;
            long request = ++activeCapture;
            captureTimeout = () -> { if (pending && activeCapture == request && !recentsCached) reset(); };
            main.postDelayed(captureTimeout, 750);
            captureWorker.post(() -> sample(points, local, root, excluded, token, request));
        }

        void sample(NavigationSampling points, Rect local, SurfaceControl root, SurfaceControl exclude, int token, long request) {
            try {
                if (!attached || token != generation || request != activeCapture || recentsCached) return;
                Object builder = argsBuilder.newInstance(root); setCrop.invoke(builder, local);
                if (exclude != null) setExcludeLayers.invoke(builder, (Object) new SurfaceControl[]{exclude});
                ObjIntConsumer<Object> callback = (buffer, status) -> worker.post(() -> {
                    Integer sampled = null;
                    boolean captured = false;
                    Bitmap hardware = null, software = null;
                    HardwareBuffer allocation = null;
                    try {
                        if (buffer != null) {
                            allocation = (HardwareBuffer) getHardwareBuffer.invoke(buffer);
                            if (status == 0 && attached && token == generation
                                    && request == activeCapture && !recentsCached) {
                                hardware = (Bitmap) asBitmap.invoke(buffer);
                                if (hardware != null) {
                                    software = hardware.copy(Bitmap.Config.ARGB_8888, false);
                                    if (software.getWidth() == points.width && software.getHeight() == points.captureHeight) {

                                        sampled = points.sample(software::getPixel);
                                        captured = true;
                                    }
                                }
                            }
                        }
                    } catch (ReflectiveOperationException | RuntimeException error) { report(error); }
                    finally {
                        if (software != null) software.recycle();
                        if (hardware != null) hardware.recycle();
                        if (allocation != null) allocation.close();
                    }
                    Integer result = sampled;
                    boolean valid = captured;
                    main.post(() -> {
                        if (request != activeCapture) return;
                        if (captureTimeout != null) main.removeCallbacks(captureTimeout);
                        captureTimeout = null;
                        captureListener = null; pending = false;
                        if (!attached || token != generation || !enabled() || recentsCached) return;
                        int[] current = new int[2]; view.getLocationOnScreen(current);
                        if (!points.matches(current[0], current[1], view.getWidth(), view.getHeight())) { reset(); return; }
                        if (!valid) { reset(); return; }
                        if (result == null) { hideBacking(); return; }
                        try { updateBacking(result); }
                        catch (ReflectiveOperationException | RuntimeException error) { report(error); reset(); return; }
                        if (!result.equals(color)) {
                            color = result;
                            try { applyIntensity.invoke(transitions(), Color.luminance(result) > .5f ? 1f : 0f); }
                            catch (ReflectiveOperationException error) { report(error); }
                        }
                    });
                });
                captureListener = listenerConstructor.newInstance(callback);
                int status = (Integer) captureLayers.invoke(null, build.invoke(builder), captureListener);
                if (status != 0) main.post(() -> { if (request == activeCapture && !recentsCached) reset(); });
            } catch (ReflectiveOperationException | RuntimeException error) {
                report(error); main.post(() -> { if (request == activeCapture && !recentsCached) reset(); });
            } finally {
                root.release();
                if (exclude != null) exclude.release();
            }
        }

        Object transitions() throws ReflectiveOperationException { return view.getClass().getField("mBarTransitions").get(view); }
        void updateBacking(int color) throws ReflectiveOperationException {
            if (task == null || !task.surface.isValid()) throw new IllegalStateException("Application task surface disappeared");
            int[] position = new int[2]; view.getLocationOnScreen(position);
            if (view.getWidth() <= 0 || view.getHeight() <= 0 || position[1] <= 0) return;
            Rect strip = new Rect(position[0], position[1] - 1,
                    position[0] + view.getWidth(), position[1] + view.getHeight());
            if (backing == null || !Integer.valueOf(color).equals(backingColor)
                    || !strip.equals(backingFrame) || !task.bounds.equals(backingTaskBounds)) {
                if (backing == null) backing = taskSurfaces.createBacking(task);
                taskSurfaces.draw(backing, task, strip, color);
                backingColor = color;
                backingFrame = strip;
                backingTaskBounds = new Rect(task.bounds);
            }
            clearNavigationBackground();
        }
        void clearNavigationBackground() {
            if (!backgroundCleared) {
                originalBackground = view.getBackground();
                backgroundCleared = true;
                view.setBackground(null);
            }
        }
        void restoreBacking() {
            if (backing != null) {
                try { AppNavigationTaskSurface.remove(backing); }
                catch (RuntimeException error) { report(error); }
                backing = null;
            }
            backingColor = null; backingFrame = null; backingTaskBounds = null;
            restoreNavigationBackground();
        }
        void restoreNavigationBackground() {
            if (backgroundCleared) {
                if (view.getBackground() == null) view.setBackground(originalBackground);
                originalBackground = null;
                backgroundCleared = false;
            }
        }
        void cancelRequests() {
            generation++;
            activeCapture++;
            activeTask++;
            if (taskTimeout != null) main.removeCallbacks(taskTimeout);
            taskTimeout = null; checkingTask = false;
            if (captureTimeout != null) main.removeCallbacks(captureTimeout);
            captureTimeout = null; captureListener = null; pending = false;
        }
        void reset() {
            cancelRequests();
            hideBacking();
            if (task != null) { task.close(); task = null; }
        }
        void hideBacking() {
            restoreBacking();
            if (color == null) return;
            color = null;
            try { reapplyIntensity.invoke(transitions()); }
            catch (ReflectiveOperationException error) { report(error); }
        }
        void close() { attached = false; main.removeCallbacks(tick); reset(); }
        void report(Throwable error) {
            if (!errorLogged) { errorLogged = true; log.accept("Cannot sample application navigation background in SystemUI", error); }
        }
    }
}
