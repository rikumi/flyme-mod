package com.rikumi.flymemod;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/** Folder icon paint and blur paths verified against Flyme Launcher 13000000. */
final class FolderAppearanceHooks {
    private static final String PREVIEW = "com.android.launcher3.folder.PreviewBackground";
    // 15% white, including when night mode is active.
    private static final int PREVIEW_BACKGROUND_COLOR = 0x26FFFFFF;
    private static final float PREVIEW_SMOOTHNESS = 0.3f;
    private final Consumer<Context> settings;
    private final BooleanSupplier restoreColor, customRadius;
    private final IntSupplier radiusDp;
    private final BiConsumer<String, Throwable> log;
    private boolean failed;
    private final Class<?> folderIcon;
    private final Field context, delegate, color, radius, blur, smoothness, managerIcon, iconBackground;
    private final Method bounds;

    FolderAppearanceHooks(ClassLoader loader, Consumer<Context> settings,
                          BooleanSupplier restoreColor, BooleanSupplier customRadius, IntSupplier radiusDp, BiConsumer<String, Throwable> log)
            throws ReflectiveOperationException {
        this.settings = settings; this.restoreColor = restoreColor;
        this.customRadius = customRadius; this.radiusDp = radiusDp; this.log = log;
        Class<?> preview = loader.loadClass(PREVIEW);
        folderIcon = loader.loadClass("com.android.launcher3.folder.FolderIcon");
        context = field(preview, "mContext"); delegate = field(preview, "mInvalidateDelegate");
        color = field(preview, "mBgColor"); radius = field(preview, "mBackgroundCornerRadius");
        blur = field(preview, "mBlurUtils");
        smoothness = field(loader.loadClass("com.meizu.flyme.launcher.utils.BackgroundBlurUtils"), "mSmoothness");
        bounds = preview.getMethod("getBackgroundBounds");
        Class<?> manager = loader.loadClass("com.android.launcher3.folder.PreviewItemManager");
        manager.getDeclaredMethod("draw", Canvas.class);
        managerIcon = field(manager, "mIcon");
        iconBackground = field(folderIcon, "mBackground");
    }

    void install(SignalHooks.Installer installer) {
        // Both Canvas and the native blur drawable consume these same fields. Temporary
        // overrides preserve native state, theme changes and open/close animation alpha.
        installer.hook(PREVIEW, "drawBackground", chain -> {
            if (failed || (!restoreColor.getAsBoolean() && !customRadius.getAsBoolean())) return chain.proceed();
            Object preview = chain.getThisObject();
            Integer originalColor = null;
            Float originalRadius = null;
            Object blurUtils = null;
            Float originalSmoothness = null;
            try {
                Context ctx = (Context) context.get(preview);
                if (folderIcon.isInstance(delegate.get(preview)) && ctx != null) {
                    settings.accept(ctx);
                    originalColor = color.getInt(preview);
                    originalRadius = radius.getFloat(preview);
                    if (restoreColor.getAsBoolean()) {
                        color.setInt(preview, PREVIEW_BACKGROUND_COLOR);
                        blurUtils = blur.get(preview);
                        if (blurUtils != null) {
                            originalSmoothness = smoothness.getFloat(blurUtils);
                            smoothness.setFloat(blurUtils, PREVIEW_SMOOTHNESS);
                        }
                    }
                    if (customRadius.getAsBoolean()) {
                        Rect rect = (Rect) bounds.invoke(preview);
                        if (rect != null) radius.setFloat(preview, boundedRadius(radiusDp.getAsInt(),
                                ctx.getResources().getDisplayMetrics().density, rect.width(), rect.height()));
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                restore(preview, originalColor, originalRadius, blurUtils, originalSmoothness);
                failed = true;
                log.accept("Disabled folder appearance override after preparation failed", error);
                return chain.proceed();
            }
            try {
                return chain.proceed();
            } finally {
                restore(preview, originalColor, originalRadius, blurUtils, originalSmoothness);
            }
        }, Canvas.class);
        installer.hook("com.android.launcher3.folder.PreviewItemManager", "draw", chain -> {
            if (failed || (!restoreColor.getAsBoolean() && !customRadius.getAsBoolean())) return chain.proceed();
            Rect rect = null;
            float scale = 1f;
            try {
                Object icon = managerIcon.get(chain.getThisObject());
                Object preview = iconBackground.get(icon);
                Context ctx = (Context) context.get(preview);
                if (ctx != null) {
                    settings.accept(ctx);
                    rect = (Rect) bounds.invoke(preview);
                    if (rect != null) {
                        float effectiveRadius = customRadius.getAsBoolean()
                                ? boundedRadius(radiusDp.getAsInt(), ctx.getResources().getDisplayMetrics().density,
                                        rect.width(), rect.height()) : radius.getFloat(preview);
                        scale = previewScale(effectiveRadius, rect.width(), rect.height());
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                failed = true;
                log.accept("Disabled folder appearance override after preview preparation failed", error);
                return chain.proceed();
            }
            if (scale == 1f) return chain.proceed();
            Canvas canvas = (Canvas) chain.getArg(0);
            int save = canvas.save();
            try {
                canvas.scale(scale, scale, rect.exactCenterX(), rect.exactCenterY());
                return chain.proceed();
            } finally {
                canvas.restoreToCount(save);
            }
        }, Canvas.class);
    }

    private void restore(Object preview, Integer originalColor, Float originalRadius,
                         Object blurUtils, Float originalSmoothness) {
        try {
            if (originalColor != null) color.setInt(preview, originalColor);
            if (originalRadius != null) radius.setFloat(preview, originalRadius);
            if (originalSmoothness != null) smoothness.setFloat(blurUtils, originalSmoothness);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            if (!failed) log.accept("Cannot restore native folder appearance fields", error);
            failed = true;
        }
    }

    static float boundedRadius(int dp, float density, int width, int height) {
        float requested = Math.max(0, Math.min(ModuleSettings.FOLDER_RADIUS_MAX, dp)) * density;
        return Math.min(requested, Math.max(0, Math.min(width, height)) / 2f);
    }

    static float previewScale(float radius, int width, int height) {
        int side = Math.min(width, height);
        if (side <= 0) return 1f;
        // At most 10% smaller; use relative radius so normal and large folders agree.
        return 1f - 0.1f * Math.max(0f, Math.min(1f, radius * 2f / side));
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
}
