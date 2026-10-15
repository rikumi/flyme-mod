package dev.rikumi.flymemod;

import android.app.Activity;
import android.app.Application;
import android.app.KeyguardManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.telephony.SubscriptionInfo;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiConsumer;

final class NetworkTileHooks {
    private final BiConsumer<String, Throwable> log;
    private final Class<?> mobileType;
    private Object mobile;
    private boolean registered;
    NetworkTileHooks(ClassLoader loader, BiConsumer<String, Throwable> log) throws ReflectiveOperationException {
        this.log = log;
        mobileType = loader.loadClass("com.flyme.systemui.statusbar.policy.MobileDataController");
        for (String name : new String[]{"isMobileDataEnabled", "isMobileDataSupported", "isDualEnabled",
                "isSimSwitching", "getActiveSubscriptionInfoList", "getDefaultDataSubscriptionInfo"}) mobileType.getMethod(name);
        mobileType.getMethod("isMobileDataClickable", boolean.class);
        mobileType.getMethod("setMobileDataEnabled", boolean.class);
        mobileType.getMethod("setDefaultDataSubId", int.class);
        mobileType.getMethod("isSubRadioOn", SubscriptionInfo.class);
    }
    void capture(Object controller) { mobile = controller; }
    private Object call(String name, Class<?>[] types, Object... args) throws ReflectiveOperationException {
        return mobileType.getMethod(name, types).invoke(mobile, args);
    }
    private Object call(String name) throws ReflectiveOperationException { return call(name, new Class<?>[0]); }
    void install(SignalHooks.Installer installer) {
        installer.hook("com.android.systemui.SystemUIApplication", "onCreate", chain -> {
            Object result = chain.proceed();
            Context context = (Context) chain.getThisObject();
            if (!registered && "com.android.systemui".equals(Application.getProcessName())) {
                context.registerReceiver(new BroadcastReceiver() {
                    @Override public void onReceive(Context context, Intent intent) {
                        if (!isOrderedBroadcast() || !NetworkTileService.ACTION.equals(intent.getAction())) return;
                        try {
                            boolean toggle = intent.getBooleanExtra("toggle", false);
                            KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
                            if (toggle && (keyguard == null || keyguard.isDeviceLocked())) {
                                setResultData("请先解锁设备"); return;
                            }
                            setResultExtras(state(context, intent.getStringExtra("kind"), toggle));
                            setResultCode(Activity.RESULT_OK);
                        } catch (Exception error) {
                            setResultData("暂时无法操作，请检查系统状态");
                            log.accept("Cannot handle network tile", error);
                        }
                    }
                }, new IntentFilter(NetworkTileService.ACTION), NetworkTileService.PERMISSION,
                        new Handler(Looper.getMainLooper()), Context.RECEIVER_EXPORTED);
                registered = true;
            }
            return result;
        });
    }
    private Bundle state(Context context, String kind, boolean toggle) throws ReflectiveOperationException {
        boolean enabled = false, available = true;
        String subtitle, label = null;
        if ("wifi".equals(kind)) {
            WifiManager wifi = context.getSystemService(WifiManager.class);
            available = wifi != null;
            int status = wifi == null ? WifiManager.WIFI_STATE_UNKNOWN : wifi.getWifiState();
            available &= status == WifiManager.WIFI_STATE_ENABLED || status == WifiManager.WIFI_STATE_DISABLED;
            enabled = status == WifiManager.WIFI_STATE_ENABLED;
            if (toggle && available && !wifi.setWifiEnabled(!enabled)) throw new IllegalStateException("Wi-Fi toggle rejected");
            status = wifi == null ? WifiManager.WIFI_STATE_UNKNOWN : wifi.getWifiState();
            enabled = status == WifiManager.WIFI_STATE_ENABLED;
            available = status == WifiManager.WIFI_STATE_ENABLED || status == WifiManager.WIFI_STATE_DISABLED;
            subtitle = available ? enabled ? "已开启" : "已关闭" : "正在切换或不可用";
            if (enabled && wifi != null) {
                WifiInfo connection = wifi.getConnectionInfo();
                String ssid = connection == null ? null : connection.getSSID();
                if (ssid != null && !ssid.isBlank() && !"<unknown ssid>".equals(ssid)) {
                    if (ssid.length() >= 2 && ssid.charAt(0) == '"' && ssid.charAt(ssid.length() - 1) == '"')
                        ssid = ssid.substring(1, ssid.length() - 1);
                    if (!ssid.isBlank()) label = ssid;
                }
            }
        } else if ("bluetooth".equals(kind)) {
            BluetoothManager manager = context.getSystemService(BluetoothManager.class);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            int status = adapter == null ? -1 : adapter.getState();
            available = status == BluetoothAdapter.STATE_ON || status == BluetoothAdapter.STATE_OFF;
            enabled = status == BluetoothAdapter.STATE_ON;
            if (toggle && available && !(enabled ? adapter.disable() : adapter.enable()))
                throw new IllegalStateException("Bluetooth toggle rejected");
            status = adapter == null ? -1 : adapter.getState();
            enabled = status == BluetoothAdapter.STATE_ON;
            available = status == BluetoothAdapter.STATE_ON || status == BluetoothAdapter.STATE_OFF;
            subtitle = available ? enabled ? "已开启" : "已关闭" : "正在切换或不可用";
        } else if ("mobile".equals(kind) || "sim".equals(kind)) {
            if (mobile == null) return result(false, false, "移动网络尚未就绪");
            boolean airplane = Settings.Global.getInt(context.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) != 0;
            if ("mobile".equals(kind)) {
                available = (Boolean) call("isMobileDataSupported") && !airplane
                        && (Boolean) call("isMobileDataClickable", new Class<?>[]{boolean.class}, false);
                enabled = (Boolean) call("isMobileDataEnabled");
                if (toggle && available) call("setMobileDataEnabled", new Class<?>[]{boolean.class}, !enabled);
                enabled = (Boolean) call("isMobileDataEnabled");
                subtitle = available ? enabled ? "已开启" : "已关闭" : "当前不可用";
            } else {
                List<SubscriptionInfo> active = new ArrayList<>();
                Object items = call("getActiveSubscriptionInfoList");
                if (items instanceof List<?> list) for (Object item : list)
                    if (item instanceof SubscriptionInfo info && (Boolean) call("isSubRadioOn",
                            new Class<?>[]{SubscriptionInfo.class}, info)) active.add(info);
                active.sort(Comparator.comparingInt(SubscriptionInfo::getSimSlotIndex));
                SubscriptionInfo current = (SubscriptionInfo) call("getDefaultDataSubscriptionInfo");
                available = !airplane && active.size() > 1 && (Boolean) call("isDualEnabled");
                if (toggle && available) {
                    int index = -1;
                    for (int i = 0; i < active.size(); i++) if (current != null && active.get(i).getSubscriptionId()
                            == current.getSubscriptionId()) index = i;
                    call("setDefaultDataSubId", new Class<?>[]{int.class}, active.get((index + 1) % active.size()).getSubscriptionId());
                    current = (SubscriptionInfo) call("getDefaultDataSubscriptionInfo");
                }
                boolean switching = (Boolean) call("isSimSwitching");
                available &= !switching;
                enabled = current != null;
                label = current == null ? "切换数据卡" : current.getDisplayName() == null
                        || current.getDisplayName().toString().isBlank()
                        ? "SIM " + (current.getSimSlotIndex() + 1) : current.getDisplayName().toString();
                subtitle = switching ? "正在切换数据卡" : current == null ? "无数据卡"
                        : "SIM " + (current.getSimSlotIndex() + 1) + " · " + current.getDisplayName();
                Bundle result = result(enabled, available, subtitle);
                result.putInt("data_sim_slot", current == null ? -1 : current.getSimSlotIndex());
                result.putString("subtitle", subtitle);
                result.putString("label", label);
                return result;
            }
        } else throw new IllegalArgumentException("Unknown network tile");
        Bundle result = result(enabled, available, subtitle);
        if (label != null) result.putString("label", label);
        return result;
    }
    private static Bundle result(boolean enabled, boolean available, String subtitle) {
        Bundle result = new Bundle();
        result.putInt("state", available ? enabled ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE : Tile.STATE_UNAVAILABLE);
        result.putString("subtitle", subtitle); return result;
    }
}
