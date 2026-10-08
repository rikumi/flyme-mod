package dev.rikumi.flymemod;

import java.util.function.BooleanSupplier;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Slow only control-center release springs, preserving their damping ratio and shape. */
final class ReboundTimingHooks {
    private static final float TIME_SCALE = 1.4f;
    private final BooleanSupplier enabled;
    private final ThreadLocal<Boolean> releaseSpring = new ThreadLocal<>();

    ReboundTimingHooks(BooleanSupplier enabled) { this.enabled = enabled; }

    static long duration(long nativeDuration, boolean enabled) {
        return enabled ? Math.round(nativeDuration * TIME_SCALE) : nativeDuration;
    }

    void install(SignalHooks.Installer installer) {
        String[] owners = {"com.flyme.systemui.controlcenter.phone.CenterController",
                "com.flyme.systemui.qs.MzQSImpl"};
        String[] methods = {"startSpringBack", "startQsHeaderAnimator"};
        for (int i = 0; i < owners.length; i++) {
            installer.hook(owners[i], methods[i], chain -> {
                Boolean previous = releaseSpring.get();
                releaseSpring.set(enabled.getAsBoolean());
                try { return chain.proceed(); }
                finally {
                    if (previous == null) releaseSpring.remove(); else releaseSpring.set(previous);
                }
            }, float.class);
        }
        installer.hook("androidx.dynamicanimation.animation.SpringForce", "setStiffness", chain -> {
            if (!Boolean.TRUE.equals(releaseSpring.get())) return chain.proceed();
            // Spring frequency is proportional to sqrt(stiffness).
            return chain.proceed(new Object[]{(Float) chain.getArg(0) / (TIME_SCALE * TIME_SCALE)});
        }, float.class);
        installer.hook("androidx.dynamicanimation.animation.DynamicAnimation", "setStartVelocity", chain -> {
            if (!Boolean.TRUE.equals(releaseSpring.get())) return chain.proceed();
            return chain.proceed(new Object[]{(Float) chain.getArg(0) / TIME_SCALE});
        }, float.class);
    }

    void installRowStretch(ClassLoader loader, SignalHooks.Installer installer) throws ReflectiveOperationException {
        for (String name : new String[]{"com.flyme.systemui.controlcenter.phone.CenterController",
                "com.flyme.systemui.qs.MzQSImpl"}) {
            Class<?> owner = loader.loadClass(name);
            Field sequence = owner.getDeclaredField("FIBONACCI_SEQUENCE");
            sequence.setAccessible(true);
            float[] coefficients = (float[]) sequence.get(null);
            float[] originals = coefficients.clone();
            float[] reduced = coefficients.clone();
            // Preserve the card area's .54 movement and halve differences between rows/header.
            for (int i = 0; i < reduced.length; i++) reduced[i] = .54f + (reduced[i] - .54f) * .5f;
            Field notifications = null;
            Method notificationTranslation = null;
            if (name.endsWith("MzQSImpl")) {
                notifications = owner.getDeclaredField("mSharedNotificationContainerInteractor");
                notifications.setAccessible(true);
                notificationTranslation = notifications.getType().getMethod("setTranslationY", float.class);
            }
            Field shared = notifications;
            Method setSharedTranslation = notificationTranslation;
            installer.hook(name, "setQSTranslationY", chain -> {
                if (!enabled.getAsBoolean()) return chain.proceed();
                System.arraycopy(reduced, 0, coefficients, 0, reduced.length);
                try {
                    Object result = chain.proceed();
                    if (shared != null) {
                        Object interactor = shared.get(chain.getThisObject());
                        if (interactor != null) setSharedTranslation.invoke(interactor, (Float) chain.getArg(0) * .57f);
                    }
                    return result;
                } finally { System.arraycopy(originals, 0, coefficients, 0, originals.length); }
            }, float.class);
        }
    }
}
