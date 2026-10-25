package dev.rikumi.flymemod;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import java.lang.reflect.Method;
import java.util.function.BiConsumer;

final class AssistantWidgetCloneHooks {
    private final BiConsumer<String, Throwable> log;
    AssistantWidgetCloneHooks(BiConsumer<String, Throwable> log) { this.log = log; }

    void install(ClassLoader loader, SignalHooks.Installer installer) throws ReflectiveOperationException {
        Class<?> helper = loader.loadClass("o2.z");
        Method instance = helper.getDeclaredMethod("v");
        Method preferences = helper.getDeclaredMethod("w");
        for (String size : new String[]{"2x2", "4x2"}) {
            String name = "com.meizu.assistant.feature.quotes.Quotes" + size + "WidgetProvider";
            Method update = loader.loadClass(name).getDeclaredMethod("onUpdate", Context.class, AppWidgetManager.class, int[].class);
            loader.loadClass(name).getDeclaredMethod("onAppWidgetOptionsChanged", Context.class, AppWidgetManager.class, int.class, Bundle.class);
            installer.hook(name, "onUpdate", chain -> {
                Context context = (Context) chain.getArg(0);
                AppWidgetManager manager = (AppWidgetManager) chain.getArg(1);
                for (int id : (int[]) chain.getArg(2)) copy(context, manager, id, instance, preferences);
                return chain.proceed();
            }, Context.class, AppWidgetManager.class, int[].class);
            installer.hook(name, "onAppWidgetOptionsChanged", chain -> {
                boolean copied = copy((Context) chain.getArg(0), (AppWidgetManager) chain.getArg(1),
                        (Integer) chain.getArg(2), instance, preferences);
                Object result = chain.proceed();
                if (copied) update.invoke(chain.getThisObject(), chain.getArg(0), chain.getArg(1),
                        new int[]{(Integer) chain.getArg(2)});
                return result;
            }, Context.class, AppWidgetManager.class, int.class, Bundle.class);
        }
    }

    private boolean copy(Context context, AppWidgetManager manager, int id, Method instance, Method preferences) {
        try {
            if (context.getPackageManager().getPackageInfo(context.getPackageName(), 0).getLongVersionCode() != 130000006) return false;
            Bundle options = manager.getAppWidgetOptions(id);
            int oldId = options.getInt(LauncherWidgetClone.SOURCE, -1);
            if (oldId < 0 || oldId == id || options.getBoolean(LauncherWidgetClone.COMPLETE)) return false;
            AppWidgetProviderInfo source = manager.getAppWidgetInfo(oldId), target = manager.getAppWidgetInfo(id);
            if (source == null || target == null || !source.provider.equals(target.provider)
                    || !source.getProfile().equals(target.getProfile())) return false;
            SharedPreferences prefs = (SharedPreferences) preferences.invoke(instance.invoke(null));
            // Never overwrite a configuration the user has already made in the new layout.
            if (!LauncherWidgetClone.copyConfiguration(prefs, oldId, id)) return false;
            options.putBoolean(LauncherWidgetClone.COMPLETE, true);
            options.putBoolean("appWidgetRestoreCompleted", true);
            manager.updateAppWidgetOptions(id, options);
            log.accept("Copied independent Flyme quote widget configuration", null);
            return true;
        } catch (Exception error) { log.accept("Cannot copy Flyme quote widget configuration", error); }
        return false;
    }
}
