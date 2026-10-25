package dev.rikumi.flymemod;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;
import java.util.function.BiConsumer;

final class HeyMelodyNoiseController {
    private final BiConsumer<String, Throwable> log;
    private IBinder activity;
    private long retryAt;
    HeyMelodyNoiseController(BiConsumer<String, Throwable> log) { this.log = log; }
    int read(Context context, String address) { return request(address, 0); }
    boolean set(String address, int mode) { return (mode == 2 || mode == 3) && request(address, mode) == 1; }
    private int request(String address, int target) {
        if (!BluetoothAdapter.checkBluetoothAddress(address)
                || SystemClock.elapsedRealtime() < retryAt) return 0;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            if (activity == null || !activity.isBinderAlive()) activity = (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, "activity");
            if (activity == null) return 0;
            data.writeInterfaceToken(HeyMelodyNoiseBridge.DESCRIPTOR);
            data.writeString(address);
            data.writeInt(target);
            if (!activity.transact(HeyMelodyNoiseBridge.TRANSACTION, data, reply, 0)) {
                retryAt = SystemClock.elapsedRealtime() + 30000;
                return 0;
            }
            reply.readException();
            return reply.readInt();
        } catch (Exception error) {
            activity = null;
            retryAt = SystemClock.elapsedRealtime() + 30000;
            log.accept("HeyMelody system bridge unavailable", error);
            return 0;
        } finally { data.recycle(); reply.recycle(); }
    }
}
