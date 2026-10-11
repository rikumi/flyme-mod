package dev.rikumi.flymemod;

import android.content.SharedPreferences;
import android.graphics.Rect;
import android.graphics.Region;
import android.view.InputEvent;
import android.view.MotionEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Share the native pin-window input consumer across all retained pin windows. */
final class SmallWindowInputHooks {
    private static final String WM = "com.android.server.wm.";
    private final SharedPreferences preferences;
    private final BiConsumer<String, Throwable> log;
    private Method count, child, asTask, taskArea, windowRoot, windowInfo, subtype, visible, hidden,
            bounds, position;
    private Class<?> pinInfo;
    private Field controllerArea, controllerWms, globalLock, handle, replaceCrop, touchRegion;
    private Method clearCrop, layout, reparent, show;
    private boolean reported;

    SmallWindowInputHooks(SharedPreferences preferences, BiConsumer<String, Throwable> log) {
        this.preferences = preferences; this.log = log;
    }

    void install(ClassLoader loader, SignalHooks.Installer installer, Consumer<Method> deoptimize)
            throws ReflectiveOperationException {
        Class<?> container = loader.loadClass(WM + "WindowContainer");
        Class<?> task = loader.loadClass(WM + "Task");
        Class<?> area = loader.loadClass(WM + "TaskDisplayArea");
        Class<?> window = loader.loadClass(WM + "WindowState");
        Class<?> transaction = loader.loadClass("android.view.SurfaceControl$Transaction");
        Class<?> surface = loader.loadClass("android.view.SurfaceControl");
        Class<?> consumer = loader.loadClass(WM + "InputConsumerImpl");
        Class<?> inputHandle = loader.loadClass("android.view.InputWindowHandle");
        Class<?> base = loader.loadClass(WM + "FlymeWindowControllerBase");
        Class<?> ext = loader.loadClass(WM + "FlymeTaskExt");
        Class<?> floatInfo = loader.loadClass(WM + "FlymeFloatWindowInfo");
        pinInfo = loader.loadClass(WM + "FlymePinWindowInfo");
        count = container.getMethod("getChildCount"); child = container.getMethod("getChildAt", int.class);
        asTask = container.getMethod("asTask"); taskArea = container.getMethod("getDisplayArea");
        visible = container.getMethod("isVisibleRequested"); windowRoot = window.getMethod("getRootTask");
        windowInfo = accessible(ext.getDeclaredMethod("getWindowInfo", task));
        subtype = accessible(ext.getDeclaredMethod("getWindowSubtype", container));
        hidden = accessible(floatInfo.getDeclaredMethod("isHidden"));
        bounds = accessible(floatInfo.getDeclaredMethod("getVisibleBound"));
        position = area.getMethod("positionChildAt", int.class, container, boolean.class);
        controllerArea = field(base, "mTaskDisplayArea"); controllerWms = field(base, "mWindowManager");
        globalLock = field(loader.loadClass(WM + "WindowManagerService"), "mGlobalLock");
        handle = field(consumer, "mWindowHandle");
        replaceCrop = inputHandle.getField("replaceTouchableRegionWithCrop");
        touchRegion = inputHandle.getField("touchableRegion");
        clearCrop = inputHandle.getMethod("setTouchableRegionCrop", surface);
        layout = accessible(consumer.getDeclaredMethod("layout", transaction, Rect.class));
        reparent = accessible(consumer.getDeclaredMethod("reparent", transaction, container));
        show = accessible(consumer.getDeclaredMethod("show", transaction, int.class));
        for (Method method : new Method[]{count, child, asTask, taskArea, visible, windowRoot, position})
            method.setAccessible(true);
        Class<?> inject = loader.loadClass(WM + "FlymeWindowManagerInjectImpl");
        Class<?> result = loader.loadClass("flyme.hook.Inject$ResultOne");
        Field arg1 = result.getField("arg1");
        Method accept = inject.getDeclaredMethod("injectInputMonitorAcceptWindow", result, consumer, transaction, window);
        installer.hook(inject.getName(), accept.getName(), chain -> {
            // Preserve native placement/layer selection and change only its single-task crop.
            boolean pending = Boolean.TRUE.equals(arg1.get(chain.getArg(0)));
            Object value = chain.proceed();
            if (enabled() && pending && Boolean.FALSE.equals(arg1.get(chain.getArg(0)))) {
                try {
                    Object root = windowRoot.invoke(chain.getArg(3));
                    if (root != null) updateRegion(chain.getArg(1), chain.getArg(2), taskArea.invoke(root));
                } catch (ReflectiveOperationException | RuntimeException error) { report(error); }
            }
            return value;
        }, result, consumer, transaction, window);
        Class<?> pin = loader.loadClass(WM + "FlymePinWindowControllerImpl");
        Method input = pin.getDeclaredMethod("onInputEvent", InputEvent.class);
        installer.hook(pin.getName(), input.getName(), chain -> {
            Object event = chain.getArg(0);
            if (enabled() && event instanceof MotionEvent motion && motion.getActionMasked() == MotionEvent.ACTION_DOWN) {
                Object controller = chain.getThisObject();
                try {
                    // Select before GestureDetector sees DOWN: taps and queued drag/fling
                    // messages then use the same native top-task target for the entire gesture.
                    synchronized (globalLock.get(controllerWms.get(controller))) {
                        Object displayArea = controllerArea.get(controller);
                        List<Pin> pins = pins(displayArea);
                        Object selected = hit(pins, motion.getRawX(), motion.getRawY());
                        if (selected != null) position.invoke(displayArea,
                                ((Number) count.invoke(displayArea)).intValue(), selected, true);
                    }
                } catch (ReflectiveOperationException | RuntimeException error) { report(error); }
            }
            return chain.proceed();
        }, InputEvent.class);
        deoptimize.accept(accept); deoptimize.accept(input);
        deoptimize.accept(loader.loadClass(WM + "InputMonitor$UpdateInputForAllWindowsConsumer")
                .getDeclaredMethod("accept", window));
        log.accept("Flyme retained pin-window touch regions and native gesture targeting registered", null);
    }

