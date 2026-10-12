package dev.rikumi.flymemod;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
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
    private final Field fill, curve;
    private final Paint arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrowPath = new Path();
    private final RectF curveBounds = new RectF();
    private final Consumer<Context> settings;
    private final BooleanSupplier enabled, arrowEnabled;
    private final IntSupplier opacity;
    private final Set<View> views = Collections.newSetFromMap(new WeakHashMap<>());

    BackGestureBackgroundHooks(ClassLoader loader, Consumer<Context> settings,
                               BooleanSupplier enabled, IntSupplier opacity, BooleanSupplier arrowEnabled) throws ReflectiveOperationException {
        this.settings = settings;
        this.enabled = enabled;
        this.arrowEnabled = arrowEnabled;
        arrowPaint.setStyle(Paint.Style.FILL);
        arrowPaint.setStrokeCap(Paint.Cap.ROUND);
        arrowPaint.setStrokeJoin(Paint.Join.ROUND);
        this.opacity = opacity;
        Class<?> type = loader.loadClass(OWNER);
        fill = type.getField("mPaint");
        curve = type.getField("mPath");
        type.getDeclaredMethod("onDraw", Canvas.class);
    }

    void install(SignalHooks.Installer installer) {
        installer.hook(OWNER, "onDraw", chain -> {
            View view = (View) chain.getThisObject();
            views.add(view);
            settings.accept(view.getContext());
            Paint paint = (Paint) fill.get(view);
            int originalColor = paint.getColor();
            try {
                if (enabled.getAsBoolean()) paint.setColor(Color.argb(Math.round(ModuleSettings.backGestureDarkOpacity(
                        opacity.getAsInt()) * 255f / 100f), 0, 0, 0));
                Object result = chain.proceed();
                if (arrowEnabled.getAsBoolean()) drawArrow(view, (Canvas) chain.getArg(0));
                return result;
            } finally {
                // Leave native paint state intact for disabling and subsequent native updates.
                paint.setColor(originalColor);
            }
        }, Canvas.class);
    }

    private void drawArrow(View view, Canvas canvas) throws IllegalAccessException {
        Path background = (Path) curve.get(view);
        if (background.isEmpty()) return;
        background.computeBounds(curveBounds, true);
        float density = view.getResources().getDisplayMetrics().density;
        // Google Material Icons chevron_left (24px): visible bounds x=8..15.41, y=6..18.
        // Scale its visible height to 10dp, then center the actual glyph rather than the viewport.
        float scale = density * 10f / 12f;
        float x = curveBounds.centerX(), y = curveBounds.centerY();
        float originX = x - 11.705f * scale, originY = y - 12f * scale;
        arrowPaint.setColor(enabled.getAsBoolean() ? Color.WHITE : Color.rgb(64,64,64));
        arrowPath.reset();
        arrowPath.moveTo(originX + 15.41f * scale, originY + 7.41f * scale);
        arrowPath.lineTo(originX + 14f * scale, originY + 6f * scale);
        arrowPath.lineTo(originX + 8f * scale, originY + 12f * scale);
        arrowPath.lineTo(originX + 14f * scale, originY + 18f * scale);
        arrowPath.lineTo(originX + 15.41f * scale, originY + 16.59f * scale);
        arrowPath.lineTo(originX + 10.83f * scale, originY + 12f * scale);
        arrowPath.close();
        int save = canvas.save();
        try {
            canvas.clipPath(background);
            canvas.drawPath(arrowPath, arrowPaint);
        } finally { canvas.restoreToCount(save); }
    }

    void refresh() {
        for (View view : new ArrayList<>(views)) {
            if (view != null) view.postInvalidateOnAnimation();
        }
    }
}
