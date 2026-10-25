package dev.rikumi.flymemod;

import android.content.Context;
import android.graphics.PointF;
import android.graphics.Point;
import android.view.MotionEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

final class BackGestureSmartHooks {
    private final Class<?> handler, view, listener;
    private final Consumer<Context> settings;
    private final BooleanSupplier enabled;
    private final BiConsumer<String,Throwable> log;
    private final Field context, allowed, crossed, down, slop, animation, plugin, leftEdge, trackpad;
    private final Field handlingEnabled, useML, uiThread, edgeLeft, edgeRight, mlWidth;
    private final Method pilfer, updateML, executor, viewContext;
    private final Field startX, startY, triggerBackFlag, dragSlopPassed, swipeThreshold, leftPanel, listenerOwner;
    private final Method cancelGesture, vibrationEffect, vibrate;
    private final Field displaySize, leftInset, rightInset, vibrator, releaseVibration;
    private final Map<Object,Object> viewHandlers = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<Object,PointF> viewBounds = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<Object, Boolean> handlers = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<Object, Boolean> handlerFirstMoveHorizontal = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<Object, Boolean> viewFirstMoveHorizontal = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<Object, Long> serviceDownTimes = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<Object, Long> loggedDownTimes = Collections.synchronizedMap(new WeakHashMap<>());

    BackGestureSmartHooks(ClassLoader loader, Consumer<Context> settings, BooleanSupplier enabled,
                          BiConsumer<String,Throwable> log) throws ReflectiveOperationException {
        this.settings=settings; this.enabled=enabled; this.log=log;
        handler=loader.loadClass("com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler");
        context=handler.getField("mContext"); allowed=handler.getField("mAllowGesture");
        crossed=handler.getField("mThresholdCrossed"); down=handler.getField("mDownPoint");
        slop=handler.getField("mTouchSlop"); animation=handler.getField("mBackAnimation");
        plugin=handler.getField("mEdgeBackPlugin");
        leftEdge=handler.getField("mIsOnLeftEdge"); trackpad=handler.getField("mIsTrackpadThreeFingerSwipe");
        handlingEnabled=handler.getField("mIsGestureHandlingEnabled"); useML=handler.getField("mUseMLModel");
        uiThread=handler.getField("mUiThreadContext"); executor=uiThread.getType().getMethod("getExecutor");
        edgeLeft=handler.getField("mEdgeWidthLeft"); edgeRight=handler.getField("mEdgeWidthRight");
        mlWidth=handler.getField("mMLEnableWidth");
        displaySize=handler.getField("mDisplaySize"); leftInset=handler.getField("mLeftInset"); rightInset=handler.getField("mRightInset");
        vibrator=handler.getField("mVibrator"); releaseVibration=handler.getField("mEdgeBackVibrateFeedBack");
        Class<?> effect=loader.loadClass("android.os.VibrationEffect");
        vibrationEffect=effect.getMethod("get",int.class);
        vibrate=vibrator.getType().getMethod("vibrate",effect);
        updateML=handler.getMethod("updateMLModelState");
        handler.getDeclaredMethod("getBackGesturePredictionsCategory",int.class,int.class,int.class);
        pilfer=handler.getDeclaredMethod("pilferPointers"); pilfer.setAccessible(true);
        view=loader.loadClass("com.flyme.systemui.navigationbar.gestural.EdgeBackView");
        viewContext=view.getMethod("getContext");
        if(view.getMethod("whetherTriggerBack",MotionEvent.class).getReturnType()!=void.class)
            throw new NoSuchMethodException("whetherTriggerBack must return void");
        startX=view.getField("mStartX"); startY=view.getField("mStartY");
        triggerBackFlag=view.getField("mTriggerBack");
        dragSlopPassed=view.getField("mDragSlopPassed"); swipeThreshold=view.getField("mSwipeThreshold");
        leftPanel=view.getField("mIsLeftPanel");
        cancelGesture=handler.getMethod("cancelGesture",MotionEvent.class);
        listener=loader.loadClass(handler.getName()+"$SystemUIGestureListener");
        Field outer=null;
        for(Field field:listener.getDeclaredFields()) if(field.getType()==handler) { outer=field; break; }
        if(outer==null)throw new NoSuchFieldException("SystemUIGestureListener outer handler");
        outer.setAccessible(true); listenerOwner=outer;
    }

