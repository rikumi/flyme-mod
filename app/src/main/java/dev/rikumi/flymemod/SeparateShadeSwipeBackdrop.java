package dev.rikumi.flymemod;

import android.graphics.drawable.Drawable;
import android.view.SurfaceControl;
import android.view.View;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/** Apart notifications use a mirrored wallpaper Surface; QS blurs the application window. */
final class SeparateShadeSwipeBackdrop {
    private static final String WALLPAPER = "com.flyme.systemui.wallpaper.LockscreenWallpaperManager";
    private static final String BINDER = "com.flyme.systemui.controlcenter.ui.binder.MzRootContainerBlurBinder";
    private final Class<?> wallpaperType;
    private final Method dependency, flowValue, setRadius, setScale, setWallpaperVisible, setWallpaperAlpha;
    private final Field interactor, binder, wallpaperFrame, wallpaperSurface, wallpaperAlpha;
    private final Field centerRadius, centerScale, panelRadius, panelScale, maxRadius;
    private final Field depth, blurUtils, centerExpanded;
    private final Method nativeMaxRadius, nativeApplyBlur;
    private final Method binderState, binderApply;
    private final Method mirrorHeight, wallpaperY;
    private final Field[] wallpaperViews;
    private Session session;
    private Session pending;
    private IntSupplier radiusOverride = () -> -1;

    void setBlurRadiusOverride(IntSupplier radius) { radiusOverride = radius; }
    boolean hasPendingRoot(View root) { return pending != null && pending.blur.root == root; }

    void onDetached(View root) {
        if (hasPendingRoot(root)) { pending.blur.close(); pending = null; }
    }

    SeparateShadeSwipeBackdrop(ClassLoader loader) throws ReflectiveOperationException {
        wallpaperType = loader.loadClass(WALLPAPER);
        dependency = loader.loadClass("com.android.systemui.Dependency").getMethod("get", Class.class);
        flowValue = loader.loadClass("kotlinx.coroutines.flow.StateFlow").getMethod("getValue");
        Class<?> center = loader.loadClass("com.flyme.systemui.controlcenter.phone.CenterController");
        Class<?> panel = loader.loadClass("com.android.systemui.shade.NotificationPanelViewController");
        interactor = field(center, "mControlCenterInteractor"); binder = field(center, "mMzRootContainerBlurBinder");
        setRadius = interactor.getType().getMethod("setBlurRadiusFactor", float.class);
        setScale = interactor.getType().getMethod("setBlurScaleFactor", float.class);
        centerRadius = field(center, "mCurrentBlurRadius"); centerScale = field(center, "mCurrentBlurScale");
        panelRadius = field(panel, "mCurrentBlurRadius"); panelScale = field(panel, "mCurrentBlurScale");
        maxRadius = field(center, "sBLUR_RADIUS_MAX");
        centerExpanded = field(center, "mExpandedFraction");
        depth = field(panel, "mDepthController"); blurUtils = field(depth.getType(), "blurUtils");
        nativeMaxRadius = blurUtils.getType().getMethod("getMaxBlurRadius");
        nativeApplyBlur = blurUtils.getType().getMethod("applyBlurMZ", loader.loadClass("android.view.ViewRootImpl"), int.class, boolean.class, float.class);
        wallpaperFrame = field(wallpaperType, "keyguardBackgroundFrame");
        wallpaperSurface = field(wallpaperType, "surfaceControl"); wallpaperAlpha = field(wallpaperType, "wallpaperSurfaceAlpha");
        wallpaperViews = new Field[]{field(wallpaperType, "wallpaperBlurBg"), field(wallpaperType, "foregroundShadeView"), field(wallpaperType, "variedpage")};
        setWallpaperVisible = wallpaperType.getMethod("setKeyguardBackgroundFrameVisibility", boolean.class);
        setWallpaperAlpha = wallpaperType.getMethod("setWallpaperAlpha", float.class);
        mirrorHeight = wallpaperType.getMethod("setWallpapeMirrorViewHeight", int.class);
        wallpaperY = wallpaperType.getMethod("setWallpaperBlurBgY", float.class, float.class);
        Class<?> blurBinder = loader.loadClass(BINDER);
        binderState = blurBinder.getMethod("getBlurState");
        binderApply = blurBinder.getMethod("applyBlurState", loader.loadClass("com.flyme.systemui.controlcenter.domain.interactor.ControlCenterBlurInteractor$BlurState"));
    }

