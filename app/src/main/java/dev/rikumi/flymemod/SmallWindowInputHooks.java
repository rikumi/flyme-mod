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

final class SmallWindowInputHooks {
    private static final String WM = "com.android.server.wm.";
    private final SharedPreferences preferences;
    private final BiConsumer<String, Throwable> log;
    private Method count, child, asTask, taskArea, windowRoot, windowInfo, subtype, visible, hidden,
            bounds, position, mode, deferBack;
    private Class<?> pinInfo, floatInfoClass;
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
        deferBack = accessible(ext.getDeclaredMethod("isDeferBack", task));
        Class<?> floatInfo = loader.loadClass(WM + "FlymeFloatWindowInfo");
        floatInfoClass = floatInfo;
        mode = accessible(task.getMethod("getWindowingMode"));
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
            // The native consumer is above all task surfaces; exclude expanded windows.
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
            if (event instanceof MotionEvent motion) {
                Object controller = chain.getThisObject();
                Object lock, displayArea;
                try {
                    lock = globalLock.get(controllerWms.get(controller));
                    displayArea = controllerArea.get(controller);
                } catch (ReflectiveOperationException | RuntimeException error) {
                    report(error);
                    return chain.proceed();
                }

                synchronized (lock) {
                    boolean finish = motion.getActionMasked() == MotionEvent.ACTION_UP
                            || motion.getActionMasked() == MotionEvent.ACTION_CANCEL;
                    try {
                        if (enabled() && motion.getActionMasked() == MotionEvent.ACTION_DOWN) {
                            Object selected = hit(pins(displayArea), motion.getRawX(), motion.getRawY());
                            SmallWindowDragOrder.begin(displayArea, selected);
                            if (selected != null) position.invoke(displayArea,
                                    ((Number) count.invoke(displayArea)).intValue(), selected, true);
                        }
                    } catch (ReflectiveOperationException | RuntimeException error) { report(error); }
                    try { return chain.proceed(); }
                    finally {
                        if (finish) {
                            Object selected = SmallWindowDragOrder.end(displayArea);
                            try {
                                if (enabled() && selected != null) restoreExpanded(displayArea);
                            } catch (ReflectiveOperationException | RuntimeException error) { report(error); }
                        }
                    }
                }
            }
            return chain.proceed();
        }, InputEvent.class);
        deoptimize.accept(accept); deoptimize.accept(input);
        deoptimize.accept(loader.loadClass(WM + "InputMonitor$UpdateInputForAllWindowsConsumer")
                .getDeclaredMethod("accept", window));
        installTaskRestore(pin, base, task, transaction, installer, deoptimize);
        log.accept("Flyme retained pin-window touch regions and native gesture targeting registered", null);
    }

    private void installTaskRestore(Class<?> pin,Class<?> base,Class<?> task,Class<?> transaction,
            SignalHooks.Installer installer,Consumer<Method> deoptimize) throws ReflectiveOperationException {
        Method restore=pin.getDeclaredMethod("switchPinTaskToMinimizeModeIfNeed",task,boolean.class);
        Method dispatch=accessible(pin.getDeclaredMethod("onHandleMessage",android.os.Handler.class,android.os.Message.class));
        Method top=base.getDeclaredMethod("getTopWindowModeRootTask");
        Method parent=accessible(task.getMethod("getParent"));
        Field handlerField=field(base,"mHandler");
        ThreadLocal<Object[]> target=new ThreadLocal<>();
        Method resolve=accessible(base.getDeclaredMethod("getWindowModeRootTask",int.class));
        Field taskId=field(task,"mTaskId");
        String targetKey="dev.rikumi.flymemod.pin_task";

        installer.hook(android.os.Handler.class.getName(),"sendMessageAtTime",chain->{
            Object[] selected=target.get();
            if(selected!=null && handlerField.get(selected[0])==chain.getThisObject())
                ((android.os.Message)chain.getArg(0)).getData().putInt(targetKey,taskId.getInt(selected[1]));
            return chain.proceed();
        },android.os.Message.class,long.class);
        installer.hook(pin.getName(),"onHandleMessage",chain->{
            android.os.Message message=(android.os.Message)chain.getArg(1);
            android.os.Bundle data=message.peekData();
            int id=data==null?0:data.getInt(targetKey,0);
            if(id<=0)return chain.proceed();
            Object controller=chain.getThisObject();
            Object lock=globalLock.get(controllerWms.get(controller));
            synchronized(lock){
                Object selected=resolve.invoke(controller,id);
                if(selected==null)return null;
                Object[] previous=target.get();target.set(new Object[]{controller,selected});
                try {return chain.proceed();}
                finally {if(previous==null)target.remove();else target.set(previous);}
            }
        },android.os.Handler.class,android.os.Message.class);
        for(String name:new String[]{"fling","moveWindowModeBound"}){
            Class<?>[] parameters=name.equals("fling")
                    ?new Class<?>[]{int.class,float.class,float.class,Rect.class,android.graphics.Point.class}
                    :new Class<?>[]{int.class,Rect.class,float[].class,float[].class,boolean.class,boolean.class};
            Method method=pin.getDeclaredMethod(name,parameters);
            installer.hook(pin.getName(),name,chain->{
                int id=(Integer)chain.getArg(0);
                if(!enabled() || id<=0)return chain.proceed();
                Object controller=chain.getThisObject();
                Object lock=globalLock.get(controllerWms.get(controller));
                synchronized(lock){
                    Object selected=resolve.invoke(controller,id);
                    if(selected==null)return name.equals("fling")?false:null;
                    Object[] previous=target.get();target.set(new Object[]{controller,selected});
                    try {return chain.proceed();}
                    finally {if(previous==null)target.remove();else target.set(previous);}
                }
            },parameters);
            deoptimize.accept(method);
        }
        deoptimize.accept(android.os.Handler.class.getDeclaredMethod("sendMessageDelayed",android.os.Message.class,long.class));
        deoptimize.accept(android.os.Message.class.getDeclaredMethod("sendToTarget"));
        installer.hook(base.getName(),top.getName(),chain->{
            Object[] selected=target.get();
            if(selected!=null && selected[0]==chain.getThisObject()) {
                Object selectedTask=selected[1];
                return parent.invoke(selectedTask)!=null && (Integer)mode.invoke(selectedTask)==12
                        ?selectedTask:null;
            }
            return chain.proceed();
        });
        installer.hook(pin.getName(),restore.getName(),chain->{
            if(!enabled())return chain.proceed();
            Object controller=chain.getThisObject(), selected=chain.getArg(0);
            Object info=windowInfo.invoke(null,selected);
            if(info==null)return chain.proceed();
            Rect from=new Rect((Rect)bounds.invoke(info));
            boolean minimize=(Boolean)chain.getArg(1);
            android.os.Handler handler=(android.os.Handler)handlerField.get(controller);

            handler.post(()->{
                Object[] previous=target.get();target.set(new Object[]{controller,selected});
                android.os.Message message=android.os.Message.obtain(handler,6,minimize?1:0,0,from);
                try {
                    log.accept("Pin bar animation target: "+selected+", minimize="+minimize,null);
                    dispatch.invoke(controller,handler,message);
                }
                catch(ReflectiveOperationException|RuntimeException error){report(error);}
                finally {message.recycle();if(previous==null)target.remove();else target.set(previous);}
            });
            return null;
        },task,boolean.class);
        deoptimize.accept(dispatch);
        // This caller can inline the small private message-producing method.
        deoptimize.accept(pin.getDeclaredMethod("updateWindowModeShowStateInner",task,
                transaction,boolean.class));
    }

    private boolean enabled() { return preferences.getBoolean(ModuleSettings.SMALL_WINDOW_LIMIT_ENABLED, false); }

    private void restoreExpanded(Object area) throws ReflectiveOperationException {

        for (int i = ((Number) count.invoke(area)).intValue() - 1; i >= 0; i--) {
            Object task = asTask.invoke(child.invoke(area, i));
            if (task != null && Boolean.TRUE.equals(visible.invoke(task))
                    && SmallWindowLimitHooks.expandedMode(((Number) mode.invoke(task)).intValue())
                    && !Boolean.TRUE.equals(deferBack.invoke(null, task))) {
                position.invoke(area, Integer.MAX_VALUE, task, false);
                break;
            }
        }
    }

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
        if (pins.isEmpty()) return;
        Region region = new Region();
        for (Pin pin : pins) region.op(pin.bounds, Region.Op.UNION);
        for (int i = ((Number) count.invoke(area)).intValue() - 1; i >= 0; i--) {
            Object task = asTask.invoke(child.invoke(area, i));
            if (task == null || !Boolean.TRUE.equals(visible.invoke(task))
                    || !SmallWindowLimitHooks.expandedMode(((Number) mode.invoke(task)).intValue())) continue;
            Object info = windowInfo.invoke(null, task);
            if (floatInfoClass.isInstance(info) && !Boolean.TRUE.equals(hidden.invoke(info)))
                region.op((Rect) bounds.invoke(info), Region.Op.DIFFERENCE);
        }
        // The dragged pin is above expanded windows until UP/CANCEL, including overlap.
        for (Pin pin : pins)
            if (SmallWindowDragOrder.isActive(pin.task)) region.op(pin.bounds, Region.Op.UNION);
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
