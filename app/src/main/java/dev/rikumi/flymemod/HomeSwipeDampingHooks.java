package dev.rikumi.flymemod;

import android.content.Context;
import android.graphics.PointF;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Lowers only the spring damping created for Launcher swipe-to-home animations. */
final class HomeSwipeDampingHooks {
    private static final String ANIMATION = "com.android.quickstep.util.RectFSpringAnim";
    private static final String SPRING_FORCE = "androidx.dynamicanimation.animation.SpringForce";
    private static final String FLYME_ANIMATION = "com.meizu.flyme.launcher.quickstep.util.FlymeRectFSpringAnim";
    private static final String FLYME_CONFIG = "com.meizu.flyme.launcher.quickstep.util.FlymeSpringConfig";
    private static final float DAMPING_SCALE = 0.8f;
    private static final float MIN_DAMPING = 0.4f;

    private final BooleanSupplier enabled;
    private final Consumer<Context> settings;
    private final Class<?> deviceProfileClass;
    private final Field animationType;
    private final Method flymeStart, flymeVelocity;
    private final ThreadLocal<Boolean> startingHomeAnimation = ThreadLocal.withInitial(() -> false);

    HomeSwipeDampingHooks(ClassLoader loader, Consumer<Context> settings, BooleanSupplier enabled) throws ReflectiveOperationException {
        this.settings = settings;
        this.enabled = enabled;
        this.deviceProfileClass = loader.loadClass("com.android.launcher3.DeviceProfile");
        loader.loadClass(ANIMATION).getDeclaredMethod("start", Context.class, deviceProfileClass, PointF.class);
        loader.loadClass(SPRING_FORCE).getDeclaredMethod("setDampingRatio", float.class);
        Class<?> flyme = loader.loadClass(FLYME_ANIMATION);
        flymeStart = flyme.getDeclaredMethod("start", Context.class, deviceProfileClass, PointF.class);
        flymeVelocity = flyme.getDeclaredMethod("setAnimVelocity", PointF.class);
        Class<?> config = loader.loadClass(FLYME_CONFIG);
        config.getDeclaredMethod("calculateDampingRatio", float.class, float.class);
        animationType = config.getDeclaredField("type");
        animationType.setAccessible(true);
    }

    void install(SignalHooks.Installer installer, Consumer<Method> deoptimizer) {
        // Flyme overrides start() and never calls the AOSP implementation. Change
        // the configuration before both synchronous and asynchronous springs use it.
        installer.hook(FLYME_ANIMATION, "start", chain -> {
            settings.accept((Context) chain.getArg(0));
            return chain.proceed();
        }, Context.class, deviceProfileClass, PointF.class);
        installer.hook(FLYME_CONFIG, "calculateDampingRatio", chain -> {
            double damping = ((Number) chain.proceed()).doubleValue();
            return enabled.getAsBoolean() && animationType.getInt(chain.getThisObject()) == 0
                    ? Math.min(damping, Math.max(MIN_DAMPING, damping * DAMPING_SCALE)) : damping;
        }, float.class, float.class);
        deoptimizer.accept(flymeVelocity);
        deoptimizer.accept(flymeStart);
        installer.hook(ANIMATION, "start", chain -> {
            settings.accept((Context) chain.getArg(0));
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
            float reduced = Math.min(damping, Math.max(MIN_DAMPING, damping * DAMPING_SCALE));
            return chain.proceed(new Object[]{reduced});
        }, float.class);
    }
}