    void install(SignalHooks.Installer installer, Consumer<Method> deoptimize) throws ReflectiveOperationException {
        // These callers previously relied on deoptimization in the now-disabled width hook.
        deoptimize.accept(handler.getDeclaredMethod("onMotionEvent",MotionEvent.class));
        deoptimize.accept(handler.getDeclaredMethod("onInputEvent",android.view.InputEvent.class));
        deoptimize.accept(handler.getDeclaredMethod("lambda$updateGestureEvent$18",MotionEvent.class));
        deoptimize.accept(handler.getDeclaredMethod("isWithinTouchRegion",MotionEvent.class));
        deoptimize.accept(handler.getDeclaredMethod("updateIsEnabledInner"));
        deoptimize.accept(handler.getClassLoader().loadClass(handler.getName()+"$4")
                .getDeclaredMethod("onPropertiesChanged",handler.getClassLoader().loadClass("android.provider.DeviceConfig$Properties")));
        deoptimize.accept(listener.getDeclaredMethod("onGesturePreTriggerBefore",int.class,MotionEvent.class));
        deoptimize.accept(listener.getDeclaredMethod("onGesturePreTrigger",int.class,MotionEvent.class));
        deoptimize.accept(listener.getDeclaredMethod("onGestureTriggered",int.class,MotionEvent.class));
        deoptimize.accept(view.getDeclaredMethod("handleMoveEvent",MotionEvent.class));
        deoptimize.accept(view.getDeclaredMethod("onMotionEvent",MotionEvent.class));
        installer.hook(handler.getName(),"updateMLModelState",chain -> {
            Object owner=chain.getThisObject(); prepare(owner);
            if(!enabled.getAsBoolean())return chain.proceed();
            synchronized(owner) {
                boolean wasHandling=handlingEnabled.getBoolean(owner), wasML=useML.getBoolean(owner);
                // Let the native disabled branch release the classifier and clear its vocabulary.
                handlingEnabled.setBoolean(owner,false);
                try {
                    Object result=chain.proceed();
                    if(wasML)log.accept("SystemUI back gesture ML disabled and classifier released",null);
                    return result;
                } finally { handlingEnabled.setBoolean(owner,wasHandling); }
            }
        });
        installer.hook(handler.getName(),"getBackGesturePredictionsCategory",chain -> {
            prepare(chain.getThisObject());
            // Also cover admission before updateMLModelState and an in-flight model load.
            return enabled.getAsBoolean()?-1:chain.proceed();
        },int.class,int.class,int.class);
        installer.hook(handler.getName(),"isWithinTouchRegion",chain -> {
            Object owner=chain.getThisObject(); prepare(owner);
            Object result=chain.proceed();
            MotionEvent event=(MotionEvent)chain.getArg(0);
            if(enabled.getAsBoolean() && event.getActionMasked()==MotionEvent.ACTION_DOWN
                    && !Long.valueOf(event.getDownTime()).equals(loggedDownTimes.get(owner))) {
                loggedDownTimes.put(owner,event.getDownTime());
                log.accept("SystemUI side gesture admission: x="+event.getX()+" y="+event.getY()
                        +" left="+edgeLeft.getInt(owner)+" right="+edgeRight.getInt(owner)
                        +" mlWidth="+mlWidth.getInt(owner)+" useML="+useML.getBoolean(owner)
                        +" accepted="+result,null);
            }
            return result;
        },MotionEvent.class);
        installer.hook(listener.getName(),"onGesturePreTrigger",chain -> {
            int type=(Integer)chain.getArg(0);
            MotionEvent event=(MotionEvent)chain.getArg(1);
            if((type==3 || type==4) && event.getActionMasked()==MotionEvent.ACTION_DOWN)
                serviceDownTimes.put(listenerOwner.get(chain.getThisObject()),event.getDownTime());
            return chain.proceed();
        },int.class,MotionEvent.class);
        installer.hook(listener.getName(),"onGestureCanceled",chain -> {
            int type=(Integer)chain.getArg(0);
            if(type==3 || type==4) serviceDownTimes.remove(listenerOwner.get(chain.getThisObject()));
            return chain.proceed();
        },int.class);
        installer.hook(handler.getName(),"onMotionEvent",chain -> {
            Object owner=chain.getThisObject(); prepare(owner);
            MotionEvent event=(MotionEvent)chain.getArg(0);
            int action=event.getActionMasked();
            Object ownedView=plugin.get(owner);
            if(view.isInstance(ownedView))viewHandlers.put(ownedView,owner);
            if(action==MotionEvent.ACTION_DOWN) {
                handlerFirstMoveHorizontal.remove(owner);
                if(!Long.valueOf(event.getDownTime()).equals(serviceDownTimes.get(owner)))
                    serviceDownTimes.remove(owner);
            } else if(enabled.getAsBoolean() && action==MotionEvent.ACTION_MOVE
                    && !handlerFirstMoveHorizontal.containsKey(owner)) {
                boolean fromService=Long.valueOf(event.getDownTime()).equals(serviceDownTimes.get(owner));
                PointF origin=(PointF)down.get(owner);
                PointF first=BackGestureMotion.firstDisplacement(event,origin.x,origin.y,false,fromService?0f:slop.getFloat(owner));
                if(first!=null) {
                    // Flyme forwards MOVE only after system_server accepts the real first sample.
                    boolean horizontal=fromService || (trackpad.getBoolean(owner)
                            ? BackGestureDirectionPolicy.isHorizontalFirstMove(first.x,first.y)
                            : BackGestureDirectionPolicy.isInwardFirstMove(first.x,first.y,leftEdge.getBoolean(owner)));
                    handlerFirstMoveHorizontal.put(owner,horizontal);
                    log.accept("SystemUI side gesture first move: source="+(fromService?"system_server":"direct")
                            +" dx="+first.x+" dy="+first.y+" accepted="+horizontal,null);
                    if(!horizontal && allowed.getBoolean(owner)) cancelGesture.invoke(owner,event);
                }
            }
            if(enabled.getAsBoolean() && action==MotionEvent.ACTION_MOVE) {
                Boolean verdict=handlerFirstMoveHorizontal.get(owner);
                Object currentPlugin=plugin.get(owner);
                if(verdict!=null && view.isInstance(currentPlugin)) viewFirstMoveHorizontal.put(currentPlugin,verdict);
                if(Boolean.TRUE.equals(verdict) && allowed.getBoolean(owner) && !crossed.getBoolean(owner)) {
                    PointF origin=(PointF)down.get(owner);
                    if(distance(event.getX(),event.getY(),origin.x,origin.y)>slop.getFloat(owner)) {
                        Object backAnimation=animation.get(owner);
                        if(backAnimation==null)pilfer.invoke(owner);
                        else backAnimation.getClass().getMethod("onThresholdCrossed").invoke(backAnimation);
                        crossed.setBoolean(owner,true);
                    }
                }
            }
            try { return chain.proceed(); }
            finally {
                if(action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL) {
                    handlerFirstMoveHorizontal.remove(owner);
                    serviceDownTimes.remove(owner);
                }
            }
        },MotionEvent.class);
        installer.hook(view.getName(),"onMotionEvent",chain -> {
            Object owner=chain.getThisObject();
            MotionEvent event=(MotionEvent)chain.getArg(0);
            int action=event.getActionMasked();
            synchronized(owner) {
                if(action==MotionEvent.ACTION_DOWN) {
                    viewFirstMoveHorizontal.remove(owner);
                    Object handlerOwner=viewHandlers.get(owner);
                    if(handlerOwner!=null)viewBounds.put(owner,new PointF(
                            edgeLeft.getInt(handlerOwner)+leftInset.getInt(handlerOwner),
                            ((Point)displaySize.get(handlerOwner)).x-edgeRight.getInt(handlerOwner)-rightInset.getInt(handlerOwner)));
                }
                else if(enabled.getAsBoolean() && action==MotionEvent.ACTION_MOVE
                        && !viewFirstMoveHorizontal.containsKey(owner)) {
                    PointF first=BackGestureMotion.firstDisplacement(event,startX.getFloat(owner),startY.getFloat(owner),false,swipeThreshold.getFloat(owner));
                    if(first!=null)viewFirstMoveHorizontal.put(owner,
                            BackGestureDirectionPolicy.isInwardFirstMove(first.x,first.y,leftPanel.getBoolean(owner)));
                }
                if(enabled.getAsBoolean() && action==MotionEvent.ACTION_UP) updateBoundary(owner,event,false);
                if(enabled.getAsBoolean() && action==MotionEvent.ACTION_UP)
                    log.accept("SystemUI back gesture release: x="+event.getX()+" y="+event.getY()
                            +" trigger="+triggerBackFlag.getBoolean(owner),null);
                Object handlerOwner=viewHandlers.get(owner);
                boolean suppress=enabled.getAsBoolean() && action==MotionEvent.ACTION_UP && handlerOwner!=null;
                boolean previous=suppress && releaseVibration.getBoolean(handlerOwner);
                if(suppress)releaseVibration.setBoolean(handlerOwner,false);
                try { return chain.proceed(); }
                finally {
                    if(suppress)releaseVibration.setBoolean(handlerOwner,previous);
                    if(action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL) {
                        viewFirstMoveHorizontal.remove(owner);
                        viewBounds.remove(owner);
                    }
                }
            }
        },MotionEvent.class);
        // Admission inside the native 12dp band must never bypass our final verdict.
        installer.hook(view.getName(),"triggerBack",chain -> {
            Object owner=chain.getThisObject();
            if(enabled.getAsBoolean() && (!Boolean.TRUE.equals(viewFirstMoveHorizontal.get(owner))
                    || !viewBounds.containsKey(owner) || !triggerBackFlag.getBoolean(owner))) {
                triggerBackFlag.setBoolean(owner,false);
                view.getMethod("cancelBack").invoke(owner);
                return null;
            }
            return chain.proceed();
        });
        installer.hook(view.getName(),"whetherTriggerBack",chain -> {
            Object owner=chain.getThisObject(); settings.accept((Context)viewContext.invoke(owner));
            if(!enabled.getAsBoolean())return chain.proceed();
            MotionEvent event=(MotionEvent)chain.getArg(0);
            synchronized(owner) {
                if(Boolean.FALSE.equals(viewFirstMoveHorizontal.get(owner))) {
                    triggerBackFlag.setBoolean(owner,false);
                    return null;
                }
                if(!Boolean.TRUE.equals(viewFirstMoveHorizontal.get(owner)))return null;
                updateBoundary(owner,event,true);
                return null;
            }
        },MotionEvent.class);
        log.accept("SystemUI side gesture hooks registered: enabled="+enabled.getAsBoolean()+" ML bypass independent of width",null);
    }

