package dev.rikumi.flymemod;

import android.content.SharedPreferences;
import android.content.Context;
import android.view.MotionEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.Consumer;

/** Flyme filters side swipes in system_server before consulting SystemUI. */
final class BackGestureServiceHooks {
    static void install(ClassLoader loader, SharedPreferences preferences, SignalHooks.Installer installer,
                        Consumer<Method> deoptimize) throws ReflectiveOperationException {
        Class<?> recent = loader.loadClass("com.android.server.policy.gesture.RecentGestureListener");
        Class<?> side = loader.loadClass("com.android.server.policy.gesture.LeftRightGestureListener");
        Field width = recent.getDeclaredField("GESTURE_VALID_LEFT_OFFSET");
        width.setAccessible(true);
        Field context = recent.getSuperclass().getDeclaredField("mContext");
        context.setAccessible(true);
        recent.getDeclaredMethod("isInRecentPanelModeArea", MotionEvent.class);
        installer.hook(recent.getName(), "isInRecentPanelModeArea", chain -> {
            Object owner = chain.getThisObject();
            if (!side.isInstance(owner) || !preferences.contains(ModuleSettings.BACK_GESTURE_WIDTH_DP)
                    || !preferences.getBoolean(ModuleSettings.BACK_GESTURE_WIDTH_ENABLED, false))
                return chain.proceed();
            int amount = preferences.getInt(ModuleSettings.BACK_GESTURE_WIDTH_DP, ModuleSettings.BACK_GESTURE_WIDTH_DEFAULT);
            synchronized (owner) {
                int nativeWidth = width.getInt(owner);
                width.setInt(owner, BackGestureWidthHooks.widthPixels((Context) context.get(owner), amount));
                try { return chain.proceed(); }
                finally { width.setInt(owner, nativeWidth); }
            }
        }, MotionEvent.class);
        deoptimize.accept(recent.getDeclaredMethod("interceptMotionBeforeQueueing", MotionEvent.class, int.class));
    }
}
