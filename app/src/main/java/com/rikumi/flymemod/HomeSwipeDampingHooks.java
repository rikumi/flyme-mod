package com.rikumi.flymemod;

import android.content.Context;
import android.graphics.PointF;
import java.util.function.BooleanSupplier;

/** Lowers only the spring damping created for Launcher swipe-to-home animations. */
final class HomeSwipeDampingHooks {
    private static final String ANIMATION = "com.android.quickstep.util.RectFSpringAnim";
    private static final String SPRING_FORCE = "androidx.dynamicanimation.animation.SpringForce";
    private static final float DAMPING_SCALE = 0.8f;
    private static final float MIN_DAMPING = 0.4f;

    private final BooleanSupplier enabled;
    private final Class<?> deviceProfileClass;
    private final ThreadLocal<Boolean> startingHomeAnimation = ThreadLocal.withInitial(() -> false);

    HomeSwipeDampingHooks(ClassLoader loader, BooleanSupplier enabled) throws ReflectiveOperationException {
        this.enabled = enabled;
        this.deviceProfileClass = loader.loadClass("com.android.launcher3.DeviceProfile");
        loader.loadClass(ANIMATION).getDeclaredMethod("start", Context.class, deviceProfileClass, PointF.class);
        loader.loadClass(SPRING_FORCE).getDeclaredMethod("setDampingRatio", float.class);
    }

    void install(SignalHooks.Installer installer) {
        installer.hook(ANIMATION, "start", chain -> {
            if (!enabled.getAsBoolean()) return chain.proceed();
            startingHomeAnimation.set(true);
            try {
                return chain.proceed();
            } finally {
                startingHomeAnimation.remove();
            }
        }, Context.class, deviceProfileClass, PointF.class);

        installer.hook(SPRING_FORCE, "setDampingRatio", chain -> {
            if (!startingHomeAnimation.get()) return chain.proceed();
            float damping = (Float) chain.getArg(0);
            float reduced = Math.max(MIN_DAMPING, damping * DAMPING_SCALE);
            return chain.proceed(new Object[]{reduced});
        }, float.class);
    }
}
