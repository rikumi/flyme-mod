package dev.rikumi.flymemod;

import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.Consumer;

/** Keep Flyme's 1.35x pressed capsule inside the mBack button's drawing bounds. */
final class MBackRippleHooks {
    private static final String RIPPLE = "com.android.systemui.shared.navigationbar.KeyButtonRipple";
    private static final float PRESSED_SCALE = 1.35f;
    private final Class<?> button;
    private final Field target;
    private final Method enterHardware, drawSoftware;

    MBackRippleHooks(ClassLoader loader) throws ReflectiveOperationException {
        button = loader.loadClass("com.flyme.systemui.navigationbar.MBackButtonView");
        Class<?> ripple = loader.loadClass(RIPPLE);
        target = ripple.getField("mTargetView");
        ripple.getDeclaredMethod("getRippleSize");
        enterHardware = ripple.getDeclaredMethod("enterHardware");
        drawSoftware = ripple.getDeclaredMethod("drawSoftware", Canvas.class);
    }

    void install(SignalHooks.Installer installer, Consumer<Method> deoptimize) {
        installer.hook(RIPPLE, "getRippleSize", chain -> {
            int nativeSize = (Integer) chain.proceed();
            Object ripple = chain.getThisObject();
            if (!button.isInstance(target.get(ripple))) return nativeSize;
            Rect bounds = ((Drawable) ripple).getBounds();
            return fittingSize(nativeSize, Math.max(bounds.width(), bounds.height()));
        });
        // Both callers can inline getRippleSize; preserve the original GPU animation
        // and software fallback while correcting their shared size input.
        deoptimize.accept(enterHardware);
        deoptimize.accept(drawSoftware);
    }

    static int fittingSize(int nativeSize, int extent) {
        if (extent <= 0) return nativeSize;
        return Math.min(nativeSize, (int) Math.floor(extent / PRESSED_SCALE));
    }
}
