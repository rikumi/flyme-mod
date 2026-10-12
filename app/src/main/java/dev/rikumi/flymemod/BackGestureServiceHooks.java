package dev.rikumi.flymemod;

import android.content.SharedPreferences;
import android.content.Context;
import android.graphics.PointF;
import android.view.MotionEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.BiConsumer;

/** Flyme filters side swipes in system_server before consulting SystemUI. */
final class BackGestureServiceHooks {
    private static final int FIRST_MOVE_VERTICAL = 0;
    private static final int FIRST_MOVE_HORIZONTAL = 1;

    static void install(ClassLoader loader, SharedPreferences preferences, SignalHooks.Installer installer,
                        Consumer<Method> deoptimize, BiConsumer<String,Throwable> log) throws ReflectiveOperationException {
        Class<?> recent = loader.loadClass("com.android.server.policy.gesture.RecentGestureListener");
        Class<?> side = loader.loadClass("com.android.server.policy.gesture.LeftRightGestureListener");
        deoptimize.accept(recent.getDeclaredMethod("interceptMotionBeforeQueueing", MotionEvent.class, int.class));
        deoptimize.accept(loader.loadClass("com.android.server.policy.gesture.SystemGesture")
                .getDeclaredMethod("interceptMotionBeforeQueueing",MotionEvent.class,int.class));
        Field edgeWidth=recent.getDeclaredField("GESTURE_VALID_LEFT_OFFSET"); edgeWidth.setAccessible(true);
        Field context=recent.getSuperclass().getDeclaredField("mContext"); context.setAccessible(true);
        Field direction=recent.getDeclaredField("mDir"); direction.setAccessible(true);
        Field startX=recent.getDeclaredField("mRecentPanelDownPosX"); startX.setAccessible(true);
        Field startY=recent.getDeclaredField("mRecentPanelDownPosY"); startY.setAccessible(true);
        Field slop=recent.getDeclaredField("RECENT_GESTURE_TOUCH_SLOP"); slop.setAccessible(true);
        Field landscapeSlop=recent.getDeclaredField("mDeviceBottomTouchSlop"); landscapeSlop.setAccessible(true);
        Field state=recent.getSuperclass().getDeclaredField("mGestureState"); state.setAccessible(true);
        Field downTime=recent.getSuperclass().getDeclaredField("mMotionDownTime"); downTime.setAccessible(true);
        Map<Object, Integer> firstMoveAxes = Collections.synchronizedMap(new WeakHashMap<>());
        Object pending=null, triggered=null, canceled=null;
        for(Object value:state.getType().getEnumConstants()) {
            if(((Enum<?>)value).name().equals("PENDING_CHECK"))pending=value;
            if(((Enum<?>)value).name().equals("TRIGGERED"))triggered=value;
            if(((Enum<?>)value).name().equals("CANCELED"))canceled=value;
        }
        if(pending==null || triggered==null || canceled==null)throw new NoSuchFieldException("GestureState");
        Object pendingState=pending, triggeredState=triggered, canceledState=canceled;
        installer.hook(recent.getName(), "interceptMotionBeforeQueueing", chain -> {
            Object owner=chain.getThisObject();
            if(!side.isInstance(owner)) return chain.proceed();
            MotionEvent event=(MotionEvent)chain.getArg(0);
            int action=event.getActionMasked();
            synchronized(owner) {
                int nativeWidth=edgeWidth.getInt(owner);
                if(preferences.getBoolean(ModuleSettings.BACK_GESTURE_WIDTH_ENABLED,false)) {
                    int dp=Math.max(0,Math.min(ModuleSettings.BACK_GESTURE_WIDTH_MAX_DEFAULT,
                            preferences.getInt(ModuleSettings.BACK_GESTURE_WIDTH_DP,ModuleSettings.BACK_GESTURE_WIDTH_DEFAULT)));
                    edgeWidth.setInt(owner,Math.round(dp*((Context)context.get(owner)).getResources().getDisplayMetrics().density));
                }
                if(action==MotionEvent.ACTION_DOWN) {
                    firstMoveAxes.remove(owner);

                }
                else if(action==MotionEvent.ACTION_MOVE && state.get(owner)==pendingState
                        && !firstMoveAxes.containsKey(owner)) {
                    String dir=((Enum<?>)direction.get(owner)).name();
                    int threshold=dir.startsWith("HORIZONTAL")?slop.getInt(owner):landscapeSlop.getInt(owner);
                    PointF first=BackGestureMotion.firstDisplacement(event,startX.getFloat(owner),startY.getFloat(owner),true,threshold);
                    if(first!=null) {
                        boolean horizontal=BackGestureDirectionPolicy.isInwardFirstMove(first.x,first.y,dir.endsWith("LEFT"));
                        firstMoveAxes.put(owner,horizontal?FIRST_MOVE_HORIZONTAL:FIRST_MOVE_VERTICAL);
                        if(preferences.getBoolean(ModuleSettings.BACK_GESTURE_REMOVE_SMART,true))
                            log.accept("System side gesture first move: downX="+startX.getFloat(owner)
                                    +" downY="+startY.getFloat(owner)+" dx="+first.x+" dy="+first.y
                                    +" slop="+threshold+" accepted="+horizontal,null);
                    }
                }
                try {
                    Object result=chain.proceed();
                    if(action==MotionEvent.ACTION_DOWN && state.get(owner)==pendingState
                            && preferences.getBoolean(ModuleSettings.BACK_GESTURE_REMOVE_SMART,true))
                        log.accept("System side gesture admitted: x="+event.getRawX()+" y="+event.getRawY()
                                +" direction="+direction.get(owner),null);
                    return result;
                }
                finally { edgeWidth.setInt(owner,nativeWidth); clearFirstMoveAxis(action, owner, firstMoveAxes); }
            }
        }, MotionEvent.class,int.class);
        installer.hook(recent.getName(),"checkRecentsGesture",chain -> {
            Object owner=chain.getThisObject();
            if(!side.isInstance(owner) || !preferences.getBoolean(ModuleSettings.BACK_GESTURE_REMOVE_SMART,true))
                return chain.proceed();
            Integer firstAxis=firstMoveAxes.get(owner);
            if(System.currentTimeMillis()-downTime.getLong(owner)>300) {
                state.set(owner,canceledState);
                return null;
            }
            if(firstAxis==null)return null; // Wait for the first sample beyond native touch slop.
            if(firstAxis==FIRST_MOVE_VERTICAL) {
                state.set(owner,canceledState);
                return null;
            }
            String dir=((Enum<?>)direction.get(owner)).name();
            if(!dir.endsWith("LEFT") && !dir.endsWith("RIGHT"))return chain.proceed();
            // Keep the native timeout and minimum movement; the first move beyond touch slop owns direction.
            MotionEvent event=(MotionEvent)chain.getArg(0);
            int threshold=dir.startsWith("HORIZONTAL")?slop.getInt(owner):landscapeSlop.getInt(owner);
            float dx=event.getRawX()-startX.getFloat(owner);
            float dy=event.getRawY()-startY.getFloat(owner);
            if(Math.hypot(dx,dy)>threshold) {
                state.set(owner,triggeredState);
            }
            return null;
        },MotionEvent.class);
        log.accept("System side gesture direction hooks registered: relaxed="
                +preferences.getBoolean(ModuleSettings.BACK_GESTURE_REMOVE_SMART,true),null);
    }

    private static void clearFirstMoveAxis(int action, Object owner, Map<Object, Integer> firstMoveAxes) {
        if(action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL) firstMoveAxes.remove(owner);
    }
}
