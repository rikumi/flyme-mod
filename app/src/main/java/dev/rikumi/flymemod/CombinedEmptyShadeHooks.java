package dev.rikumi.flymemod;

import android.content.Context;
import android.os.Handler;
import android.view.View;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

final class CombinedEmptyShadeHooks {
    private static final String PANEL = "com.android.systemui.shade.NotificationPanelViewController";
    private static final String STACK = "com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout";
    private final Consumer<Context> settings;
    private final BooleanSupplier enabled;
    private final BiConsumer<String, Throwable> log;
    private final Class<?> centralSurfaceUtil, notifStatsType;
    private final Method dependencyGet, centralForUtil, panelForSurfaces, isCombinedMode, modeValue, flingQs, notifCount;
    private final Field expandedFraction, barState, panelView, shadeInteractor, qsController;
    private final Field stackController, closeHandler, closeRunnable, notifStats, stackView, clearInProgress, mediaDismiss;
    private final Field expansionAnimator, animatorExpand;
    private final Method fullyExpanded, tracking, cancelExpansion, setExpandImmediate;
    private final Method stackAnimating, requestChildrenUpdate;
    private final Field keyguardMediaController, mediaHost;
    private final Method mediaVisible, updateEmptyShade;
    private final Map<View, Boolean> visibleStates = new WeakHashMap<>();
    private final Map<View, Boolean> pendingExpansions = new WeakHashMap<>();

    CombinedEmptyShadeHooks(ClassLoader loader, Consumer<Context> settings, BooleanSupplier enabled,
                            BiConsumer<String, Throwable> log) throws ReflectiveOperationException {
        this.settings = settings;
        this.enabled = enabled;
        this.log = log;
        Class<?> dependency = loader.loadClass("com.android.systemui.Dependency");
        centralSurfaceUtil = loader.loadClass("com.flyme.systemui.statusbar.CentralSurfaceUtil");
        centralForUtil = centralSurfaceUtil.getMethod("getCentralSurfacesImpl");
        Class<?> centralImpl = loader.loadClass("com.android.systemui.statusbar.phone.CentralSurfacesImpl");
        Class<?> panel = loader.loadClass(PANEL);
        Class<?> shadeInteractorClass = loader.loadClass("com.android.systemui.shade.domain.interactor.ShadeInteractor");
        Class<?> flowClass = loader.loadClass("kotlinx.coroutines.flow.StateFlow");
        Class<?> qsControllerClass = loader.loadClass("com.android.systemui.shade.QuickSettingsControllerImpl");
        dependencyGet = dependency.getMethod("get", Class.class);
        panelForSurfaces = centralImpl.getMethod("getNotificationPanelViewController");
        expandedFraction = panel.getField("mExpandedFraction");
        barState = panel.getField("mBarState");
        panelView = panel.getField("mView");
        shadeInteractor = panel.getField("mShadeInteractor");
        qsController = panel.getField("mQsController");
        stackController = panel.getField("mNotificationStackScrollLayoutController");
        Class<?> stackControllerType = loader.loadClass(STACK + "Controller");
        closeHandler = stackControllerType.getField("mCloseShadeHandler");
        closeRunnable = stackControllerType.getField("mCloseRunnable");
        notifStats = stackControllerType.getField("mNotifStats");
        stackView = stackControllerType.getField("mView");
        keyguardMediaController = stackControllerType.getField("mKeyguardMediaController");
        mediaHost = loader.loadClass("com.android.systemui.media.controls.ui.controller.KeyguardMediaController").getField("mediaHost");
        mediaVisible = loader.loadClass("com.android.systemui.media.controls.ui.view.MediaHost").getMethod("getVisible");
        updateEmptyShade = loader.loadClass(STACK).getMethod("updateEmptyShadeView", boolean.class, boolean.class, boolean.class);
        clearInProgress = loader.loadClass(STACK).getField("mClearAllInProgress");
        mediaDismiss = loader.loadClass(STACK).getField("mMediaDismiss");
        stackAnimating = loader.loadClass(STACK).getMethod("isCurrentlyAnimating");
        requestChildrenUpdate = loader.loadClass(STACK).getMethod("requestChildrenUpdate");
        expansionAnimator = qsControllerClass.getField("mExpansionAnimator");
        animatorExpand = qsControllerClass.getField("mAnimatorExpand");
        fullyExpanded = qsControllerClass.getMethod("getFullyExpanded");
        tracking = qsControllerClass.getMethod("isTracking");
        cancelExpansion = qsControllerClass.getMethod("cancelExpansionAnimation");
        setExpandImmediate = qsControllerClass.getMethod("setExpandImmediate", boolean.class);
        notifStatsType = loader.loadClass("com.android.systemui.statusbar.notification.data.model.NotifStats");
        notifCount = notifStatsType.getMethod("getNumActiveNotifs");
        isCombinedMode = shadeInteractorClass.getMethod("isExpandToQsEnabled");
        modeValue = flowClass.getMethod("getValue");
        flingQs = qsControllerClass.getMethod("flingQs", float.class, int.class, Runnable.class, boolean.class);
        dependencyGet.setAccessible(true);
    }

