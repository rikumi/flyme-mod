package dev.rikumi.flymemod;

import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.ViewParent;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Only the PhoneStatusBarViewController's clock is affected; icons remain native. */
final class StatusBarLyricHooks {
    private final BooleanSupplier enabled;
    private final Consumer<Context> settings;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<View, State> clocks = new WeakHashMap<>();
    private final ThreadLocal<Boolean> applying = ThreadLocal.withInitial(() -> false);
    private List<StatusBarOverlayFrames.Frame> overlays = List.of();
    private Context observedContext;
    private final ContentObserver observer = new ContentObserver(handler) {
        @Override public void onChange(boolean selfChange) { readFrames(); applyAll(); }
    };
    StatusBarLyricHooks(Consumer<Context> settings, BooleanSupplier enabled) {
        this.settings = settings; this.enabled = enabled;
    }
    void install(ClassLoader loader, SignalHooks.Installer installer, Consumer<Method> deoptimize)
            throws ReflectiveOperationException {
        Class<?> controller = loader.loadClass("com.android.systemui.statusbar.phone.PhoneStatusBarViewController");
        var clockField = controller.getDeclaredField("clock"); clockField.setAccessible(true);
        installer.hook(controller.getName(), "onViewAttached", chain -> {
            Object result = chain.proceed();
            View clock = (View) clockField.get(chain.getThisObject());
            if (clock != null) bind(clock);
            return result;
        });
        installer.hook("android.view.View", "setVisibility", chain -> {
            View view = (View) chain.getThisObject();
            State state = clocks.get(view);
            if (state == null || applying.get()) return chain.proceed();
            int requested = (Integer) chain.getArg(0);
            state.nativeVisibility = requested;
            if (requested == View.VISIBLE && state.covered()) return chain.proceed(new Object[]{View.INVISIBLE});
            return chain.proceed();
        }, int.class);
        Class<?> clock = loader.loadClass("com.android.systemui.statusbar.policy.Clock");
        deoptimize.accept(clock.getDeclaredMethod("setVisibility", int.class));
        deoptimize.accept(clock.getDeclaredMethod("updateClockVisibility"));
    }
    private void bind(View clock) {
        settings.accept(clock.getContext());
        if (!clocks.containsKey(clock)) {
            View root = clock;
            for (ViewParent p = clock.getParent(); p instanceof View v; p = v.getParent()) {
                if (v.getClass().getName().equals("com.android.systemui.statusbar.phone.PhoneStatusBarView")) {
                    root = v; break;
                }
            }
            // Never infer a status bar from an arbitrary clock parent.
            if (!root.getClass().getName().equals("com.android.systemui.statusbar.phone.PhoneStatusBarView")) return;
            State state = new State(clock, root);
            clocks.put(clock, state);
            clock.addOnAttachStateChangeListener(state);
        }
        refresh();
    }
    void refresh() {
        if (Looper.myLooper() != Looper.getMainLooper()) { handler.post(this::refresh); return; }
        boolean needObserver = enabled.getAsBoolean() && clocks.keySet().stream().anyMatch(View::isAttachedToWindow);
        if (!needObserver && observedContext != null) {
            observedContext.getContentResolver().unregisterContentObserver(observer);
            observedContext = null; overlays = List.of();
        } else if (needObserver && observedContext == null) {
            for (View clock : new ArrayList<>(clocks.keySet())) {
                if (!clock.isAttachedToWindow()) continue;
                observedContext = clock.getContext().getApplicationContext();
                if (observedContext == null) observedContext = clock.getContext();
                observedContext.getContentResolver().registerContentObserver(
                        Settings.Global.getUriFor(StatusBarOverlayFrames.KEY), false, observer);
                readFrames(); break;
            }
        }
        for (State state : new ArrayList<>(clocks.values())) state.observeLayout(needObserver);
        applyAll();
    }
    private void readFrames() {
        overlays = observedContext == null ? List.of() : StatusBarOverlayFrames.parse(
                Settings.Global.getString(observedContext.getContentResolver(), StatusBarOverlayFrames.KEY));
    }
    private void applyAll() { for (State state : new ArrayList<>(clocks.values())) state.apply(); }
    private final class State implements View.OnAttachStateChangeListener, View.OnLayoutChangeListener {
        private final WeakReference<View> clock, root;
        private int nativeVisibility;
        private boolean layoutObserved;
        State(View clock, View root) {
            this.clock = new WeakReference<>(clock); this.root = new WeakReference<>(root);
            nativeVisibility = clock.getVisibility();
        }
        boolean covered() {
            View c = clock.get(), bar = root.get();
            if (!enabled.getAsBoolean() || c == null || bar == null || !c.isAttachedToWindow()
                    || !bar.isShown() || bar.getDisplay() == null) return false;
            int[] location = new int[2]; bar.getLocationOnScreen(location);
            StatusBarOverlayFrames.Frame bounds = new StatusBarOverlayFrames.Frame(bar.getDisplay().getDisplayId(),
                    location[0], location[1], location[0] + bar.getWidth(), location[1] + bar.getHeight());
            for (StatusBarOverlayFrames.Frame overlay : overlays) if (overlay.covers(bounds)) return true;
            return false;
        }
        void observeLayout(boolean needed) {
            View bar = root.get(), c = clock.get();
            needed &= c != null && c.isAttachedToWindow();
            if (bar == null || needed == layoutObserved) return;
            layoutObserved = needed;
            if (needed) bar.addOnLayoutChangeListener(this); else bar.removeOnLayoutChangeListener(this);
        }
        void apply() {
            View c = clock.get();
            if (c == null) return;
            int target = nativeVisibility == View.VISIBLE && covered() ? View.INVISIBLE : nativeVisibility;
            if (c.getVisibility() == target) return;
            applying.set(true);
            try { c.setVisibility(target); } finally { applying.set(false); }
        }
        @Override public void onViewAttachedToWindow(View view) { refresh(); }
        @Override public void onViewDetachedFromWindow(View view) { observeLayout(false); refresh(); }
        @Override public void onLayoutChange(View v, int l, int t, int r, int b, int oldL, int oldT, int oldR, int oldB) {
            apply();
        }
    }
}
