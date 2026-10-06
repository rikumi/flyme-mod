package com.rikumi.flymemod;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.os.Looper;
import android.media.AudioManager;
import android.view.animation.PathInterpolator;
import android.widget.ImageView;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;

/** Replace only QS volume mute presentation; leave volume state and native Lottie intact. */
final class MuteSlashHooks {
    private static final String CONTROLLER = "com.flyme.systemui.volume.QSVolumeControllerImpl";
    private final Consumer<Context> settings;
    private final BooleanSupplier enabled;
    private final ToIntFunction<ImageView> foreground;
    private final Field iconField, streamField;
    private final Map<ImageView, State> states = new WeakHashMap<>();
    private final Path speaker = speakerPath();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private final Path transformed = new Path();
    private final PorterDuffXfermode clear = new PorterDuffXfermode(PorterDuff.Mode.CLEAR);

    MuteSlashHooks(ClassLoader loader, Consumer<Context> settings, BooleanSupplier enabled,
                   ToIntFunction<ImageView> foreground,
                   BiConsumer<String, Throwable> log) throws ReflectiveOperationException {
        this.settings = settings; this.enabled = enabled; this.foreground = foreground;
        Class<?> controller = loader.loadClass(CONTROLLER);
        iconField = controller.getField("mIcon");
        streamField = controller.getField("mCurrentStreamType");
        controller.getMethod("onInit");
        controller.getMethod("onViewAttached");
        controller.getMethod("changeIconResource", int.class);
        controller.getMethod("setUpIconFrame", int.class, int.class, int.class);
        controller.getField("SCALE_SIZE");
        controller.getMethod("onProgressChanged", int.class, int.class, int.class, int.class,
                int.class, boolean.class, boolean.class, boolean.class, String.class);
        controller.getMethod("getTypeIconResId", int.class, boolean.class);
        ImageView.class.getDeclaredMethod("onDraw", Canvas.class);
    }

    void install(SignalHooks.Installer installer) {
        // Publish the new mute state before native Lottie frames/resources change.
        installer.hook(CONTROLLER, "onProgressChanged", chain -> {
            ImageView icon = (ImageView) iconField.get(chain.getThisObject());
            int stream = (Integer) chain.getArg(0);
            if (icon != null) dispatch(icon, speakerStream(stream)
                    && (Integer) chain.getArg(2) <= (Integer) chain.getArg(3));
            return chain.proceed();
        }, int.class, int.class, int.class, int.class, int.class,
                boolean.class, boolean.class, boolean.class, String.class);
        installer.hook(CONTROLLER, "setUpIconFrame", chain -> {
            ImageView icon = (ImageView) iconField.get(chain.getThisObject());
            int value = (Integer) chain.getArg(0), minimum = (Integer) chain.getArg(1);
            int stream = streamField.getInt(chain.getThisObject());
            if (icon != null) {
                dispatch(icon, speakerStream(stream) && value <= minimum);
                State state = states.get(icon);
                int scale = chain.getThisObject().getClass().getField("SCALE_SIZE").getInt(chain.getThisObject());
                // Frame zero contains the original cross, including near-zero
                // non-muted volume values rounded down by the native controller.
                if (state != null) state.nativeMuteFrame = speakerStream(stream)
                        && (value == minimum || value / Math.max(1, scale) == 0);
            }
            return chain.proceed();
        }, int.class, int.class, int.class);
        for (String method : new String[]{"onInit", "onViewAttached"}) {
            installer.hook(CONTROLLER, method, chain -> {
                initialize(chain.getThisObject());
                return chain.proceed();
            });
        }
        installer.hook(CONTROLLER, "changeIconResource", chain -> {
            Object controller = chain.getThisObject();
            ImageView icon = (ImageView) iconField.get(controller);
            if (icon == null) return chain.proceed();
            if (!states.containsKey(icon)) initialize(controller);
            settings.accept(icon.getContext());
            if (!enabled.getAsBoolean()) return chain.proceed();
            int resource = (Integer) chain.getArg(0);
            for (String type : new String[]{"media", "ring"}) {
                int muted = icon.getResources().getIdentifier("ic_qs_volume_" + type + "_mute", "drawable", "com.android.systemui");
                if (muted != 0 && resource == muted) {
                    int normal = icon.getResources().getIdentifier("ic_qs_volume_" + type, "drawable", "com.android.systemui");
                    if (normal != 0) return chain.proceed(new Object[]{normal});
                }
            }
            return chain.proceed();
        }, int.class);
        installer.hook(CONTROLLER, "getTypeIconResId", chain -> {
            ImageView icon = (ImageView) iconField.get(chain.getThisObject());
            int stream = (Integer) chain.getArg(0);
            boolean muted = speakerStream(stream) && Boolean.TRUE.equals(chain.getArg(1));
            if (icon != null) dispatch(icon, muted);
            return icon != null && enabled.getAsBoolean() && muted
                    ? chain.proceed(new Object[]{stream, false}) : chain.proceed();
        }, int.class, boolean.class);
        installer.hook(ImageView.class.getName(), "onDraw", chain -> {
            ImageView icon = (ImageView) chain.getThisObject();
            State state = states.get(icon);
            if (state == null || !enabled.getAsBoolean()) return chain.proceed();
            Canvas canvas = (Canvas) chain.getArg(0);
            int save = canvas.save();
            // Scale both native unmuted Lottie and the animated mute drawing,
            // without changing the slider's layout or accumulating View scales.
            canvas.scale(.9f, .9f, icon.getWidth() / 2f, icon.getHeight() / 2f);
            try {
                if (!state.muted && state.progress <= 0f && !state.nativeMuteFrame) return chain.proceed();
                draw(icon, state.progress, canvas);
                return null;
            } finally { canvas.restoreToCount(save); }
        }, Canvas.class);
    }

