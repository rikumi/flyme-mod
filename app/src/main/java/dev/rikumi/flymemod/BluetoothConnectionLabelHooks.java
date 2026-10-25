package dev.rikumi.flymemod;

import android.content.Context;
import android.os.Handler;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

final class BluetoothConnectionLabelHooks {
    static void install(ClassLoader loader, SignalHooks.Installer installer,
            Consumer<Method> deoptimize, Consumer<Context> settings, BooleanSupplier enabled)
            throws ReflectiveOperationException {
        String controllerName = "com.android.systemui.statusbar.policy.BluetoothControllerImpl";
        Class<?> controller = loader.loadClass(controllerName);
        Class<?> model = loader.loadClass("com.android.systemui.statusbar.policy.bluetooth.ConnectionStatusModel");
        Class<?> state = loader.loadClass("com.android.systemui.plugins.qs.QSTile$BooleanState");
        Class<?> cached = loader.loadClass("com.android.settingslib.bluetooth.CachedBluetoothDevice");
        Method getConnected = controller.getMethod("getConnectedDevices");
        Method getDevices = controller.getMethod("getDevices");
        Method isConnected = cached.getMethod("isConnected");
        Method getName = cached.getMethod("getName");
        Field connectionState = controller.getField("mConnectionState");
        Field handler = controller.getField("mHandler");
        installer.hook(controllerName, "onConnectionStatusFetched", chain -> {
            Object owner = chain.getThisObject();
            int beforeState = connectionState.getInt(owner);
            List<?> before = (List<?>) getConnected.invoke(owner);
            Object result = chain.proceed();
            if (enabled.getAsBoolean() && beforeState == connectionState.getInt(owner)
                    && !before.equals(getConnected.invoke(owner))) {
                // Native code notifies only when the aggregate connection state changes.
                ((Handler) handler.get(owner)).sendEmptyMessage(2);
            }
            return result;
        }, model);
        deoptimize.accept(controller.getDeclaredMethod("onConnectionStatusFetched", model));

        Field contextField = loader.loadClass("com.android.systemui.qs.tileimpl.QSTileImpl").getField("mContext");
        Field subtitle = state.getField("secondaryLabel");
        Field value = state.getField("value");
        Field transientState = state.getField("isTransient");
        Class<?> strings = loader.loadClass("com.android.systemui.res.R$string");
        int disconnected = strings.getField("quick_settings_unconnected_label").getInt(null);
        int countResource = loader.loadClass("com.android.systemui.res.R$plurals")
                .getField("quick_settings_bluetooth_secondary_label_num_devices").getInt(null);
        for (String[] target : new String[][]{
                {"com.flyme.systemui.qs.tiles.ConnectivityTile", "updateBluetoothState", "bluetoothController"},
                {"com.android.systemui.qs.tiles.BluetoothTile", "mzHandleUpdateState", "mController"}}) {
            Class<?> tile = loader.loadClass(target[0]);
            Field tileController = tile.getField(target[2]);
            installer.hook(target[0], target[1], chain -> {
                Object result = chain.proceed();
                Object owner = chain.getThisObject(), snapshot = chain.getArg(0);
                Context context = (Context) contextField.get(owner);
                settings.accept(context);
                CharSequence original = (CharSequence) subtitle.get(snapshot);
                if (!enabled.getAsBoolean() || !value.getBoolean(snapshot) || transientState.getBoolean(snapshot)
                        || original == null || !context.getString(disconnected).contentEquals(original)) return result;
                Object source = tileController.get(owner);
                if (!controller.isInstance(source)) return result;
                List<Object> connected = new ArrayList<>();
                for (Object device : (Collection<?>) getDevices.invoke(source))
                    if (Boolean.TRUE.equals(isConnected.invoke(device))) connected.add(device);
                if (connected.isEmpty()) return result;
                String label = connected.size() == 1 ? (String) getName.invoke(connected.get(0)) : null;
                if (label == null || label.isBlank()) label = context.getResources()
                        .getQuantityString(countResource, connected.size(), connected.size());
                subtitle.set(snapshot, label);
                state.getField("stateDescription").set(snapshot, label);
                return result;
            }, state, Object.class);
            deoptimize.accept(tile.getDeclaredMethod(target[1], state, Object.class));
            for (Method method : tile.getDeclaredMethods())
                if (method.getName().equals("handleUpdateState")) deoptimize.accept(method);
        }
    }
}
