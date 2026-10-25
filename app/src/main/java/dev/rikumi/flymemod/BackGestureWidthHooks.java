package dev.rikumi.flymemod;

import android.content.Context;
import android.view.MotionEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

final class BackGestureWidthHooks {
    private static final String OWNER = "com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler";
    private final Field context, left, right, mlWidth, uiThread;
    private final Method update, executor;
    private final Consumer<Context> settings;
    private final BooleanSupplier enabled, removeSmart;
    private final IntSupplier widthDp;
    private final BiConsumer<String, Throwable> log;
    private final Map<Object, Integer> nativeMlWidths = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<Object, Boolean> handlers = Collections.synchronizedMap(new WeakHashMap<>());

    BackGestureWidthHooks(ClassLoader loader, Consumer<Context> settings, BooleanSupplier enabled,
                          IntSupplier widthDp, BooleanSupplier removeSmart, BiConsumer<String, Throwable> log) throws ReflectiveOperationException {
        this.settings = settings; this.enabled = enabled; this.widthDp = widthDp; this.removeSmart = removeSmart; this.log = log;
        Class<?> type = loader.loadClass(OWNER);
        context = type.getField("mContext"); left = type.getField("mEdgeWidthLeft");
        right = type.getField("mEdgeWidthRight"); mlWidth = type.getField("mMLEnableWidth");
        uiThread = type.getField("mUiThreadContext");
        executor = uiThread.getType().getMethod("getExecutor");
        update = type.getMethod("updateCurrentUserResources");
        type.getDeclaredMethod("isWithinTouchRegion", MotionEvent.class);
    }

    void install(SignalHooks.Installer installer, Consumer<Method> deoptimize) throws ReflectiveOperationException {
        installer.hook(OWNER, "updateCurrentUserResources", chain -> {
            Object owner = chain.getThisObject();
            settings.accept((Context) context.get(owner));
            Object result = chain.proceed();
            nativeMlWidths.put(owner, mlWidth.getInt(owner));
            handlers.put(owner, true);
            apply(owner);
            return result;
        });
        installer.hook(OWNER, "isWithinTouchRegion", chain -> {
            Object owner=chain.getThisObject();
            settings.accept((Context)context.get(owner));
            handlers.put(owner,true);
            synchronized(owner) {
                apply(owner);
                return enabled.getAsBoolean() && widthDp.getAsInt()==0?false:chain.proceed();
            }
        }, MotionEvent.class);
        Class<?> type=context.getDeclaringClass();
        installer.hook(OWNER,"onMotionEvent",chain -> {
            prepare(chain.getThisObject());
            return chain.proceed();
        },MotionEvent.class);
        Class<?> listener=type.getClassLoader().loadClass(OWNER+"$SystemUIGestureListener");
        Field outer=null;
        for(Field field:listener.getDeclaredFields()) if(field.getType()==type) { outer=field; break; }
        if(outer==null)throw new NoSuchFieldException("SystemUIGestureListener outer handler");
        outer.setAccessible(true);
        Field ownerField=outer;
        installer.hook(listener.getName(),"onGesturePreTriggerBefore",chain -> {
            prepare(ownerField.get(chain.getThisObject()));
            return chain.proceed();
        },int.class,MotionEvent.class);
        deoptimize.accept(type.getDeclaredMethod("onMotionEvent",MotionEvent.class));
        deoptimize.accept(listener.getDeclaredMethod("onGesturePreTriggerBefore",int.class,MotionEvent.class));
        deoptimize.accept(type.getDeclaredMethod("onInputEvent",android.view.InputEvent.class));
        deoptimize.accept(type.getDeclaredMethod("lambda$updateGestureEvent$18",MotionEvent.class));
    }

    private void prepare(Object owner) throws IllegalAccessException {
        settings.accept((Context)context.get(owner));
        handlers.put(owner,true);
        synchronized(owner) { apply(owner); }
    }

    private void apply(Object owner) throws IllegalAccessException {
        nativeMlWidths.putIfAbsent(owner, mlWidth.getInt(owner));
        if(enabled.getAsBoolean()) {
            int pixels=widthPixels((Context)context.get(owner),widthDp.getAsInt());
            boolean changed=left.getInt(owner)!=pixels || right.getInt(owner)!=pixels;
            left.setInt(owner,pixels); right.setInt(owner,pixels);
            if(changed)log.accept("Back gesture width: dp="+widthDp.getAsInt()+" pixels="+pixels,null);
        }
        // Cover the entire geometric edge band only when smart filtering is removed.
        mlWidth.setInt(owner, removeSmart.getAsBoolean() ? Math.max(left.getInt(owner),right.getInt(owner))
                : Math.min(nativeMlWidths.get(owner),Math.min(left.getInt(owner),right.getInt(owner))));
    }

    void refresh() {
        ArrayList<Object> current;
        synchronized (handlers) { current = new ArrayList<>(handlers.keySet()); }
        for (Object owner : current) {
            try {
                ((Executor) executor.invoke(uiThread.get(owner))).execute(() -> {
                    try { update.invoke(owner); }
                    catch (ReflectiveOperationException error) { log.accept("Cannot refresh back gesture width", error); }
                });
            } catch (ReflectiveOperationException error) { log.accept("Cannot schedule back gesture width refresh", error); }
        }
    }

    static int widthPixels(Context context, int dp) {
        return Math.round(Math.max(0, dp) * context.getResources().getDisplayMetrics().density);
    }
}
