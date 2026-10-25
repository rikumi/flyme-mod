package dev.rikumi.flymemod;

import android.content.res.Configuration;
import android.graphics.Point;
import android.graphics.Rect;
import android.view.DisplayCutout;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class GameWidthSmallWindowHooks {
    private static final String WM = "com.android.server.wm.";
    private final Supplier<Map<String, Integer>> modes;
    private final BiConsumer<String, Throwable> log;
    private final ThreadLocal<Float> ratio = new ThreadLocal<>();
    private boolean reported;

    GameWidthSmallWindowHooks(Supplier<Map<String, Integer>> modes, BiConsumer<String, Throwable> log) {
        this.modes = modes; this.log = log;
    }

    void install(ClassLoader loader, SignalHooks.Installer installer, Consumer<Method> deoptimize)
            throws ReflectiveOperationException {
        Class<?> container = loader.loadClass(WM + "WindowContainer"), task = loader.loadClass(WM + "Task");
        Class<?> display = loader.loadClass(WM + "DisplayContent"), info = loader.loadClass("android.view.DisplayInfo");
        Class<?> floating = loader.loadClass(WM + "FlymeFloatWindowInfo"), mini = loader.loadClass(WM + "FlymeMiniWindowInfo");
        Method top = method(container, "getTopActivity", boolean.class, boolean.class);
        Method displayInfo = method(display, "getDisplayInfo");
        Field packageName = field(loader.loadClass(WM + "ActivityRecord"), "packageName");
        Field taskDisplay = field(container, "mDisplayContent"), infoDisplay = field(floating, "mDisplayContent");
        Field width = info.getField("logicalWidth"), height = info.getField("logicalHeight"), cutout = info.getField("displayCutout");
        Field aspect = field(floating, "mAspectRatio"), screen = field(floating, "mScreenRatio"), special = field(floating, "mSpecialSize");
        Field leaning = field(mini, "mLeftLeaning"), scale = field(mini, "mFreeScale");
        Method visible = method(mini, "getVisibleBound"), state = method(floating, "getWindowState");
        Constructor<?> rebuild = mini.getDeclaredConstructor(display, int.class, int.class, int.class,
                boolean.class, boolean.class, boolean.class, float.class, Point.class, int.class);
        rebuild.setAccessible(true);
        Method calculate = method(floating, "calculateAspectRatio", int.class, int.class, int.class,
                int.class, int.class, boolean.class, boolean.class);
        installer.hook(floating.getName(), calculate.getName(), chain -> {
            Float target = ratio.get();
            if (target != null && (int) chain.getArg(2) == Configuration.ORIENTATION_LANDSCAPE) return target;
            return chain.proceed();
        }, int.class, int.class, int.class, int.class, int.class, boolean.class, boolean.class);
        deoptimize.accept(calculate);
        for (String name : new String[]{"FlymeMiniWindowControllerImpl", "FlymeFreeFormWindowControllerImpl",
                "FlymePinWindowControllerImpl", "FlymeWeltWindowControllerImpl"}) {
            Class<?> controller = loader.loadClass(WM + name);
            Method create = method(controller, "createWindowInfo", task, int.class, int.class, int.class);
            installer.hook(controller.getName(), create.getName(), chain -> {
                Float previous = ratio.get(), target = null;
                try {
                    if ((int) chain.getArg(2) == Configuration.ORIENTATION_LANDSCAPE) {
                        Object app = top.invoke(chain.getArg(0), true, true);
                        Integer mode = app == null ? null : modes.get().get(packageName.get(app));
                        Object dc = taskDisplay.get(chain.getArg(0));
                        if (mode != null && dc != null) {
                            Object di = displayInfo.invoke(dc);
                            int longSide = Math.max(width.getInt(di), height.getInt(di));
                            int shortSide = Math.min(width.getInt(di), height.getInt(di));
                            DisplayCutout hole = (DisplayCutout) cutout.get(di);
                            int inset = hole == null ? 0 : Math.max(Math.max(hole.getSafeInsetLeft(), hole.getSafeInsetRight()),
                                    Math.max(hole.getSafeInsetTop(), hole.getSafeInsetBottom()));
                            if (shortSide > 0) target = GameWidthGeometry.width(mode, longSide, shortSide, inset) / (float) shortSide;
                        }
                    }
                } catch (ReflectiveOperationException | RuntimeException error) { report(error); }
                // Clear an outer task's context for nested construction of another app's window.
                if (target == null) ratio.remove(); else ratio.set(target);
                try {
                    Object result = chain.proceed();
                    if (target != null && name.equals("FlymeFreeFormWindowControllerImpl") && result != null) {
                        try {

                            if (Math.abs(aspect.getFloat(result) - target) > .0001f) {
                                Rect bound = (Rect) visible.invoke(result);
                                result = rebuild.newInstance(infoDisplay.get(result), chain.getArg(1), chain.getArg(2),
                                        state.invoke(result), screen.getBoolean(result), special.getBoolean(result),
                                        leaning.getBoolean(result), scale.getFloat(result),
                                        new Point(bound.centerX(), bound.centerY()), 4);
                            }
                        } catch (ReflectiveOperationException | RuntimeException error) { report(error); }
                    }
                    return result;
                } finally {
                    if (previous == null) ratio.remove(); else ratio.set(previous);
                }
            }, task, int.class, int.class, int.class);
            deoptimize.accept(create);
        }
        log.accept("Flyme landscape game small-window aspect ratio hooks registered", null);
    }

    private void report(Throwable error) {
        if (!reported) { reported = true; log.accept("Cannot adapt Flyme game small-window aspect ratio", error); }
    }
    private static Method method(Class<?> type, String name, Class<?>... args) throws NoSuchMethodException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try { Method value = current.getDeclaredMethod(name, args); value.setAccessible(true); return value; }
            catch (NoSuchMethodException ignored) {}
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }
    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field value = type.getDeclaredField(name); value.setAccessible(true); return value;
    }
}
