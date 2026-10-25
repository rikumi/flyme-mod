package dev.rikumi.flymemod;

import android.content.Context;
import android.content.res.Resources;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

final class BluetoothDeviceCountHooks {
    private static final String CONNECTIVITY = "com.flyme.systemui.qs.tiles.ConnectivityTile";
    private static final String BLUETOOTH = "com.android.systemui.qs.tiles.BluetoothTile";

    static void install(ClassLoader loader, SignalHooks.Installer installer,
                        Consumer<Method> deoptimize) throws ReflectiveOperationException {
        Class<?> connectivity = loader.loadClass(CONNECTIVITY);
        Class<?> bluetooth = loader.loadClass(BLUETOOTH);
        Class<?> state = loader.loadClass("com.android.systemui.plugins.qs.QSTile$BooleanState");
        Field context = loader.loadClass("com.android.systemui.qs.tileimpl.QSTileImpl").getField("mContext");
        Field secondaryLabel = state.getField("secondaryLabel");
        Field connectivityController = connectivity.getField("bluetoothController");
        Field bluetoothController = bluetooth.getField("mController");
        Method connectedDevices = loader.loadClass("com.android.systemui.statusbar.policy.BluetoothController")
                .getMethod("getConnectedDevices");
        int countResource = loader.loadClass("com.android.systemui.res.R$plurals")
                .getField("quick_settings_bluetooth_secondary_label_num_devices").getInt(null);
        Method update = connectivity.getDeclaredMethod("updateBluetoothState", state, Object.class);
        Method getLabel = bluetooth.getDeclaredMethod("getSecondaryLabel",
                boolean.class, boolean.class, boolean.class, boolean.class);

        installer.hook(CONNECTIVITY, "updateBluetoothState", chain -> {
            Object result = chain.proceed();
            Object tile = chain.getThisObject();
            Object tileState = chain.getArg(0);
            CharSequence original = (CharSequence) secondaryLabel.get(tileState);
            String replacement = replace((Context) context.get(tile), original,
                    connectivityController.get(tile), connectedDevices, countResource);
            if (replacement != null) secondaryLabel.set(tileState, replacement);
            return result;
        }, state, Object.class);
        installer.hook(BLUETOOTH, "getSecondaryLabel", chain -> {
            Object result = chain.proceed();
            Object tile = chain.getThisObject();
            String replacement = replace((Context) context.get(tile), (CharSequence) result,
                    bluetoothController.get(tile), connectedDevices, countResource);
            return replacement != null ? replacement : result;
        }, boolean.class, boolean.class, boolean.class, boolean.class);
        // Both producers are final; invalidate their callers' inlined native label generation.
        deoptimize.accept(update);
        deoptimize.accept(getLabel);
        for (Class<?> type : new Class<?>[]{connectivity, bluetooth}) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.getName().equals("handleUpdateState")) deoptimize.accept(method);
            }
        }
    }

    private static String replace(Context context, CharSequence original, Object controller,
                                  Method connectedDevices, int countResource)
            throws ReflectiveOperationException {
        if (original == null || original.length() == 0) return null;
        List<?> devices = (List<?>) connectedDevices.invoke(controller);
        if (devices == null || devices.size() <= 1) return null;
        int count = devices.size();
        Resources resources = context.getResources();
        String nativeLabel = resources.getQuantityString(countResource, count, count);
        return replacement(original, nativeLabel, count, resources.getConfiguration().getLocales().get(0));
    }

    static String replacement(CharSequence original, String nativeLabel, int count, Locale locale) {
        if (count <= 1 || original == null || !nativeLabel.contentEquals(original)) return null;
        String number = NumberFormat.getIntegerInstance(locale).format(count);
        return switch (locale.getLanguage()) {
            case "zh" -> {
                boolean traditional = "Hant".equals(locale.getScript())
                        || "TW".equals(locale.getCountry()) || "HK".equals(locale.getCountry())
                        || "MO".equals(locale.getCountry());
                yield number + (traditional ? " 個裝置" : " 个设备");
            }
            case "ja" -> number + " 台のデバイス";
            default -> number + " devices";
        };
    }
}
