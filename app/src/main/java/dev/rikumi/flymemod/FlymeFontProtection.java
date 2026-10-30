package dev.rikumi.flymemod;

import android.graphics.Typeface;
import android.content.SharedPreferences;
import android.util.Log;
import java.lang.reflect.Method;

final class FlymeFontProtection {
    private static boolean installed;
    static synchronized void install(XposedInit module, ClassLoader loader) {
        if (installed) return;
        try {
            SharedPreferences prefs = module.getRemotePreferences(SystemFontHooks.PREFS);
            Class<?> helper = Class.forName("android.content.res.flymetheme.FlymeFontsHelper", false, loader);
            Method has = helper.getDeclaredMethod("hasFlymeTypeface");
            Method defaults = helper.getDeclaredMethod("isDefaultTypeface", Typeface.class);
            // A theme font replaces the whole fallback chain. Selected system fonts take priority.
            module.hook(has).intercept(chain -> {
                if (prefs.getInt(SystemFontHooks.ZH, 0) == 2 || prefs.getInt(SystemFontHooks.LATIN, 0) != 0
                        || prefs.getInt(SystemFontHooks.EMOJI, 0) != 0)
                    return false;
                return chain.proceed();
            });
            module.hook(defaults).intercept(chain -> {
                if ((prefs.getInt(SystemFontHooks.MONO, -1) >= 0)
                        && MonospacePolicy.isMonospace((Typeface) chain.getArg(0))) return false;
                return chain.proceed();
            });
            module.deoptimize(has);
            module.deoptimize(defaults);
            // These small helper calls may already be inlined in boot-image callers.
            module.deoptimize(Typeface.class.getDeclaredMethod("defaultFromStyle", int.class));
            module.deoptimize(android.graphics.Paint.class.getDeclaredMethod("setTypefaceWithoutWarning", Typeface.class));
            module.deoptimize(android.graphics.Paint.class.getDeclaredMethod("setFlymeTypeface"));
            installed = true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            module.log(Log.ERROR, "FlymeMod", "Cannot protect system fonts from Flyme theme replacement", error);
        }
    }
    private FlymeFontProtection() {}
}
