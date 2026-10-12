package dev.rikumi.flymemod;

import android.content.Context;
import android.view.MotionEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/** Set explicit dp edge widths; disabling always recomputes native policy. */
final class BackGestureWidthHooks {
    private static final String OWNER = "com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler";
    private final Field context, left, right, mlWidth, uiThread;
    private final Method update, executor;
    private final Consumer<Context> settings;
    private final BooleanSupplier enabled;
    private final IntSupplier widthDp;
    private final BiConsumer<String, Throwable> log;
    private final Map<Object, Boolean> handlers = Collections.synchronizedMap(new WeakHashMap<>());

    BackGestureWidthHooks(ClassLoader loader, Consumer<Context> settings, BooleanSupplier enabled,
                          IntSupplier widthDp, BiConsumer<String, Throwable> log) throws ReflectiveOperationException {
        this.settings = settings; this.enabled = enabled; this.widthDp = widthDp; this.log = log;
        Class<?> type = loader.loadClass(OWNER);
        context = type.getField("mContext"); left = type.getField("mEdgeWidthLeft");
        right = type.getField("mEdgeWidthRight"); mlWidth = type.getField("mMLEnableWidth");
        uiThread = type.getField("mUiThreadContext");
        executor = uiThread.getType().getMethod("getExecutor");
        update = type.getMethod("updateCurrentUserResources");
        type.getDeclaredMethod("isWithinTouchRegion", MotionEvent.class);
    }

    void install(SignalHooks.Installer installer) {
        installer.hook(OWNER, "updateCurrentUserResources", chain -> {
            Object owner = chain.getThisObject();
            settings.accept((Context) context.get(owner));
            Object result = chain.proceed();
            handlers.put(owner, true);
            if (enabled.getAsBoolean()) {
                int pixels = widthPixels((Context) context.get(owner), widthDp.getAsInt());
                left.setInt(owner, pixels);
                right.setInt(owner, pixels);
                mlWidth.setInt(owner, Math.min(mlWidth.getInt(owner),
                        Math.min(left.getInt(owner), right.getInt(owner))));
            }
            return result;
        });
        installer.hook(OWNER, "isWithinTouchRegion", chain ->
                enabled.getAsBoolean() && widthDp.getAsInt() == 0 ? false : chain.proceed(), MotionEvent.class);
    }

    void refresh() {
        ArrayList<Object> current;
        synchronized (handlers) { current = new ArrayList<>(handlers.keySet()); }
        for (Object owner : current) {
            try {
                ((Executor) executor.invoke(uiThread.get(owner))).execute(() -> {
                    try { update.invoke(owner); }
                    catch (ReflectiveOperationException error) { log.accept("Cannot refresh back gesture width", error); }
                });
            } catch (ReflectiveOperationException error) { log.accept("Cannot schedule back gesture width refresh", error); }
        }
    }

    static int widthPixels(Context context, int dp) {
        return Math.round(Math.max(0, dp) * context.getResources().getDisplayMetrics().density);
    }
}