    void install(SignalHooks.Installer installer) {
        installer.hook(WALLPAPER, "setWallpapeMirrorViewHeight", chain -> {
            if (ownsWallpaper(chain.getThisObject()) && !session.ending)
                return chain.proceed(new Object[]{session.blur.root.getHeight()});
            return chain.proceed();
        }, int.class);
        installer.hook(WALLPAPER, "setWallpaperBlurBgY", chain -> {
            if (ownsWallpaper(chain.getThisObject()) && !session.ending)
                return chain.proceed(new Object[]{0f, (float)session.blur.root.getHeight()});
            return chain.proceed();
        }, float.class, float.class);
        installer.hook(WALLPAPER, "setKeyguardBackgroundFrameVisibility", chain -> {
            if (ownsWallpaper(chain.getThisObject()) && !session.ending) {
                if (!session.writingVisibility) session.requestedVisibility = (Boolean) chain.getArg(0);
                return chain.proceed(new Object[]{true});
            }
            return chain.proceed();
        }, boolean.class);
        installer.hook(WALLPAPER, "setWallpaperAlpha", chain -> {
            if (ownsWallpaper(chain.getThisObject()) && !session.ending) {
                applyWallpaperSurface(session);
                return null; // Do not enqueue a background coroutine with a stale full-open alpha.
            }
            return chain.proceed();
        }, float.class);
        installer.hook(BINDER, "applyBlurState", chain -> {
            // Wallpaper blur stays in its BackgroundBlurDrawable. Applying the repository's
            // application blur to mz_root_container as well would blur the foreground twice.
            return session != null && !session.ending && chain.getThisObject() == session.binder ? null : chain.proceed();
        }, binderApply.getParameterTypes()[0]);
        installer.hook("com.android.systemui.statusbar.NotificationShadeDepthController", "onBlurApplied", chain -> {
            Object result = chain.proceed();
            Session completing = pending;
            if (completing != null && completing.depth == chain.getThisObject()
                    && ((Integer) chain.getArg(0) >= completing.nativeRadius || centerExpanded.getFloat(completing.center) <= 0f)) {
                // The full-strength native window blur and removal of our region are
                // submitted for the same render frame, instead of dropping the blur early.
                completing.blur.close(); pending = null;
            }
            return result;
        }, int.class, float.class);
    }

    void deoptimizeCallers(ClassLoader loader, Consumer<Method> deoptimize) throws ReflectiveOperationException {
        deoptimize.accept(wallpaperType.getMethod("updatePanelWallPaperVisibility", boolean.class, boolean.class));
        // The ROM can inline these tiny setters into coroutine collectors.
        for (String name : new String[]{WALLPAPER + "$setNotificationShadeWindowView$4$1",
                WALLPAPER + "$setNotificationShadeWindowView$5$2", WALLPAPER + "$setNotificationShadeWindowView$8$2",
                BINDER + "$bind$1$1"}) {
            for (Method method : loader.loadClass(name).getDeclaredMethods()) deoptimize.accept(method);
        }
    }

    private boolean ownsWallpaper(Object owner) { return session != null && session.wallpaper == owner; }

    void begin(Object panel, Object center, View root, boolean sourceCenter) throws ReflectiveOperationException {
        if (pending != null) { pending.blur.close(); pending = null; }
        Session current = new Session();
        current.panel = panel; current.center = center;
        current.wallpaper = dependency.invoke(null, wallpaperType);
        current.interactor = interactor.get(center); current.binder = binder.get(center);
        current.depth = depth.get(panel);
        Object nativeBlur = blurUtils.get(current.depth);
        current.nativeRadius = Math.round(((Number) nativeMaxRadius.invoke(nativeBlur)).floatValue());
        int override = radiusOverride.getAsInt();
        current.blur = new SeparateShadeFixedBlur(root, override >= 0 ? override : current.nativeRadius);
        current.frame = (View) wallpaperFrame.get(current.wallpaper);
        for (Field field : wallpaperViews) {
            View view = (View) field.get(current.wallpaper);
            if (view != null) current.layers.add(new Layer(view));
        }
        current.frameAlpha = current.frame.getAlpha();
        current.requestedVisibility = current.frame.getVisibility() == View.VISIBLE;
        int id = root.getResources().getIdentifier("mz_root_container", "id", "com.android.systemui");
        current.rootContainer = id == 0 ? null : root.findViewById(id);
        current.fraction = sourceCenter ? 1f : 0f;
        session = current;
        // Keep the lower background opaque. Wallpaper opacity supplies the visible
        // crossfade; fading both stacked layers would leak f*(1-f) of the raw app.
        current.blur.alpha(1f);
        publish(current, 0f);
        // Clear the full-window backend once. BackgroundBlurDrawable independently
        // composites a fixed-radius region with the gesture's alpha.
        Method getViewRoot = View.class.getDeclaredMethod("getViewRootImpl"); getViewRoot.setAccessible(true);
        nativeApplyBlur.invoke(nativeBlur, getViewRoot.invoke(root), 0, false, 0f);
        // Both foregrounds may be expanded, but that must not remove the notification
        // wallpaper or switch the full-screen RenderEffect at the gesture threshold.
        current.writingVisibility = true;
        try { setWallpaperVisible.invoke(current.wallpaper, true); }
        finally { current.writingVisibility = false; }
        // A center-only source has a one-pixel wallpaper mirror and an offscreen
        // blur view. Prepare the notification endpoint before its flow collectors run.
        mirrorHeight.invoke(current.wallpaper, root.getHeight());
        wallpaperY.invoke(current.wallpaper, 0f, (float)root.getHeight());
        applyWallpaperSurface(current);
        if (current.rootContainer != null) current.rootContainer.setRenderEffect(null);
    }

