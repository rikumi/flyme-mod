package dev.rikumi.flymemod;

import android.bluetooth.BluetoothAdapter;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.Parcel;
import android.os.Process;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Narrow system_server broker. Never changes export flags or component permission checks. */
final class HeyMelodyNoiseBridge {
    static final String DESCRIPTOR = "dev.rikumi.flymemod.IHeyMelodyNoise.v1";
    // Private extension outside IActivityManager's generated transaction range.
    static final int TRANSACTION = 0x00464d4e;
    private static final String PACKAGE = "com.heytap.headset";
    private static final String SERVICE = "com.oplus.melody.alive.service.MelodyAliveService";
    private static final String CERT = "159e6f37c336f2f41272542dbc1dc85721dd567ed4ac68ac13f2e43003ce0dce";
    private static final long TTL = 5000;
    private final SharedPreferences preferences;
    private final BiConsumer<String, Throwable> log;
    private final ConcurrentHashMap<String, Snapshot> snapshots = new ConcurrentHashMap<>();
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile long lastRequest;
    private Context context;
    private Handler worker;
    private Messenger remote, responses;
    private boolean bound;
    private int sequence;
    private String lastError;
    private record Snapshot(int mode, HeyMelodyNoiseModes modes, long at) {}
    private final SharedPreferences.OnSharedPreferenceChangeListener changes = (prefs, key) -> {
        if (key == null || ModuleSettings.EARPHONE_NOISE_CONTROL.equals(key)) {
            if (!enabled()) {
                snapshots.clear();
                synchronized (this) { if (worker != null) worker.post(this::release); }
            }
        }
    };
    HeyMelodyNoiseBridge(SharedPreferences preferences, BiConsumer<String, Throwable> log) {
        this.preferences = preferences; this.log = log;
    }
    void install(ClassLoader loader, SignalHooks.Installer installer, Consumer<Method> deoptimize)
            throws ReflectiveOperationException {
        Class<?> ams = loader.loadClass("com.android.server.am.ActivityManagerService");
        Method transact = ams.getDeclaredMethod("onTransact", int.class, Parcel.class, Parcel.class, int.class);
        Field contextField = ams.getDeclaredField("mContext");
        contextField.setAccessible(true);
        installer.hook(ams.getName(), "onTransact", chain -> {
            if ((int) chain.getArg(0) != TRANSACTION) return chain.proceed();
            Parcel data = (Parcel) chain.getArg(1), reply = (Parcel) chain.getArg(2);
            Context ctx = (Context) contextField.get(chain.getThisObject());
            int caller = Binder.getCallingUid();
            // User 0 only: the broker must never control another user's earphones/app.
            if (caller != ctx.getPackageManager().getPackageUid("com.android.systemui", 0))
                throw new SecurityException("HeyMelody broker requires SystemUI UID");
            data.enforceInterface(DESCRIPTOR);
            String address = data.readString();
            int target = data.readInt();
            if (data.dataAvail() != 0 || !BluetoothAdapter.checkBluetoothAddress(address)
                    || (target != 0 && target != 2 && target != 3)) throw new IllegalArgumentException("Noise request");
            if (reply == null) return true;
            int result = 0;
            if (enabled()) {
                long identity = Binder.clearCallingIdentity();
                try {
                    initialize(ctx);
                    long now = SystemClock.elapsedRealtime();
                    lastRequest = now;
                    Snapshot old = snapshots.get(address);
                    if (target == 0 && old != null && now - old.at() < TTL) result = old.mode();
                    // At most one provider query or control request can be queued/in flight.
                    if (busy.compareAndSet(false, true)) {
                        worker.post(() -> refresh(address, target));
                        if (target != 0) result = 1; // queued, not an acknowledgement from the earphone
                    }
                } finally { Binder.restoreCallingIdentity(identity); }
            }
            reply.writeNoException(); reply.writeInt(result);
            return true;
        }, int.class, Parcel.class, Parcel.class, int.class);
        deoptimize.accept(transact);
        preferences.registerOnSharedPreferenceChangeListener(changes);
        log.accept("HeyMelody system broker registered", null);
    }
    private boolean enabled() { return preferences.getBoolean(ModuleSettings.EARPHONE_NOISE_CONTROL, false); }
    private synchronized void initialize(Context ctx) {
        if (worker != null) return;
        context = ctx;
        HandlerThread thread = new HandlerThread("FlymeModHeyMelody"); thread.start();
        worker = new Handler(thread.getLooper());
        responses = new Messenger(new Handler(thread.getLooper(), message -> {
            // Real state is always read back from the provider; never treat a send as success.
            return true;
        }));
    }
    private boolean trusted() throws Exception {
        PackageInfo info = context.getPackageManager().getPackageInfo(PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES);
        // This is a private API. Unknown versions fail closed until their contract is checked.
        if (!"17.6.5".equals(info.versionName) || info.signingInfo == null
                || info.signingInfo.getApkContentsSigners().length != 1) return false;
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(info.signingInfo.getApkContentsSigners()[0].toByteArray());
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return CERT.contentEquals(hex);
    }
    private boolean audioOutput(String address) {
        AudioManager audio = context.getSystemService(AudioManager.class);
        if (audio != null) for (AudioDeviceInfo device : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if ((device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                    || device.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET) && address.equals(device.getAddress())) return true;
        }
        return false;
    }
    private void refresh(String address, int target) {
        long started = SystemClock.elapsedRealtime();
        try {
            if (!enabled() || !audioOutput(address) || !trusted()) { release(); return; }
            bind();
            if (!bound || remote == null) { snapshots.remove(address); return; }
            JSONObject device = query("RepositoryProvider", "3060", "value", address);
            if (device == null || !address.equalsIgnoreCase(device.optString("macAddress"))
                    || device.optInt("connectionState", -1) != 2) { snapshots.remove(address); return; }
            JSONObject config = query("WhitelistProvider", "find_whitelist", "content", address);
            JSONObject function = config == null ? null : config.optJSONObject("function");
            HeyMelodyNoiseModes modes = new HeyMelodyNoiseModes(entries(function == null ? null
                    : function.optJSONArray("noiseReductionMode"), device.optJSONObject("supportNoiseReductionInfo"), 0));
            int mode = modes.mode(device.optInt("noiseReductionModeIndex", -1));
            if (mode == 0 || !enabled() || !audioOutput(address)) { snapshots.remove(address); return; }
            if (snapshots.size() >= 8) snapshots.clear();
            snapshots.put(address, new Snapshot(mode, modes, started));
            if (target != 0 && remote != null && SystemClock.elapsedRealtime() - started < TTL) {
                int index = modes.target(target);
                if (index < 0) return;
                Bundle args = new Bundle(); args.putString("macAddress", address); args.putInt("value", index);
                Message command = Message.obtain();
                command.what = 3058; command.arg1 = Process.myPid(); command.arg2 = ++sequence;
                command.setData(args); command.replyTo = responses;
                remote.send(command);
            }
            lastError = null;
        } catch (PackageManager.NameNotFoundException absent) {
            release();
        } catch (Exception error) {
            release();
            String text = error.getClass().getSimpleName() + ": " + error.getMessage();
            if (!text.equals(lastError)) { lastError = text; log.accept("HeyMelody broker request failed", error); }
        } finally {
            busy.set(false);
            worker.removeCallbacks(expire);
            worker.postDelayed(expire, TTL + 1000);
        }
    }
    private JSONObject query(String provider, String path, String column, String address) throws Exception {
        Uri uri = new Uri.Builder().scheme("content").authority(PACKAGE + ".alive." + provider)
                .appendPath(path).appendQueryParameter("macAddress", address).build();
        try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) return null;
            String value = cursor.getString(cursor.getColumnIndexOrThrow(column));
            if (value == null || value.length() > 262144) return null;
            return new JSONObject(value);
        }
    }
    private static List<HeyMelodyNoiseModes.Entry> entries(JSONArray array, JSONObject support, int depth) throws Exception {
        List<HeyMelodyNoiseModes.Entry> result = new ArrayList<>();
        if (array == null) return result;
        if (depth > 4 || array.length() > 32) throw new IllegalArgumentException("Noise mode configuration");
        for (int i = 0; i < array.length(); i++) {
            JSONObject value = array.getJSONObject(i);
            int index = value.getInt("protocolIndex");
            boolean available = !value.optBoolean("decideByEarDevice", false)
                    || (support != null && index >= 0 && index < 32 && (support.optInt("value") & (1 << index)) != 0);
            result.add(new HeyMelodyNoiseModes.Entry(index, value.getInt("modeType"), available,
                    entries(value.optJSONArray("childrenMode"), support, depth + 1)));
        }
        return result;
    }
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) { remote = new Messenger(binder); }
        @Override public void onServiceDisconnected(ComponentName name) { remote = null; snapshots.clear(); }
        @Override public void onBindingDied(ComponentName name) { release(); }
        @Override public void onNullBinding(ComponentName name) { release(); }
    };
    private void bind() {
        if (bound) return;
        Intent intent = new Intent(PACKAGE + ".alive.action.SEND_COMMAND").setComponent(new ComponentName(PACKAGE, SERVICE));
        // Deliver connection callbacks on the same worker as all provider/control operations.
        bound = context.bindService(intent, Context.BIND_AUTO_CREATE, command -> worker.post(command), connection);
    }
    private final Runnable expire = () -> {
        if (!enabled() || SystemClock.elapsedRealtime() - lastRequest >= TTL) release();
    };
    private void release() {
        snapshots.clear(); remote = null;
        if (bound) {
            bound = false;
            try { context.unbindService(connection); } catch (RuntimeException ignored) { }
        }
    }
}
