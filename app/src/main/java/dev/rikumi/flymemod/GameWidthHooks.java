package dev.rikumi.flymemod;

import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Point;
import android.graphics.Rect;
import android.view.DisplayCutout;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

final class GameWidthHooks {
    private static final String WM = "com.android.server.wm.";
    private final SharedPreferences preferences;
    private final BiConsumer<String, Throwable> log;
    private volatile Map<String, Integer> modes = Map.of();
    private final Map<Object, Boolean> adaptedSurfaces = new WeakHashMap<>();
    private final Map<Object, Boolean> roundedWindows = new WeakHashMap<>();
    private final Map<Object, Integer> cutoutModes = new WeakHashMap<>();
    private final SharedPreferences.OnSharedPreferenceChangeListener changes = (prefs, key) -> {
        if (key == null || ModuleSettings.LANDSCAPE_GAME_MODES.equals(key)) readModes();
    };
    private Field packageName, displayContent, appCompat, logicalWidth, logicalHeight, density, cutout,
            windowActivity, windowAttrs, surfaceInsets;
    private Method windowMode, requestedOrientation, fixedInfo, displayInfo, resolved, setBounds, setAppBounds,
            setMaxBounds, compute, sizePolicy, clearSize, aspectPolicy, letterboxBounds, surface,
            relativePosition, hasLeash, matrix, position, cropSurface, cornerRadius, mainWindow, frame;
    private Field animator;
    private Class<?> activityClass;
    private boolean reported;