    private static boolean speakerStream(int stream) { return stream == 2 || stream == 3; }

    private void initialize(Object controller) throws ReflectiveOperationException {
        ImageView icon = (ImageView) iconField.get(controller);
        if (icon == null) return;
        int stream = streamField.getInt(controller);
        AudioManager audio = (AudioManager) icon.getContext().getSystemService(Context.AUDIO_SERVICE);
        boolean muted = speakerStream(stream) && audio != null
                && (audio.isStreamMute(stream) || audio.getStreamVolume(stream) <= audio.getStreamMinVolume(stream));
        dispatch(icon, muted);
    }

    private void dispatch(ImageView icon, boolean muted) {
        if (Looper.myLooper() == Looper.getMainLooper()) update(icon, muted);
        else icon.post(() -> update(icon, muted));
    }

    void refresh() {
        for (ImageView icon : new ArrayList<>(states.keySet())) {
            if (icon == null) continue;
            State state = states.get(icon);
            if (state != null) animate(icon, state, enabled.getAsBoolean() && state.muted ? 1f : 0f);
            icon.invalidate();
        }
    }

    private void update(ImageView icon, boolean muted) {
        settings.accept(icon.getContext());
        State state = states.get(icon);
        float target = enabled.getAsBoolean() && muted ? 1f : 0f;
        if (state == null) {
            state = new State(); state.muted = muted; state.progress = target;
            states.put(icon, state); icon.invalidate(); return;
        }
        if (state.muted != muted) {
            state.muted = muted; animate(icon, state, target);
        }
    }

    private void animate(ImageView icon, State state, float target) {
        if (state.animator != null) state.animator.cancel();
        state.animator = null;
        if (state.progress == target) return;
        if (!icon.isShown() || !icon.isAttachedToWindow() || !enabled.getAsBoolean()) {
            state.progress = target; icon.invalidate(); return;
        }
        WeakReference<ImageView> reference = new WeakReference<>(icon);
        ValueAnimator animator = ValueAnimator.ofFloat(state.progress, target);
        state.animator = animator;
        animator.setDuration(220);
        animator.setInterpolator(new PathInterpolator(.4f, 0f, .2f, 1f));
        animator.addUpdateListener(value -> {
            state.progress = (Float) value.getAnimatedValue();
            ImageView view = reference.get();
            if (view == null || !view.isAttachedToWindow()) { value.cancel(); return; }
            view.invalidate();
        });
        animator.start();
    }

