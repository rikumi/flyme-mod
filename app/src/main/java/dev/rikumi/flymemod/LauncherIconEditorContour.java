package dev.rikumi.flymemod;

import android.content.Context;
import android.database.ContentObserver;
import android.graphics.*;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.*;

final class LauncherIconEditorContour {
    private final Consumer<Context> settings;
    private final BooleanSupplier icons, menus;
    private final BiConsumer<String, Throwable> log;
    private final Set<View> backgrounds = Collections.newSetFromMap(new WeakHashMap<>());

    LauncherIconEditorContour(Consumer<Context> settings, BooleanSupplier icons,
            BooleanSupplier menus, BiConsumer<String, Throwable> log) {
        this.settings = settings; this.icons = icons; this.menus = menus; this.log = log;
    }

    private void observe(View host, Runnable redraw) {
        ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override public void onChange(boolean selfChange) {
                settings.accept(host.getContext()); redraw.run();
            }
        };
        View.OnAttachStateChangeListener lifecycle = new View.OnAttachStateChangeListener() {
            boolean registered;
            @Override public void onViewAttachedToWindow(View view) {
                if (registered) return;
                settings.accept(view.getContext());
                view.getContext().getContentResolver().registerContentObserver(ModuleSettings.URI, false, observer);
                registered = true; redraw.run();
            }
            @Override public void onViewDetachedFromWindow(View view) {
                if (registered) view.getContext().getContentResolver().unregisterContentObserver(observer);
                registered = false;
            }
        };
        host.addOnAttachStateChangeListener(lifecycle);
        if (host.isAttachedToWindow()) lifecycle.onViewAttachedToWindow(host);
    }

    ImageView image(Context context) {
        ImageView host = new ImageView(context) {
            Bitmap source, mask, shadow;
            int generation;
            float drawWidth, drawHeight;
            LauncherContourRenderer renderer;
            boolean failed;
            @Override protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                if (!icons.getAsBoolean() || IconContourDrawScope.isRaw() || !canvas.isHardwareAccelerated()
                        || !(getDrawable() instanceof BitmapDrawable drawable)) return;
                Bitmap bitmap = drawable.getBitmap();
                RectF bounds = new RectF(drawable.getBounds());
                if (bounds.isEmpty() || bitmap.isRecycled()) return;
                int saved = canvas.save();
                try {
                    canvas.translate(getPaddingLeft(), getPaddingTop()); canvas.concat(getImageMatrix());
                    if (source != bitmap || generation != bitmap.getGenerationId()
                            || drawWidth != bounds.width() || drawHeight != bounds.height()) {
                        Bitmap readable = bitmap.getConfig() == Bitmap.Config.HARDWARE
                                ? bitmap.copy(Bitmap.Config.ARGB_8888, false) : bitmap;
                        int w = readable.getWidth(), h = readable.getHeight();
                        int[] pixels = new int[w*h];
                        readable.getPixels(pixels, 0, w, 0, 0, w, h);
                        float density = getResources().getDisplayMetrics().density;
                        Bitmap nextMask = Bitmap.createBitmap(IconContourPixels.mask(pixels, w, h,
                                2*density*w/bounds.width()), w, h, Bitmap.Config.ARGB_8888);
                        for (int i=0;i<pixels.length;i++) pixels[i]=(pixels[i]>>>24)>=64?0xff000000:0;
                        Bitmap nextShadow = Bitmap.createBitmap(IconInnerShadow.pixels(pixels, w, h,
                                3*density*w/bounds.width()), w, h, Bitmap.Config.ARGB_8888);
                        if (readable != bitmap) readable.recycle();
                        mask = nextMask; shadow = nextShadow;
                        source = bitmap; generation = bitmap.getGenerationId();
                        drawWidth = bounds.width(); drawHeight = bounds.height();
                    }
                    if (renderer == null) renderer = new LauncherContourRenderer(this);
                    Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                    paint.setAlpha(drawable.getAlpha());
                    canvas.drawBitmap(shadow, null, bounds, paint);
                    renderer.draw(this, canvas, bounds, 0, drawable.getAlpha()/255f, mask);
                } catch (Exception | LinkageError error) {
                    if (!failed) { failed = true; log.accept("Cannot render editor icon contour", error); }
                } finally { canvas.restoreToCount(saved); }
            }
        };
        observe(host, host::invalidate); return host;
    }

    void background(View host) {
        if (host == null || !backgrounds.add(host)) return;
        Drawable overlay = new Drawable() {
            LauncherContourRenderer renderer;
            boolean failed;
            @Override public void draw(Canvas canvas) {
                if (!menus.getAsBoolean() || !canvas.isHardwareAccelerated()) return;
                try {
                    Rect rect = new Rect(0, 0, host.getWidth(), host.getHeight());
                    float radius = 0;
                    Drawable background = host.getBackground();
                    if (background != null) {
                        Outline outline = new Outline(); background.getOutline(outline);
                        if (outline.getRect(rect)) radius = Math.max(0, outline.getRadius());

                    }
                    if (renderer == null) renderer = new LauncherContourRenderer(host);
                    renderer.drawMenu(host, canvas, new RectF(rect), radius);
                } catch (Exception | LinkageError error) {
                    if (!failed) { failed = true; log.accept("Cannot render editor background contour", error); }
                }
            }
            @Override public void setAlpha(int alpha) {}
            @Override public void setColorFilter(ColorFilter filter) {}
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
        host.getOverlay().add(overlay);
        observe(host, () -> { overlay.invalidateSelf(); host.invalidate(); });
        host.addOnLayoutChangeListener((view,l,t,r,b,ol,ot,or,ob) -> {
            overlay.setBounds(0, 0, r-l, b-t); view.invalidate();
        });
        overlay.setBounds(0, 0, host.getWidth(), host.getHeight());
    }
}