    private void updateBoundary(Object owner, MotionEvent event, boolean feedback) throws ReflectiveOperationException {
        PointF bounds=viewBounds.get(owner);
        Object handlerOwner=viewHandlers.get(owner);
        if(bounds==null || handlerOwner==null) {
            triggerBackFlag.setBoolean(owner,false);
            return;
        }
        boolean wasOutside=triggerBackFlag.getBoolean(owner);
        boolean outside=Boolean.TRUE.equals(viewFirstMoveHorizontal.get(owner))
                && (leftPanel.getBoolean(owner)?event.getX()>bounds.x:event.getX()<bounds.y);
        triggerBackFlag.setBoolean(owner,outside);
        if(outside)dragSlopPassed.setBoolean(owner,true);
        if(feedback && outside && !wasOutside)
            vibrate.invoke(vibrator.get(handlerOwner),vibrationEffect.invoke(null,31025));
    }

    private void prepare(Object owner) throws IllegalAccessException {
        handlers.put(owner,true);
        settings.accept((Context)context.get(owner));
    }

    void refresh() {
        ArrayList<Object> current;
        synchronized(handlers) { current=new ArrayList<>(handlers.keySet()); }
        for(Object owner:current) {
            try {
                ((Executor)executor.invoke(uiThread.get(owner))).execute(() -> {
                    try { updateML.invoke(owner); }
                    catch(ReflectiveOperationException error) { log.accept("Cannot refresh back gesture ML policy",error); }
                });
            } catch(ReflectiveOperationException error) { log.accept("Cannot schedule back gesture ML policy",error); }
        }
    }

    static double distance(float x,float y,float startX,float startY) {
        return Math.hypot(x-startX,y-startY);
    }
}
