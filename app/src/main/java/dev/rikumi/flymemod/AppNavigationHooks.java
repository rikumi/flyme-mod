package dev.rikumi.flymemod;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.hardware.HardwareBuffer;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.View;
import android.view.Gravity;
import android.view.WindowManager;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;
import java.util.function.ObjIntConsumer;

/** Samples the composited display in SystemUI; no code is injected into selected applications. */
final class AppNavigationHooks {
    private static final String NAV = "com.android.systemui.navigationbar.views.NavigationBarView";
    private static final String TRANSITIONS = "com.android.systemui.navigationbar.views.NavigationBarTransitions";
    private final SharedPreferences preferences;
    private final BiConsumer<String, Throwable> log;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Handler worker, captureWorker;
    private final Map<View, State> states = new WeakHashMap<>();
    private Constructor<?> argsBuilder, listenerConstructor;
    private Method setCrop, build, captureDisplay, asBitmap, getHardwareBuffer, applyIntensity, reapplyIntensity;
    private Object windowManager;
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
        nav.getDeclaredMethod("onDraw", Canvas.class);
        nav.getField("mBarTransitions");
        nav.getField("mIsVertical");
        Class<?> transitions = loader.loadClass(TRANSITIONS);
        transitions.getField("mView");
        applyIntensity = transitions.getMethod("applyDarkIntensity", float.class);
        reapplyIntensity = transitions.getMethod("reapplyDarkIntensity");
        Class<?> builder = loader.loadClass("android.window.ScreenCapture$CaptureArgs$Builder");
        Class<?> args = loader.loadClass("android.window.ScreenCapture$CaptureArgs");
        Class<?> listener = loader.loadClass("android.window.ScreenCapture$ScreenCaptureListener");
        Class<?> buffer = loader.loadClass("android.window.ScreenCapture$ScreenshotHardwareBuffer");
        argsBuilder = builder.getConstructor();
        setCrop = builder.getMethod("setSourceCrop", Rect.class);
        build = builder.getMethod("build");
        listenerConstructor = listener.getConstructor(ObjIntConsumer.class);
        asBitmap = buffer.getMethod("asBitmap");
        getHardwareBuffer = buffer.getMethod("getHardwareBuffer");
        windowManager = loader.loadClass("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null);
        captureDisplay = loader.loadClass("android.view.IWindowManager").getMethod("captureDisplay", int.class, args, listener);
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
        installer.hook(NAV, "onDraw", chain -> {
            State state = states.get(chain.getThisObject());
            if (state != null && state.color != null && enabled()) {
                ((Canvas) chain.getArg(0)).drawColor(state.color);
            }
            return chain.proceed();
        }, Canvas.class);
        installer.hook(TRANSITIONS, "applyDarkIntensity", chain -> {
            View view = (View) chain.getThisObject().getClass().getField("mView").get(chain.getThisObject());
            State state = states.get(view);
            if (state != null && state.color != null && enabled()) {
                return chain.proceed(new Object[]{Color.luminance(state.color) > .5f ? 1f : 0f});
            }
            return chain.proceed();
        }, float.class);
        preferences.registerOnSharedPreferenceChangeListener(changes);
        log.accept("SystemUI application navigation sampling hooks registered", null);
    }

    private boolean enabled() {
        return preferences.getBoolean(ModuleSettings.APP_IMMERSIVE_NAVIGATION, false);
    }

