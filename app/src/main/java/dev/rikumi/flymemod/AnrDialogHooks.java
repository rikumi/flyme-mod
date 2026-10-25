package dev.rikumi.flymemod;

import android.app.AlertDialog;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.View;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;

final class AnrDialogHooks {
    private static final String ANR = "com.android.server.am.AppNotRespondingDialog";
    private static final Map<View, Boolean> NATIVE_SURFACES = Collections.synchronizedMap(new WeakHashMap<>());
    private final BiConsumer<String, Throwable> log;

    AnrDialogHooks(BiConsumer<String, Throwable> log) {
        this.log = log;
    }

    static boolean nativeSurface(View view) { return NATIVE_SURFACES.containsKey(view.getRootView()); }

    void install(ClassLoader loader, ThemeColorHooks.Installer installer) {
        try {
            Class<?> anr = Class.forName(ANR, false, loader);
            Class<?> base = Class.forName("com.android.server.am.BaseErrorDialog", false, loader);
            installer.hook(anr.getDeclaredMethod("onCreate", Bundle.class), chain -> {
                Object result = chain.proceed();
                shown((AlertDialog) chain.getThisObject());
                return result;
            });
            installer.hook(base.getDeclaredMethod("onStart"), chain -> {
                Object result = chain.proceed();
                if (chain.getThisObject().getClass() == anr) shown((AlertDialog) chain.getThisObject());
                return result;
            });
            installer.hook(base.getDeclaredMethod("onStop"), chain -> {
                if (chain.getThisObject().getClass() == anr) clear((AlertDialog) chain.getThisObject());
                return chain.proceed();
            });
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) { report(error); }
    }

    private void shown(AlertDialog dialog) {
        if (!ANR.equals(dialog.getClass().getName())) return;
        try {
            View root = dialog.getWindow().getDecorView();
            NATIVE_SURFACES.put(root, Boolean.TRUE);
            TypedArray attributes = dialog.getContext().obtainStyledAttributes(new int[]{android.R.attr.windowBackground});
            try {
                Drawable background = attributes.getDrawable(0);
                if (background != null) {
                    // Reload the real theme drawable, retaining the ROM's night shade and 16dp corners.
                    dialog.getWindow().setBackgroundDrawable(background);
                    root.setForceDarkAllowed(false);
                }
            } finally { attributes.recycle(); }
        } catch (RuntimeException | LinkageError error) { report(error); }
    }

    private void clear(AlertDialog dialog) {
        View root = dialog.getWindow().getDecorView();
        NATIVE_SURFACES.remove(root);
    }

    private void report(Throwable error) {
        log.accept("Cannot adapt exact native ANR window", error);
    }
}
