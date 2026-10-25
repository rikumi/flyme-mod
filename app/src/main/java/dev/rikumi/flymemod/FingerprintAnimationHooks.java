package dev.rikumi.flymemod;

import android.view.View;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

final class FingerprintAnimationHooks {
    private static final String VIEW = "com.android.systemui.biometrics.UdfpsKeyguardViewLegacy";
    private static final String ANIMATION = "com.flyme.systemui.biometrics.FlymeFpAuthAnimView";

    static void installSystemUi(ClassLoader loader, SignalHooks.Installer installer,
                                Consumer<Method> deoptimize, BooleanSupplier enabled)
            throws ReflectiveOperationException {
        Class<?> animation = loader.loadClass(ANIMATION);
        Method stop = animation.getDeclaredMethod("stopDownAnimation");
        animation.getDeclaredMethod("startDownAnimation");
        installer.hook(ANIMATION, "startDownAnimation", chain -> {
            if (!enabled.getAsBoolean()) return chain.proceed();
            stop.invoke(chain.getThisObject());
            return null;
        });
        Class<?> view = loader.loadClass(VIEW);
        for (String suffix : new String[]{"", "Left", "Right"}) {
            String show = "showFingerprintDownAnim" + suffix;
            for (String caller : new String[]{"AuthController", "UdfpsController", "UdfpsControllerOverlay",
                    "UdfpsAnimationViewController"}) {
                deoptimize.accept(loader.loadClass("com.android.systemui.biometrics." + caller)
                        .getDeclaredMethod(show));
            }
            deoptimize.accept(view.getDeclaredMethod(show));
            if (suffix.isEmpty()) continue;
            Field ripple = view.getField("mFlymeFpAuthAnimView" + suffix);
            installer.hook(VIEW, show, chain -> {
                if (!enabled.getAsBoolean()) return chain.proceed();
                View effect = (View) ripple.get(chain.getThisObject());
                if (effect != null) effect.setAlpha(0f);
                return null;
            });
        }
    }

    static void installSettings(ClassLoader loader, SignalHooks.Installer installer,
                                Consumer<Method> deoptimize, BooleanSupplier enabled)
            throws ReflectiveOperationException {
        String controllerName = "com.meizu.settings.fingerprint.FpAnimationPreferenceController";
        Class<?> controller = loader.loadClass(controllerName);
        controller.getDeclaredMethod("getAvailabilityStatus");
        Class<?> base = loader.loadClass("com.android.settings.core.BasePreferenceController");
        deoptimize.accept(base.getDeclaredMethod("isAvailable"));
        deoptimize.accept(base.getDeclaredMethod("isSupported"));
        installer.hook(controllerName, "getAvailabilityStatus", chain ->
                enabled.getAsBoolean() ? 3 : chain.proceed());

        String pageName = "com.meizu.settings.fingerprint.FlymeFingerprintManagementFragment";
        Class<?> page = loader.loadClass(pageName);
        page.getDeclaredMethod("onResume");
        Method find = page.getMethod("findPreference", CharSequence.class);
        Method visible = loader.loadClass("androidx.preference.Preference").getMethod("setVisible", boolean.class);
        installer.hook(pageName, "onResume", chain -> {
            Object result = chain.proceed();
            Object preference = find.invoke(chain.getThisObject(), "fp_animation");
            if (preference != null) visible.invoke(preference, !enabled.getAsBoolean());
            return result;
        });
    }
}
