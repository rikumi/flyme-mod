package dev.rikumi.flymemod;

import android.content.Context;
import android.graphics.Point;
import android.graphics.PointF;
import android.view.View;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Flyme SystemUI 16260625 modern clock offsets and legacy AOD layout positioning. */
final class AodMovementHooks {
    private static final String DATA = "com.flyme.systemui.keyguard.ui.binder.ClockContainerViewBinderData";
    private static final String DISPLAY = "com.flyme.aod.view.AODDisplayView";
    private static final String ALGORITHM = "com.flyme.aod.algorithm.SnakeRandomPositionSimulateAlgorithm";
    private static final String COLORFUL = "com.flyme.systemui.plugins.clocks.colorful_paradise.ui.ColorfulParadiseClockViewModel";
    private static final String PHOTO = "com.flyme.systemui.plugins.clocks.photo_frame.ui.PhotoFrameClockViewModel";
    private static final String SELF = "com.flyme.systemui.plugins.clocks.distinctive_self.ui.DistinctiveSelfClockViewModel";
    private final Consumer<Context> settings;
    private final BooleanSupplier enabled;
    private final Class<?> authController;
    private final Field context, x, y, anchor, algorithm, legacyContent;
    private final Field colorfulContext, contentOffset;
    private final Field controllerContext;
    private final Field[] colorfulOffsets;
    private final Method colorfulLockscreenState;
    private final Class<?> colorfulData, hostState;
    private final Method burnInOffset;
    private final Field photoContext, selfContext, timeTop;
    private final Field photoTop, photoOffset;
    private final Method lockscreenTop, selfLockscreenState;
    private final Class<?> selfData, resourceState, photoData;
    private final ThreadLocal<View> layout = new ThreadLocal<>();
    private final Map<View, Position> positions = new WeakHashMap<>();

    AodMovementHooks(ClassLoader loader, Consumer<Context> settings, BooleanSupplier enabled)
            throws ReflectiveOperationException {
        this.settings = settings;
        this.enabled = enabled;
        authController = loader.loadClass("com.android.systemui.biometrics.AuthController");
        Class<?> data = loader.loadClass(DATA);
        context = data.getField("context");
        x = data.getField("newClockAodXPosition");
        y = data.getField("newClockAodYPosition");
        Class<?> display = loader.loadClass(DISPLAY);
        anchor = display.getField("mAliveDefaultTop");
        algorithm = display.getField("mPositionSimulateAlgorithm");
        legacyContent = display.getField("mContentView");
        colorfulContext = loader.loadClass(COLORFUL).getField("pluginContext");
        colorfulData = loader.loadClass("com.flyme.systemui.plugins.clocks.colorful_paradise.data.ColorfulParadisePluginData");
        hostState = loader.loadClass("com.flyme.systemui.plugins.clocks.core.data.HostState");
        burnInOffset = hostState.getMethod("getBurnInOffset");
        contentOffset = loader.loadClass("com.flyme.systemui.plugins.clocks.colorful_paradise.ui.state.ColorfulParadiseState")
                .getField("contentOffset");
        Class<?> colorfulState = contentOffset.getDeclaringClass();
        colorfulOffsets = new Field[]{colorfulState.getField("h1Offset"), colorfulState.getField("h2Offset"),
                colorfulState.getField("m1Offset"), colorfulState.getField("m2Offset"),
                colorfulState.getField("dateOffset"), colorfulState.getField("decor1Offset"),
                colorfulState.getField("decor2Offset"), colorfulState.getField("decor3Offset")};
        colorfulLockscreenState = loader.loadClass(COLORFUL).getMethod("createLockscreenState",
                colorfulData, hostState, boolean.class);
        controllerContext = loader.loadClass("com.flyme.systemui.clock.BaseClockController").getField("context");
        Class<?> photo = loader.loadClass(PHOTO);
        photoContext = photo.getField("pluginContext");
        lockscreenTop = photo.getMethod("getLockscreenDoubleLineTop");
        photoData = loader.loadClass("com.flyme.systemui.plugins.clocks.photo_frame.data.PhotoFramePluginData");
        Class<?> photoState = loader.loadClass("com.flyme.systemui.plugins.clocks.photo_frame.ui.state.PhotoFrameState");
        photoTop = photoState.getField("doubleLineTop");
        photoTop.setAccessible(true);
        photoOffset = photoState.getField("contentOffset");
        photoOffset.setAccessible(true);
        selfContext = loader.loadClass(SELF).getField("pluginContext");
        timeTop = loader.loadClass("com.flyme.systemui.plugins.clocks.distinctive_self.ui.state.DistinctiveSelfState")
                .getField("timeMarginTop");
        timeTop.setAccessible(true);
        selfData = loader.loadClass("com.flyme.systemui.plugins.clocks.distinctive_self.data.DistinctiveSelfPluginData");
        resourceState = loader.loadClass("com.flyme.systemui.plugins.clocks.distinctive_self.data.DistinctiveSelfDataRepo$ResourceState");
        selfLockscreenState = loader.loadClass(SELF).getMethod("createLockscreenState",
                selfData, hostState, resourceState, Integer.class);
    }

