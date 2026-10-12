package dev.rikumi.flymemod;

import android.content.SharedPreferences;
import android.content.Context;
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
        Field width = recent.getDeclaredField("GESTURE_VALID_LEFT_OFFSET");
        width.setAccessible(true);
        Field context = recent.getSuperclass().getDeclaredField("mContext");
        context.setAccessible(true);
        deoptimize.accept(recent.getDeclaredMethod("interceptMotionBeforeQueueing", MotionEvent.class, int.class));
        deoptimize.accept(loader.loadClass("com.android.server.policy.gesture.SystemGesture")
                .getDeclaredMethod("interceptMotionBeforeQueueing",MotionEvent.class,int.class));
        Field direction=recent.getDeclaredField("mDir"); direction.setAccessible(true);
        Field startX=recent.getDeclaredField("mRecentPanelDownPosX"); startX.setAccessible(true);
        Field startY=recent.getDeclaredField("mRecentPanelDownPosY"); startY.setAccessible(true);
        Field slop=recent.getDeclaredField("RECENT_GESTURE_TOUCH_SLOP"); slop.setAccessible(true);
        Field landscapeSlop=recent.getDeclaredField("mDeviceBottomTouchSlop"); landscapeSlop.setAccessible(true);
        Field state=recent.getSuperclass().getDeclaredField("mGestureState"); state.setAccessible(true);
        Field downTime=recent.getSuperclass().getDeclaredField("mMotionDownTime"); downTime.setAccessible(true);
        Map<Object, Integer> firstMoveAxes = Collections.synchronizedMap(new WeakHashMap<>());
        Object triggered=null, canceled=null;
        for(Object value:state.getType().getEnumConstants()) {
            if(((Enum<?>)value).name().equals("TRIGGERED"))triggered=value;
            if(((Enum<?>)value).name().equals("CANCELED"))canceled=value;
        }
        if(triggered==null || canceled==null)throw new NoSuchFieldException("GestureState");
        Object triggeredState=triggered, canceledState=canceled;
        installer.hook(recent.getName(), "interceptMotionBeforeQueueing", chain -> {
            Object owner=chain.getThisObject();
            if(!side.isInstance(owner)) return chain.proceed();
            MotionEvent event=(MotionEvent)chain.getArg(0);
            int action=event.getActionMasked();
            synchronized(owner) {
                if(action==MotionEvent.ACTION_DOWN) firstMoveAxes.remove(owner);
                else if(action==MotionEvent.ACTION_MOVE && !firstMoveAxes.containsKey(owner)) {
                    float dx=event.getRawX()-startX.getFloat(owner);
                    float dy=event.getRawY()-startY.getFloat(owner);
                    firstMoveAxes.put(owner, BackGestureDirectionPolicy.isHorizontalFirstMove(dx,dy)
                            ? FIRST_MOVE_HORIZONTAL : FIRST_MOVE_VERTICAL);
                }
                boolean overrideWidth=preferences.contains(ModuleSettings.BACK_GESTURE_WIDTH_DP)
                        && preferences.getBoolean(ModuleSettings.BACK_GESTURE_WIDTH_ENABLED, false);
                if(!overrideWidth) {
                    try { return chain.proceed(); }
                    finally { clearFirstMoveAxis(action, owner, firstMoveAxes); }
                }
                int amount=preferences.getInt(ModuleSettings.BACK_GESTURE_WIDTH_DP,
                        ModuleSettings.BACK_GESTURE_WIDTH_DEFAULT);
                int nativeWidth=width.getInt(owner);
                width.setInt(owner, BackGestureWidthHooks.widthPixels((Context)context.get(owner),amount));
                try { return chain.proceed(); }
                finally {
                    width.setInt(owner,nativeWidth);
                    clearFirstMoveAxis(action, owner, firstMoveAxes);
                }
            }
        }, MotionEvent.class,int.class);
        installer.hook(recent.getName(),"checkRecentsGesture",chain -> {
            Object owner=chain.getThisObject();
            if(!side.isInstance(owner) || !preferences.getBoolean(ModuleSettings.BACK_GESTURE_REMOVE_SMART,true))
                return chain.proceed();
            Integer firstAxis=firstMoveAxes.get(owner);
            if(firstAxis==null || firstAxis==FIRST_MOVE_VERTICAL) {
                // Keep the native direction rejection when the initial movement is vertical.
                state.set(owner,canceledState);
                return null;
            }
            String dir=((Enum<?>)direction.get(owner)).name();
            if(!dir.endsWith("LEFT") && !dir.endsWith("RIGHT"))return chain.proceed();
            // Keep native timeout and minimum movement; remove angle and direction checks.
            if(System.currentTimeMillis()-downTime.getLong(owner)>300)state.set(owner,canceledState);
            else {
                MotionEvent event=(MotionEvent)chain.getArg(0);
                int threshold=dir.startsWith("HORIZONTAL")?slop.getInt(owner):landscapeSlop.getInt(owner);
                if(Math.hypot(event.getRawX()-startX.getFloat(owner),event.getRawY()-startY.getFloat(owner))>threshold)
                    state.set(owner,triggeredState);
            }
            return null;
        },MotionEvent.class);
        preferences.registerOnSharedPreferenceChangeListener((prefs,key) -> {
            if(key==null || ModuleSettings.BACK_GESTURE_WIDTH_DP.equals(key)
                    || ModuleSettings.BACK_GESTURE_WIDTH_ENABLED.equals(key))
                log.accept("System side gesture width preferences: enabled="
                        +prefs.getBoolean(ModuleSettings.BACK_GESTURE_WIDTH_ENABLED,false)
                        +" dp="+prefs.getInt(ModuleSettings.BACK_GESTURE_WIDTH_DP,ModuleSettings.BACK_GESTURE_WIDTH_DEFAULT),null);
        });
        log.accept("System side gesture width hooks registered: enabled="
                +preferences.getBoolean(ModuleSettings.BACK_GESTURE_WIDTH_ENABLED,false)
                +" dp="+preferences.getInt(ModuleSettings.BACK_GESTURE_WIDTH_DP,ModuleSettings.BACK_GESTURE_WIDTH_DEFAULT),null);
    }

    private static void clearFirstMoveAxis(int action, Object owner, Map<Object, Integer> firstMoveAxes) {
        if(action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL) firstMoveAxes.remove(owner);
    }
}
