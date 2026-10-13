package dev.rikumi.flymemod;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

/** Optional control-center tile; adding it never changes the native switch. */
public final class GoogleServiceTileService extends TileService {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ContentObserver observer = new ContentObserver(main) {
        @Override public void onChange(boolean selfChange) { refresh(); }
    };
    private boolean listening, ready, pending, pendingToggle;
    private int request;
    private String unavailable;

    @Override public void onStartListening() {
        super.onStartListening();
        if (!listening) {
            listening = true;
            getContentResolver().registerContentObserver(Settings.Global.getUriFor(GoogleServiceTile.SETTING), false, observer);
        }
        send(false);
    }

    @Override public void onStopListening() {
        if (listening) getContentResolver().unregisterContentObserver(observer);
        listening = false;
        // Collapsing the shade ends listening before the toggle result may arrive.
        if (!pendingToggle) {
            pending = false;
            request++;
        }
        super.onStopListening();
    }

    @Override public void onDestroy() {
        if (listening) getContentResolver().unregisterContentObserver(observer);
        listening = false;
        request++;
        super.onDestroy();
    }

    @Override public void onClick() {
        super.onClick();
        if (pending) return;
        if (isLocked()) unlockAndRun(() -> send(true));
        else send(true);
    }

    private void send(boolean toggle) {
        if (pending) return;
        pending = true;
        pendingToggle = toggle;
        int currentRequest = ++request;
        refresh();
        if (toggle) {
            boolean enabled = Settings.Global.getInt(getContentResolver(), GoogleServiceTile.SETTING, 0) == 1;
            Toast.makeText(this, getString(enabled ? R.string.google_service_disabling : R.string.google_service_enabling),
                    Toast.LENGTH_LONG).show();
        }
        Intent intent = new Intent(GoogleServiceTile.ACTION).setPackage(GoogleServiceTile.SYSTEM_UI)
                .putExtra(GoogleServiceTile.TOGGLE, toggle);
        // The final result is canceled when no hooked SystemUI receiver is present.
        try {
            sendOrderedBroadcast(intent, null, new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    if (currentRequest == request) complete(getResultCode(), toggle);
                }
            }, main, Activity.RESULT_CANCELED, null, null);
        } catch (RuntimeException error) {
            complete(Activity.RESULT_CANCELED, toggle);
        }
    }

    private void complete(int result, boolean toggle) {
        pending = false;
        pendingToggle = false;
        ready = result == Activity.RESULT_OK || result == GoogleServiceTile.WRITE_FAILED || result == GoogleServiceTile.LOCKED;
        unavailable = getString(switch (result) {
            case GoogleServiceTile.NOT_INSTALLED -> R.string.google_service_not_installed;
            case GoogleServiceTile.WRITE_FAILED -> R.string.google_service_write_failed;
            case GoogleServiceTile.LOCKED -> R.string.google_service_locked;
            default -> R.string.google_service_bridge_unavailable;
        });
        refresh();
        if (toggle && result != Activity.RESULT_OK) Toast.makeText(this, unavailable, Toast.LENGTH_LONG).show();
    }

    private void refresh() {
        if (!listening) return;
        Tile tile = getQsTile();
        if (tile == null) return;
        boolean enabled = Settings.Global.getInt(getContentResolver(), GoogleServiceTile.SETTING, 0) == 1;
        String status = pending ? getString(R.string.google_service_pending)
                : ready ? getString(enabled ? R.string.google_service_enabled : R.string.google_service_disabled)
                : unavailable == null ? getString(R.string.google_service_bridge_unavailable) : unavailable;
        tile.setIcon(Icon.createWithResource(this, R.drawable.ic_google_service_tile));
        tile.setLabel(getString(R.string.google_service_tile));
        tile.setState(ready && !pending ? enabled ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE : Tile.STATE_UNAVAILABLE);
        if (Build.VERSION.SDK_INT >= 29) tile.setSubtitle(status);
        tile.setContentDescription(getString(R.string.google_service_tile) + "，" + status);
        tile.updateTile();
    }
}
