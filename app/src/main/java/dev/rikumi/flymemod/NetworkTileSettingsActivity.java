package dev.rikumi.flymemod;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;

public final class NetworkTileSettingsActivity extends Activity {
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ComponentName tile = getIntent().getParcelableExtra(Intent.EXTRA_COMPONENT_NAME);
        String name = tile == null ? "" : tile.getClassName();
        Intent destination;
        if (name.equals(NetworkTileService.Wifi.class.getName())) destination = new Intent(Settings.ACTION_WIFI_SETTINGS);
        else if (name.equals(NetworkTileService.Bluetooth.class.getName())) destination = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
        else if (name.equals(NetworkTileService.Mobile.class.getName()) || name.equals(NetworkTileService.DataSim.class.getName()))
            destination = new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName("com.android.settings",
                    "com.android.settings.Settings$MobileNetworkSettingsActivity"));
        else destination = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.parse("package:" + getPackageName()));
        try { startActivity(destination); }
        catch (android.content.ActivityNotFoundException error) { startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS)); }
        finish();
    }
}