    GameWidthHooks(SharedPreferences preferences, BiConsumer<String, Throwable> log) {
        this.preferences = preferences; this.log = log; readModes();
    }
    private void readModes() {
        Map<String, Integer> result = new HashMap<>();
        for (String entry : preferences.getString(ModuleSettings.LANDSCAPE_GAME_MODES, "").split("\n")) {
            int split = entry.indexOf('=');
            if (split <= 0) continue;
            try { int value = Integer.parseInt(entry.substring(split + 1));
                if (value >= 0 && value < GameWidthGeometry.NAMES.length) result.put(entry.substring(0, split), value);
            } catch (NumberFormatException ignored) {}
        }
        modes = Map.copyOf(result);
    }
    void install(ClassLoader loader, SignalHooks.Installer installer, Consumer<Method> deoptimize)
            throws ReflectiveOperationException {
        activityClass = loader.loadClass(WM + "ActivityRecord");
        Class<?> container = loader.loadClass(WM + "WindowContainer");
        Class<?> config = loader.loadClass(WM + "ConfigurationContainer");
        Class<?> info = loader.loadClass("android.view.DisplayInfo");
        Class<?> wc = loader.loadClass("android.app.WindowConfiguration");
        Class<?> controller = loader.loadClass(WM + "AppCompatController");
        Class<?> size = loader.loadClass(WM + "AppCompatSizeCompatModePolicy");
        Class<?> aspect = loader.loadClass(WM + "AppCompatAspectRatioPolicy");
        Class<?> sc = loader.loadClass("android.view.SurfaceControl");
        Class<?> tx = loader.loadClass("android.view.SurfaceControl$Transaction");
        packageName = field(activityClass, "packageName"); displayContent = field(container, "mDisplayContent");
        appCompat = field(activityClass, "mAppCompatController"); animator = field(container, "mSurfaceAnimator");
        logicalWidth = info.getField("logicalWidth"); logicalHeight = info.getField("logicalHeight");
        density = info.getField("logicalDensityDpi");
        cutout = info.getField("displayCutout");
        windowMode = config.getMethod("getWindowingMode"); fixedInfo = activityClass.getMethod("getFixedRotationTransformDisplayInfo");
        requestedOrientation = accessible(activityClass.getMethod("getRequestedConfigurationOrientation"));
        displayInfo = loader.loadClass(WM + "DisplayContent").getMethod("getDisplayInfo");
        resolved = config.getMethod("getResolvedOverrideConfiguration");
        setBounds = wc.getMethod("setBounds", Rect.class); setAppBounds = wc.getMethod("setAppBounds", Rect.class);
        setMaxBounds = wc.getMethod("setMaxBounds", Rect.class);
        compute = activityClass.getMethod("computeConfigByResolveHint", Configuration.class, Configuration.class);
        sizePolicy = accessible(controller.getDeclaredMethod("getSizeCompatModePolicy"));
        clearSize = accessible(size.getDeclaredMethod("clearSizeCompatModeAttributes"));
        aspectPolicy = accessible(controller.getDeclaredMethod("getAspectRatioPolicy"));
        letterboxBounds = accessible(aspect.getDeclaredMethod("setLetterboxBoundsForAspectRatio", Rect.class));
        surface = container.getMethod("getSurfaceControl"); relativePosition = container.getMethod("getRelativePosition", Point.class);
        hasLeash = accessible(loader.loadClass(WM + "SurfaceAnimator").getDeclaredMethod("hasLeash"));
        matrix = tx.getMethod("setMatrix", sc, float.class, float.class, float.class, float.class);
        position = tx.getMethod("setPosition", sc, float.class, float.class);
        cropSurface = tx.getMethod("setWindowCrop", sc, Rect.class);
        cornerRadius = tx.getMethod("setCornerRadius", sc, float.class);
        for (Method method : new Method[]{windowMode, fixedInfo, displayInfo, resolved, compute,
                surface, relativePosition}) method.setAccessible(true);
        Method resolve = activityClass.getDeclaredMethod("resolveOverrideConfiguration", Configuration.class);
        installer.hook(activityClass.getName(), resolve.getName(), chain -> {
            Object record = chain.getThisObject();
            Object result = chain.proceed();
            try {
                Plan plan = plan(record);
                if (plan != null) applyConfiguration(record, (Configuration) chain.getArg(0), plan);
            } catch (ReflectiveOperationException | RuntimeException error) { report(error); }
            return result;
        }, Configuration.class);

        Method insets = activityClass.getDeclaredMethod("shouldCreateAppCompatDisplayInsets");
        installer.hook(activityClass.getName(), insets.getName(), chain -> {
            try { if (plan(chain.getThisObject()) != null) return false; }
            catch (ReflectiveOperationException | RuntimeException error) { report(error); }
            return chain.proceed();
        });
        Class<?> token = loader.loadClass(WM + "WindowToken");
        Method update = token.getDeclaredMethod("updateSurfacePosition", tx);
        installer.hook(token.getName(), update.getName(), chain -> {
            Object value = chain.proceed(), record = chain.getThisObject();
            if (!activityClass.isInstance(record)) return value;
            try { applySurface(record, chain.getArg(0)); }
            catch (ReflectiveOperationException | RuntimeException error) { report(error); }
            return value;
        }, tx);
        Class<?> utils = loader.loadClass(WM + "AppCompatLetterboxUtils");
        Class<?> window = loader.loadClass(WM + "WindowState");
        windowActivity = field(window, "mActivityRecord"); windowAttrs = field(window, "mAttrs");
        surfaceInsets = loader.loadClass("android.view.WindowManager$LayoutParams").getField("surfaceInsets");
        mainWindow = accessible(activityClass.getMethod("findMainWindow", boolean.class));
        frame = accessible(window.getMethod("getFrame"));
        Method updateWindow = window.getDeclaredMethod("updateSurfacePosition", tx);
        installer.hook(window.getName(), updateWindow.getName(), chain -> {
            Object value = chain.proceed();
            try { applyWindowCrop(chain.getThisObject(), chain.getArg(0)); }
            catch (ReflectiveOperationException | RuntimeException error) { report(error); }
            return value;
        }, tx);
        Class<?> inject = loader.loadClass(WM + "FlymeWindowManagerInjectImpl");
        Class<?> attrs = loader.loadClass("android.view.WindowManager$LayoutParams");
        Field cutoutMode = attrs.getField("layoutInDisplayCutoutMode");
        Class<?> frames = loader.loadClass(WM + "DisplayFrames");
        Method layout = inject.getDeclaredMethod("handleWinLayoutingAttrs", window, attrs, frames);
        installer.hook(inject.getName(), layout.getName(), chain -> {
            Object value = chain.proceed();
            try {
                Object record = windowActivity.get(chain.getArg(0)), params = chain.getArg(1);
                if (record != null && plan(record) != null) {
                    cutoutModes.putIfAbsent(params, cutoutMode.getInt(params));
                    cutoutMode.setInt(params, 3); // ALWAYS: the activity viewport already handles cutout avoidance.
                } else {
                    Integer original = cutoutModes.remove(params);
                    if (original != null) cutoutMode.setInt(params, original);
                }
            } catch (ReflectiveOperationException | RuntimeException error) { report(error); }
            return value;
        }, window, attrs, frames);
        Class<?> resultOne = loader.loadClass("flyme.hook.Inject$ResultOne");
        Field resultValue = resultOne.getField("arg1");
        Class<?> insetState = loader.loadClass("android.view.InsetsState");
        Class<?> insetSource = loader.loadClass("android.view.InsetsSource");
        java.lang.reflect.Constructor<?> copyInsets = insetState.getConstructor(insetState);
        Method sourceCount = insetState.getMethod("sourceSize"), sourceAt = insetState.getMethod("sourceAt", int.class),
                sourceType = insetSource.getMethod("getType"), removeSource = insetState.getMethod("removeSourceAt", int.class);
        Method handleInsets = inject.getDeclaredMethod("handleWindowInsetsState", resultOne,
                loader.loadClass(WM + "DisplayContent"), token);
        installer.hook(inject.getName(), handleInsets.getName(), chain -> {
            Object value = chain.proceed(), record = chain.getArg(2);
            try {
                if (activityClass.isInstance(record) && plan(record) != null && resultValue.get(chain.getArg(0)) != null) {
                    Object state = copyInsets.newInstance(resultValue.get(chain.getArg(0)));
                    for (int i = ((Number) sourceCount.invoke(state)).intValue() - 1; i >= 0; i--)
                        if (((Number) sourceType.invoke(sourceAt.invoke(state, i))).intValue()
                                == android.view.WindowInsets.Type.displayCutout()) removeSource.invoke(state, i);
                    resultValue.set(chain.getArg(0), state);
                }
            } catch (ReflectiveOperationException | RuntimeException error) { report(error); }
            return value;
        }, resultOne, loader.loadClass(WM + "DisplayContent"), token);
        Method inner = utils.getDeclaredMethod("calculateLetterboxInnerBounds", activityClass, window, Rect.class);
        installer.hook(utils.getName(), inner.getName(), chain -> {
            Object value = chain.proceed();
            try { Plan plan = plan(chain.getArg(0));
                if (plan != null && plan.mode == 4) ((Rect) chain.getArg(2)).set(plan.full);
            } catch (ReflectiveOperationException | RuntimeException error) { report(error); }
            return value;
        }, activityClass, window, Rect.class);
        deoptimize.accept(resolve); deoptimize.accept(insets); deoptimize.accept(update); deoptimize.accept(inner);
        deoptimize.accept(updateWindow);
        deoptimize.accept(layout); deoptimize.accept(handleInsets);
        preferences.registerOnSharedPreferenceChangeListener(changes);
        log.accept("Flyme landscape game viewport and stretch hooks registered", null);
        new GameWidthSmallWindowHooks(() -> modes, log).install(loader, installer, deoptimize);
    }
    private Plan plan(Object record) throws ReflectiveOperationException {
        Integer mode = modes.get(packageName.get(record));
        if (mode == null || ((Number) windowMode.invoke(record)).intValue() != 1
                || ((Number) requestedOrientation.invoke(record)).intValue() == Configuration.ORIENTATION_PORTRAIT) return null;
        Object display = displayContent.get(record);
        if (display == null) return null;
        Object currentInfo = displayInfo.invoke(display);

        if (logicalWidth.getInt(currentInfo) <= logicalHeight.getInt(currentInfo)) return null;
        Object info = fixedInfo.invoke(record);
        if (info == null) info = currentInfo;
        int width = logicalWidth.getInt(info), height = logicalHeight.getInt(info);
        if (width <= height || height <= 0) return null;
        DisplayCutout hole = (DisplayCutout) cutout.get(info);
        int inset = hole == null ? 0 : Math.max(hole.getSafeInsetLeft(), hole.getSafeInsetRight());
        int valid = GameWidthGeometry.validMode(mode, width, height);
        int viewportWidth = GameWidthGeometry.width(valid, width, height, inset);
        int left = (width - viewportWidth) / 2;
        return new Plan(valid, new Rect(0, 0, width, height), new Rect(left, 0, left + viewportWidth, height),
                Math.max(1f, density.getInt(info) / 160f));
    }
    private void applyConfiguration(Object record, Configuration parent, Plan plan) throws ReflectiveOperationException {
        Object compat = appCompat.get(record);
        clearSize.invoke(sizePolicy.invoke(compat));
        Configuration configuration = (Configuration) resolved.invoke(record);
        Object wc = Configuration.class.getField("windowConfiguration").get(configuration);
        setBounds.invoke(wc, plan.viewport); setAppBounds.invoke(wc, plan.viewport); setMaxBounds.invoke(wc, plan.viewport);
        compute.invoke(record, configuration, parent);

        setAppBounds.invoke(wc, plan.viewport); setMaxBounds.invoke(wc, plan.viewport);
        configuration.orientation = Configuration.ORIENTATION_LANDSCAPE;
        if (configuration.densityDpi > 0) {
            configuration.screenWidthDp = Math.round(plan.viewport.width() * 160f / configuration.densityDpi);
            configuration.screenHeightDp = Math.round(plan.viewport.height() * 160f / configuration.densityDpi);
            configuration.smallestScreenWidthDp = Math.min(configuration.screenWidthDp, configuration.screenHeightDp);
        }
        letterboxBounds.invoke(aspectPolicy.invoke(compat), plan.viewport);
    }
    private void applySurface(Object record, Object transaction) throws ReflectiveOperationException {
        Plan plan = plan(record);
        boolean stretch = plan != null && plan.mode == 4;
        if (plan == null && !adaptedSurfaces.containsKey(record)) return;
        Object control = surface.invoke(record);
        if (control == null || Boolean.TRUE.equals(hasLeash.invoke(animator.get(record)))) return;
        Point origin = new Point(); relativePosition.invoke(record, origin);
        float scale = stretch ? plan.full.width() / (float) plan.viewport.width() : 1f;
        matrix.invoke(transaction, control, scale, 0f, 0f, 1f);
        position.invoke(transaction, control, (float) (origin.x - (stretch ? plan.viewport.left : 0)), (float) origin.y);
        if (plan != null) adaptedSurfaces.put(record, true); else adaptedSurfaces.remove(record);
    }
    private void applyWindowCrop(Object window, Object transaction) throws ReflectiveOperationException {
        Object record = windowActivity.get(window);
        Plan plan = record == null ? null : plan(record);
        boolean rounded = plan != null && plan.mode != 4 && plan.viewport.width() < plan.full.width()
                && mainWindow.invoke(record, false) == window;
        if (!rounded && !roundedWindows.containsKey(window)) return;
        Object control = surface.invoke(window);
        if (control == null || Boolean.TRUE.equals(hasLeash.invoke(animator.get(window)))) return;
        Rect crop = null;
        if (rounded) {
            Rect bounds = (Rect) frame.invoke(window);
            Rect insets = (Rect) surfaceInsets.get(windowAttrs.get(window));
            crop = new Rect(insets.left, insets.top, insets.left + bounds.width(), insets.top + bounds.height());
        }

        cropSurface.invoke(transaction, control, crop);
        cornerRadius.invoke(transaction, control, rounded ? 8f * plan.density : 0f);
        if (rounded) roundedWindows.put(window, true); else roundedWindows.remove(window);
    }
    private record Plan(int mode, Rect full, Rect viewport, float density) {}
    private void report(Throwable error) { if (!reported) { reported = true; log.accept("Cannot adapt landscape game viewport", error); } }
    private static Method accessible(Method method) { method.setAccessible(true); return method; }
    private static Field field(Class<?> type, String name) throws NoSuchFieldException { Field f = type.getDeclaredField(name); f.setAccessible(true); return f; }
}