    void apply(float centerFraction) throws ReflectiveOperationException {
        Session current = session;
        if (current == null) return;
        current.fraction = Math.max(0f, Math.min(1f, centerFraction));
        // Radius stays unchanged throughout the gesture, including spring overshoot.
        // The window backend stays off until the full-opacity control-center endpoint.
        publish(current, 0f);
        current.blur.alpha(1f);
        current.frame.setVisibility(View.VISIBLE);
        current.frame.setAlpha(current.frameAlpha);
        for (Layer layer : current.layers) layer.fade(1f - current.fraction);
        applyWallpaperSurface(current);
    }

    private void publish(Session current, float fraction) throws ReflectiveOperationException {
        centerRadius.setFloat(current.center, maxRadius.getInt(null) * fraction);
        centerScale.setFloat(current.center, fraction);
        // A separate notification panel owns its wallpaper blur, not this repository.
        panelRadius.setFloat(current.panel, 0f); panelScale.setFloat(current.panel, 0f);
        if (current.published == fraction) return;
        current.published = fraction;
        setRadius.invoke(current.interactor, fraction);
        setScale.invoke(current.interactor, fraction);
    }

    private void applyWallpaperSurface(Session current) throws IllegalAccessException {
        SurfaceControl surface = (SurfaceControl) wallpaperSurface.get(current.wallpaper);
        if (surface == null || !surface.isValid()) return;
        try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
            transaction.setAlpha(surface, 1f - current.fraction).apply();
        }
    }

    void finish(boolean center, boolean keepOpen) throws ReflectiveOperationException {
        Session current = session;
        if (current == null) return;
        try {
            current.ending = true;
            if (keepOpen && center) { current.blur.alpha(1f); pending = current; }
            else current.blur.close();
            if (keepOpen) {
                publish(current, center ? 1f : 0f);
                setWallpaperVisible.invoke(current.wallpaper, !center);
                mirrorHeight.invoke(current.wallpaper, center ? 1 : current.blur.root.getHeight());
                wallpaperY.invoke(current.wallpaper, center ? -(float)current.blur.root.getHeight() : 0f,
                        center ? 0f : (float)current.blur.root.getHeight());
            } else {
                publish(current, 0f);
                setWallpaperVisible.invoke(current.wallpaper, current.requestedVisibility);
            }
            // Restore drawing alpha separately from native visibility. A source-center
            // frame was originally GONE but must stay visible when returning to notifications.
            for (Layer layer : current.layers) layer.restore();
            current.frame.setAlpha(current.frameAlpha);
            float nativeAlpha = ((Number) flowValue.invoke(wallpaperAlpha.get(current.wallpaper))).floatValue();
            setWallpaperAlpha.invoke(current.wallpaper, keepOpen && !center ? 1f : nativeAlpha);
            if (keepOpen && current.rootContainer != null) current.rootContainer.setRenderEffect(null);
            else binderApply.invoke(current.binder, flowValue.invoke(binderState.invoke(current.binder)));
        } finally { session = null; }
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field result = type.getDeclaredField(name); result.setAccessible(true); return result;
    }
    private static final class Layer {
        final View view; float alpha, appliedAlpha;
        Drawable drawable; int drawableAlpha, appliedDrawableAlpha;
        Layer(View view) { this.view = view; alpha = appliedAlpha = view.getAlpha(); }
        void fade(float amount) {
            if (view.getAlpha() != appliedAlpha) alpha = view.getAlpha();
            Drawable background = view.getBackground();
            if (background != drawable) {
                if (drawable != null) drawable.setAlpha(drawableAlpha);
                drawable = background;
                if (drawable != null) drawableAlpha = appliedDrawableAlpha = drawable.getAlpha();
            }
            if (drawable != null && drawable.getAlpha() != appliedDrawableAlpha) drawableAlpha = drawable.getAlpha();
            if (drawable != null && drawable.getClass().getName().equals("com.android.internal.graphics.drawable.BackgroundBlurDrawable")) {
                // Blur region alpha is submitted independently of View/Canvas alpha.
                appliedDrawableAlpha = Math.round(drawableAlpha * amount);
                drawable.setAlpha(appliedDrawableAlpha);
                view.setAlpha(alpha);
            } else view.setAlpha(alpha * amount);
            appliedAlpha = view.getAlpha();
        }
        void restore() { view.setAlpha(alpha); if (drawable != null) drawable.setAlpha(drawableAlpha); }
    }
    private static final class Session {
        Object panel, center, interactor, wallpaper, binder, depth;
        SeparateShadeFixedBlur blur;
        int nativeRadius;
        View frame, rootContainer;
        float fraction, frameAlpha, published = Float.NaN;
        boolean ending, writingVisibility, requestedVisibility;
        final List<Layer> layers = new ArrayList<>();
    }
}
