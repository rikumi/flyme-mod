package dev.rikumi.flymemod;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import java.lang.reflect.Method;
import java.util.List;

/** Keep all original SDKStage blacklist writes, and separately persist explicit opt-ins. */
final class NightModeWhitelistHooks {
    static void install(ClassLoader loader, SharedPreferences preferences, SignalHooks.Installer installer)
            throws ReflectiveOperationException {
        String manager = "com.meizu.flyme.sdkstage.common.a";
        Class<?> base = loader.loadClass("com.meizu.flyme.sdkstage.common.d");
        var context = base.getDeclaredField("b"); context.setAccessible(true);
        Class<?> model = loader.loadClass("com.meizu.flyme.sdkstage.data.model.AppInfo");
        Method pkg = model.getDeclaredMethod("getPackageName");
        Method state = model.getDeclaredMethod("setNightModeEnabled", boolean.class);
        Method read = base.getDeclaredMethod("j", String.class);
        installer.hook(manager, "j", chain -> {
            if (!preferences.getBoolean(ModuleSettings.DARK_APP_WHITELIST_MODE, OneClickPreset.defaultSwitch(ModuleSettings.DARK_APP_WHITELIST_MODE))) return chain.proceed();
            Context ctx = (Context) context.get(chain.getThisObject());
            return NightModeWhitelistPolicy.contains(Settings.Global.getString(ctx.getContentResolver(),
                    NightModeWhitelistPolicy.KEY), (String) chain.getArg(0));
        }, String.class);
        installer.hook(manager, "t", chain -> {
            Object result = chain.proceed();
            if (preferences.getBoolean(ModuleSettings.DARK_APP_WHITELIST_MODE, OneClickPreset.defaultSwitch(ModuleSettings.DARK_APP_WHITELIST_MODE))) {
                Context ctx = (Context) context.get(chain.getThisObject());
                synchronized (NightModeWhitelistHooks.class) {
                    String original = Settings.Global.getString(ctx.getContentResolver(), NightModeWhitelistPolicy.KEY);
                    String updated = NightModeWhitelistPolicy.update(original, (String) chain.getArg(0), (boolean) chain.getArg(1));
                    if (!updated.equals(original)) Settings.Global.putString(ctx.getContentResolver(), NightModeWhitelistPolicy.KEY, updated);
                }
            }
            return result;
        }, String.class, boolean.class);
        installer.hook(manager, "s", chain -> {
            Object result = chain.proceed();
            if (preferences.getBoolean(ModuleSettings.DARK_APP_WHITELIST_MODE, OneClickPreset.defaultSwitch(ModuleSettings.DARK_APP_WHITELIST_MODE))) {
                Context ctx = (Context) context.get(chain.getThisObject());
                synchronized (NightModeWhitelistHooks.class) {
                    String original = Settings.Global.getString(ctx.getContentResolver(), NightModeWhitelistPolicy.KEY);
                    String updated = original;
                    for (Object row : (List<?>) chain.getArg(0)) updated = NightModeWhitelistPolicy.update(
                            updated, (String) pkg.invoke(row), (boolean) chain.getArg(1));
                    if (updated != null && !updated.equals(original)) Settings.Global.putString(ctx.getContentResolver(), NightModeWhitelistPolicy.KEY, updated);
                }
            }
            return result;
        }, List.class, boolean.class);
        // Refresh cached UI flags through the native state accessor after every load.
        installer.hook(base.getName(), "e", chain -> {
            Object result = chain.proceed();
            if (result instanceof List<?> rows) for (Object row : rows)
                state.invoke(row, read.invoke(chain.getThisObject(), pkg.invoke(row)));
            return result;
        });
    }
}
