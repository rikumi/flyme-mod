package dev.rikumi.flymemod;

import android.content.Context;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

final class BackGestureImeHooks {
    static void install(ClassLoader loader, Consumer<Context> settings, BooleanSupplier enabled,
                        BiConsumer<String, Throwable> log, SignalHooks.Installer installer,
                        Consumer<Method> deoptimize) throws ReflectiveOperationException {
        Class<?> handler = loader.loadClass("com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler");
        Field context = handler.getField("mContext");
        Field manager = handler.getField("mWindowManager");
        Method admission = handler.getDeclaredMethod("isWithinTouchRegion", MotionEvent.class);
        installer.hook(handler.getName(), admission.getName(), chain -> {
            Object owner = chain.getThisObject();
            settings.accept((Context) context.get(owner));
            Object result = chain.proceed();
            if (!enabled.getAsBoolean() || !Boolean.TRUE.equals(result)) return result;
            try {
                WindowMetrics metrics = ((WindowManager) manager.get(owner)).getCurrentWindowMetrics();
                WindowInsets insets = metrics.getWindowInsets();
                Rect bounds = metrics.getBounds();
                MotionEvent event = (MotionEvent) chain.getArg(0);
                int height = insets.getInsets(WindowInsets.Type.ime()).bottom;
                return !inKeyboard(event.getX(), event.getY(), bounds.left, bounds.top,
                        bounds.right, bounds.bottom, insets.isVisible(WindowInsets.Type.ime()), height);
            } catch (RuntimeException error) {
                log.accept("Cannot read keyboard bounds for back gesture", error);
                return result;
            }
        }, MotionEvent.class);
        // Direct input and Flyme's system_server forwarding both consult this method.
        deoptimize.accept(handler.getDeclaredMethod("onMotionEvent", MotionEvent.class));
        Class<?> listener = loader.loadClass(handler.getName() + "$SystemUIGestureListener");
        deoptimize.accept(listener.getDeclaredMethod("onGesturePreTriggerBefore", int.class, MotionEvent.class));
    }

    static boolean inKeyboard(float x, float y, int left, int top, int right, int bottom,
                              boolean visible, int height) {
        return visible && height > 0 && right > left && bottom > top
                && x >= left && x < right && y >= Math.max(top, bottom - height) && y < bottom;
    }
}