    void install(SignalHooks.Installer installer) {
        installer.hook(STACK + "Controller$NotifStackControllerImpl", "setNotifStats", chain -> {
            Object panel = activePanel();
            boolean becameEmpty = panel != null && ((Number) notifCount.invoke(chain.getArg(0))).intValue() == 0
                    && ((Number) notifCount.invoke(notifStats.get(stackController.get(panel)))).intValue() > 0;
            Object result = chain.proceed();
            if (panel != null && ((Number) notifCount.invoke(chain.getArg(0))).intValue() == 0) {
                Object controller = stackController.get(panel);
                ((Handler) closeHandler.get(controller)).removeCallbacks((Runnable) closeRunnable.get(controller));
                if (becameEmpty) scheduleExpansion(panel);
            }
            return result;
        }, notifStatsType);

        installer.hook(STACK, "updateEmptyShadeView", chain -> {
            Object panel = activePanel();
            if (panel != null && stackView.get(stackController.get(panel)) == chain.getThisObject()
                    && isContentEmpty(stackController.get(panel))) {
                Object[] args = chain.getArgs().toArray();
                args[0] = false;
                return chain.proceed(args);
            }
            return chain.proceed();
        }, boolean.class, boolean.class, boolean.class);
        installer.hook("com.android.systemui.media.controls.ui.controller.KeyguardMediaController",
                "onMediaHostVisibilityChanged", chain -> {
            Object result = chain.proceed();
            if (Boolean.TRUE.equals(chain.getArg(0))) return result;
            Object panel = activePanel();
            if (panel != null && keyguardMediaController.get(stackController.get(panel)) == chain.getThisObject()
                    && isContentEmpty(stackController.get(panel))) scheduleExpansion(panel);
            return result;
        }, boolean.class);
        installer.hook(STACK, "onChildAnimationFinished", chain -> {
            Object result = chain.proceed();
            Object panel = activePanel();
            if (panel != null && stackView.get(stackController.get(panel)) == chain.getThisObject()) {
                View view = (View) panelView.get(panel);
                if (Boolean.FALSE.equals(pendingExpansions.get(view))) postExpansion(panel, view);
            }
            return result;
        });

        installer.hook(STACK + "Controller", "collapsePanelWhenMediaDismiss", chain -> {
            Object panel = activePanel();
            if (panel != null && stackController.get(panel) == chain.getThisObject()) return null;
            return chain.proceed();
        });
        installer.hook(STACK, "setMediaDismiss", chain -> {
            Object result = chain.proceed();
            Object panel = activePanel();
            if (panel != null && stackView.get(stackController.get(panel)) == chain.getThisObject()) {
                Object controller = stackController.get(panel);
                if (((Number) notifCount.invoke(notifStats.get(controller))).intValue() == 0) {
                    ((Handler) closeHandler.get(controller)).removeCallbacks((Runnable) closeRunnable.get(controller));
                    requestChildrenUpdate.invoke(chain.getThisObject());
                    scheduleExpansion(panel);
                }
            }
            return result;
        });
        // A hidden media container may produce no animation event, hence no completion callback.
        installer.hook(STACK, "startAnimationToState", chain -> {
            Object result = chain.proceed();
            if (pendingExpansions.isEmpty()) return result;
            Object stack = chain.getThisObject();
            if (mediaDismiss.getBoolean(stack) || Boolean.TRUE.equals(stackAnimating.invoke(stack))) return result;
            Object panel = activePanel();
            if (panel != null && stackView.get(stackController.get(panel)) == stack) {
                View view = (View) panelView.get(panel);
                if (Boolean.FALSE.equals(pendingExpansions.get(view))) postExpansion(panel, view);
            }
            return result;
        });

        installer.hook(STACK, "clearNotifications", chain -> {
            View stack = (View) chain.getThisObject();
            settings.accept(stack.getContext());
            if (activePanel() == null) return chain.proceed();
            Object[] args = chain.getArgs().toArray();
            args[1] = false;
            return chain.proceed(args);
        }, int.class, boolean.class, boolean.class, boolean.class);

        // Observe the actual notification-presence flow as well as the legacy empty-shade view.
        installer.hook(PANEL, "onVisibleNotificationsChanged", chain -> {
            Object result = chain.proceed();
            Object panel = chain.getThisObject();
            View view = (View) panelView.get(panel);
            settings.accept(view.getContext());
            boolean visible = (Boolean) chain.getArg(0);
            Boolean previous = visibleStates.put(view, visible);
            if (!visible && Boolean.TRUE.equals(previous) && activePanel() == panel) {
                scheduleExpansion(panel);
            }
            return result;
        }, boolean.class);
    }

