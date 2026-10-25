package dev.rikumi.flymemod;

import android.content.Context;
import android.content.res.ColorStateList;
import android.database.ContentObserver;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.*;

final class LauncherContourHooks {
    private static final String PREVIEW = "com.android.launcher3.folder.PreviewBackground";
    private static final String WIDGET = "com.meizu.flyme.launcher.widget.BaseFlymeAppWidgetHostView";
    private static final String SPRING = "com.meizu.flyme.launcher.popup.PopupContainerSpringAnimationSet";
    private static final String POPUP = "com.android.launcher3.popup.ArrowPopup";
    private static final String CLEAR_ALL = "com.android.quickstep.views.ClearAllButton";
    private final Consumer<Context> settings;
    private final BooleanSupplier folder, widget, menu, customRadius, icon, alignment;
    private final IntSupplier radiusDp;
    private final BiConsumer<String, Throwable> log;
    private final Field delegate, folderRadius, folderBlur, widgetBlur, widgetRadius,
            hideBlur, blurAlpha, blurVisible, arrow, menuRadius;
    private final Method folderBounds, widgetDragBounds, widgetScale, generatePath;
    private final Field springPopup, blurSmoothness, blurView, leftAligned, iconInfo, iconPixels, iconScale;
    private final Method anchorView, reorient, folderPreview;
    private final Class<?> folderIcon, bubbleTextView, floatingIconView, pressedWidget, widgetTitle;
    private final Method pressedWidgetBounds;
    private final Object customWidgetTag;
    private final Map<Drawable, IconMask> iconMasks = new WeakHashMap<>();
    private final Map<View, LauncherContourRenderer> renderers = new WeakHashMap<>();
    private final Map<View, SmoothShape> smoothShapes = new WeakHashMap<>();
    private final Map<View, WidgetMask> masks = new WeakHashMap<>();
    private final Map<View, Long> nextMaskScan = new WeakHashMap<>();
    private final Map<View, Integer> arrowVisibility = new WeakHashMap<>();
    private final Map<View, MenuContour> menus = new WeakHashMap<>();
    private final Map<View, ClearButtonStyle> clearButtons = new WeakHashMap<>();
    private final Set<View> hosts = Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<View> errors = Collections.newSetFromMap(new WeakHashMap<>());
    private ContentObserver observer;

    LauncherContourHooks(ClassLoader loader, Consumer<Context> settings, BooleanSupplier folder,
            BooleanSupplier widget, BooleanSupplier menu, BooleanSupplier customRadius,
            BooleanSupplier icon, BooleanSupplier alignment, IntSupplier radiusDp, BiConsumer<String, Throwable> log) throws ReflectiveOperationException {
        this.settings = settings; this.folder = folder; this.widget = widget; this.menu = menu;
        this.customRadius = customRadius; this.icon = icon; this.alignment = alignment;
        this.radiusDp = radiusDp; this.log = log;
        loader.loadClass(CLEAR_ALL).getDeclaredMethod("draw", Canvas.class);
        Class<?> p = loader.loadClass(PREVIEW), w = loader.loadClass(WIDGET), a = loader.loadClass(POPUP);
        pressedWidget = loader.loadClass("com.meizu.flyme.launcher.widget.FlymeLauncherAppWidgetHostView");
        pressedWidgetBounds = pressedWidget.getMethod("getIconBounds", View.class, Rect.class);
        widgetTitle = loader.loadClass("com.meizu.flyme.launcher.widget.view.FlymeWidgetTitleContainer");
        customWidgetTag = loader.loadClass("com.meizu.flyme.launcher.utils.FlymeWidgetUtils")
                .getField("TAG_FLYME_WIDGET_CUSTOM_VIEW").get(null);
        folderIcon = loader.loadClass("com.android.launcher3.folder.FolderIcon");
        folderPreview = folderIcon.getMethod("getFolderBackground");
        bubbleTextView = loader.loadClass("com.android.launcher3.BubbleTextView");
        floatingIconView = loader.loadClass("com.android.launcher3.views.FloatingIconView");
        Class<?> fast = loader.loadClass("com.android.launcher3.icons.FastBitmapDrawable");
        fast.getDeclaredMethod("draw", Canvas.class);
        iconInfo = field(fast, "mBitmapInfo"); iconScale = field(fast, "mScale");
        iconPixels = field(loader.loadClass("com.android.launcher3.icons.BitmapInfo"), "icon");
        anchorView = a.getMethod("getAnchorView"); leftAligned = field(a, "mIsLeftAligned");
        reorient = a.getDeclaredMethod("orientAboutObject"); reorient.setAccessible(true);
        a.getDeclaredMethod("flymeOrientAboutObject", boolean.class, boolean.class);
        Class<?> spring = loader.loadClass(SPRING);
        springPopup = field(spring, "mPopupView");
        spring.getDeclaredMethod("animateOpen");
        Class<?> b = loader.loadClass("com.meizu.flyme.launcher.utils.BackgroundBlurUtils");
        delegate = field(p, "mInvalidateDelegate"); folderRadius = field(p, "mBackgroundCornerRadius");
        folderBlur = field(p, "mBlurUtils"); folderBounds = p.getMethod("getBackgroundBounds");
        widgetBlur = field(w, "mBlurUtils");
        widgetRadius = field(w, "mBackgroundCornerRadius"); hideBlur = field(w, "mForceHideBlur");
        blurSmoothness = field(b, "mSmoothness"); blurView = field(b, "mView");
        widgetDragBounds = w.getMethod("getWorkspaceVisualDragBounds", Rect.class);
        widgetScale = w.getMethod("getWidgetScale");
        generatePath = loader.loadClass("com.meizu.common.util.SmoothCornerPathGenerator").getMethod("genSmoothCornerPath",
                float.class, float.class, float.class, float.class, float.class, float.class, boolean.class);
        blurAlpha = field(b, "mAlpha"); blurVisible = field(b, "mVisible");
        arrow = field(a, "mArrow"); menuRadius = field(a, "mOutlineRadius");
        a.getMethod("showArrow", boolean.class);
        p.getDeclaredMethod("drawBackground", Canvas.class); w.getDeclaredMethod("draw", Canvas.class);
        a.getDeclaredMethod("onLayout", boolean.class, int.class, int.class, int.class, int.class);
    }

