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
import android.media.AudioDeviceCallback;
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

final class HeyMelodyNoiseBridge {
    static final String DESCRIPTOR = "dev.rikumi.flymemod.IHeyMelodyNoise.v1";
    // Private extension outside IActivityManager's generated transaction range.
    static final int TRANSACTION = 0x00464d4e;
    private static final String PACKAGE = "com.heytap.headset";
    private static final String SERVICE = "com.oplus.melody.alive.service.MelodyAliveService";
    private static final String CERT = "159e6f37c336f2f41272542dbc1dc85721dd567ed4ac68ac13f2e43003ce0dce";
    private static final long POLL_INTERVAL = 3000;
    private final SharedPreferences preferences;
    private final BiConsumer<String, Throwable> log;
    private final ConcurrentHashMap<String, Snapshot> snapshots = new ConcurrentHashMap<>();
    private final AtomicBoolean busy = new AtomicBoolean();
    private Context context;
    private Object cachedAppOptimizer;
    private Method unfreezeUid;
    private volatile Handler worker;
    private Messenger remote, responses;
    private boolean bound, bindingRegistered;

    private final java.util.concurrent.Executor connectionExecutor = command -> worker.post(command);
    private int sequence;
    private String lastError;
    private record Snapshot(int mode, HeyMelodyNoiseModes modes) {}
    private final SharedPreferences.OnSharedPreferenceChangeListener changes = (prefs, key) -> {
        if (key == null || ModuleSettings.EARPHONE_NOISE_CONTROL.equals(key)
                || ModuleSettings.HEYMELODY_NOISE_CONTROL.equals(key)) {
            if (!enabled()) snapshots.clear();
            scheduleReconcile();
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
        Field oomAdjuster = ams.getDeclaredField("mOomAdjuster");
        oomAdjuster.setAccessible(true);
        Field optimizer = loader.loadClass("com.android.server.am.OomAdjuster").getDeclaredField("mCachedAppOptimizer");
        optimizer.setAccessible(true);
        Method unfreeze = loader.loadClass("com.android.server.am.CachedAppOptimizer")
                .getDeclaredMethod("unfreezeTemporarily", int.class);
        unfreeze.setAccessible(true);
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
            if (enabled() && worker != null) {
                Snapshot state = snapshots.get(address);
                if (target == 0 && state != null) result = state.mode();

                if (target != 0 && state != null && busy.compareAndSet(false, true)) {
                    worker.post(() -> control(address, target));
                    result = 1;
                }
            }
            reply.writeNoException(); reply.writeInt(result);
            return true;
        }, int.class, Parcel.class, Parcel.class, int.class);
        Class<?> trace = loader.loadClass("com.android.server.utils.TimingsTraceAndSlog");
        Method ready = ams.getDeclaredMethod("systemReady", Runnable.class, trace);
        installer.hook(ams.getName(), "systemReady", chain -> {
            Object result = chain.proceed();
            try {
                initialize((Context) contextField.get(chain.getThisObject()),
                        optimizer.get(oomAdjuster.get(chain.getThisObject())), unfreeze);
            } catch (Exception error) {
                log.accept("Cannot initialize HeyMelody system session", error);
            }
            return result;
        }, Runnable.class, trace);
        deoptimize.accept(ready);
        deoptimize.accept(transact);
        preferences.registerOnSharedPreferenceChangeListener(changes);
        log.accept("HeyMelody system broker registered", null);
    }
    private boolean enabled() {
        return preferences.getBoolean(ModuleSettings.EARPHONE_NOISE_CONTROL, false)
                && preferences.getBoolean(ModuleSettings.HEYMELODY_NOISE_CONTROL, false);
    }
    private synchronized void initialize(Context ctx, Object optimizer, Method unfreeze) {
        if (worker != null) return;
        context = ctx;
        cachedAppOptimizer = optimizer;
        unfreezeUid = unfreeze;
        HandlerThread thread = new HandlerThread("FlymeModHeyMelody"); thread.start();
        worker = new Handler(thread.getLooper());
        responses = new Messenger(new Handler(thread.getLooper(), message -> {
            // Real state is always read back from the provider; never treat a send as success.
            return true;
        }));
        worker.post(() -> {
            AudioManager audio = context.getSystemService(AudioManager.class);
            if (audio != null) audio.registerAudioDeviceCallback(new AudioDeviceCallback() {
                @Override public void onAudioDevicesAdded(AudioDeviceInfo[] devices) { scheduleReconcile(); }
                @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] devices) {
                    for (AudioDeviceInfo device : devices) snapshots.remove(device.getAddress());
                    scheduleReconcile();
                }
            }, worker);
            reconcile();
        });
    }
    private void scheduleReconcile() {
        Handler handler = worker;
        if (handler == null) return;
        handler.removeCallbacks(poll);
        handler.post(poll);
    }
    private final Runnable poll = this::reconcile;
    private List<String> connectedOutputs() {
        List<String> addresses = new ArrayList<>();
        AudioManager audio = context.getSystemService(AudioManager.class);
        if (audio != null) for (AudioDeviceInfo device : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if ((device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                    || device.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET)
                    && BluetoothAdapter.checkBluetoothAddress(device.getAddress())
                    && !addresses.contains(device.getAddress())) addresses.add(device.getAddress());
        }
        return addresses;
    }
    private void reconcile() {
        worker.removeCallbacks(poll);
        if (!enabled()) { release(); return; }
        List<String> addresses = connectedOutputs();
        snapshots.keySet().retainAll(addresses);
        if (addresses.isEmpty()) { release(); return; }
        for (String address : addresses) refresh(address);
        if (enabled()) worker.postDelayed(poll, POLL_INTERVAL);
    }
    private void control(String address, int target) {
        try {
            Snapshot state = snapshots.get(address);
            if (!enabled() || !audioOutput(address) || state == null || remote == null || !trusted()) return;
            int index = state.modes().target(target);
            if (index < 0) return;
            Bundle args = new Bundle(); args.putString("macAddress", address); args.putInt("value", index);
            Message command = Message.obtain();
            command.what = 3058; command.arg1 = Process.myPid(); command.arg2 = ++sequence;
            command.setData(args); command.replyTo = responses;
            remote.send(command);
            // Sending is not confirmation: only the periodic provider read updates state.
        } catch (Exception error) {
            log.accept("HeyMelody control request failed", error);
        } finally { busy.set(false); }
    }
    private boolean trusted() throws Exception {
        PackageInfo info = context.getPackageManager().getPackageInfo(PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES);
        if (info.signingInfo == null || info.signingInfo.getApkContentsSigners().length != 1) {
            rejection("HeyMelody signing information unavailable or ambiguous");
            return false;
        }
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(info.signingInfo.getApkContentsSigners()[0].toByteArray());
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        if (!CERT.contentEquals(hex) && !"49bd5f8f3f687aa3c71e79d570034455d68945c3ec45bb5f186169b8503c8e3e".contentEquals(hex)) {
            rejection("HeyMelody signing certificate mismatch for version " + info.versionName);
            return false;
        }
        PackageManager pm = context.getPackageManager();
        android.content.pm.ServiceInfo service = pm.getServiceInfo(new ComponentName(PACKAGE, SERVICE), 0);
        if (!service.enabled || !service.applicationInfo.enabled) {
            rejection("HeyMelody control service disabled");
            return false;
        }
        for (String provider : new String[]{"RepositoryProvider", "WhitelistProvider"}) {
            android.content.pm.ProviderInfo resolved = pm.resolveContentProvider(PACKAGE + ".alive." + provider, 0);
            if (resolved == null || !PACKAGE.equals(resolved.packageName)
                    || !resolved.enabled || !resolved.applicationInfo.enabled) {
                rejection("HeyMelody control provider unavailable: " + provider);
                return false;
            }
        }
        return true;
    }
    private void rejection(String reason) {
        if (!reason.equals(lastError)) { lastError = reason; log.accept(reason, null); }
    }
    private boolean audioOutput(String address) {
        AudioManager audio = context.getSystemService(AudioManager.class);
        if (audio != null) for (AudioDeviceInfo device : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if ((device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                    || device.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET) && address.equals(device.getAddress())) return true;
        }
        return false;
    }
    private void refresh(String address) {
        try {
            if (!enabled() || !audioOutput(address) || !trusted()) { release(); return; }
            bindForOutput(address);
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
            snapshots.put(address, new Snapshot(mode, modes));
            lastError = null;
        } catch (PackageManager.NameNotFoundException absent) {
            release();
            rejection("HeyMelody package or control service unavailable");
        } catch (Exception error) {
            release();
            String text = error.getClass().getSimpleName() + ": " + error.getMessage();
            if (!text.equals(lastError)) { lastError = text; log.accept("HeyMelody broker request failed", error); }
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
        @Override public void onServiceConnected(ComponentName name, IBinder binder) { remote = new Messenger(binder); scheduleReconcile(); }
        @Override public void onServiceDisconnected(ComponentName name) { remote = null; snapshots.clear(); }
        @Override public void onBindingDied(ComponentName name) { release(); }
        @Override public void onNullBinding(ComponentName name) { release(); }
    };
    private void bindForOutput(String address) throws Exception {
        bind();
        if (!bound && enabled() && audioOutput(address) && trusted()) {

            int uid = context.getPackageManager().getPackageUid(PACKAGE, 0);
            unfreezeUid.invoke(cachedAppOptimizer, uid);
            bind();
        }
    }
    private void bind() {
        if (bound) return;
        Intent intent = new Intent(PACKAGE + ".alive.action.SEND_COMMAND").setComponent(new ComponentName(PACKAGE, SERVICE));
        // Deliver connection callbacks on the same worker as all provider/control operations.
        bindingRegistered = true;
        try {
            bound = context.bindService(intent, Context.BIND_AUTO_CREATE, connectionExecutor, connection);
            if (!bound) {
                release();
                rejection("HeyMelody control service binding returned false");
            }
        } catch (RuntimeException error) {
            release();
            throw error;
        }
    }
    private void release() {
        snapshots.clear(); remote = null;
        bound = false;
        if (bindingRegistered) {
            bindingRegistered = false;
            try { context.unbindService(connection); } catch (RuntimeException ignored) { }
        }
    }
}
