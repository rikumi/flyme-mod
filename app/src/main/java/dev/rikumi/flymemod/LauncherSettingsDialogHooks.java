package dev.rikumi.flymemod;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.preference.ListPreference;
import java.lang.reflect.Method;
import java.util.function.Consumer;

/** Flyme removes negative buttons in device-default ListPreference dialogs. */
final class LauncherSettingsDialogHooks {
    private final ClassLoader loader;
    LauncherSettingsDialogHooks(ClassLoader loader){this.loader=loader;}
    void install(SignalHooks.Installer installer,Consumer<Method> deoptimize) throws ReflectiveOperationException {
        installer.hook("android.preference.ListPreference","onPrepareDialogBuilder",chain -> {
            Object result=chain.proceed();ListPreference preference=(ListPreference)chain.getThisObject();
            int key=preference.getContext().getResources().getIdentifier("key_notification_pull_down_control_style","string","com.meizu.flyme.launcher");
            if(key!=0 && preference.getContext().getString(key).equals(preference.getKey()))
                ((AlertDialog.Builder)chain.getArg(0)).setNegativeButton(android.R.string.cancel,(DialogInterface.OnClickListener)preference);
            return result;
        },AlertDialog.Builder.class);
        deoptimize.accept(loader.loadClass("android.preference.DialogPreference").getDeclaredMethod("showDialog",Bundle.class));
    }
}