    void install(ClassLoader loader, SignalHooks.Installer installer, Consumer<Method> deoptimize) throws ReflectiveOperationException {
        installer.hook(CLEAR_ALL, "draw", chain -> {
            View host = (View) chain.getThisObject();
            try { styleClearButton(host); }
            catch (Exception | LinkageError error) { report(host, error); }
            return chain.proceed();
        }, Canvas.class);
        deoptimize.accept(loader.loadClass(CLEAR_ALL).getDeclaredMethod("draw", Canvas.class));
        installer.hook(PREVIEW, "drawBackground", chain -> {
            Object result = chain.proceed();
            Object preview = chain.getThisObject();
            View host = (View) delegate.get(preview);
            if (host == null) return result;
            track(host);
            if (folder.getAsBoolean()) try {
                Rect r = (Rect) folderBounds.invoke(preview);
                float radius = customRadius.getAsBoolean() ? FolderAppearanceHooks.boundedRadius(
                        radiusDp.getAsInt(), density(host), r.width(), r.height()) : folderRadius.getFloat(preview);
                Object blur = folderBlur.get(preview);
                drawSurface(host, (Canvas) chain.getArg(0), new RectF(r), radius, alpha(blur), blur);
            } catch (Exception | LinkageError e) { report(host, e); }
            return result;
        }, Canvas.class);
        installer.hook(WIDGET, "dispatchDraw", chain -> {
            Object result = chain.proceed();
            ViewGroup host = (ViewGroup) chain.getThisObject(); track(host);
            if (!widget.getAsBoolean()) return result;
            try {
                Canvas canvas = (Canvas) chain.getArg(0);
                Object blur = widgetBlur.get(host);
                if (blur != null) {
                    if (!hideBlur.getBoolean(host)) {
                        Rect r = new Rect(); widgetDragBounds.invoke(host, r);
                        float scale = ((Number) widgetScale.invoke(host)).floatValue();
                        int x = (int)(r.width()*(1f-scale)/2f), y = (int)(r.height()*(1f-scale)/2f);
                        r.inset(x+(int)(host.getPaddingLeft()*scale), y+(int)(host.getPaddingTop()*scale),
                                x+(int)(host.getPaddingRight()*scale), y+(int)(host.getPaddingBottom()*scale));
                        drawSurface(host, canvas, new RectF(r), widgetRadius.getFloat(host), alpha(blur), blur);
                    }
                } else drawWidgetMask(host, canvas);
            } catch (Exception | LinkageError e) { report(host, e); }
            return result;
        }, Canvas.class);
        installer.hook(WIDGET, "onLayout", chain -> {
            Object result = chain.proceed(); masks.remove((View) chain.getThisObject()); return result;
        }, boolean.class, int.class, int.class, int.class, int.class);
        installer.hook(WIDGET, "updateAppWidget", chain -> {
            masks.remove((View) chain.getThisObject()); return chain.proceed();
        }, android.widget.RemoteViews.class);
        installer.hook(POPUP, "onLayout", chain -> {
            Object result = chain.proceed();
            View host = (View) chain.getThisObject();
            MenuContour contour = menus.get(host);
            if (contour == null) {
                contour = new MenuContour(host); menus.put(host, contour); host.getOverlay().add(contour);
            }
            contour.setBounds(0, 0, host.getWidth(), host.getHeight());
            track(host);
            suppressArrow(host, false);
            if (alignment.getAsBoolean()) alignFolderMenu(host);
            return result;
        }, boolean.class, int.class, int.class, int.class, int.class);
        installer.hook(POPUP, "showArrow", chain -> {
            Object result = chain.proceed();
            suppressArrow((View) chain.getThisObject(), true);
            return result;
        }, boolean.class);
        installer.hook(SPRING, "animateOpen", chain -> {
            Object result = chain.proceed();
            View popup = (View) springPopup.get(chain.getThisObject());
            track(popup); suppressArrow(popup, true);
            return result;
        });
        installer.hook(POPUP, "addArrow", chain -> {
            Object result = chain.proceed();
            suppressArrow((View) chain.getThisObject(), true);
            return result;
        });
        installer.hook(POPUP, "flymeOrientAboutObject", chain -> {
            Object result = chain.proceed();
            View popup = (View) chain.getThisObject(); track(popup);
            if (alignment.getAsBoolean()) alignFolderMenu(popup);
            return result;
        }, boolean.class, boolean.class);
        installer.hook("com.android.launcher3.icons.FastBitmapDrawable", "draw", chain -> {
            Object result = chain.proceed();
            Drawable drawable = (Drawable) chain.getThisObject();
            if(IconContourDrawScope.isRaw() || LauncherPreviewContourHooks.drawsTransitionIcon())return result;
            if (!(drawable.getCallback() instanceof View host) || !(bubbleTextView.isInstance(host)
                    || host instanceof ImageView && floatingIconView.isInstance(host.getParent()))) return result;
            track(host);
            if (icon.getAsBoolean()) try { drawIcon(host, drawable, (Canvas)chain.getArg(0)); }
            catch (Exception | LinkageError error) { report(host, error); }
            return result;
        }, Canvas.class);
        new LauncherIconSurfaceContourHooks(loader, settings, icon, log).install(installer, deoptimize);
        Class<?> drag = loader.loadClass("com.android.launcher3.dragndrop.DragView");
        Field dragMask = field(drag, "mScaledMaskPath");
        Method dragDraw = drag.getDeclaredMethod("draw", Canvas.class);
        installer.hook(drag.getName(), "draw", chain -> {
            Object result = chain.proceed();
            View host = (View) chain.getThisObject();
            Canvas canvas = (Canvas) chain.getArg(0);
            if (IconContourDrawScope.isRaw() || !canvas.isHardwareAccelerated()) return result;
            track(host);
            if (!icon.getAsBoolean()) return result;
            try {

                Path path = (Path) dragMask.get(host);
                if (path == null || path.isEmpty() || host.getWidth() <= 0 || host.getHeight() <= 0) return result;
                LauncherContourRenderer renderer = renderer(host);
                Bitmap mask = renderer.pathMask(host, path, host.getWidth(), host.getHeight());

                renderer.draw(host, canvas, new RectF(0, 0, host.getWidth(), host.getHeight()), 0f, 1f, mask);
            } catch (Exception | LinkageError error) { report(host, error); }
            return result;
        }, Canvas.class);
        deoptimize.accept(dragDraw);
        for (String method : new String[]{"setAlpha", "setVisible"}) {
            installer.hook("com.meizu.flyme.launcher.utils.BackgroundBlurUtils", method, chain -> {
                Object result = chain.proceed();
                if (folder.getAsBoolean() && blurView.get(chain.getThisObject()) instanceof View view
                        && folderIcon.isInstance(view)) {

                    view.postInvalidateOnAnimation();
                }
                return result;
            }, method.equals("setAlpha") ? float.class : boolean.class);
        }
        // Avoid compiled callers bypassing the small draw/layout/visibility methods.
        for (String name : new String[]{PREVIEW, WIDGET, POPUP,
                "com.android.launcher3.popup.PopupContainerWithArrow", SPRING,
                "com.meizu.flyme.launcher.popup.PopupContainerSpringAnimationSet$$ExternalSyntheticLambda0",
                "com.android.launcher3.folder.FolderIcon",
                "com.android.launcher3.widget.LauncherAppWidgetHostView",
                "com.meizu.flyme.launcher.widget.FlymeLauncherAppWidgetHostView",
                "com.android.launcher3.icons.FastBitmapDrawable", "com.android.launcher3.BubbleTextView",
                "com.meizu.flyme.launcher.utils.BackgroundBlurUtils"}) {
            for (Method method : loader.loadClass(name).getDeclaredMethods()) deoptimize.accept(method);
        }
        for (Method method : View.class.getDeclaredMethods()) {
            if (method.getName().equals("draw") || method.getName().equals("updateDisplayListIfDirty")) deoptimize.accept(method);
        }
        for (Method method : android.widget.TextView.class.getDeclaredMethods()) {
            if (method.getName().equals("onDraw")) deoptimize.accept(method);
        }
        for (Method method : ViewGroup.class.getDeclaredMethods()) {
            if (method.getName().equals("dispatchDraw") || method.getName().equals("drawChild")) deoptimize.accept(method);
        }
    }