    static int averageColor(int[] pixels) {
        if (pixels.length == 0) return Color.TRANSPARENT;
        long r = 0, g = 0, b = 0;
        for (int pixel : pixels) { r += Color.red(pixel); g += Color.green(pixel); b += Color.blue(pixel); }
        return Color.rgb((int) (r / pixels.length), (int) (g / pixels.length), (int) (b / pixels.length));
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
        View extendedRoot;
        WindowManager.LayoutParams extendedParams;
        int originalWindowHeight, originalPaddingTop;
        final ColorDrawable topBacking = new ColorDrawable();
        final Map<WindowManager.LayoutParams, Integer> windowHeights = new IdentityHashMap<>();
        State(View view) { this.view = view; }

        void tick() {
            if (!attached) return;
            main.removeCallbacks(tick);
            if (!enabled()) { reset(); return; }
            try {
                KeyguardManager keyguard = view.getContext().getSystemService(KeyguardManager.class);
                boolean vertical = view.getClass().getField("mIsVertical").getBoolean(view);
                if (!enabled() || !view.isShown() || vertical || view.getDisplay() == null
                        || (keyguard != null && keyguard.isKeyguardLocked())) {
                    reset();
                } else if (!checkingTask) {
                    int[] position = new int[2]; view.getLocationOnScreen(position);
                    if (position[1] > 1 && view.getWidth() > 0) {
                        Rect line = new Rect(position[0], position[1] - 2, position[0] + view.getWidth(), position[1] - 1);
                        int display = view.getDisplay().getDisplayId(), token = generation;
                        checkingTask = true;
                        long taskRequest = ++activeTask;
                        taskTimeout = () -> { if (checkingTask && taskRequest == activeTask) reset(); };
                        main.postDelayed(taskTimeout, 750);
                        worker.post(() -> checkTask(line, display, token, taskRequest));
                    } else reset();
                }
            } catch (ReflectiveOperationException | RuntimeException error) { report(error); reset(); }
            main.postDelayed(tick, color == null ? 150 : 50);
        }

        void checkTask(Rect line, int display, int token, long taskRequest) {
            if (!attached || token != generation || taskRequest != activeTask) return;
            try {
                ActivityManager manager = view.getContext().getSystemService(ActivityManager.class);
                List<ActivityManager.RunningTaskInfo> tasks = manager == null ? null : manager.getRunningTasks(1);
                ActivityManager.RunningTaskInfo task = tasks == null || tasks.isEmpty() ? null : tasks.get(0);
                boolean selected = task != null && task.topActivity != null
                        && task.getClass().getField("displayId").getInt(task) == display
                        && ModuleSettings.navigationApplications(preferences).contains(task.topActivity.getPackageName());
                main.post(() -> {
                    if (taskRequest != activeTask) return;
                    if (taskTimeout != null) main.removeCallbacks(taskTimeout);
                    taskTimeout = null;
                    checkingTask = false;
                    if (!attached || token != generation || !enabled()) return;
                    if (!selected) { reset(); return; }
                    if (pending) return;
                    pending = true;
                    long request = ++activeCapture;
                    captureTimeout = () -> {
                        if (pending && activeCapture == request) reset();
                    };
                    main.postDelayed(captureTimeout, 750);
                    captureWorker.post(() -> sample(line, display, token, request));
                });
            } catch (ReflectiveOperationException | RuntimeException error) {
                report(error); main.post(() -> { if (taskRequest == activeTask) reset(); });
            }
        }

        void sample(Rect line, int display, int token, long request) {
            if (!attached || token != generation || request != activeCapture) return;
            try {
                Object builder = argsBuilder.newInstance(); setCrop.invoke(builder, line);
                ObjIntConsumer<Object> callback = (buffer, status) -> worker.post(() -> {
                    Integer sampled = null;
                    Bitmap hardware = null, software = null;
                    HardwareBuffer allocation = null;
                    try {
                        if (buffer != null) {
                            allocation = (HardwareBuffer) getHardwareBuffer.invoke(buffer);
                            if (status == 0) {
                                hardware = (Bitmap) asBitmap.invoke(buffer);
                                if (hardware != null) {
                                    software = hardware.copy(Bitmap.Config.ARGB_8888, false);
                                    int[] pixels = new int[software.getWidth() * software.getHeight()];
                                    software.getPixels(pixels, 0, software.getWidth(), 0, 0, software.getWidth(), software.getHeight());
                                    sampled = averageColor(pixels);
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
                    main.post(() -> {
                        if (request != activeCapture) return;
                        if (captureTimeout != null) main.removeCallbacks(captureTimeout);
                        captureTimeout = null;
                        captureListener = null; pending = false;
                        if (!attached || token != generation || !enabled()) return;
                        int[] current = new int[2]; view.getLocationOnScreen(current);
                        if (current[0] != line.left || current[1] - 1 != line.bottom || view.getWidth() != line.width()) { reset(); return; }
                        if (result == null) { reset(); return; }
                        try { extendBacking(result); }
                        catch (ReflectiveOperationException | RuntimeException error) { report(error); reset(); return; }
                        if (!result.equals(color)) {
                            color = result; view.invalidate();
                            try { applyIntensity.invoke(transitions(), Color.luminance(result) > .5f ? 1f : 0f); }
                            catch (ReflectiveOperationException error) { report(error); }
                        }
                    });
                });
                captureListener = listenerConstructor.newInstance(callback);
                captureDisplay.invoke(windowManager, display, build.invoke(builder), captureListener);
            } catch (ReflectiveOperationException | RuntimeException error) {
                report(error); main.post(() -> { if (request == activeCapture) reset(); });
            }
        }

        Object transitions() throws ReflectiveOperationException { return view.getClass().getField("mBarTransitions").get(view); }
        void extendBacking(int color) throws ReflectiveOperationException {
            View root = view.getRootView();
            if (extendedRoot != null && (extendedRoot != root || root.getLayoutParams() != extendedParams
                    || extendedParams.height != originalWindowHeight + 1)) restoreBacking();
            if (extendedRoot == null && root != view && root.getLayoutParams() instanceof WindowManager.LayoutParams params
                    && params.type == 2019 && params.height > 0
                    && (params.gravity & Gravity.VERTICAL_GRAVITY_MASK) == Gravity.BOTTOM) {
                originalWindowHeight = params.height;
                originalPaddingTop = root.getPaddingTop();
                extendedRoot = root; extendedParams = params;
                // Grow only the drawable strip; padding keeps the navigation children at their native coordinates.
                growWindow(params);
                WindowManager.LayoutParams[] rotations = (WindowManager.LayoutParams[]) params.getClass()
                        .getField("paramsForRotation").get(params);
                if (rotations != null) for (WindowManager.LayoutParams rotation : rotations) growWindow(rotation);
                root.setPadding(root.getPaddingLeft(), originalPaddingTop + 1, root.getPaddingRight(), root.getPaddingBottom());
                root.getContext().getSystemService(WindowManager.class).updateViewLayout(root, params);
                root.getOverlay().add(topBacking);
            }
            if (extendedRoot != null) {
                topBacking.setBounds(0, 0, extendedRoot.getWidth(), 1);
                topBacking.setColor(color);
            }
        }
        void growWindow(WindowManager.LayoutParams params) {
            if (params == null || windowHeights.containsKey(params) || params.height <= 0
                    || (params.gravity & Gravity.VERTICAL_GRAVITY_MASK) != Gravity.BOTTOM) return;
            windowHeights.put(params, params.height);
            params.height++;
        }
        void restoreBacking() {
            if (extendedRoot == null) return;
            View root = extendedRoot;
            root.getOverlay().remove(topBacking);
            if (root.getPaddingTop() == originalPaddingTop + 1) root.setPadding(root.getPaddingLeft(),
                    originalPaddingTop, root.getPaddingRight(), root.getPaddingBottom());
            boolean ownsParams = root.getLayoutParams() == extendedParams && extendedParams.height == originalWindowHeight + 1;
            for (Map.Entry<WindowManager.LayoutParams, Integer> entry : windowHeights.entrySet()) {
                if (entry.getKey().height == entry.getValue() + 1) entry.getKey().height = entry.getValue();
            }
            windowHeights.clear();
            if (ownsParams && root.isAttachedToWindow()) {
                try { root.getContext().getSystemService(WindowManager.class).updateViewLayout(root, extendedParams); }
                catch (RuntimeException error) { report(error); }
            }
            extendedRoot = null; extendedParams = null;
        }
        void reset() {
            generation++;
            activeCapture++;
            activeTask++;
            if (taskTimeout != null) main.removeCallbacks(taskTimeout);
            taskTimeout = null; checkingTask = false;
            if (captureTimeout != null) main.removeCallbacks(captureTimeout);
            captureTimeout = null; captureListener = null; pending = false;
            restoreBacking();
            if (color == null) return;
            color = null; view.invalidate();
            try { reapplyIntensity.invoke(transitions()); }
            catch (ReflectiveOperationException error) { report(error); }
        }
        void close() { attached = false; main.removeCallbacks(tick); reset(); }
        void report(Throwable error) {
            if (!errorLogged) { errorLogged = true; log.accept("Cannot sample application navigation background in SystemUI", error); }
        }
    }
}
