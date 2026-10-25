package dev.rikumi.flymemod;

import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

final class ChargingInfoHooks {
    private static final String BATTERY = "com.flyme.statusbar.battery.FlymeBatteryMeterView";
    private final Consumer<Context> settings;
    private final IntSupplier mode;
    private final BiConsumer<TextView, TextView> font;
    private final BiConsumer<String, Throwable> log;
    private final Consumer<String> trace;
    private final Field charging, bypass, percent, tint;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "FlymeChargingInput"); thread.setDaemon(true); return thread;
    });
    private final Map<View, WeakReference<State>> states = new WeakHashMap<>();
    private ChargingInfo.Sample sample;
    private long sampledAt;
    private int generation;
    private boolean polling, inFlight;
    private Context receiverContext;
    private boolean screenOn = true, disconnected;
    private String lastReadStatus;
    private final Runnable tick = this::read;
    private final BroadcastReceiver events = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) screenOn = false;
            else if (Intent.ACTION_SCREEN_ON.equals(action)) screenOn = true;
            else if (Intent.ACTION_BATTERY_CHANGED.equals(action)) disconnected = intent.getIntExtra("plugged", 0) == 0;
            refresh();
        }
    };

    ChargingInfoHooks(ClassLoader loader, Consumer<Context> settings, IntSupplier mode,
            BiConsumer<TextView, TextView> font, BiConsumer<String, Throwable> log, Consumer<String> trace) throws ReflectiveOperationException {
        this.settings = settings; this.mode = mode; this.font = font; this.log = log;
        this.trace = trace;
        Class<?> type = loader.loadClass(BATTERY);
        charging = type.getField("mCharging"); bypass = type.getField("mBypassCharging");
        percent = type.getField("mBatteryPercentView"); tint = type.getField("mFilterColor");
        type.getDeclaredMethod("setBatteryPercentView", TextView.class, String.class);
        type.getDeclaredMethod("apply", boolean.class);
    }

    void install(SignalHooks.Installer installer) {
        installer.hook(BATTERY, "setBatteryPercentView", chain -> {
            Object result = chain.proceed();
            String location = (String) chain.getArg(1);
            if ("StatusBar".equals(location) || "KeyguardStatusBar".equals(location)) {
                View battery = (View) chain.getThisObject(); settings.accept(battery.getContext());
                observe(battery);
                trace.accept("Charging information battery bound: " + location + ", mode=" + mode.getAsInt());
            }
            return result;
        }, TextView.class, String.class);
        installer.hook(BATTERY, "apply", chain -> {
            Object result = chain.proceed();
            View battery = (View) chain.getThisObject();
            State state = state(battery);
            if (state != null) { state.update(false); reconcile(); }
            return result;
        }, boolean.class);
    }
    private State state(View view) {
        WeakReference<State> reference = states.get(view); return reference == null ? null : reference.get();
    }
    private void observe(View battery) {
        State state = state(battery);
        if (state == null) {
            state = new State(battery); states.put(battery, new WeakReference<>(state));
            battery.addOnAttachStateChangeListener(state);
            if (battery.isAttachedToWindow()) state.onViewAttachedToWindow(battery);
        }
        state.update(true); reconcile();
    }
    void refresh() {
        receivers();
        for (WeakReference<State> reference : new ArrayList<>(states.values())) {
            State state = reference.get(); if (state != null) state.update(true);
        }
        reconcile();
    }
    private Context activeContext() {
        for (WeakReference<State> reference : new ArrayList<>(states.values())) {
            State state = reference.get();
            View battery = state == null ? null : state.owner.get();
            if (battery != null && state.active()) return battery.getContext().getApplicationContext();
        }
        return null;
    }
    private void receivers() {
        Context attached = null;
        for (View battery : new ArrayList<>(states.keySet())) {
            State state = battery == null ? null : state(battery);
            if (state != null && state.attached && mode.getAsInt() != ChargingInfo.OFF) {
                attached = battery.getContext().getApplicationContext(); break;
            }
        }
        if (attached != null && receiverContext == null) {
            receiverContext = attached;
            android.os.PowerManager power = attached.getSystemService(android.os.PowerManager.class);
            screenOn = power == null || power.isInteractive();
            IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            filter.addAction(Intent.ACTION_SCREEN_OFF); filter.addAction(Intent.ACTION_SCREEN_ON);
            Intent sticky = attached.registerReceiver(events, filter, null, main, Context.RECEIVER_NOT_EXPORTED);
            if (sticky != null) disconnected = sticky.getIntExtra("plugged", 0) == 0;
        } else if (attached == null && receiverContext != null) {
            receiverContext.unregisterReceiver(events); receiverContext = null;
        }
    }
    private void reconcile() {
        boolean needed = activeContext() != null;
        if (needed == polling) return;
        polling = needed; generation++; sample = null; sampledAt = 0;
        main.removeCallbacks(tick);
        if (polling && !inFlight) main.post(tick);
        for (WeakReference<State> reference : new ArrayList<>(states.values())) {
            State state = reference.get(); if (state != null) state.render();
        }
    }
    private void read() {
        Context context = activeContext();
        if (!polling || context == null || inFlight) { reconcile(); return; }
        int token = generation; inFlight = true;
        worker.execute(() -> {
            ChargingInfo.Sample result = null;
            String status = "provider_unavailable";
            try {
                Bundle data = context.getContentResolver().call(ModuleSettings.URI, ChargingInfo.METHOD, null, null);
                if (data != null) {
                    String micro = data.getString("sample_micro");
                    result = micro == null ? ChargingInfo.parse(data.getString("sample")) : ChargingInfo.parseMicro(micro);
                    status = data.getString("status", result == null ? "no_valid_input" : "valid_input");
                }
            } catch (RuntimeException error) { status = "provider_error_" + error.getClass().getSimpleName(); }
            ChargingInfo.Sample value = result;
            String readStatus = status;
            main.post(() -> {
                inFlight = false;
                if (token == generation && polling) {
                    if (!readStatus.equals(lastReadStatus)) {
                        lastReadStatus = readStatus; trace.accept("Charging information input: " + readStatus);
                    }
                    sample = value; sampledAt = SystemClock.elapsedRealtime(); refresh();
                }
                if (polling) { main.removeCallbacks(tick); main.postDelayed(tick, token == generation ? 5000 : 0); }
            });
        });
    }

    private static void configureMode(TextView text, TextView reference, int mode) {
        text.setSingleLine(true);
        text.setMaxLines(1);
        text.setGravity(Gravity.CENTER);
        text.setIncludeFontPadding(reference.getIncludeFontPadding());
        text.setLineSpacing(0f, 1f);
        text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, reference.getTextSize());
        text.getPaint().setFakeBoldText(false);
        if (text.getLayoutParams() instanceof LinearLayout.LayoutParams params) {
            params.height = LinearLayout.LayoutParams.WRAP_CONTENT;
            params.weight = 0f;
            params.topMargin = 0;
            text.setLayoutParams(params);
        }
    }

    private static boolean configureMargins(LinearLayout.LayoutParams params, int nativeGap, float density, boolean rtl) {
        int shift = Math.round(2f * density);
        int iconGap = Math.max(0, nativeGap - shift);
        int start = rtl ? iconGap : shift;
        int end = rtl ? shift : iconGap;
        if (params.getMarginStart() == start && params.getMarginEnd() == end) return false;
        params.setMarginStart(start); params.setMarginEnd(end);
        return true;
    }

    private static int batteryIndex(LinearLayout parent, View label, View battery) {
        int index = parent.indexOfChild(battery);
        int existing = parent.indexOfChild(label);
        if (existing >= 0 && existing < index) index--;

        return index + (parent.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? 1 : 0);
    }

    private final class State implements View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
        final WeakReference<View> owner;
        LinearLayout label;
        TextView primaryText, secondaryText;
        ViewTreeObserver observer;
        boolean failed;
        boolean attached;
        float lastSize = -1f;
        int lastHeight = -1;
        int lastMode = -1;
        Object lastTypeface;
        State(View battery) { owner = new WeakReference<>(battery); }
        boolean active() {
            View battery = owner.get();
            if (battery == null || failed || !(battery.getParent() instanceof LinearLayout)) return false;
            try {
                return ChargingInfo.active(mode.getAsInt(), attached && battery.isAttachedToWindow(), battery.isShown(),
                        charging.getBoolean(battery), bypass.getBoolean(battery)) && screenOn && !disconnected;
            } catch (IllegalAccessException error) { fail(error); return false; }
        }
        @Override public void onViewAttachedToWindow(View battery) {
            if (attached) return;
            attached = true;
            observer = battery.getViewTreeObserver(); observer.addOnPreDrawListener(this);
            receivers(); settings.accept(battery.getContext()); update(true); reconcile();
        }
        @Override public void onViewDetachedFromWindow(View battery) {
            attached = false;
            if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(this);
            observer = null;
            removeText(); receivers(); reconcile();
        }
        @Override public boolean onPreDraw() { update(false); reconcile(); return true; }
        void createLabel(Context context) {
            label = new LinearLayout(context);
            label.setOrientation(LinearLayout.VERTICAL); label.setGravity(Gravity.CENTER);
            label.setTag("flyme_mod_charging_info"); label.setVisibility(View.GONE);
            label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            primaryText = new TextView(context); secondaryText = new TextView(context);
            primaryText.setTag("flyme_mod_charging_top"); secondaryText.setTag("flyme_mod_charging_bottom");
            for (TextView child : new TextView[]{primaryText, secondaryText}) {
                child.setSingleLine(true); child.setVisibility(View.GONE);
                child.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                params.gravity = Gravity.CENTER_HORIZONTAL;
                label.addView(child, params);
            }
        }
        void update(boolean refreshFont) {
            View battery = owner.get(); if (battery == null || failed) return;
            if (!active()) { removeText(); return; }
            try {
                TextView reference = (TextView) percent.get(battery);
                if (reference == null) { removeText(); return; }
                LinearLayout parent = (LinearLayout) battery.getParent();
                if (label == null) {
                    createLabel(battery.getContext());
                    refreshFont = true;
                }
                int leading = batteryIndex(parent, label, battery);
                if (label.getParent() != parent || parent.indexOfChild(label) != leading) {
                    removeFromParent();
                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT);
                    params.gravity = Gravity.CENTER_VERTICAL;

                    parent.addView(label, leading, params);
                    trace.accept("Charging information placement: battery left, index=" + leading
                            + ", rtl=" + (parent.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL));
                    refreshFont = true;
                }
                int currentMode = mode.getAsInt();
                if (refreshFont || lastMode != currentMode || lastSize != reference.getTextSize() || lastTypeface != reference.getTypeface() || lastHeight != parent.getHeight()) {
                    for (TextView child : new TextView[]{primaryText, secondaryText}) {
                        font.accept(child, reference);
                        configureMode(child, reference, currentMode);
                    }
                    if (lastMode != currentMode || lastSize != reference.getTextSize() || lastTypeface != reference.getTypeface()
                            || lastHeight != parent.getHeight())
                        trace.accept("Charging information layout: mode=" + currentMode
                            + ", rows=" + 1
                            + ", fontPx=" + primaryText.getTextSize()
                            + ", weight=" + primaryText.getTypeface().getWeight()
                            + ", rowsHeight=" + primaryText.getLineHeight()
                            + ", availableHeight=" + parent.getHeight());
                    lastHeight = parent.getHeight(); lastMode = currentMode; lastSize = reference.getTextSize(); lastTypeface = reference.getTypeface();
                }
                int color = tint.getInt(battery);
                for (TextView child : new TextView[]{primaryText, secondaryText})
                    if (child.getCurrentTextColor() != color) child.setTextColor(color);
                // Keep the requested 2dp shift toward the first icon, with no overlap.
                LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) label.getLayoutParams();
                int id = battery.getResources().getIdentifier("header_battery_margin", "dimen", "com.android.systemui");
                int gap = reference.getLayoutParams() instanceof android.view.ViewGroup.MarginLayoutParams margins
                        ? margins.getMarginStart() : id == 0 ? 0 : battery.getResources().getDimensionPixelSize(id);
                float density = battery.getResources().getDisplayMetrics().density;

                if (currentMode == ChargingInfo.VOLTAGE_CURRENT) gap = 2 * Math.round(2f * density);
                boolean rtl = parent.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
                if (configureMargins(params, gap, density, rtl))
                    label.setLayoutParams(params);
                render();
            } catch (ReflectiveOperationException | RuntimeException error) { fail(error); }
        }
        void render() {
            if (label == null) return;
            String value = active() && sample != null && SystemClock.elapsedRealtime() - sampledAt <= 7000
                    ? sample.format(mode.getAsInt()) : "";
            setLine(primaryText, value);
            setLine(secondaryText, "");
            label.setVisibility(value.isEmpty() ? View.GONE : View.VISIBLE);
        }
        void setLine(TextView child, String value) {
            if (!value.contentEquals(child.getText())) child.setText(value);
            child.setVisibility(value.isEmpty() ? View.GONE : View.VISIBLE);
        }
        void removeFromParent() {
            if (label != null && label.getParent() instanceof android.view.ViewGroup parent) parent.removeView(label);
        }
        void removeText() {
            removeFromParent(); label = null; primaryText = secondaryText = null;
            lastHeight = -1; lastSize = -1f; lastMode = -1; lastTypeface = null;
        }
        void fail(Throwable error) {
            if (!failed) log.accept("Cannot attach charging information to native battery layout", error);
            failed = true; removeText();
        }
    }
}