    private boolean enabled() { return preferences.getBoolean(ModuleSettings.SMALL_WINDOW_LIMIT_ENABLED, false); }

    private List<Pin> pins(Object area) throws ReflectiveOperationException {
        List<Pin> pins = new ArrayList<>();
        if (area == null) return pins;
        int limit = Math.max(1, Math.min(ModuleSettings.SMALL_WINDOW_LIMIT_MAX,
                preferences.getInt(ModuleSettings.SMALL_WINDOW_LIMIT, ModuleSettings.SMALL_WINDOW_LIMIT_DEFAULT)));
        for (int i = ((Number) count.invoke(area)).intValue() - 1; i >= 0 && pins.size() < limit; i--) {
            Object task = asTask.invoke(child.invoke(area, i));
            if (task == null || !Boolean.TRUE.equals(visible.invoke(task))) continue;
            Object info = windowInfo.invoke(null, task);
            if (!pinInfo.isInstance(info) || Boolean.TRUE.equals(hidden.invoke(info))
                    || ((Number) subtype.invoke(null, task)).intValue() != 0) continue;
            Rect rect = new Rect((Rect) bounds.invoke(info));
            if (!rect.isEmpty()) pins.add(new Pin(task, rect));
        }
        return pins;
    }

    private void updateRegion(Object consumer, Object transaction, Object area) throws ReflectiveOperationException {
        List<Pin> pins = pins(area);
        if (pins.size() < 2) return;
        Region region = new Region();
        for (Pin pin : pins) region.op(pin.bounds, Region.Op.UNION);
        Rect frame = region.getBounds();
        // InputWindowHandle regions are local to the input surface, not display coordinates.
        region.translate(-frame.left, -frame.top);
        Object input = handle.get(consumer);
        clearCrop.invoke(input, new Object[]{null});
        replaceCrop.setBoolean(input, false);
        ((Region) touchRegion.get(input)).set(region);
        layout.invoke(consumer, transaction, frame);
        reparent.invoke(consumer, transaction, area);
        show.invoke(consumer, transaction, Integer.MAX_VALUE - 1);
    }

    record Pin(Object task, Rect bounds) {}
    static Object hit(List<Pin> ordered, float x, float y) {
        for (Pin pin : ordered) if (pin.bounds.contains((int) Math.floor(x), (int) Math.floor(y))) return pin.task;
        return null;
    }
    private void report(Throwable error) {
        if (!reported) { reported = true; log.accept("Cannot route retained Flyme pin-window input", error); }
    }
    private static Method accessible(Method method) { method.setAccessible(true); return method; }
    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
}
