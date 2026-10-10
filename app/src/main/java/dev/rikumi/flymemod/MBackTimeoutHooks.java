package dev.rikumi.flymemod;

import android.content.Context;
import android.os.Handler;
import android.os.Message;
import android.view.MotionEvent;
import android.view.View;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Use View's system long-press timer as the only timer for non-pressure mBack. */
final class MBackTimeoutHooks {
    private static final String CONTROLLER = "com.flyme.systemui.navigationbar.MBackButtonController";
    private static final String BUTTON = "com.flyme.systemui.navigationbar.MBackButtonView";
    private static final int LONG_CLICK = 7;
    private final Consumer<Context> settings;
    private final BooleanSupplier enabled;
    private final BiConsumer<String, Throwable> log;
    private final Field context, pressure, handler, buttonController, longTouchHome;
    private final Class<?> controllerType, buttonType;
    private final Method updateConfig;

    MBackTimeoutHooks(ClassLoader loader, Consumer<Context> settings, BooleanSupplier enabled,
            BiConsumer<String, Throwable> log) throws ReflectiveOperationException {
        this.settings = settings; this.enabled = enabled; this.log = log;
        controllerType = loader.loadClass(CONTROLLER);
        buttonType = loader.loadClass(BUTTON);
        context = controllerType.getField("mContext");
        pressure = controllerType.getField("mPressureHomeKey");
        handler = controllerType.getField("mHandler");
        longTouchHome = controllerType.getField("mLongTouchHomeKey");
        buttonController = buttonType.getField("mMBackButtonController");
        updateConfig = controllerType.getDeclaredMethod("updateConfig", android.content.res.Configuration.class);
        loader.loadClass(CONTROLLER + "$1").getDeclaredMethod("handleMessage", Message.class);
    }

    private boolean active(Object owner) throws IllegalAccessException {
        settings.accept((Context) context.get(owner));
        return enabled.getAsBoolean() && !pressure.getBoolean(owner);
    }

    void install(SignalHooks.Installer installer, Consumer<Method> deoptimize)
            throws ReflectiveOperationException {
        installer.hook(CONTROLLER, "handleTouch", chain -> {
            Object owner = chain.getThisObject();
            MotionEvent event = (MotionEvent) chain.getArg(0);
            boolean down = event.getActionMasked() == MotionEvent.ACTION_DOWN;
            boolean controlled = down && active(owner);
            if (controlled) {
                Context ctx = (Context) context.get(owner);
                // Recompute native action gates from the current hardware flag and rotation.
                updateConfig.invoke(owner, ctx.getResources().getConfiguration());
            }
            Object result = chain.proceed();
            if (controlled) {
                // handleTouch(DOWN) has just posted message 7. View.onTouchEvent
                // will post its system-timeout callback after this method returns.
                ((Handler) handler.get(owner)).removeMessages(LONG_CLICK);
            }
            if (down && enabled.getAsBoolean()) log.accept("MBack system timeout down: controlled="
                    + controlled + " pressure=" + pressure.getBoolean(owner)
                    + " longHome=" + longTouchHome.getBoolean(owner), null);
            return result;
        }, MotionEvent.class);
        installer.hook(BUTTON, "onLongClick", chain -> {
            Object owner = buttonController.get(chain.getThisObject());
            if (!active(owner)) return chain.proceed();
            Handler target = (Handler) handler.get(owner);
            target.removeMessages(LONG_CLICK);
            // Dispatch the original action synchronously. View records the returned
            // true as a completed long click and suppresses its own short click.
            target.dispatchMessage(target.obtainMessage(LONG_CLICK));
            log.accept("MBack system timeout long click: longHome=" + longTouchHome.getBoolean(owner), null);
            return true;
        }, View.class);
        installer.hook(CONTROLLER, "toHome", chain -> {
            Object owner = chain.getThisObject();
            if (active(owner)) log.accept("MBack system timeout native home action", null);
            return chain.proceed();
        });
        deoptimize.accept(controllerType.getDeclaredMethod("handleTouch", MotionEvent.class));
        deoptimize.accept(buttonType.getDeclaredMethod("onTouchEvent", MotionEvent.class));
        deoptimize.accept(Class.forName("android.view.View$CheckForLongPress")
                .getDeclaredMethod("run"));
        // The original callback only returns true, and may be inlined into View.
        for (Method method : View.class.getDeclaredMethods()) {
            if (method.getName().equals("performLongClickInternal")
                    || method.getName().equals("performLongClick")) deoptimize.accept(method);
        }
    }
}