    private void track(View host) {
        hosts.add(host);
        if (observer != null) return;
        Context ctx = host.getContext().getApplicationContext(); settings.accept(ctx);
        observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override public void onChange(boolean selfChange) {
                settings.accept(ctx); masks.clear(); nextMaskScan.clear(); iconMasks.clear();
                for (View view : new ArrayList<>(hosts)) if (view != null) {
                    if (arrow.getDeclaringClass().isInstance(view)) {
                        try { suppressArrow(view, false); if (view.isAttachedToWindow()) reorient.invoke(view); }
                        catch (ReflectiveOperationException e) { report(view, e); }
                    }
                    view.invalidate();
                }
            }
        };
        ctx.getContentResolver().registerContentObserver(ModuleSettings.URI, false, observer);
    }

    private void styleClearButton(View host) throws Exception {
        if (!clearButtons.containsKey(host)) {

            boolean circular = host.getId() != View.NO_ID && "flyme_clear_all".equals(
                    host.getResources().getResourceEntryName(host.getId()));
            clearButtons.put(host, circular ? new ClearButtonStyle() : null);
            if (circular) track(host);
        }
        ClearButtonStyle style = clearButtons.get(host);
        if (style == null) return;
        if (icon.getAsBoolean()) {
            if (style.replacement == null) {
                Context module = host.getContext().createPackageContext("dev.rikumi.flymemod", 0);
                style.replacement = module.getDrawable(R.drawable.recents_clear_all_contour).mutate();
            }
            if (host.getBackground() != style.replacement) {
                style.original = host.getBackground();
                style.tint = host.getBackgroundTintList();
                host.setBackgroundTintList(null);
                host.setBackground(style.replacement);
            }
        } else if (host.getBackground() == style.replacement && style.replacement != null) {
            host.setBackground(style.original);
            host.setBackgroundTintList(style.tint);
            style.original = null;
            style.tint = null;
        }
    }

    private static final class ClearButtonStyle {
        Drawable original, replacement;
        ColorStateList tint;
    }

    private void suppressArrow(View host, boolean nativeUpdated) throws IllegalAccessException {
        View pointer = (View) arrow.get(host);
        if (menu.getAsBoolean()) {
            if (nativeUpdated || !arrowVisibility.containsKey(host)) arrowVisibility.put(host, pointer.getVisibility());
            pointer.setVisibility(View.INVISIBLE);
        } else {
            Integer previous = arrowVisibility.remove(host);
            if (previous != null && !nativeUpdated) pointer.setVisibility(previous);
        }
    }

    private float alpha(Object blur) throws IllegalAccessException {
        return blur == null ? 1f : blurVisible.getBoolean(blur) ? blurAlpha.getFloat(blur) : 0f;
    }
    private void report(View host, Throwable error) {
        if (errors.add(host)) log.accept("Cannot render launcher background contour", error);
    }
    private static float density(View v) { return v.getResources().getDisplayMetrics().density; }
    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field f = type.getDeclaredField(name); f.setAccessible(true); return f;
    }

    private LauncherContourRenderer renderer(View host) throws Exception {
        LauncherContourRenderer renderer = renderers.get(host);
        if (renderer == null) { renderer = new LauncherContourRenderer(host); renderers.put(host, renderer); }
        return renderer;
    }
    private static final class SmoothShape {
        int width, height; float radius, smoothness; Path path;
    }
    private void drawSurface(View host, Canvas canvas, RectF bounds, float radius, float alpha, Object blur) throws Exception {
        if (bounds.isEmpty() || alpha <= 0) return;
        LauncherContourRenderer renderer = renderer(host);
        Bitmap mask = null;
        float smoothness = blur == null ? 0 : blurSmoothness.getFloat(blur);
        if (smoothness > 0) {
            SmoothShape shape = smoothShapes.computeIfAbsent(host, key -> new SmoothShape());
            int width = Math.round(bounds.width()), height = Math.round(bounds.height());
            if (shape.path == null || shape.width != width || shape.height != height
                    || shape.radius != radius || shape.smoothness != smoothness) {
                shape.path = (Path) generatePath.invoke(null, 0f, 0f, (float)width, (float)height, smoothness, radius, false);
                shape.width = width; shape.height = height; shape.radius = radius; shape.smoothness = smoothness;
            }
            mask = renderer.pathMask(host, shape.path, width, height);
        }
        renderer.draw(host, canvas, bounds, radius, alpha, mask);
    }

    private final class MenuContour extends Drawable {
        private final WeakReference<View> owner;
        MenuContour(View host) { owner = new WeakReference<>(host); }
        @Override public void draw(Canvas canvas) {
            View host = owner.get(); if (host == null || !menu.getAsBoolean()) return;
            try { renderer(host).drawMenu(host, canvas, new RectF(0, 0, host.getWidth(), host.getHeight()),
                    menuRadius.getFloat(host)); }
            catch (Exception | LinkageError e) { report(host, e); }
        }
        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter filter) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private void alignFolderMenu(View popup) {
        try {
            View folder = (View) anchorView.invoke(popup);
            if (!folderIcon.isInstance(folder) || !(popup.getParent() instanceof ViewGroup parent)) return;
            Rect rect = (Rect) folderBounds.invoke(folderPreview.invoke(folder));
            RectF visible = new RectF(rect);
            Matrix transform = new Matrix(), inverse = new Matrix();
            folder.transformMatrixToGlobal(transform); parent.transformMatrixToGlobal(inverse);
            if (!inverse.invert(inverse)) return;
            transform.postConcat(inverse); transform.mapRect(visible);
            Rect insets = (Rect) parent.getClass().getMethod("getInsets").invoke(parent);
            boolean left = leftAligned.getBoolean(popup);
            float width = popup.getMeasuredWidth();
            float start = visible.left, end = visible.right-width;
            float x = left ? start : end;
            if (x < insets.left || x+width > parent.getWidth()-insets.right) {
                float other = left ? end : start;
                if (other >= insets.left && other+width <= parent.getWidth()-insets.right) {
                    x = other; left = !left;
                }
            }
            x = Math.max(insets.left, Math.min(x, parent.getWidth()-insets.right-width));
            leftAligned.setBoolean(popup, left); popup.setX(x);
            View pointer = (View) arrow.get(popup);
            pointer.setX(visible.centerX()-pointer.getMeasuredWidth()/2f);
        } catch (ReflectiveOperationException | RuntimeException error) { report(popup, error); }
    }
    private static final class IconMask {
        final Bitmap source, mask, shadow;
        final int generation, width, height;
        final float density;
        IconMask(Bitmap source, Bitmap mask, Bitmap shadow, Rect bounds, float density) {
            this.source=source; this.mask=mask; this.shadow=shadow; generation=source.getGenerationId();
            width=bounds.width(); height=bounds.height(); this.density=density;
        }
    }
    private void drawIcon(View host, Drawable drawable, Canvas canvas) throws Exception {
        Bitmap source = (Bitmap) iconPixels.get(iconInfo.get(drawable));
        Rect bounds = drawable.getBounds();
        if (source == null || source.isRecycled() || bounds.isEmpty()) return;
        IconMask cache = iconMasks.get(drawable);
        float density = density(host);
        if (cache == null || cache.source != source || cache.generation != source.getGenerationId()
                || cache.width != bounds.width() || cache.height != bounds.height() || cache.density != density) {
            // Launcher stores the actual themed/icon-pack silhouette in BitmapInfo.icon.
            Bitmap readable = source.getConfig() == Bitmap.Config.HARDWARE ? source.copy(Bitmap.Config.ARGB_8888, false) : source;
            int w=readable.getWidth(), h=readable.getHeight();
            int[] pixels=new int[w*h]; readable.getPixels(pixels,0,w,0,0,w,h);
            if (readable != source) readable.recycle();
            Bitmap mask=Bitmap.createBitmap(IconContourPixels.mask(pixels,w,h,2f*density*w/bounds.width()),
                    w,h,Bitmap.Config.ARGB_8888);
            // Exclude the weak bitmap shadow from the inner shadow silhouette.
            for (int i=0;i<pixels.length;i++) pixels[i]=(pixels[i]>>>24)>=64 ? 0xff000000 : 0;
            Bitmap shadow=Bitmap.createBitmap(IconInnerShadow.pixels(pixels,w,h,3f*density*w/bounds.width()),
                    w,h,Bitmap.Config.ARGB_8888);
            cache = new IconMask(source,mask,shadow,bounds,density); iconMasks.put(drawable,cache);
        }
        int save=canvas.save(); float scale=iconScale.getFloat(drawable);
        canvas.scale(scale,scale,bounds.exactCenterX(),bounds.exactCenterY());
        try {
            Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            shade.setAlpha(drawable.getAlpha());
            canvas.drawBitmap(cache.shadow,null,bounds,shade);
            renderer(host).draw(host,canvas,new RectF(bounds),0f,drawable.getAlpha()/255f,cache.mask);
        } finally { canvas.restoreToCount(save); }
    }

    private static final class WidgetMask {
        final WeakReference<View> target;
        final Drawable background;
        final int width, height;
        final Bitmap edge;
        WidgetMask(View view, Bitmap edge) {
            target = new WeakReference<>(view); background = surface(view);
            width = view.getWidth(); height = view.getHeight(); this.edge = edge;
        }
    }
    private void drawWidgetMask(ViewGroup host, Canvas canvas) throws Exception {
        WidgetMask mask = masks.get(host);
        if (mask == null && masks.containsKey(host)) {
            if (android.os.SystemClock.uptimeMillis() < nextMaskScan.getOrDefault(host, 0L)) return;
            masks.remove(host);
        }
        View target = mask == null ? null : mask.target.get();
        if (target == null || surface(target) != mask.background
                || target.getWidth() != mask.width || target.getHeight() != mask.height) {
            List<View> candidates = new ArrayList<>(); collect(host, candidates);
            candidates.sort((a, b) -> Long.compare((long)b.getWidth()*b.getHeight(), (long)a.getWidth()*a.getHeight()));
            mask = null;
            for (View v : candidates) {
                Bitmap edge = backgroundMask(v);
                if (edge != null) { mask = new WidgetMask(v, edge); break; }
            }
            if (mask == null) {
                masks.put(host, null); nextMaskScan.put(host, android.os.SystemClock.uptimeMillis()+500L); return;
            }
            masks.put(host, mask); target = mask.target.get();
        }
        if (target == null || !target.isShown()) return;
        Matrix transform = new Matrix(), inverse = new Matrix();
        target.transformMatrixToGlobal(transform); host.transformMatrixToGlobal(inverse);
        if (!inverse.invert(inverse)) return;
        transform.postConcat(inverse);
        float alpha = 1f;
        for (View v = target; v != host; ) {
            alpha *= v.getAlpha(); if (!(v.getParent() instanceof View parent)) return; v = parent;
        }
        int save = canvas.save();
        try {
            if (pressedWidget.isInstance(host) && target != host) {
                // Flyme drawChild applies this scale outside the child View matrix.
                View content = target;
                while (content.getParent() instanceof View parent && parent != host) content = parent;
                if (!widgetTitle.isInstance(content) && !customWidgetTag.equals(content.getTag())) {
                    Rect pivot = new Rect(); pressedWidgetBounds.invoke(host, content, pivot);
                    float scale = ((Number)widgetScale.invoke(host)).floatValue();
                    canvas.scale(scale, scale, pivot.exactCenterX(), pivot.exactCenterY());
                }
            }
            canvas.concat(transform);
            renderer(host).draw(host, canvas, new RectF(0, 0, mask.width, mask.height), 0f, alpha, mask.edge);
        } finally { canvas.restoreToCount(save); }
    }
    private static void collect(View v, List<View> candidates) {
        if (v.getVisibility() != View.VISIBLE || v.getAlpha() <= 0) return;
        if (surface(v) != null && v.getWidth() > 0 && v.getHeight() > 0) candidates.add(v);
        if (v instanceof ViewGroup group) for (int i=0; i<group.getChildCount(); i++) collect(group.getChildAt(i), candidates);
    }
    private static Drawable surface(View view) {
        Drawable background = view.getBackground();
        return background != null ? background : view instanceof ImageView image ? image.getDrawable() : null;
    }
    private static Bitmap backgroundMask(View view) {
        Drawable drawable = surface(view);
        float scale = Math.min(1f, 512f / Math.max(view.getWidth(), view.getHeight()));
        int w = Math.max(1, Math.round(view.getWidth()*scale)), h = Math.max(1, Math.round(view.getHeight()*scale));
        Bitmap sample = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(sample); canvas.scale(scale, scale);
        Rect saved = new Rect(drawable.getBounds());
        try {
            if (view.getBackground() == null && view instanceof ImageView image) {
                canvas.translate(view.getPaddingLeft(), view.getPaddingTop());
                canvas.concat(image.getImageMatrix());
                drawable.draw(canvas);
            } else {
                drawable.setBounds(0, 0, view.getWidth(), view.getHeight()); drawable.draw(canvas);
            }
        } finally { drawable.setBounds(saved); }
        int[] pixels = new int[w*h]; sample.getPixels(pixels, 0, w, 0, 0, w, h); sample.recycle();
        int filled = 0;
        for (int pixel : pixels) if ((pixel >>> 24) >= 8) filled++;
        // Both opaque and translucent backgrounds qualify; sparse glyphs do not.
        if (filled < pixels.length*.5) return null;
        // Only encode inward distance and shape coverage. The existing native-path shader supplies all lighting.
        return distanceMask(pixels, w, h, 2f*density(view)*scale);
    }
    static Bitmap distanceMask(int[] pixels, int w, int h, float stroke) {

        float saturatedDistance = stroke + .5f;
        float[] distance = new float[pixels.length];
        for (int y=0; y<h; y++) for (int x=0; x<w; x++) {
            int i=y*w+x;
            distance[i] = (pixels[i]>>>24)<8 ? 0 : Math.min(saturatedDistance, Math.min(Math.min(x+1,w-x),Math.min(y+1,h-y)));
        }
        for (int y=0; y<h; y++) for (int x=0; x<w; x++) {
            int i=y*w+x; float d=distance[i];
            if (x>0) d=Math.min(d,distance[i-1]+1);
            if (y>0) {
                d=Math.min(d,distance[i-w]+1);
                if (x>0) d=Math.min(d,distance[i-w-1]+1.41421356f);
                if (x+1<w) d=Math.min(d,distance[i-w+1]+1.41421356f);
            }
            distance[i]=d;
        }
        for (int y=h-1; y>=0; y--) for (int x=w-1; x>=0; x--) {
            int i=y*w+x; float d=distance[i];
            if (x+1<w) d=Math.min(d,distance[i+1]+1);
            if (y+1<h) {
                d=Math.min(d,distance[i+w]+1);
                if (x>0) d=Math.min(d,distance[i+w-1]+1.41421356f);
                if (x+1<w) d=Math.min(d,distance[i+w+1]+1.41421356f);
            }
            distance[i]=d;
        }
        int[] mask = new int[pixels.length];
        for (int i=0; i<mask.length; i++) if ((pixels[i]>>>24)>=8) {
            int red = Math.round(255f*Math.min(1f,Math.max(0f,distance[i]-.5f)/stroke));
            mask[i] = 0xFF000000 | (red<<16);
        }
        return Bitmap.createBitmap(mask, w, h, Bitmap.Config.ARGB_8888);
    }
}