    void install(SignalHooks.Installer installer, ClassLoader loader, Consumer<Method> deoptimizer)
            throws ReflectiveOperationException {
        installer.hook("com.flyme.systemui.clock.BaseClockController", "setBurnInOffset", chain -> {
            Context ctx = (Context) controllerContext.get(chain.getThisObject());
            settings.accept(ctx);
            if (!enabled.getAsBoolean()) return chain.proceed();
            int range = rangePx(ctx);
            return chain.proceed(new Object[]{Math.max(-range, Math.min(range, (Float) chain.getArg(0))),
                    Math.max(-range, Math.min(range, (Float) chain.getArg(1)))});
        }, float.class, float.class);
        installer.hook(DATA, "calculateBurnInOffset", chain -> {
            Object data = chain.getThisObject();
            Context ctx = (Context) context.get(data);
            settings.accept(ctx);
            if (!enabled.getAsBoolean()) return chain.proceed();
            Object result = chain.proceed();
            int range = rangePx(ctx);
            // Preserve Flyme's style-specific cadence, then clamp its calculated
            // offsets. Style IDs outside the current 1..4 set must be bounded too.
            x.setInt(data, clamp(x.getInt(data), range));
            y.setInt(data, clamp(y.getInt(data), range));
            return result;
        }, int.class, int.class, authController, boolean.class);
        installer.hook(DISPLAY, "onLayout", chain -> {
            View view = (View) chain.getThisObject();
            settings.accept(view.getContext());
            if (!enabled.getAsBoolean()) return chain.proceed();
            View previous = layout.get();
            layout.set(view);
            try {
                Object result = chain.proceed();
                // Bound the actual layout too: optimized ROM code can inline the
                // position algorithm, and first-entry branches bypass it entirely.
                if (legacyContent.get(view) instanceof View content) {
                    int range = rangePx(view.getContext());
                    int top = anchor.getInt(view) + clamp(content.getTop() - anchor.getInt(view), range);
                    int centerLeft = (view.getWidth() - content.getWidth()) / 2;
                    int left = centerLeft + clamp(content.getLeft() - centerLeft, range);
                    content.layout(left, top, left + content.getWidth(), top + content.getHeight());
                }
                return result;
            } finally {
                if (previous == null) layout.remove();
                else layout.set(previous);
            }
        }, boolean.class, int.class, int.class, int.class, int.class);
        installer.hook(ALGORITHM, "getYOffset", chain -> {
            View view = layout.get();
            if (view == null || algorithm.get(view) != chain.getThisObject()) return chain.proceed();
            int index = (Integer) chain.getArg(0);
            int range = rangePx(view.getContext());
            Position position = positions.get(view);
            if (position == null || position.index != index || position.range != range) {
                position = new Position(index, range, randomOffset(range));
                positions.put(view, position);
            }
            // Legacy AOD starts at its fixed top near the lock clock (15% of screen height).
            // Keep that anchor instead of traversing the screen. Preserve the system's cadence.
            return anchor.getInt(view) + position.offset;
        }, int.class);
        installer.hook(COLORFUL, "createAodState", chain -> {
            Object state = chain.proceed();
            Context ctx = (Context) colorfulContext.get(chain.getThisObject());
            settings.accept(ctx);
            if (enabled.getAsBoolean()) {
                Point offset = (Point) burnInOffset.invoke(chain.getArg(1));
                int range = rangePx(ctx);
                // Stock rejects offsets when both axes are nonpositive and substitutes a large
                // fingerprint-related shift. Accept signed movement and keep it inside the range.
                ((PointF) contentOffset.get(state)).set(clamp(offset.x, range), clamp(offset.y, range));
                // AOD also has per-digit offsets that move the layout toward screen center.
                // Bounding only contentOffset leaves those large design offsets untouched.
                Object lockState = colorfulLockscreenState.invoke(chain.getThisObject(),
                        chain.getArg(0), chain.getArg(1), chain.getArg(2));
                for (Field field : colorfulOffsets) {
                    PointF base = (PointF) field.get(lockState);
                    ((PointF) field.get(state)).set(base.x, base.y);
                }
            }
            return state;
        }, colorfulData, hostState, boolean.class, boolean.class);
        installer.hook(PHOTO, "getAodDoubleLineTop", chain -> {
            Object model = chain.getThisObject();
            settings.accept((Context) photoContext.get(model));
            // AOD has a separate, lower design top. Use the lockscreen anchor even on first load.
            return enabled.getAsBoolean() ? lockscreenTop.invoke(model) : chain.proceed();
        });
        installer.hook(PHOTO, "createAodState", chain -> {
            Object model = chain.getThisObject();
            settings.accept((Context) photoContext.get(model));
            Object state = chain.proceed();
            if (enabled.getAsBoolean()) {
                int range = rangePx((Context) photoContext.get(model));
                Point offset = (Point) burnInOffset.invoke(chain.getArg(1));
                photoOffset.set(state, new Point(clamp(offset.x, range), clamp(offset.y, range)));
                // Enforce the produced state, rather than relying on an inlinable getter.
                photoTop.setFloat(state, ((Number) lockscreenTop.invoke(model)).floatValue());
            }
            return state;
        }, photoData, hostState);
        installer.hook(SELF, "createAodState", chain -> {
            Object state = chain.proceed();
            Context ctx = (Context) selfContext.get(chain.getThisObject());
            settings.accept(ctx);
            if (enabled.getAsBoolean()) {
                // The anchor belongs to the clock plugin's resources, not necessarily
                // the SystemUI resource namespace. Ask the same plugin for its lock state.
                Object lockState = selfLockscreenState.invoke(chain.getThisObject(),
                        chain.getArg(0), chain.getArg(1), chain.getArg(2), null);
                Point offset = (Point) burnInOffset.invoke(chain.getArg(1));
                timeTop.setInt(state, timeTop.getInt(lockState) + clamp(offset.y, rangePx(ctx)));
            }
            return state;
        }, selfData, hostState, resourceState);
        // These are the actual Kotlin flow producers and native position callers.
        // Deoptimize callers as well as hooks so precompiled/inlined methods still
        // pass through the clamp when the display first enters AOD or ticks again.
        deoptimizeCallers(loader.loadClass(DATA), deoptimizer);
        deoptimizeCallers(loader.loadClass(DISPLAY), deoptimizer);
        for (String producer : new String[]{
                "com.flyme.systemui.keyguard.ui.binder.ClockContainerViewBinder$bind$1$1$9$2",
                "com.flyme.systemui.keyguard.ui.binder.ClockContainerViewBinder$bind$1$1$10$2",
                COLORFUL + "$colorfulParadiseState$1",
                PHOTO + "$photoFrameState$1",
                SELF + "$distinctiveSelfState$1"}) {
            deoptimizeCallers(loader.loadClass(producer), deoptimizer);
        }
        deoptimizer.accept(loader.loadClass(PHOTO).getDeclaredMethod("createAodState", photoData, hostState));
    }

    private static void deoptimizeCallers(Class<?> type, Consumer<Method> deoptimizer) {
        for (Method method : type.getDeclaredMethods()) {
            String name = method.getName();
            if (!Modifier.isAbstract(method.getModifiers()) && !Modifier.isNative(method.getModifiers())
                    && (name.equals("emit") || name.equals("invoke") || name.equals("invokeSuspend")
                    || name.equals("refreshAODDelay") || name.equals("onLayout"))) {
                deoptimizer.accept(method);
            }
        }
    }

    private static int rangePx(Context context) {
        // Floor prevents rounding to an offset larger than 10dp on fractional densities.
        return Math.max(0, (int) (10f * context.getResources().getDisplayMetrics().density));
    }

    static int randomOffset(int range) {
        return ThreadLocalRandom.current().nextInt(-range, range + 1);
    }

    private static int clamp(int offset, int range) {
        return Math.max(-range, Math.min(range, offset));
    }

    private static final class Position {
        final int index, range, offset;
        Position(int index, int range, int offset) {
            this.index = index;
            this.range = range;
            this.offset = offset;
        }
    }
}
