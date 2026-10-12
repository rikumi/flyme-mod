package dev.rikumi.flymemod;

import android.content.Context;
import android.graphics.Rect;
import java.lang.ref.WeakReference;
import android.view.View;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.*;

/** Flyme 13 TaskView thumbnails and TaskViewSimulator share this card-to-window interpolation. */
final class RecentsCornerHooks {
    private static final String PARAMS = "com.android.quickstep.FullscreenDrawParams";
    private static final String TASK = "com.android.quickstep.views.TaskView";
    private final Class<?> paramsClass;
    private final Field taskRadius, windowRadius, currentRadius, noScaleRadius;
    private final Method setProgress, updateTask, taskParams, container, deviceProfile;
    private final Field profileWidth, simulatorProfile, simulatorTaskBounds;
    private final Field simulatorParams, simulatorContext;
    private final Class<?> transformParams, surfaceTransaction;
    private final Consumer<Context> settings;
    private final BooleanSupplier enabled;
    private final IntSupplier radiusDp;
    private final BiConsumer<String, Throwable> log;
    private final Map<Object, State> states = new WeakHashMap<>();
    private final Map<View, Boolean> cards = new WeakHashMap<>();

    RecentsCornerHooks(ClassLoader loader, Consumer<Context> settings, BooleanSupplier enabled,
            IntSupplier radiusDp, BiConsumer<String, Throwable> log) throws ReflectiveOperationException {
        this.settings=settings; this.enabled=enabled; this.radiusDp=radiusDp; this.log=log;
        paramsClass=loader.loadClass(PARAMS);
        taskRadius=field(paramsClass,"taskCornerRadius"); windowRadius=field(paramsClass,"windowCornerRadius");
        currentRadius=field(paramsClass,"currentCornerRadius"); noScaleRadius=field(paramsClass,"mCurrentNoScaleCornerRadius");
        paramsClass.getDeclaredMethod("updateCornerRadius",Context.class);
        setProgress=paramsClass.getDeclaredMethod("setProgress",float.class,float.class,float.class);
        Class<?> task=loader.loadClass(TASK);
        task.getDeclaredMethod("onAttachedToWindow");
        taskParams=task.getDeclaredMethod("getThumbnailFullscreenParams"); taskParams.setAccessible(true);
        container=task.getDeclaredMethod("getContainer"); container.setAccessible(true);
        deviceProfile=container.getReturnType().getMethod("getDeviceProfile");
        profileWidth=loader.loadClass("com.android.launcher3.DeviceProfile").getField("widthPx");
        task.getDeclaredMethod("onLayout",boolean.class,int.class,int.class,int.class,int.class);
        Class<?> simulator=loader.loadClass("com.android.quickstep.util.TaskViewSimulator");
        simulatorProfile=field(simulator,"mDp"); simulatorTaskBounds=field(simulator,"mFullTaskSize");
        simulatorParams=field(simulator,"mCurrentFullscreenParams"); simulatorContext=field(simulator,"mContext");
        transformParams=loader.loadClass("com.android.quickstep.util.TransformParams");
        surfaceTransaction=loader.loadClass("com.android.quickstep.util.SurfaceTransaction");
        simulator.getDeclaredMethod("apply",transformParams,surfaceTransaction);
        updateTask=task.getDeclaredMethod("updateFullscreenParams"); updateTask.setAccessible(true);
    }
    private static Field field(Class<?> type,String name) throws ReflectiveOperationException {
        Field result=type.getDeclaredField(name); result.setAccessible(true); return result;
    }
    void install(SignalHooks.Installer installer,Consumer<Method> deoptimize) throws ReflectiveOperationException {
        installer.hook(PARAMS,"updateCornerRadius",chain -> {
            Object result=chain.proceed(); Object params=chain.getThisObject();
            // Desktop/freeform params use the same provider for both ends. Keep their native window geometry.
            track(params,(Context)chain.getArg(0));
            return result;
        },Context.class);
        installer.hook(PARAMS,"setProgress",chain -> {
            Object params=chain.getThisObject(); State state;
            synchronized(states) { state=states.get(params); }
            if(state==null || params.getClass()!=paramsClass) return chain.proceed();
            state.progress=(Float)chain.getArg(0); state.parentScale=(Float)chain.getArg(1);
            state.taskScale=(Float)chain.getArg(2); state.hasProgress=true;
            float nativeTask=taskRadius.getFloat(params);
            // The standalone legacy thumbnail starts with zero providers until a real TaskView assigns its params.
            if(!enabled.getAsBoolean() || nativeTask<=0 || state.parentScale<=0 || state.taskScale<=0)
                return chain.proceed();
            float task=cardPixels(state);
            if(!Float.isFinite(task) || task<=0) return chain.proceed();
            taskRadius.setFloat(params,task);
            try {
                Object result=chain.proceed();
                float window=windowRadius.getFloat(params);
                currentRadius.setFloat(params,RecentsCornerGeometry.drawn(task,window,state.progress,state.parentScale,state.taskScale));
                // Leave the native window endpoint/provider untouched, including its taskbar-specific logic.
                noScaleRadius.setFloat(params,RecentsCornerGeometry.interpolated(task,window,state.progress));
                return result;
            } finally { taskRadius.setFloat(params,nativeTask); }
        },float.class,float.class,float.class);
        installer.hook(TASK,"onAttachedToWindow",chain -> {
            View card=(View)chain.getThisObject();
            settings.accept(card.getContext()); trackOwner(taskParams.invoke(card),card.getContext(),card,false);
            Object result=chain.proceed(); synchronized(cards) { cards.put(card,true); }
            if(enabled.getAsBoolean()) updateTask.invoke(card);
            return result;
        });
        installer.hook(TASK,"updateFullscreenParams",chain -> {
            View card=(View)chain.getThisObject();
            trackOwner(chain.getArg(0),card.getContext(),card,false);
            return chain.proceed();
        },paramsClass);
        installer.hook("com.android.quickstep.util.TaskViewSimulator","apply",chain -> {
            Object simulator=chain.getThisObject();
            trackOwner(simulatorParams.get(simulator),(Context)simulatorContext.get(simulator),simulator,true);
            return chain.proceed();
        },transformParams,surfaceTransaction);
        installer.hook(TASK,"onLayout",chain -> {
            Object result=chain.proceed();
            if(enabled.getAsBoolean()) updateTask.invoke(chain.getThisObject());
            return result;
        },boolean.class,int.class,int.class,int.class,int.class);
        // Kotlin/ART can inline the progress setter into these callers.
        for(String name:new String[]{PARAMS,TASK,"com.android.quickstep.util.TaskViewSimulator",
                "com.android.quickstep.views.DesktopTaskView"})
            for(Method method:paramsClass.getClassLoader().loadClass(name).getDeclaredMethods())
                if(method.getName().equals("setProgress") || method.getName().equals("updateCornerRadius")
                        || method.getName().equals("updateFullscreenParams") || method.getName().equals("apply")
                        || method.getName().equals("onAttachedToWindow") || method.getName().equals("onLayout")) deoptimize.accept(method);
    }
    private void track(Object params,Context context) {
        if(params==null || params.getClass()!=paramsClass) return;
        synchronized(states) {
            State state=states.get(params);
            if(state==null) states.put(params,new State(context.getResources().getDisplayMetrics().density));
            else state.density=context.getResources().getDisplayMetrics().density;
        }
    }
    private void trackOwner(Object params,Context context,Object owner,boolean simulator) {
        track(params,context);
        synchronized(states) {
            State state=states.get(params);
            if(state!=null) { state.owner=new WeakReference<>(owner); state.simulator=simulator; }
        }
    }
    private float cardPixels(State state) throws ReflectiveOperationException {
        Object owner=state.owner==null?null:state.owner.get();
        if(owner==null) return Float.NaN;
        float width;
        Object profile;
        if(state.simulator) {
            // apply() recalculates this rectangle before calling setProgress(). Read
            // it here, not before apply, so rotations/layout changes cannot use stale geometry.
            width=((Rect)simulatorTaskBounds.get(owner)).width();
            profile=simulatorProfile.get(owner);
        } else {
            View card=(View)owner;
            width=card.getMeasuredWidth();
            profile=deviceProfile.invoke(container.invoke(card));
        }
        return profile==null?Float.NaN:RecentsCornerGeometry.cardPixels(radiusDp.getAsInt(),state.density,
                width,profileWidth.getInt(profile));
    }
    void refresh() {
        ArrayList<Map.Entry<Object,State>> current;
        synchronized(states) { current=new ArrayList<>(states.entrySet()); }
        for(Map.Entry<Object,State> entry:current) {
            State state=entry.getValue();
            if(!state.hasProgress) continue;
            try { setProgress.invoke(entry.getKey(),state.progress,state.parentScale,state.taskScale); }
            catch(ReflectiveOperationException | RuntimeException error) { log.accept("Cannot refresh recent-task animation corners",error); }
        }
        ArrayList<View> views;
        synchronized(cards) { views=new ArrayList<>(cards.keySet()); }
        for(View card:views) if(card!=null && card.isAttachedToWindow()) {
            try { updateTask.invoke(card); card.invalidate(); card.invalidateOutline(); }
            catch(ReflectiveOperationException | RuntimeException error) { log.accept("Cannot refresh recent-task thumbnail corners",error); }
        }
    }
    private static final class State {
        float density;
        WeakReference<Object> owner;
        boolean simulator;
        float progress, parentScale=1, taskScale=1;
        boolean hasProgress;
        State(float density) { this.density=density; }
    }
}
