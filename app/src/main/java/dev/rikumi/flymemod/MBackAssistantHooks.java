package dev.rikumi.flymemod;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Message;
import java.lang.reflect.Method;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Select Flyme's native home action when the mBack assistant is uninstalled. */
final class MBackAssistantHooks {
    private static final String ACTIONS = "com.flyme.systemui.navigationbar.actions.NavBarActionsConfig";
    private static final String ASSISTANT = "com.meizu.voiceassistant";
    private final ClassLoader loader;
    private final Consumer<Context> settings;
    private final BooleanSupplier enabled;

    MBackAssistantHooks(ClassLoader loader, Consumer<Context> settings, BooleanSupplier enabled)
            throws ReflectiveOperationException {
        this.loader = loader;
        this.settings = settings;
        this.enabled = enabled;
        loader.loadClass(ACTIONS).getDeclaredMethod("getMBackLongTouchAction", Context.class);
    }

    void install(SignalHooks.Installer installer, Consumer<Method> deoptimize)
            throws ReflectiveOperationException {
        installer.hook(ACTIONS, "getMBackLongTouchAction", chain -> {
            Object action = chain.proceed();
            Context context = (Context) chain.getArg(0);
            settings.accept(context);
            if (!enabled.getAsBoolean() || !"ai".equals(action)) return action;
            try {
                // No MATCH_UNINSTALLED_PACKAGES: a system package removed for the
                // current user must also be treated as absent. Disabled is installed.
                context.getPackageManager().getApplicationInfo(ASSISTANT, 0);
                return action;
            } catch (PackageManager.NameNotFoundException absent) {
                return "home";
            }
        }, Context.class);
        deoptimize.accept(loader.loadClass("com.flyme.systemui.navigationbar.MBackButtonController$1")
                .getDeclaredMethod("handleMessage", Message.class));
        deoptimize.accept(loader.loadClass("com.flyme.systemui.navigationbar.NavBarExt")
                .getDeclaredMethod("onLongTouchWithoutPressure"));
        deoptimize.accept(loader.loadClass("com.flyme.systemui.navigationbar.MBackButtonController")
                .getDeclaredMethod("prepareLongClickTime"));
    }
}
