package dev.rikumi.flymemod;

import android.content.Context;
import android.graphics.PointF;
import android.graphics.RectF;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

final class HomeIconMinimumSizeHooks {
    private static final String CONFIG = "com.meizu.flyme.launcher.quickstep.util.FlymeSpringConfig";
    private static final String ANIMATION = "com.meizu.flyme.launcher.quickstep.util.FlymeRectFSpringAnim";
    private final Consumer<Context> settings;
    private final BooleanSupplier enabled;
    private final Field type, width, minValue, animationConfig;
    private final Method setBounds, getStart, getTarget;
    private final Class<?> deviceProfile;

    HomeIconMinimumSizeHooks(ClassLoader loader, Consumer<Context> settings, BooleanSupplier enabled)
            throws ReflectiveOperationException {
        this.settings = settings; this.enabled = enabled;
        Class<?> config = loader.loadClass(CONFIG);
        type = config.getDeclaredField("type"); type.setAccessible(true);
        width = config.getDeclaredField("width"); width.setAccessible(true);
        minValue = loader.loadClass("com.meizu.flyme.launcher.quickstep.util.FlymeSpringConstants$SpringConfig")
                .getField("minValue");
        setBounds = config.getDeclaredMethod("setBounds", RectF.class, RectF.class);
        Class<?> animation = loader.loadClass(ANIMATION);
        animationConfig = animation.getDeclaredField("mSpringConfig"); animationConfig.setAccessible(true);
        getStart = animation.getMethod("getStartRect"); getTarget = animation.getMethod("getTargetRect");
        deviceProfile = loader.loadClass("com.android.launcher3.DeviceProfile");
        animation.getDeclaredMethod("start", Context.class, deviceProfile, PointF.class);
    }

    void install(SignalHooks.Installer installer, Consumer<Method> deoptimize)
            throws ReflectiveOperationException {
        installer.hook(CONFIG, "setBounds", chain -> {
            Object result = chain.proceed();
            Object config = chain.getThisObject();
            RectF target = (RectF) chain.getArg(1);
            if (enabled.getAsBoolean() && type.getInt(config) == 0 && validWidth(target.width()))
                minValue.setFloat(width.get(config), target.width());
            return result;
        }, RectF.class, RectF.class);
        installer.hook(ANIMATION, "start", chain -> {
            settings.accept((Context) chain.getArg(0));
            Object animation = chain.getThisObject();
            Object config = animationConfig.get(animation);
            if (type.getInt(config) == 0) {
                RectF start = (RectF) getStart.invoke(animation);
                RectF target = (RectF) getTarget.invoke(animation);
                if (enabled.getAsBoolean()) ensureStartWidth(start, target.width());
                // Re-evaluate settings at animation start, including toggles after construction.
                setBounds.invoke(config, start, target);
            }
            return chain.proceed();
        }, Context.class, deviceProfile, PointF.class);
        deoptimize.accept(setBounds);
        deoptimize.accept(animationConfig.getDeclaringClass().getDeclaredMethod("onTargetPositionChanged"));
    }

    static boolean validWidth(float width) { return Float.isFinite(width) && width > 0f; }

    static void ensureStartWidth(RectF start, float minimum) {
        float width = start.width();
        if (!validWidth(width) || !validWidth(minimum) || width >= minimum || !Float.isFinite(start.height())) return;
        float x = start.centerX(), y = start.centerY();
        float halfWidth = minimum / 2f, halfHeight = start.height() * (minimum / width) / 2f;
        start.set(x - halfWidth, y - halfHeight, x + halfWidth, y + halfHeight);
    }
}
