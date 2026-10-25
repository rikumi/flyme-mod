package dev.rikumi.flymemod;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

final class StatusBarOverlayServiceHooks {
    private final SharedPreferences preferences;
    private final BiConsumer<String, Throwable> log;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<Object, Boolean> pending = new IdentityHashMap<>();
    private final Map<Object, StatusBarOverlayFrames.Frame> frames = new IdentityHashMap<>();
    private final Map<Integer, Boolean> owners = new HashMap<>();
    private volatile boolean enabled;
    private boolean posted;
    private volatile Object service;
    private Context context;
    private Object windowLock;
    private Field attrs, uid, animator, shown, visibility, serviceField, windows;
    private Method frame, display, visible;
    private String published;
    // Retain the listener for the lifetime of system_server.
    private final SharedPreferences.OnSharedPreferenceChangeListener changes = (prefs, key) -> {
        if (key == null || ModuleSettings.STATUS_BAR_LYRIC_AVOID.equals(key)
                || ModuleSettings.TICKER_LYRIC_AVOID.equals(key)) {
            enabled = trackingEnabled();
            handler.post(this::rescan);
        }
    };
    StatusBarOverlayServiceHooks(SharedPreferences preferences, BiConsumer<String, Throwable> log) {
        this.preferences = preferences; this.log = log;
        enabled = trackingEnabled();
    }
    private boolean trackingEnabled() {
        return preferences.getBoolean(ModuleSettings.STATUS_BAR_LYRIC_AVOID, false)
                || preferences.getBoolean(ModuleSettings.TICKER_LYRIC_AVOID, false);
    }
    void install(ClassLoader loader, SignalHooks.Installer installer, Consumer<Method> deoptimize)
            throws ReflectiveOperationException {
        Class<?> win = loader.loadClass("com.android.server.wm.WindowState");
        Class<?> wms = loader.loadClass("com.android.server.wm.WindowManagerService");
        attrs = field(win, "mAttrs"); uid = field(win, "mOwnerUid");
        animator = field(win, "mWinAnimator"); visibility = field(win, "mViewVisibility");
        shown = field(animator.getType(), "mSurfaceShown"); serviceField = field(win, "mWmService");
        windows = field(wms, "mWindowMap");
        frame = win.getDeclaredMethod("getFrame"); display = win.getDeclaredMethod("getDisplayId");
        visible = win.getDeclaredMethod("isVisible");
        installer.hook(wms.getName(), "onInitReady", chain -> {
            Object result = chain.proceed();
            initialize(chain.getThisObject());
            return result;
        });
        for (String name : new String[]{"setFrames", "setViewVisibility", "onSurfaceShownChanged",
                "show", "hide", "onDisplayChanged", "removeImmediately"}) {
            for (Method method : win.getDeclaredMethods()) {
                if (!method.getName().equals(name)) continue;
                installer.hook(win.getName(), name, chain -> {
                    Object result = chain.proceed();
                    event(chain.getThisObject(), name.equals("removeImmediately"));
                    return result;
                }, method.getParameterTypes());
                deoptimize.accept(method);
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(changes);
        log.accept("Status-bar overlay window lifecycle hooks registered", null);
    }
    private void initialize(Object wms) {
        try {
            synchronized (this) {
                if (service != null) return;
                service = wms;
                context = (Context) field(wms.getClass(), "mContext").get(wms);
                windowLock = field(wms.getClass(), "mGlobalLock").get(wms);
            }
            handler.post(this::rescan);
        } catch (ReflectiveOperationException | RuntimeException error) { log.accept("Overlay bridge initialization", error); }
    }
    private void event(Object window, boolean removed) {
        if (!enabled) return;
        try {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) attrs.get(window);
            if (!StatusBarOverlayFrames.overlayType(params.type)) {
                // LayoutParams type may change, so clear a previously tracked entry.
                synchronized (this) { if (!frames.containsKey(window) && !pending.containsKey(window)) return; }
                removed = true;
            }
            if (service == null) initialize(serviceField.get(window));
            synchronized (this) {
                pending.put(window, removed);
                if (!posted) { posted = true; handler.post(this::flush); }
            }
        } catch (ReflectiveOperationException | RuntimeException error) { log.accept("Overlay window event", error); }
    }
    private void rescan() {
        if (service == null) return;
        try {
            synchronized (windowLock) {
                synchronized (this) {
                    pending.clear(); frames.clear(); owners.clear();
                    if (enabled) for (Object window : ((Map<?, ?>) windows.get(service)).values()) update(window);
                }
            }
            publish();
        } catch (ReflectiveOperationException | RuntimeException error) { log.accept("Overlay initial window snapshot", error); }
    }
    private void flush() {
        if (service == null) return;
        try {
            synchronized (windowLock) {
                synchronized (this) {
                    posted = false;
                    if (!enabled) { pending.clear(); frames.clear(); }
                    else {
                        for (Map.Entry<Object, Boolean> entry : pending.entrySet()) {
                            if (entry.getValue()) frames.remove(entry.getKey()); else update(entry.getKey());
                        }
                        pending.clear();
                    }
                }
            }
            publish();
        } catch (ReflectiveOperationException | RuntimeException error) {
            synchronized (this) { posted = false; pending.clear(); frames.clear(); }
            publish(); log.accept("Overlay window snapshot", error);
        }
    }
    private void update(Object window) throws ReflectiveOperationException {
        frames.remove(window);
        WindowManager.LayoutParams params = (WindowManager.LayoutParams) attrs.get(window);
        if (!StatusBarOverlayFrames.overlayType(params.type) || params.alpha <= 0
                || visibility.getInt(window) != View.VISIBLE || !(Boolean) visible.invoke(window)
                || !shown.getBoolean(animator.get(window)) || !thirdParty(uid.getInt(window))) return;
        Rect r = (Rect) frame.invoke(window);
        StatusBarOverlayFrames.Frame next = new StatusBarOverlayFrames.Frame((Integer) display.invoke(window),
                r.left, r.top, r.right, r.bottom);
        if (next.valid()) frames.put(window, next);
    }
    private boolean thirdParty(int owner) {
        Boolean known = owners.get(owner);
        if (known != null) return known;
        boolean eligible = owner % 100000 >= 10000;
        try {
            String[] packages = context.getPackageManager().getPackagesForUid(owner);
            if (packages == null || packages.length == 0) eligible = false;
            else for (String pkg : packages) {
                ApplicationInfo info = context.getPackageManager().getApplicationInfo(pkg, 0);
                if (pkg.equals("dev.rikumi.flymemod") || pkg.equals("com.android.systemui")
                        || (info.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0)
                    eligible = false;
            }
        } catch (Exception ignored) { eligible = false; }
        owners.put(owner, eligible);
        return eligible;
    }
    private void publish() {
        if (context == null) return;
        String value;
        synchronized (this) { value = enabled ? StatusBarOverlayFrames.encode(frames.values()) : ""; }
        if (value.equals(published)) return;
        try {
            if (Settings.Global.putString(context.getContentResolver(), StatusBarOverlayFrames.KEY, value)) published = value;
        } catch (RuntimeException error) { log.accept("Overlay snapshot publication", error); }
    }
    static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try { Field f = c.getDeclaredField(name); f.setAccessible(true); return f; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }
}
