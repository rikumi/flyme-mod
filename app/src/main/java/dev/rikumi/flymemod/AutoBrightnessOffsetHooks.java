package dev.rikumi.flymemod;

import android.content.SharedPreferences;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.Consumer;

final class AutoBrightnessOffsetHooks {
    static void install(ClassLoader loader, SharedPreferences preferences, SignalHooks.Installer installer,
                        Consumer<Method> deoptimize) throws ReflectiveOperationException {
        Class<?> controller = loader.loadClass("com.android.server.display.AutomaticBrightnessController");
        Field state = controller.getDeclaredField("mDisplayState");
        Field policy = controller.getDeclaredField("mDisplayPolicy");
        state.setAccessible(true);
        policy.setAccessible(true);
        Class<?> event = loader.loadClass("com.android.server.display.brightness.BrightnessEvent");
        Class<?> legacy = loader.loadClass("com.android.server.display.brightness.strategy.AutomaticBrightnessStrategy2");
        Class<?> modern = loader.loadClass("com.android.server.display.brightness.strategy.AutomaticBrightnessStrategy");
        Method legacyTarget = legacy.getDeclaredMethod("getAutomaticScreenBrightness", event);
        Method modernTarget = modern.getDeclaredMethod("getAutomaticScreenBrightness", event, boolean.class);
        Field legacyController = legacy.getDeclaredField("mAutomaticBrightnessController");
        Field modernController = modern.getDeclaredField("mAutomaticBrightnessController");
        legacyController.setAccessible(true);
        modernController.setAccessible(true);
        // Both paths exist in this Flyme build. Deoptimize the callers, not just the hooked getters.
        deoptimize.accept(loader.loadClass("com.android.server.display.DisplayPowerController")
                .getDeclaredMethod("updatePowerState"));
        deoptimize.accept(modern.getDeclaredMethod("isAutoBrightnessValid"));
        deoptimize.accept(modern.getDeclaredMethod("updateBrightness",
                loader.loadClass("com.android.server.display.brightness.StrategyExecutionRequest")));
        hook(legacyTarget, legacyController, state, policy, preferences, installer);
        hook(modernTarget, modernController, state, policy, preferences, installer);
    }

    private static void hook(Method target, Field controllerField, Field state, Field policy,
                             SharedPreferences preferences, SignalHooks.Installer installer) throws ReflectiveOperationException {
        Class<?> strategy = target.getDeclaringClass();
        // The modern strategy shadows these fields, so resolve each declaring class separately.
        Field enabled = strategy.getDeclaredField("mIsAutoBrightnessEnabled");
        Field temporary = strategy.getDeclaredField("mTemporaryAutoBrightnessAdjustment");
        enabled.setAccessible(true);
        temporary.setAccessible(true);
        installer.hook(target.getDeclaringClass().getName(), target.getName(), chain -> {
            Object result = chain.proceed();
            if (!preferences.getBoolean(ModuleSettings.AUTO_BRIGHTNESS_OFFSET_ENABLED, false)) return result;
            Object strategyInstance = chain.getThisObject();
            if (!enabled.getBoolean(strategyInstance)
                    || !Float.isNaN(temporary.getFloat(strategyInstance))) return result;
            Object controller = controllerField.get(strategyInstance);
            // STATE_ON only: do not brighten AOD, suspended displays, or screen-off transitions.
            if (controller == null || state.getInt(controller) != 2 || policy.getInt(controller) == 1) return result;
            return offset((Float) result, preferences.getInt(ModuleSettings.AUTO_BRIGHTNESS_OFFSET,
                    ModuleSettings.AUTO_BRIGHTNESS_OFFSET_DEFAULT));
        }, target.getParameterTypes());
    }

    static float offset(float brightness, int percent) {
        // Preserve invalid/off sentinels and native high-brightness values outside the normal range.
        if (!Float.isFinite(brightness) || brightness < 0f || brightness > 1f) return brightness;
        int clamped = Math.max(-30, Math.min(50, percent));
        return Math.max(0f, Math.min(1f, brightness + clamped / 100f));
    }
}
