package com.rikumi.flymemod;

import android.content.Context;
import android.os.SystemClock;
import android.view.View;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Keeps merged control center open and switches to all tiles after its last notification is removed. */
final class CombinedEmptyShadeHooks {
    private final Consumer<Context> settings;
    private final BooleanSupplier enabled;
    private final BiConsumer<String, Throwable> log;
    private final Method dependencyGet, centerForSurfaces, isCombinedMode, modeValue, flingQs;
    private final Field expandedFraction, shadeInteractor, qsController;
    private final Map<View, Boolean> emptyStates = new WeakHashMap<>();
    private volatile long collapseHoldUntil;

    CombinedEmptyShadeHooks(ClassLoader loader, Consumer<Context> settings, BooleanSupplier enabled,
                            BiConsumer<String, Throwable> log) throws ReflectiveOperationException {
        this.settings = settings;
        this.enabled = enabled;
        this.log = log;
        Class<?> dependency = loader.loadClass("com.android.systemui.Dependency");
        Class<?> centralSurfaces = loader.loadClass("com.android.systemui.statusbar.phone.CentralSurfaces");
        Class<?> centralImpl = loader.loadClass("com.android.systemui.statusbar.phone.CentralSurfacesImpl");
        Class<?> center = loader.loadClass("com.flyme.systemui.controlcenter.phone.CenterController");
        Class<?> shadeInteractorClass = loader.loadClass("com.flyme.systemui.shade.domain.interactor.ShadeInteractor");
        Class<?> flowClass = loader.loadClass("kotlinx.coroutines.flow.StateFlow");
        Class<?> qsControllerClass = loader.loadClass("com.android.systemui.shade.QuickSettingsControllerImpl");
        dependencyGet = dependency.getMethod("get", Class.class);
        centerForSurfaces = centralImpl.getMethod("getCenterController");
        expandedFraction = center.getField("mExpandedFraction");
        shadeInteractor = center.getField("mShadeInteractor");
        qsController = centralImpl.getField("mQsController");
        isCombinedMode = shadeInteractorClass.getMethod("isExpandToQsEnabled");
        modeValue = flowClass.getMethod("getValue");
        flingQs = qsControllerClass.getMethod("flingQs", float.class, int.class);
        dependencyGet.setAccessible(true);
    }

    void install(SignalHooks.Installer installer) {
        installer.hook("com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout",
                "updateEmptyShadeView", chain -> {
                    View stack = (View) chain.getThisObject();
                    settings.accept(stack.getContext());
                    boolean showEmpty = (Boolean) chain.getArg(0);
                    boolean wasEmpty = emptyStates.getOrDefault(stack, true);
                    emptyStates.put(stack, showEmpty);
                    boolean active = enabled.getAsBoolean() && showEmpty && isMergedCenterOpen(stack.getContext());
                    if (!active) return chain.proceed();

                    Object[] args = chain.getArgs().toArray();
                    args[0] = false;
                    Object result = chain.proceed(args);
                    if (!wasEmpty) {
                        collapseHoldUntil = SystemClock.uptimeMillis() + 900L;
                        stack.post(() -> expandAllTiles(stack.getContext()));
                    }
                    return result;
                }, boolean.class, boolean.class, boolean.class);

        installer.hook("com.flyme.systemui.controlcenter.phone.CenterController", "collapsePanel", chain -> {
            Object center = chain.getThisObject();
            if (enabled.getAsBoolean() && SystemClock.uptimeMillis() <= collapseHoldUntil
                    && expandedFraction.getFloat(center) > 0f
                    && isCombined((Context) center.getClass().getField("mContext").get(center))) {
                collapseHoldUntil = 0L;
                expandAllTiles((Context) center.getClass().getField("mContext").get(center));
                return null;
            }
            return chain.proceed();
        }, boolean.class, boolean.class, float.class);
    }

    private boolean isMergedCenterOpen(Context context) {
        try {
            Object central = dependencyGet.invoke(null,
                    context.getClassLoader().loadClass("com.android.systemui.statusbar.phone.CentralSurfaces"));
            Object center = centerForSurfaces.invoke(central);
            return expandedFraction.getFloat(center) > 0f && isCombined(context);
        } catch (ReflectiveOperationException | RuntimeException e) {
            log.accept("Cannot inspect merged control center state", e);
            return false;
        }
    }

    private boolean isCombined(Context context) {
        try {
            Object central = dependencyGet.invoke(null,
                    context.getClassLoader().loadClass("com.android.systemui.statusbar.phone.CentralSurfaces"));
            Object center = centerForSurfaces.invoke(central);
            Object interactor = shadeInteractor.get(center);
            return Boolean.TRUE.equals(modeValue.invoke(isCombinedMode.invoke(interactor)));
        } catch (ReflectiveOperationException | RuntimeException e) {
            log.accept("Cannot inspect merged control center mode", e);
            return false;
        }
    }

    private void expandAllTiles(Context context) {
        if (!enabled.getAsBoolean() || !isMergedCenterOpen(context)) return;
        try {
            Object central = dependencyGet.invoke(null,
                    context.getClassLoader().loadClass("com.android.systemui.statusbar.phone.CentralSurfaces"));
            Object qs = qsController.get(central);
            // QSC fling target 0 is the native full-tile expansion endpoint.
            flingQs.invoke(qs, 0f, 0);
        } catch (ReflectiveOperationException | RuntimeException e) {
            log.accept("Cannot expand merged control center tiles", e);
        }
    }
}
