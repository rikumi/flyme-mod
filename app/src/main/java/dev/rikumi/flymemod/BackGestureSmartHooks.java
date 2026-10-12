package dev.rikumi.flymemod;

import android.content.Context;
import android.graphics.PointF;
import android.view.MotionEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Flyme's side gesture and visual confirmation use displacement without directional rejection. */
final class BackGestureSmartHooks {
    private final Class<?> handler, view;
    private final Consumer<Context> settings;
    private final BooleanSupplier enabled;
    private final BiConsumer<String,Throwable> log;
    private final Field context, allowed, crossed, down, slop, animation;
    private final Method pilfer;
    private final Method viewContext;
    private final Method triggerBack;
    private final Field startX, startY, triggerBackFlag;
    private final Method cancelGesture;
    private final Map<Object, Boolean> handlerFirstMoveHorizontal = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<Object, Boolean> viewFirstMoveHorizontal = Collections.synchronizedMap(new WeakHashMap<>());

    BackGestureSmartHooks(ClassLoader loader, Consumer<Context> settings, BooleanSupplier enabled,
                          BiConsumer<String,Throwable> log)
            throws ReflectiveOperationException {
        this.settings=settings; this.enabled=enabled; this.log=log;
        handler=loader.loadClass("com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler");
        context=handler.getField("mContext"); allowed=handler.getField("mAllowGesture");
        crossed=handler.getField("mThresholdCrossed"); down=handler.getField("mDownPoint");
        slop=handler.getField("mTouchSlop"); animation=handler.getField("mBackAnimation");
        pilfer=handler.getDeclaredMethod("pilferPointers"); pilfer.setAccessible(true);
        view=loader.loadClass("com.flyme.systemui.navigationbar.gestural.EdgeBackView");
        viewContext=view.getMethod("getContext");
        triggerBack=view.getMethod("whetherTriggerBack",MotionEvent.class);
        if(triggerBack.getReturnType()!=void.class)
            throw new NoSuchMethodException("whetherTriggerBack must return void");
        startX=view.getField("mStartX"); startY=view.getField("mStartY");
        triggerBackFlag=view.getField("mTriggerBack");
        cancelGesture=handler.getMethod("cancelGesture",MotionEvent.class);
    }

    void install(SignalHooks.Installer installer, Consumer<Method> deoptimize) throws ReflectiveOperationException {
        deoptimize.accept(view.getDeclaredMethod("handleMoveEvent",MotionEvent.class));
        deoptimize.accept(view.getDeclaredMethod("onMotionEvent",MotionEvent.class));
        installer.hook(handler.getName(),"onMotionEvent",chain -> {
            Object owner=chain.getThisObject(); settings.accept((Context)context.get(owner));
            MotionEvent event=(MotionEvent)chain.getArg(0);
            int action=event.getActionMasked();
            if(action==MotionEvent.ACTION_DOWN) handlerFirstMoveHorizontal.remove(owner);
            else if(enabled.getAsBoolean() && action==MotionEvent.ACTION_MOVE
                    && !handlerFirstMoveHorizontal.containsKey(owner)) {
                PointF origin=(PointF)down.get(owner);
                float dx=event.getX()-origin.x, dy=event.getY()-origin.y;
                if(dx!=0f || dy!=0f) {
                    boolean horizontal=BackGestureDirectionPolicy.isHorizontalFirstMove(dx,dy);
                    handlerFirstMoveHorizontal.put(owner,horizontal);
                    log.accept("SystemUI side gesture first move: dx="+dx+" dy="+dy+" accepted="+horizontal,null);
                    if(!horizontal && allowed.getBoolean(owner)) cancelGesture.invoke(owner,event);
                }
            }
            if(enabled.getAsBoolean() && event.getActionMasked()==MotionEvent.ACTION_MOVE
                    && Boolean.TRUE.equals(handlerFirstMoveHorizontal.get(owner))
                    && allowed.getBoolean(owner) && !crossed.getBoolean(owner)) {
                PointF origin=(PointF)down.get(owner);
                if(distance(event.getX(),event.getY(),origin.x,origin.y)>slop.getFloat(owner)) {
                    Object backAnimation=animation.get(owner);
                    if(backAnimation==null)pilfer.invoke(owner);
                    else backAnimation.getClass().getMethod("onThresholdCrossed").invoke(backAnimation);
                    crossed.setBoolean(owner,true);
                }
            }
            try { return chain.proceed(); }
            finally {
                if(action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL)
                    handlerFirstMoveHorizontal.remove(owner);
            }
        },MotionEvent.class);
        installer.hook(view.getName(),"onMotionEvent",chain -> {
            Object owner=chain.getThisObject();
            MotionEvent event=(MotionEvent)chain.getArg(0);
            int action=event.getActionMasked();
            synchronized(owner) {
                if(action==MotionEvent.ACTION_DOWN) viewFirstMoveHorizontal.remove(owner);
                else if(enabled.getAsBoolean() && action==MotionEvent.ACTION_MOVE
                        && !viewFirstMoveHorizontal.containsKey(owner)) {
                    float dx=event.getX()-startX.getFloat(owner);
                    float dy=event.getY()-startY.getFloat(owner);
                    if(dx!=0f || dy!=0f)
                        viewFirstMoveHorizontal.put(owner,BackGestureDirectionPolicy.isHorizontalFirstMove(dx,dy));
                }
                try { return chain.proceed(); }
                finally {
                    if(action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL)
                        viewFirstMoveHorizontal.remove(owner);
                }
            }
        },MotionEvent.class);
        installer.hook(view.getName(),"whetherTriggerBack",chain -> {
            Object owner=chain.getThisObject(); settings.accept((Context)viewContext.invoke(owner));
            if(!enabled.getAsBoolean())return chain.proceed();
            MotionEvent event=(MotionEvent)chain.getArg(0);
            synchronized(owner) {
                if(Boolean.FALSE.equals(viewFirstMoveHorizontal.get(owner))) {
                    triggerBackFlag.setBoolean(owner,false);
                    return null;
                }
                if(!Boolean.TRUE.equals(viewFirstMoveHorizontal.get(owner)))return chain.proceed();
                float originX=startX.getFloat(owner);
                float displacement=(float)distance(event.getX(),event.getY(),originX,startY.getFloat(owner));
                // Feed radial displacement into the native confirmation method.
                // Its accumulated delta, reversal threshold and cancellation remain unchanged.
                startX.setFloat(owner,event.getX()-displacement);
                try { return chain.proceed(); }
                finally {
                    startX.setFloat(owner,originX);
                }
            }
        },MotionEvent.class);
        log.accept("SystemUI side gesture hooks registered: enabled="+enabled.getAsBoolean(),null);
    }

    static double distance(float x,float y,float startX,float startY) {
        return Math.hypot(x-startX,y-startY);
    }
}
