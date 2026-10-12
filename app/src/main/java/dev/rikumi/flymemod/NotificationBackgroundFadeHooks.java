package dev.rikumi.flymemod;

import android.graphics.drawable.Drawable;
import android.view.View;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Merged-shade foregrounds inherit the stack fade exactly once. */
final class NotificationBackgroundFadeHooks {
    private final Class<?> stackClass, liveBlurClass;
    private final Field shade, expanded, drawableAlpha, background;
    private final Method classics, value, onKeyguard, filter;
    private final ThreadLocal<View> updating = new ThreadLocal<>();

    NotificationBackgroundFadeHooks(ClassLoader loader) throws ReflectiveOperationException {
        stackClass=loader.loadClass("com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout");
        shade=stackClass.getField("mShadeInteractor"); expanded=stackClass.getField("mIsExpanded");
        classics=shade.getType().getMethod("isClassicsMode");
        value=loader.loadClass("kotlinx.coroutines.flow.StateFlow").getMethod("getValue");
        onKeyguard=stackClass.getMethod("onKeyguard"); filter=stackClass.getMethod("isFilterStackLayout");
        Class<?> surface=loader.loadClass("com.android.systemui.statusbar.notification.row.NotificationBackgroundView");
        surface.getDeclaredMethod("updateLiveBlurAlpha",int.class);
        surface.getDeclaredMethod("setDrawableAlpha",int.class);
        drawableAlpha=surface.getField("mDrawableAlpha"); background=surface.getField("mBackground");
        liveBlurClass=loader.loadClass("com.android.internal.graphics.drawable.BackgroundBlurDrawable");
    }

    void install(SignalHooks.Installer installer) {
        String surface=background.getDeclaringClass().getName();
        installer.hook(surface,"updateLiveBlurAlpha",chain -> {
            View view=(View)chain.getThisObject();
            if (!merged(view)) return chain.proceed();
            View previous=updating.get(); updating.set(view);
            try { return chain.proceed(); }
            finally { if(previous==null) updating.remove(); else updating.set(previous); }
        },int.class);
        installer.hook(surface,"setDrawableAlpha",chain -> {
            Object result=chain.proceed();
            View view=(View)chain.getThisObject();
            if (updating.get()==view) {
                // BackgroundBlurDrawable's alpha drives the compositor region;
                // its fill Paint retains the configured color alpha. Preserve that
                // region alpha, but stop multiplying it into contours/color effects.
                drawableAlpha.setInt(view,255);
                Object drawable=background.get(view);
                if (drawable instanceof Drawable fill && !liveBlurClass.isInstance(fill)) fill.setAlpha(255);
            }
            return result;
        },int.class);
    }

    private boolean merged(View view) throws ReflectiveOperationException {
        for (View current=view; current!=null;
                current=current.getParent() instanceof View parent ? parent : null) {
            if (stackClass.isInstance(current))
                return expanded.getBoolean(current) && !Boolean.TRUE.equals(onKeyguard.invoke(current))
                        && !Boolean.TRUE.equals(filter.invoke(current))
                        && Boolean.TRUE.equals(value.invoke(classics.invoke(shade.get(current))));
        }
        return false;
    }
}
