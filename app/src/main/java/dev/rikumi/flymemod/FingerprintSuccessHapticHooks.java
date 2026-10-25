package dev.rikumi.flymemod;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

final class FingerprintSuccessHapticHooks {
    static void install(ClassLoader loader, SharedPreferences preferences, SignalHooks.Installer installer,
                        Consumer<Method> deoptimize, BiConsumer<String,Throwable> log)
            throws ReflectiveOperationException {
        Class<?> client=loader.loadClass("com.android.server.biometrics.sensors.fingerprint.aidl.FingerprintAuthenticationClient");
        Class<?> identifier=loader.loadClass("android.hardware.biometrics.BiometricAuthenticator$Identifier");
        Method success=client.getMethod("wasAuthSuccessful");
        Method context=client.getMethod("getContext");
        Method nativeEffect=VibrationEffect.class.getMethod("get", int.class, boolean.class);
        deoptimize.accept(client.getDeclaredMethod("onAuthenticated",identifier,boolean.class,ArrayList.class));
        installer.hook(client.getName(),"onAuthenticated",chain -> {
            Object result=chain.proceed();
            if(Boolean.TRUE.equals(chain.getArg(1)) && Boolean.TRUE.equals(success.invoke(chain.getThisObject()))
                    && preferences.getBoolean(ModuleSettings.FINGERPRINT_SUCCESS_HAPTIC, false)) {
                try {
                    Vibrator vibrator=((Context)context.invoke(chain.getThisObject())).getSystemService(Vibrator.class);
                    if(vibrator!=null && vibrator.hasVibrator()) {
                        vibrator.vibrate((VibrationEffect)nativeEffect.invoke(null,31025,false),
                                new VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_HARDWARE_FEEDBACK).build());
                        log.accept("Fingerprint success feedback requested: effect=31025, fallback=false",null);
                    }
                } catch(ReflectiveOperationException | RuntimeException error) {
                    log.accept("Cannot play fingerprint success haptic feedback",error);
                }
            }
            return result;
        },identifier,boolean.class,ArrayList.class);
        log.accept("Fingerprint success feedback installed: effect=31025, fallback=false",null);
    }
}
