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

/** Keeps merged control center open and switches to all tiles after its last notification is removed. */
final class CombinedEmptyShadeHooks {
    private static final String PANEL = "com.android.systemui.shade.NotificationPanelViewController";
    private static final String STACK = "com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout";
    private final Consumer<Context> settings;
    private final BooleanSupplier enabled;
    private final BiConsumer<String, Throwable> log;
    private final Class<?> centralSurfaceUtil, notifStatsType;
    private final Method dependencyGet, centralForUtil, panelForSurfaces, isCombinedMode, modeValue, flingQs, notifCount;
    private final Field expandedFraction, barState, panelView, shadeInteractor, qsController;
    private final Field stackController, closeHandler, closeRunnable, notifStats;
    private final Map<View, Boolean> emptyStates = new WeakHashMap<>();
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
        notifStatsType = loader.loadClass("com.android.systemui.statusbar.notification.data.model.NotifStats");
        notifCount = notifStatsType.getMethod("getNumActiveNotifs");
        isCombinedMode = shadeInteractorClass.getMethod("isExpandToQsEnabled");
        modeValue = flowClass.getMethod("getValue");
        flingQs = qsControllerClass.getMethod("flingQs", float.class, int.class);
        dependencyGet.setAccessible(true);
    }

    void install(SignalHooks.Installer installer) {
        installer.hook(STACK + "Controller$NotifStackControllerImpl", "setNotifStats", chain -> {
            Object panel = activePanel();
            boolean becameEmpty = panel != null && ((Number) notifCount.invoke(chain.getArg(0))).intValue() == 0
                    && ((Number) notifCount.invoke(notifStats.get(stackController.get(panel)))).intValue() > 0;
            Object result = chain.proceed();
            if (becameEmpty) {
                Object controller = stackController.get(panel);
                ((Handler) closeHandler.get(controller)).removeCallbacks((Runnable) closeRunnable.get(controller));
                scheduleExpansion(panel);
            }
            return result;
        }, notifStatsType);
        installer.hook(STACK, "updateEmptyShadeView", chain -> {
            View stack = (View) chain.getThisObject();
            settings.accept(stack.getContext());
            boolean showEmpty = (Boolean) chain.getArg(0);
            boolean wasEmpty = emptyStates.getOrDefault(stack, true);
            emptyStates.put(stack, showEmpty);
            Object panel = activePanel();
            if (!showEmpty || panel == null) return chain.proceed();

            Object[] args = chain.getArgs().toArray();
            args[0] = false;
            Object result = chain.proceed(args);
            if (!wasEmpty) scheduleExpansion(panel);
            return result;
        }, boolean.class, boolean.class, boolean.class);

        // The clear-all animation schedules NPVC collapse after 200ms when this argument is true.
        // Disable that request at its source rather than swallowing subsequent manual collapses.
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
            if (!(Boolean) chain.getArg(0) && activePanel() != null) {
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
            // The combined shade belongs to NPVC, not the separate CenterController.
            // Preserve lockscreen notification behavior.
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
        if (pendingExpansions.put(view, true) != null) return;
        view.post(() -> {
            pendingExpansions.remove(view);
            if (activePanel() != panel) return;
            try {
                // QSC fling target 0 is the native full-tile expansion endpoint.
                flingQs.invoke(qsController.get(panel), 0f, 0);
            } catch (ReflectiveOperationException | RuntimeException e) {
                log.accept("Cannot expand merged control center tiles", e);
            }
        });
    }
}
