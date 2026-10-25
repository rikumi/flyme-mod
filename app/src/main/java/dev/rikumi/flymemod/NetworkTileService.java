package dev.rikumi.flymemod;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

public abstract class NetworkTileService extends TileService {
    static final String ACTION = "dev.rikumi.flymemod.action.NETWORK_TILE";
    static final String PERMISSION = "dev.rikumi.flymemod.permission.CONTROL_NETWORK_TILES";
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean listening, pending;
    private int generation;
    protected abstract String kind();
    protected abstract int label();
    protected abstract int icon();
    protected int icon(boolean enabled) { return icon(); }
    protected int icon(Bundle state) { return icon(state != null && state.getInt("state") == Tile.STATE_ACTIVE); }
    private final Runnable refresh = () -> query(false);
    @Override public void onStartListening() { super.onStartListening(); listening = true; query(false); }
    @Override public void onStopListening() {
        listening = false; main.removeCallbacks(refresh); super.onStopListening();
    }
    @Override public void onDestroy() {
        listening = false; generation++; main.removeCallbacksAndMessages(null); super.onDestroy();
    }
    @Override public void onClick() {
        super.onClick();
        if (pending) return;
        if (isLocked()) unlockAndRun(() -> query(true)); else query(true);
    }
    private void query(boolean toggle) {
        if (pending || (!listening && !toggle)) return;
        main.removeCallbacks(refresh);
        pending = true;
        int request = ++generation;
        Intent intent = new Intent(ACTION).setPackage("com.android.systemui")
                .putExtra("kind", kind()).putExtra("toggle", toggle);
        Runnable timeout = () -> {
            if (generation == request && pending) {
                generation++; finish(null, toggle, "系统界面未响应");
            }
        };
        main.postDelayed(timeout, 5000);
        try {
            sendOrderedBroadcast(intent, null, new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    if (generation != request) return;
                    main.removeCallbacks(timeout);
                    finish(getResultCode() == Activity.RESULT_OK ? getResultExtras(false) : null,
                            toggle, getResultData());
                }
            }, main, Activity.RESULT_CANCELED, null, null);
        } catch (RuntimeException error) {
            main.removeCallbacks(timeout); finish(null, toggle, null);
        }
    }
    private void finish(Bundle result, boolean toggle, String error) {
        pending = false;
        Tile tile = getQsTile();
        if (tile != null) {
            String subtitle = result == null ? "请启用模块并重启系统界面" : result.getString("subtitle", "");
            String tileLabel = result == null ? getString(label()) : result.getString("label", getString(label()));
            int state = result == null ? Tile.STATE_UNAVAILABLE : result.getInt("state", Tile.STATE_UNAVAILABLE);
            tile.setLabel(tileLabel); tile.setIcon(Icon.createWithResource(this, icon(result)));
            tile.setState(state);
            if (android.os.Build.VERSION.SDK_INT >= 29) tile.setSubtitle(subtitle);
            tile.setContentDescription(tileLabel + "，" + subtitle); tile.updateTile();
        }
        if (toggle && error != null) Toast.makeText(this, error, Toast.LENGTH_SHORT).show();

        if (listening) main.postDelayed(refresh, 1500);
    }
    public static final class Wifi extends NetworkTileService {
        protected String kind() { return "wifi"; }
        protected int label() { return R.string.network_tile_wifi; }
        protected int icon() { return R.drawable.ic_control_wifi; }
    }
    public static final class Bluetooth extends NetworkTileService {
        protected String kind() { return "bluetooth"; }
        protected int label() { return R.string.network_tile_bluetooth; }
        protected int icon() { return R.drawable.ic_control_bluetooth; }
    }
    public static final class Mobile extends NetworkTileService {
        protected String kind() { return "mobile"; }
        protected int label() { return R.string.network_tile_mobile; }
        protected int icon() { return R.drawable.ic_control_mobile; }
    }
    public static final class DataSim extends NetworkTileService {
        private int lastDataSimSlot = -1;
        protected String kind() { return "sim"; }
        protected int label() { return R.string.network_tile_sim; }
        protected int icon() { return R.drawable.ic_network_sim; }
        @Override protected int icon(Bundle state) {
            if (state == null) return icon();
            if (state.containsKey("data_sim_slot")) {
                lastDataSimSlot = state.getInt("data_sim_slot", -1);
            } else {

                String subtitle = state.getString("subtitle", "");
                if (subtitle.startsWith("SIM 1 · ")) lastDataSimSlot = 0;
                else if (subtitle.startsWith("SIM 2 · ")) lastDataSimSlot = 1;
                else if (!"正在切换数据卡".equals(subtitle)) lastDataSimSlot = -1;
            }
            return switch (lastDataSimSlot) {
                case 0 -> R.drawable.ic_network_sim_1;
                case 1 -> R.drawable.ic_network_sim_2;
                default -> icon();
            };
        }
    }
}