    private void draw(ImageView icon, float progress, Canvas canvas) {
        float width = icon.getWidth() - icon.getPaddingLeft() - icon.getPaddingRight();
        float height = icon.getHeight() - icon.getPaddingTop() - icon.getPaddingBottom();
        float side = Math.min(width, height);
        if (side <= 0f) return;
        float left = icon.getPaddingLeft() + (width - side) / 2f;
        float top = icon.getPaddingTop() + (height - side) / 2f;
        int color = foreground.applyAsInt(icon);
        // View alpha is applied to the final composite by the existing slider tint.
        paint.setColor(color); paint.setStyle(Paint.Style.FILL); paint.setXfermode(null);
        matrix.setScale(side / 24f, side / 24f); matrix.postTranslate(left, top);
        speaker.transform(matrix, transformed);
        int layer = canvas.saveLayer(0, 0, icon.getWidth(), icon.getHeight(), null);
        canvas.drawPath(transformed, paint);
        if (progress > 0f) {
            float stroke = 2f * icon.getResources().getDisplayMetrics().density;
            float startX = left + side * .15f, startY = top + side * .15f;
            float endX = startX + side * .7f * progress, endY = startY + side * .7f * progress;
            float offset = stroke / (float) Math.sqrt(2);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(stroke); paint.setStrokeCap(Paint.Cap.ROUND);
            // Cut only this isolated icon layer: a parallel equally long line on
            // the lower-left side, then draw the upper-right mute stroke.
            paint.setXfermode(clear);
            canvas.drawLine(startX - offset, startY + offset, endX - offset, endY + offset, paint);
            paint.setXfermode(null);
            canvas.drawLine(startX, startY, endX, endY, paint);
        }
        canvas.restoreToCount(layer);
        paint.setXfermode(null);
    }

    private static Path speakerPath() {
        // Exact speaker body from Flyme's volume.json layer "0", centered in 24 units.
        Path path = new Path();
        path.moveTo(6.5000f, 15.4440f);
        path.cubicTo(6.5000f, 15.9960f, 6.9480f, 16.4440f, 7.5000f, 16.4440f);
        path.cubicTo(7.5000f, 16.4440f, 9.6140f, 16.4440f, 9.6140f, 16.4440f);
        path.cubicTo(10.5250f, 16.4440f, 11.3870f, 16.8580f, 11.9560f, 17.5700f);
        path.cubicTo(11.9560f, 17.5700f, 14.5990f, 20.8740f, 14.5990f, 20.8740f);
        path.cubicTo(15.1680f, 21.5860f, 16.0310f, 22.0000f, 16.9420f, 22.0000f);
        path.cubicTo(16.9420f, 22.0000f, 17.0000f, 22.0000f, 17.0000f, 22.0000f);
        path.cubicTo(17.2760f, 22.0000f, 17.5000f, 21.7760f, 17.5000f, 21.5000f);
        path.cubicTo(17.5000f, 21.5000f, 17.5000f, 2.5000f, 17.5000f, 2.5000f);
        path.cubicTo(17.5000f, 2.2240f, 17.2760f, 2.0000f, 17.0000f, 2.0000f);
        path.cubicTo(17.0000f, 2.0000f, 16.9420f, 2.0000f, 16.9420f, 2.0000f);
        path.cubicTo(16.0310f, 2.0000f, 15.1680f, 2.4140f, 14.5990f, 3.1260f);
        path.cubicTo(14.5990f, 3.1260f, 11.9560f, 6.4300f, 11.9560f, 6.4300f);
        path.cubicTo(11.3870f, 7.1420f, 10.5250f, 7.5560f, 9.6140f, 7.5560f);
        path.cubicTo(9.6140f, 7.5560f, 7.5000f, 7.5560f, 7.5000f, 7.5560f);
        path.cubicTo(6.9480f, 7.5560f, 6.5000f, 8.0040f, 6.5000f, 8.5560f);
        path.cubicTo(6.5000f, 8.5560f, 6.5000f, 15.4440f, 6.5000f, 15.4440f);
        path.close(); return path;
    }

    private static final class State {
        boolean muted, nativeMuteFrame;
        float progress;
        ValueAnimator animator;
    }
}
