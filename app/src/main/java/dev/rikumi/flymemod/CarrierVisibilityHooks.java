package dev.rikumi.flymemod;

import android.content.Context;
import android.view.View;
import android.widget.TextView;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

final class CarrierVisibilityHooks {
    private static final String[] IDS = {"keyguard_carrier_text", "shade_carrier_text", "no_carrier_text"};
    private final Consumer<Context> settings;
    private final BooleanSupplier enabled;
    private final Map<View, Integer> views = new WeakHashMap<>();
    private final Map<android.content.res.Resources, int[]> resourceIds = new WeakHashMap<>();
    private final ThreadLocal<Boolean> applying = new ThreadLocal<>();

    CarrierVisibilityHooks(Consumer<Context> settings, BooleanSupplier enabled) {
        this.settings = settings; this.enabled = enabled;
    }

    void install(ClassLoader loader, SignalHooks.Installer installer, Consumer<Method> deoptimize)
            throws ReflectiveOperationException {
        Method visibility = View.class.getDeclaredMethod("setVisibility", int.class);
        installer.hook(View.class.getName(), visibility.getName(), chain -> {
            View view = (View) chain.getThisObject();
            if (Boolean.TRUE.equals(applying.get()) || !(view instanceof TextView) || !target(view)) return chain.proceed();
            settings.accept(view.getContext());
            views.put(view, (int) chain.getArg(0));
            return enabled.getAsBoolean() ? chain.proceed(new Object[]{View.GONE}) : chain.proceed();
        }, int.class);
        deoptimize.accept(visibility);
        for (String name : new String[]{"com.android.systemui.statusbar.phone.KeyguardStatusBarView",
                "com.flyme.statusbar.bouncer.KeyguardBouncerStatusBarView",
                "com.android.systemui.shade.carrier.ShadeCarrier"}) {
            Method inflate = loader.loadClass(name).getDeclaredMethod("onFinishInflate");
            installer.hook(name, inflate.getName(), chain -> {
                Object result = chain.proceed();
                View root = (View) chain.getThisObject();
                for (String id : IDS) {
                    int resource = root.getResources().getIdentifier(id, "id", "com.android.systemui");
                    if (resource != 0) track(root.findViewById(resource));
                }
                return result;
            });
            deoptimize.accept(inflate);
        }
        String group = "com.android.systemui.shade.carrier.ShadeCarrierGroup";
        Method noSim = loader.loadClass(group).getDeclaredMethod("getNoSimTextView");
        installer.hook(group, noSim.getName(), chain -> {
            Object result = chain.proceed();
            track((View) result);
            return result;
        });
        deoptimize.accept(noSim);
    }

    private boolean target(View view) {
        if (views.containsKey(view)) return true;
        if (view.getId() == View.NO_ID) return false;
        int[] ids = resourceIds.get(view.getResources());
        if (ids == null) {
            ids = new int[IDS.length];
            for (int i = 0; i < IDS.length; i++)
                ids[i] = view.getResources().getIdentifier(IDS[i], "id", "com.android.systemui");
            resourceIds.put(view.getResources(), ids);
        }
        for (int resource : ids) {
            if (resource != 0 && resource == view.getId()) return true;
        }
        return false;
    }

    private void track(View view) {
        if (view == null) return;
        settings.accept(view.getContext());
        views.putIfAbsent(view, view.getVisibility());
        apply(view, views.get(view));
    }

    void refresh() {
        for (Map.Entry<View, Integer> entry : new ArrayList<>(views.entrySet())) {
            View view = entry.getKey();
            if (view != null) apply(view, entry.getValue());
        }
    }

    private void apply(View view, int original) {
        int visibility = enabled.getAsBoolean() ? View.GONE : original;
        if (view.getVisibility() == visibility) return;
        applying.set(true);
        try { view.setVisibility(visibility); }
        finally { applying.remove(); }
    }
}