    private Object activePanel() {
        try {
            // Flyme registers CentralSurfaceUtil, not CentralSurfaces, in Dependency.
            Object central = centralForUtil.invoke(dependencyGet.invoke(null, centralSurfaceUtil));
            if (central == null) return null;
            Object panel = panelForSurfaces.invoke(central);
            if (panel == null) return null;
            settings.accept(((View) panelView.get(panel)).getContext());
            if (!enabled.getAsBoolean()) return null;

            if (barState.getInt(panel) != 0 || expandedFraction.getFloat(panel) <= 0f) return null;
            Object interactor = shadeInteractor.get(panel);
            return Boolean.TRUE.equals(modeValue.invoke(isCombinedMode.invoke(interactor))) ? panel : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            log.accept("Cannot inspect merged notification panel state", e);
            return null;
        }
    }

    private void scheduleExpansion(Object panel) throws IllegalAccessException {
        View view = (View) panelView.get(panel);
        if (Boolean.TRUE.equals(pendingExpansions.get(view))) return;
        postExpansion(panel, view);
    }

    private boolean isContentEmpty(Object controller) throws ReflectiveOperationException {
        if (((Number) notifCount.invoke(notifStats.get(controller))).intValue() != 0) return false;
        Object host = mediaHost.get(keyguardMediaController.get(controller));
        return !Boolean.TRUE.equals(mediaVisible.invoke(host));
    }

    private void postExpansion(Object panel, View view) {
        pendingExpansions.put(view, true);
        view.post(() -> {
            try {
                if (activePanel() != panel) {
                    pendingExpansions.remove(view);
                    return;
                }
                Object controller = stackController.get(panel);
                if (!isContentEmpty(controller)) {
                    pendingExpansions.remove(view);
                    return;
                }
                Object stack = stackView.get(controller);
                if (clearInProgress.getBoolean(stack) || mediaDismiss.getBoolean(stack)
                        || Boolean.TRUE.equals(stackAnimating.invoke(stack))) {
                    pendingExpansions.put(view, false);
                    return;
                }
                pendingExpansions.remove(view);
                updateEmptyShade.invoke(stack, false, false, false);
                Object qs = qsController.get(panel);
                if (Boolean.TRUE.equals(tracking.invoke(qs)) || Boolean.TRUE.equals(fullyExpanded.invoke(qs))) return;
                if (expansionAnimator.get(qs) != null && animatorExpand.getBoolean(qs)) return;
                cancelExpansion.invoke(qs);
                setExpandImmediate.invoke(qs, true);
                // QSC fling target 0 is the native full-tile expansion endpoint.
                flingQs.invoke(qs, 0f, 0, null, true);
            } catch (ReflectiveOperationException | RuntimeException e) {
                pendingExpansions.remove(view);
                log.accept("Cannot expand merged control center tiles", e);
            }
        });
    }
}
