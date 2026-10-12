package dev.rikumi.flymemod;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/** Flyme SystemUI 16260625: mPaint fills the curve, mPaint1 draws its edge. */
final class BackGestureBackgroundHooks {
    private static final String OWNER = "com.flyme.systemui.navigationbar.gestural.EdgeBackView";
    private final Field fill;
    private final Consumer<Context> settings;
    private final BooleanSupplier enabled;
    private final IntSupplier opacity;
    private final Set<View> views = Collections.newSetFromMap(new WeakHashMap<>());

    BackGestureBackgroundHooks(ClassLoader loader, Consumer<Context> settings,
                               BooleanSupplier enabled, IntSupplier opacity) throws ReflectiveOperationException {
        this.settings = settings;
        this.enabled = enabled;
        this.opacity = opacity;
        Class<?> type = loader.loadClass(OWNER);
        fill = type.getField("mPaint");
        type.getDeclaredMethod("onDraw", Canvas.class);
    }

    void install(SignalHooks.Installer installer) {
        installer.hook(OWNER, "onDraw", chain -> {
            View view = (View) chain.getThisObject();
            views.add(view);
            settings.accept(view.getContext());
            if (!enabled.getAsBoolean()) return chain.proceed();
            Paint paint = (Paint) fill.get(view);
            int originalColor = paint.getColor();
            try {
                paint.setColor(Color.argb(Math.round(ModuleSettings.backGestureDarkOpacity(
                        opacity.getAsInt()) * 255f / 100f), 0, 0, 0));
                return chain.proceed();
            } finally {
                // Leave native paint state intact for disabling and subsequent native updates.
                paint.setColor(originalColor);
            }
        }, Canvas.class);
    }

    void refresh() {
        for (View view : new ArrayList<>(views)) {
            if (view != null) view.postInvalidateOnAnimation();
        }
    }
}
