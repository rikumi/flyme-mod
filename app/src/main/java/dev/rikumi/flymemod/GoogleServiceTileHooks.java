package dev.rikumi.flymemod;

import android.app.Activity;
import android.app.Application;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import java.util.function.BiConsumer;

final class GoogleServiceTileHooks {
    private final BiConsumer<String, Throwable> log;
    private BroadcastReceiver receiver;

    GoogleServiceTileHooks(BiConsumer<String, Throwable> log) { this.log = log; }

    void install(ClassLoader loader, SignalHooks.Installer installer) throws ReflectiveOperationException {
        // Validate the actual declaration before registering the hook.
        Class.forName("com.android.systemui.SystemUIApplication", false, loader).getDeclaredMethod("onCreate");
        installer.hook("com.android.systemui.SystemUIApplication", "onCreate", chain -> {
            Object result = chain.proceed();
            register((Context) chain.getThisObject());
            return result;
        });
    }

    private void register(Context context) {
        // Screenshot and other SystemUI subprocesses must not toggle a second time.
        if (receiver != null || !GoogleServiceTile.SYSTEM_UI.equals(Application.getProcessName())) return;
        BroadcastReceiver candidate = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (!GoogleServiceTile.ACTION.equals(intent.getAction()) || !isOrderedBroadcast()) return;
                try {
                    context.getPackageManager().getApplicationInfo(GoogleServiceTile.GOOGLE, 0);
                    if (intent.getBooleanExtra(GoogleServiceTile.TOGGLE, false)) {
                        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
                        if (keyguard == null || keyguard.isDeviceLocked()) {
                            setResultCode(GoogleServiceTile.LOCKED);
                            return;
                        }
                        boolean enabled = Settings.Global.getInt(context.getContentResolver(), GoogleServiceTile.SETTING, 0) == 1;
                        try {
                            context.sendBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
                                    .setPackage(GoogleServiceTile.SYSTEM_UI).putExtra("reason", "google_service_tile"));
                        } catch (RuntimeException error) {
                            log.accept("Cannot collapse control center for Google services tile", error);
                        }
                        // This is the complete native setChecked write; Flyme handles the service lifecycle.
                        if (!Settings.Global.putInt(context.getContentResolver(), GoogleServiceTile.SETTING, enabled ? 0 : 1)) {
                            setResultCode(GoogleServiceTile.WRITE_FAILED);
                            return;
                        }
                    }
                    setResultCode(Activity.RESULT_OK);
                } catch (PackageManager.NameNotFoundException missing) {
                    setResultCode(GoogleServiceTile.NOT_INSTALLED);
                } catch (RuntimeException error) {
                    setResultCode(GoogleServiceTile.WRITE_FAILED);
                    log.accept("Cannot change native Google basic services state", error);
                }
            }
        };
        try {
            // Only apps signed with the module's certificate can send requests to this receiver.
            context.registerReceiver(candidate, new IntentFilter(GoogleServiceTile.ACTION),
                    GoogleServiceTile.PERMISSION, new Handler(Looper.getMainLooper()), Context.RECEIVER_EXPORTED);
            receiver = candidate;
        } catch (RuntimeException error) {
            log.accept("Cannot register Google services tile bridge", error);
        }
    }
}
