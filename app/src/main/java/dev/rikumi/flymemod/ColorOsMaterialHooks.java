package dev.rikumi.flymemod;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.Build;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.SeekBar;
import android.widget.ImageView;
import android.view.ViewTreeObserver;
import java.lang.ref.WeakReference;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.*;

/** ColorOS AGSL algorithms, with Flyme 16260625 view-local contour overlays. */
final class ColorOsMaterialHooks {
    // View-owned identity survives repeated hook installation, including different
    // module class loaders. Each tile may expose exactly one contour drawable.
    private static final int TILE_CONTOUR_TAG = 0x7e464d01;
    private static final Map<Class<?>, Map<String, Optional<Field>>> FIELD_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<android.content.res.Resources, Map<String, Integer>> resourceIds = new WeakHashMap<>();
    private final TileCornerHooks corners;
    private final Consumer<Context> settings;
    private final BooleanSupplier contourEnabled, splitNetworkEnabled, notificationEnabled;
    private final IntSupplier notificationOpacity;
    private final Map<ImageView, ButtonStyle> notificationButtons = new WeakHashMap<>();
    private final Map<ImageView, DeleteBadgeDrawable> deleteBadges = new WeakHashMap<>();
    private final Map<View, Contour> notificationContours = new WeakHashMap<>();
    private final Set<View> notificationErrors = Collections.newSetFromMap(new WeakHashMap<>());
    private final BiConsumer<String, Throwable> log;
    private final Map<View, Host> hosts = new WeakHashMap<>();
    private final Set<View> mediaViews = Collections.newSetFromMap(new WeakHashMap<>());
    private final ClassLoader loader;
    private final MediaSwipeCornerHooks mediaSwipeCorners;
    private final Map<String, String> sources = new HashMap<>();
    private Context assets;
    private final Class<?> notificationOutline;
    private final Method childNeedsClipping, customChildClipPath, notificationClipPath;

    ColorOsMaterialHooks(ClassLoader loader, Consumer<Context> settings,
            BooleanSupplier contour, BooleanSupplier splitNetwork, BooleanSupplier notification, IntSupplier notificationOpacity,
            TileCornerHooks corners, ToDoubleFunction<View> notificationRadius, BiConsumer<String, Throwable> log)
            throws ReflectiveOperationException {
        this.corners = corners; this.loader = loader; this.settings = settings; contourEnabled = contour; splitNetworkEnabled = splitNetwork; notificationEnabled = notification; this.log = log;
        this.notificationOpacity = notificationOpacity;
        mediaSwipeCorners = new MediaSwipeCornerHooks(loader, settings,
                view -> notificationEnabled.getAsBoolean() ? notificationRadius.applyAsDouble(view) : Double.NaN);
        notificationOutline = loader.loadClass("com.android.systemui.statusbar.notification.row.ExpandableOutlineView");
        childNeedsClipping = notificationOutline.getMethod("childNeedsClipping",View.class);
        customChildClipPath = notificationOutline.getMethod("getCustomClipPath",View.class);
        notificationClipPath = notificationOutline.getMethod("getClipPath",boolean.class);

    }

    void install(ClassLoader loader, SignalHooks.Installer installer) throws ReflectiveOperationException {
        if (Build.VERSION.SDK_INT < 33) return;
        mediaSwipeCorners.install(installer);
        String tile = "com.flyme.systemui.controlcenter.qs.UnifiedQSTileView";
        loader.loadClass(tile).getDeclaredMethod("getMDeleteBadge");
        installer.hook(tile, "getMDeleteBadge", chain -> {
            Object result = chain.proceed();
            if (result instanceof ImageView badge && !deleteBadges.containsKey(badge)) {
                settings.accept(badge.getContext());
                DeleteBadgeDrawable styled = new DeleteBadgeDrawable(badge, badge.getDrawable());
                deleteBadges.put(badge, styled);
                badge.setImageDrawable(styled);
            }
            return result;
        });
        String stack = "com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout";
        for (String method : new String[]{"setDismissView", "setFilterDismissView"}) {
            loader.loadClass(stack).getDeclaredMethod(method, ImageView.class);
            installer.hook(stack, method, chain -> {
                Object result = chain.proceed();
                if (chain.getArg(0) instanceof ImageView button) observeButton(button);
                return result;
            }, ImageView.class);
        }
        String menu = "com.android.systemui.statusbar.notification.row.NotificationMenuRow$NotificationMenuItem";
        loader.loadClass(menu).getDeclaredMethod("getMenuView");
        installer.hook(menu, "getMenuView", chain -> {
            Object result = chain.proceed();
            if (result instanceof ImageView button && "deletenotification".equals(button.getTag())) observeButton(button);
            return result;
        });
        View.class.getDeclaredMethod("invalidateOutline");
        installer.hook("android.view.View", "invalidateOutline", chain -> {
            Object result = chain.proceed();
            View view = (View) chain.getThisObject();
            Host host = hosts.get(view);
            if (host != null && host.outline != null) {
                host.outline.maskWidth = 0;
                host.outline.invalidateSelf();
            }
            return result;
        });
        // Render on the background view itself, below notification content, using
        // the bounds produced by native drawing (including expand/clip geometry).
        installer.hook("com.android.systemui.statusbar.notification.row.NotificationBackgroundView", "onDraw", chain -> {
            Object result = chain.proceed();
            View view = (View) chain.getThisObject();
            settings.accept(view.getContext());
            if (!notificationEnabled.getAsBoolean()) {
                notificationContours.put(view, null);
                return result;
            }
            try {
                Drawable background = (Drawable) field(view, "mBackground");
                if (background == null || background.getBounds().isEmpty()) return result;
                Rect bounds = background.getBounds();
                Canvas canvas = (Canvas) chain.getArg(0);
                int saved = canvas.save();
                try {
                    if (!(Boolean) field(view, "mExpandAnimationRunning")) {
                        int height = ((Number) field(view, "mActualHeight")).intValue();
                        if (height < 0) height = view.getHeight();
                        canvas.clipRect(0, ((Number) field(view, "mClipTopAmount")).intValue(), view.getWidth(),
                                height - ((Number) field(view, "mClipBottomAmount")).intValue());
                    }
                    if ((Boolean) field(view, "mDrawDismissButtonCutout")) {
                        canvas.clipPath((Path) view.getClass().getMethod("calculateDismissButtonCutoutPath", Rect.class)
                                .invoke(view, bounds));
                    }
                    Contour contour = notificationContours.get(view);
                    if (contour == null) {
                        contour = new Contour(view);
                        notificationContours.put(view, contour);
                    }
                    Rect visible = contour.notificationBounds(view,canvas,bounds);
                    if (visible.isEmpty()) return result;
                    contour.setBounds(0, 0, visible.width(), visible.height());
                    contour.setAlpha(((Number) field(view, "mDrawableAlpha")).intValue());
                    canvas.translate(visible.left, visible.top);
                    contour.draw(canvas);
                } finally { canvas.restoreToCount(saved); }
            } catch (Exception | LinkageError error) {
                // Use the same once-per-host logging policy as control-center contours.
                if (notificationErrors.add(view)) log.accept("Cannot render ColorOS notification contour", error);
            }
            return result;
        }, Canvas.class);
        String notificationRow = "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow";
        Class<?> notificationRowClass = loader.loadClass(notificationRow);
        Method swipeTranslation = notificationRowClass.getDeclaredMethod("getTranslation");
        notificationRowClass.getDeclaredMethod("setTranslation",float.class);
        notificationRowClass.getDeclaredMethod("resetTranslation");
        for (String method : new String[]{"setTranslation", "setTranslationX", "resetTranslation"}) {
            installer.hook(notificationRow, method, chain -> {
                View row = (View)chain.getThisObject();
                if (!notificationEnabled.getAsBoolean()) return chain.proceed();
                float previous = ((Number)swipeTranslation.invoke(row)).floatValue();
                float previousRow = row.getTranslationX();
                Object result = chain.proceed();
                if (((Number)swipeTranslation.invoke(row)).floatValue()!=previous || row.getTranslationX()!=previousRow)
                    invalidateNotificationContours(row);
                return result;
            }, method.equals("resetTranslation") ? new Class<?>[0] : new Class<?>[]{float.class});
        }
        Class<?> state = loader.loadClass("com.android.systemui.plugins.qs.QSTile$State");
        for (String name : new String[]{"com.android.systemui.qs.tileimpl.QSTileViewImpl",
                "com.flyme.systemui.qs.tileimpl.FlymeCustomQSTileView"}) {
            installer.hook(name, "updateResources", chain -> {
                Object result = chain.proceed(); track((View) chain.getThisObject()); return result;
            });
            installer.hook(name, "handleStateChanged", chain -> {
                Object result = chain.proceed(); track((View) chain.getThisObject()); return result;
            }, state);
        }
        // Controllers supply the two card roots after XML inflation; initialize() binds the sliders later.
        for (String panel : new String[]{"com.flyme.systemui.controlcenter.phone.MzQSPanel",
                "com.flyme.systemui.controlcenter.phone.MzQQSPanel"}) {
            Class<?> panelClass = loader.loadClass(panel);
            panelClass.getDeclaredMethod("initialize");
            panelClass.getField("mBrightnessView"); panelClass.getField("mVolumeView");
            installer.hook(panel, "initialize", chain -> {
                Object result = chain.proceed(); Object owner = chain.getThisObject();
                if (field(owner, "mConnectivityTilesWrapper") instanceof View wrapper) track(wrapper, 1);
                for (String name : new String[]{"mBrightnessView", "mVolumeView"})
                    if (field(owner, name) instanceof View card) track(card);
                return result;
            });
            for (String setter : new String[]{"setBrightnessView", "setVolumeView"}) {
                panelClass.getDeclaredMethod(setter, View.class);
                installer.hook(panel, setter, chain -> {
                    Object result = chain.proceed();
                    if (chain.getArg(0) instanceof View card) track(card);
                    return result;
                }, View.class);
            }
        }
        installer.hook("com.flyme.systemui.media.controls.ui.view.MediaCarouseTransitionLayout", "setBackground", chain -> {
            Object result = chain.proceed(); track((View) chain.getThisObject()); return result;
        });
        installer.hook("com.android.systemui.util.animation.TransitionLayout", "applyCurrentState", chain -> {
            Object result = chain.proceed();
            View view = (View) chain.getThisObject();
            if (isMedia(view)) apply(view, false); // updateBounds bypasses ordinary layout listeners.
            return result;
        });
        String mediaHandler = "com.android.systemui.media.controls.ui.view.MediaCarouselScrollHandler";
        for (String method : new String[]{"setContentTranslation", "onMediaScrollingChanged",
                "setCarouselBounds", "updateClipToOutline"}) {
            for (Method target : loader.loadClass(mediaHandler).getDeclaredMethods()) {
                if (!target.getName().equals(method)) continue;
                installer.hook(mediaHandler, method, chain -> {
                    Object result = chain.proceed();
                    invalidateMediaWithin((View) field(chain.getThisObject(), "scrollView"));
                    return result;
                }, target.getParameterTypes());
            }
        }
        String mediaContainer = "com.android.systemui.statusbar.notification.stack.MediaContainerView";
        installer.hook(mediaContainer, "updateClipping", chain -> {
            View view = (View) chain.getThisObject();
            int previous = ((Number) field(view, "clipHeight")).intValue();
            Object result = chain.proceed();
            if (previous != ((Number) field(view, "clipHeight")).intValue()) invalidateMediaWithin(view);
            return result;
        });
        for (String method : new String[]{"setCustomScaleX", "setCustomScaleY", "setCustomTranslationY", "setTranslationY"}) {
            installer.hook(mediaContainer, method, chain -> {
                View view = (View) chain.getThisObject();
                float oldX = view.getScaleX(), oldY = view.getScaleY(), oldTranslation = view.getTranslationY();
                Object result = chain.proceed();
                if (oldX != view.getScaleX() || oldY != view.getScaleY() || oldTranslation != view.getTranslationY())
                    invalidateMediaWithin(view);
                return result;
            }, float.class);
        }
        installer.hook("com.android.systemui.statusbar.notification.row.ExpandableView", "onLayout", chain -> {
            Object result = chain.proceed();
            View view = (View) chain.getThisObject();
            if (view.getClass().getName().equals(mediaContainer)) invalidateMediaWithin(view);
            return result;
        }, boolean.class, int.class, int.class, int.class, int.class);
        for (String method : new String[]{"updateRoundedClipPath", "updateUseRoundedRectClipping"}) {
            installer.hook(stack, method, chain -> {
                Object result = chain.proceed(); invalidateMediaWithin((View) chain.getThisObject()); return result;
            });
        }
    }

    void refresh() {
        mediaSwipeCorners.refresh();
        for (View view : new ArrayList<>(hosts.keySet())) if (view != null) apply(view);
        for (View view : new ArrayList<>(notificationContours.keySet())) if (view != null) view.invalidate();
        for (ButtonStyle button : new ArrayList<>(notificationButtons.values())) button.update();
        for (DeleteBadgeDrawable badge : new ArrayList<>(deleteBadges.values())) badge.invalidateSelf();
    }

    private void observeButton(ImageView view) {
        ButtonStyle style = notificationButtons.get(view);
        if (style == null) {
            style = new ButtonStyle(view);
            notificationButtons.put(view, style);
            view.addOnAttachStateChangeListener(style);
            if (view.isAttachedToWindow()) style.onViewAttachedToWindow(view);
        }
        style.update();
    }

    private static boolean isMedia(View view) {
        return view.getClass().getName().equals("com.flyme.systemui.media.controls.ui.view.MediaCarouseTransitionLayout");
    }
    private void invalidateMediaWithin(View ancestor) {
        if (!notificationEnabled.getAsBoolean()) return;
        for (View media : new ArrayList<>(mediaViews)) {
            for (android.view.ViewParent parent = media.getParent(); parent instanceof View view; parent = view.getParent()) {
                if (view == ancestor) {
                    Host host = hosts.get(media);
                    if (host != null && host.outline != null) host.outline.invalidateSelf();
                    media.invalidate(); break;
                }
            }
        }
    }

    private void invalidateNotificationContours(View row) throws ReflectiveOperationException {
        invalidateMediaWithin(row);
        // Flyme's content-only swipe changes child RenderNodes without rebuilding
        // their display lists. Redraw the affected backgrounds, not the whole stack.
        for (String name : new String[]{"mBackgroundNormal", "mBackgroundFlyme"})
            if (field(row,name) instanceof View background) background.invalidate();
        Object collapsed = field(row,"mGroupCollapse");
        if (collapsed != null && field(collapsed,"mBackgroundFlyme") instanceof View background)
            background.invalidate();
    }

    private final class ButtonStyle implements View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
        final WeakReference<ImageView> owner;
        Drawable original;
        NotificationButtonDrawable styled;
        ViewTreeObserver observer;
        int previousOpacity = -1;
        boolean failed;
        ButtonStyle(ImageView view) { owner = new WeakReference<>(view); }
        public void onViewAttachedToWindow(View view) {
            if(observer==view.getViewTreeObserver() && observer.isAlive())return;
            observer = view.getViewTreeObserver(); observer.addOnPreDrawListener(this); update();
        }
        public void onViewDetachedFromWindow(View view) {
            if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(this);
            observer = null;
        }
        public boolean onPreDraw() { update(); return true; }
        void update() {
            ImageView view = owner.get(); if (view == null || failed) return;
            settings.accept(view.getContext());
            if (!notificationEnabled.getAsBoolean()) {
                if (styled != null && view.getDrawable() == styled) view.setImageDrawable(original);
                styled = null; original = null; previousOpacity = -1;
                return;
            }
            try {
                if (view.getDrawable() != styled || styled == null) {
                    original = view.getDrawable();
                    styled = new NotificationButtonDrawable(view, original);
                    view.setImageDrawable(styled);
                    previousOpacity = -1;
                }
                int opacity = Math.max(0, Math.min(ModuleSettings.LIGHT_OPACITY_MAX, notificationOpacity.getAsInt()));
                if (opacity != previousOpacity) {
                    styled.backgroundAlpha = Math.round(opacity * 255f / 100f);
                    styled.invalidateSelf(); previousOpacity = opacity;
                }
            } catch (Exception | LinkageError error) {
                failed = true;
                log.accept("Cannot style notification dismiss button", error);
            }
        }
    }

    /** Keep the native 24-unit delete hit area, 18-unit disk and white minus. */
    private final class DeleteBadgeDrawable extends Drawable {
        final WeakReference<View> owner;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final int width, height;
        Contour contour;
        int alpha = 255;
        boolean failed;
        DeleteBadgeDrawable(View view, Drawable original) {
            owner = new WeakReference<>(view);
            int size = Math.round(24f * view.getResources().getDisplayMetrics().density);
            width = original != null && original.getIntrinsicWidth() > 0 ? original.getIntrinsicWidth() : size;
            height = original != null && original.getIntrinsicHeight() > 0 ? original.getIntrinsicHeight() : size;
        }
        @Override public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            View view = owner.get();
            if (view == null || bounds.isEmpty()) return;
            int saved = canvas.save();
            if (alpha < 255) canvas.saveLayerAlpha(bounds.left, bounds.top, bounds.right, bounds.bottom, alpha);
            canvas.translate(bounds.left, bounds.top);
            float sx = bounds.width() / 24f, sy = bounds.height() / 24f;
            paint.setColor(0xff808080);
            canvas.drawOval(3f * sx, 3f * sy, 21f * sx, 21f * sy, paint);
            if (contourEnabled.getAsBoolean() && !failed && canvas.isHardwareAccelerated()) {
                int stroke = canvas.save();
                try {
                    if (contour == null) contour = new Contour(view, true);
                    canvas.translate(3f * sx, 3f * sy);
                    contour.setBounds(0, 0, Math.round(18f * sx), Math.round(18f * sy));
                    contour.draw(canvas);
                } catch (Exception | LinkageError error) {
                    failed = true;
                    log.accept("Cannot render control-center delete badge contour", error);
                } finally { canvas.restoreToCount(stroke); }
            }
            paint.setColor(Color.WHITE);
            canvas.drawRoundRect(7f * sx, 11f * sy, 17f * sx, 13f * sy, .5f * sx, .5f * sy, paint);
            canvas.restoreToCount(saved);
        }
        @Override public int getIntrinsicWidth() { return width; }
        @Override public int getIntrinsicHeight() { return height; }
        @Override public void setAlpha(int value) { alpha = value; invalidateSelf(); }
        @Override public int getAlpha() { return alpha; }
        @Override public void setColorFilter(ColorFilter filter) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** Same 48-unit circle and cross geometry as native ic_btn_dismiss_all. */
    private final class NotificationButtonDrawable extends Drawable {
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), cross = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Contour contour;
        final int width, height;
        int backgroundAlpha, alpha = 255;
        NotificationButtonDrawable(View view, Drawable original) throws Exception {
            int nativeSize = Math.round(48f * view.getResources().getDisplayMetrics().density);
            width = original != null && original.getIntrinsicWidth() > 0 ? original.getIntrinsicWidth() : nativeSize;
            height = original != null && original.getIntrinsicHeight() > 0 ? original.getIntrinsicHeight() : nativeSize;
            contour = new Contour(view, true);
            cross.setColor(Color.WHITE); cross.setStrokeWidth(3f); cross.setStrokeCap(Paint.Cap.ROUND);
        }
        public void draw(Canvas canvas) {
            Rect bounds = getBounds(); if (bounds.isEmpty()) return;
            int saved = canvas.save();
            int layer = alpha < 255 ? canvas.saveLayerAlpha(bounds.left,bounds.top,bounds.right,bounds.bottom,alpha) : -1;
            canvas.translate(bounds.left,bounds.top); canvas.scale(bounds.width()/48f,bounds.height()/48f);
            fill.setColor(Color.argb(backgroundAlpha,255,255,255)); canvas.drawCircle(24f,24f,23f,fill);
            // Draw in view pixels before the icon's local scaling, keeping the
            // contour's physical stroke width consistent with notification rows.
            int strokeSave = canvas.save();
            canvas.translate(1f,1f);
            canvas.scale(48f/bounds.width(),48f/bounds.height());
            contour.setBounds(0,0,Math.round(bounds.width()*46f/48f),Math.round(bounds.height()*46f/48f));
            contour.draw(canvas); canvas.restoreToCount(strokeSave);
            canvas.drawLine(18.35f,18.35f,29.65f,29.65f,cross);
            canvas.drawLine(18.35f,29.65f,29.65f,18.35f,cross);
            if (layer != -1) canvas.restoreToCount(layer);
            canvas.restoreToCount(saved);
        }
        public int getIntrinsicWidth() { return width; }
        public int getIntrinsicHeight() { return height; }
        public void setAlpha(int value) { if (alpha != value) { alpha = value; invalidateSelf(); } }
        public void setColorFilter(ColorFilter filter) { } // Native tint must not recolor the white cross.
        public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    // Invoked after SplitNetworkCardHooks has applied/restored each row's native background.
    void updateNetworkRows(View card) {
        try {
            track(card, 1);
            for (String name : new String[]{"wifiContainer", "bluetoothContainer"}) {
                if (field(card, name) instanceof View row) {
                    if (row.getParent() instanceof View parent && parent != card) track(parent, 1);
                    track(row, 2);
                }
            }
        } catch (ReflectiveOperationException error) { log.accept("Cannot bind split network contours", error); }
    }

    void observeDialog(View view) { track(view, 3); }
    private void track(View view) { track(view, 0); }
    private void track(View view, int networkRole) {
        settings.accept(view.getContext());
        if (isMedia(view)) mediaViews.add(view);
        if (corners != null && networkRole != 3 && !view.getClass().getName().endsWith(".MediaCarouseTransitionLayout")) corners.observe(view, networkRole);
        if (!hosts.containsKey(view)) {
            hosts.put(view, new Host());
            view.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> apply(v, false));
            view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                public void onViewAttachedToWindow(View v) { apply(v); }
                public void onViewDetachedFromWindow(View v) { remove(v); }
            });
        }
        if (networkRole != 0) hosts.get(view).networkRole = networkRole;
        apply(view);
    }

    private void apply(View view) {
        apply(view, true);
    }
    private void apply(View view, boolean invalidateMask) {
        if (Build.VERSION.SDK_INT < 33) return;
        Host host = hosts.get(view);
        if (host == null) return;
        try {
            boolean split = splitNetworkEnabled.getAsBoolean();
            boolean visible = host.networkRole == 1 ? !split : host.networkRole != 2 || split;
            boolean enabled = isMedia(view) ? notificationEnabled.getAsBoolean() : contourEnabled.getAsBoolean();
            if (!enabled || !visible) {
                remove(view);
            } else {
                if (host.outline == null) host.outline = new Contour(view);
                Object previous = view.getTag(TILE_CONTOUR_TAG);
                if (previous != host.outline) {
                    if (previous instanceof Drawable drawable) view.getOverlay().remove(drawable);
                    view.setTag(TILE_CONTOUR_TAG, host.outline);
                }
                // Native transitions may clear the overlay. An attached drawable
                // already has a callback and must not be detached on every refresh.
                if (host.outline.getCallback() == null) view.getOverlay().add(host.outline);
                // Ordinary layout reuses the mask when its size, radius, provider and
                // path generation match. Settings refresh still forces regeneration.
                if (invalidateMask) host.outline.maskWidth = 0;
                host.outline.setBounds(0, 0, view.getWidth(), view.getHeight());
                host.outline.invalidateSelf();
            }

        } catch (Exception | LinkageError error) { fail(view, error); }
    }

    private void remove(View view) {
        Host host = hosts.get(view);
        if (host == null) return;
        if (host.outline != null) {
            host.outline.renderCache.clear();
            view.getOverlay().remove(host.outline);
            if (view.getTag(TILE_CONTOUR_TAG) == host.outline) view.setTag(TILE_CONTOUR_TAG, null);
            host.outline = null;
        }

    }
    private void fail(View view, Throwable error) {
        Host host = hosts.get(view);
        if (host != null && !host.errorLogged) {
            host.errorLogged = true;
            log.accept("Cannot render ColorOS contour for " + view.getClass().getName(), error);
        }
    }
    private String source(View view, String name) throws Exception {
        if (sources.containsKey(name)) return sources.get(name);
        if (assets == null) assets = view.getContext().createPackageContext("dev.rikumi.flymemod", 0);
        try (java.io.InputStream input = assets.getAssets().open("coloros_material/" + name + ".agsl")) {
            String value = new String(input.readAllBytes(), StandardCharsets.UTF_8); sources.put(name, value); return value;
        }
    }
    private static Field cachedField(Class<?> type, String name) {
        return FIELD_CACHE.computeIfAbsent(type, key -> new java.util.concurrent.ConcurrentHashMap<>())
                .computeIfAbsent(name, key -> {
            try { return Optional.of(type.getField(key)); }
            catch (NoSuchFieldException missing) { return Optional.empty(); }
        }).orElse(null);
    }
    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        Field field = cachedField(owner.getClass(), name);
        if (field == null) throw new NoSuchFieldException(name);
        return field.get(owner);
    }
    private static Object optionalField(Object owner, String name) throws IllegalAccessException {
        Field field = cachedField(owner.getClass(), name);
        return field == null ? null : field.get(owner);
    }
    private int resourceId(View view, String name, String type) {
        android.content.res.Resources resources = view.getResources();
        return resourceIds.computeIfAbsent(resources, key -> new HashMap<>())
                .computeIfAbsent(type + '/' + name,
                        key -> resources.getIdentifier(name, type, "com.android.systemui"));
    }
    private static boolean night(View view) { return (view.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES; }
    private static Drawable nativeFill(View view, Drawable fallback) {
        // NotificationBackgroundView draws mBackground itself; it does not
        // assign that drawable to View.getBackground().
        if (view.getClass().getName().endsWith(".NotificationBackgroundView")) {
            try { if (field(view, "mBackground") instanceof Drawable drawable) return drawable; }
            catch (ReflectiveOperationException ignored) { }
        }
        try { if (optionalField(view, "backgroundDrawable") instanceof Drawable drawable) return drawable; }
        catch (ReflectiveOperationException ignored) { }
        return fallback;
    }
    private Drawable surface(View view, Drawable drawable) {
        drawable = nativeFill(view, drawable);
        if (drawable instanceof LayerDrawable layers) {
            int id = resourceId(view, "background", "id");
            Drawable surface = layers.findDrawableByLayerId(id);
            if (surface != null) drawable = surface;
        }
        while (drawable instanceof DrawableWrapper wrapper && wrapper.getDrawable() != null) drawable = wrapper.getDrawable();
        if (view instanceof SeekBar seek && seek.getProgressDrawable() instanceof LayerDrawable layers) {
            Drawable background = layers.findDrawableByLayerId(android.R.id.background);
            if (background != null) drawable = background;
        }
        return drawable;
    }
    private float radius(View view, Drawable drawable) {
        Host host = hosts.get(view);
        if (host != null && host.networkRole == 3 && drawable != null) {
            Outline outline = new Outline();
            drawable.getOutline(outline);
            if (!outline.isEmpty()) return outline.getRadius();
        }
        if (corners != null && corners.isEnabled() && !(hosts.containsKey(view) && hosts.get(view).networkRole == 3) && !view.getClass().getName().endsWith(".NotificationBackgroundView")
                && !view.getClass().getName().endsWith(".MediaCarouseTransitionLayout"))
            return corners.radius(view, view.getWidth(), view.getHeight());
        // Both slider cards use the root's native outline rather than the progress Drawable's rectangle.
        if (view.getClass().getName().equals("com.android.systemui.settings.brightness.BrightnessSliderView")) {
            int id = resourceId(view, "qs_corner_radius", "dimen");
            if (id != 0) return view.getResources().getDimension(id);
        }
        if (drawable instanceof GradientDrawable gradient && gradient.getCornerRadius() > 0) return gradient.getCornerRadius();
        if (drawable != null) {
            try { if (optionalField(drawable, "radius") instanceof Number value) return value.floatValue(); }
            catch (ReflectiveOperationException ignored) { }
        }
        try { if (optionalField(view, "mClipCornerRadius") instanceof Number value) return value.floatValue(); }
        catch (ReflectiveOperationException ignored) { }
        return 14f * view.getResources().getDisplayMetrics().density;
    }
    private static final class Host { Contour outline; int networkRole; boolean errorLogged; }

    private final class Contour extends Drawable {
        final WeakReference<View> owner;
        final boolean circularButton;
        final RuntimeShader round, notificationRound, nativeCurve;
        final Rect visibleBounds = new Rect(), canvasClip = new Rect(), outlineRect = new Rect();
        final RectF mappedClip = new RectF();
        final Matrix toAncestor = new Matrix(), fromAncestor = new Matrix();
        final Outline ancestorOutline = new Outline();
        final Method generatePath;
        final Field outlinePath;
        final MediaContourGeometry mediaGeometry;
        ViewOutlineProvider maskProvider;
        int maskWidth, maskHeight;
        float maskRadius = -1, maskStroke;
        boolean maskSmooth;
        Path maskSource;
        int maskGeneration;
        float maskSmoothness;
        final Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final ContourMaskCache renderedMask = new ContourMaskCache();
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final ContourRenderCache renderCache = new ContourRenderCache();
        long shaderGeneration;
        int alpha = 255;
        RuntimeShader configuredShader;
        int configuredWidth, configuredHeight;
        float configuredRadius, configuredStroke;
        boolean configuredNight;
        final float[] configuredCorners = {Float.NaN, Float.NaN, Float.NaN, Float.NaN};
        final float[] nearParams = new float[]{0, .4f, .2f, .2f, 0, 0};
        final float[] farParams = new float[]{0, .2f, .2f, .2f, 0, 0};
        Contour(View view) throws Exception { this(view, false); }
        Contour(View view, boolean circularButton) throws Exception {
            owner = new WeakReference<>(view);
            this.circularButton = circularButton;
            mediaGeometry = isMedia(view) ? new MediaContourGeometry(loader) : null;
            round = new RuntimeShader(source(view, "contour_round"));
            notificationRound = new RuntimeShader(source(view,"contour_round")
                    .replace("uniform float2 u_size;", "uniform float4 u_notificationCorners; uniform float2 u_size;")
                    .replace("float corner = min(inputParams.corner, min(halfSize.x, halfSize.y));",
                            "float corner = min(pos.x < 0.0 ? (pos.y < 0.0 ? u_notificationCorners.x : u_notificationCorners.w)"
                            + " : (pos.y < 0.0 ? u_notificationCorners.y : u_notificationCorners.z), min(halfSize.x, halfSize.y));"));
            {
                nativeCurve = new RuntimeShader(source(view, "contour_native_path"));
                Class<?> generator = loader.loadClass("com.meizu.common.util.SmoothCornerPathGenerator");
                generatePath = generator.getMethod("genSmoothCornerPath", float.class, float.class,
                        float.class, float.class, float.class, float.class, boolean.class);
                // Verified against the target Flyme framework Outline: public Path mPath.
                outlinePath = Outline.class.getField("mPath");
            }
        }
        public void draw(Canvas canvas) {
            View view = owner.get(); Rect bounds = getBounds();
            if (view == null || bounds.isEmpty()) return;
            // A stale display list can still reference a displaced drawable. Only
            // the current tile owner may draw; notification contours are direct draws.
            if (!circularButton && !view.getClass().getName().endsWith(".NotificationBackgroundView")
                    && view.getTag(TILE_CONTOUR_TAG) != this) return;
            int mediaSave = mediaGeometry == null ? -1 : canvas.save();
            try {
                if (mediaGeometry != null) {
                    if (!notificationEnabled.getAsBoolean() || !mediaGeometry.update(view, canvas,
                            ((Number) field(view, "mClipCornerRadius")).floatValue())) return;
                    canvas.translate(mediaGeometry.visible.left, mediaGeometry.visible.top);
                    bounds = mediaGeometry.local;
                }
                Drawable surface = surface(view, view.getBackground());
                float radius = circularButton ? Math.min(bounds.width(),bounds.height()) / 2f
                        : Math.min(radius(view, surface), Math.min(bounds.width(), bounds.height()) / 2f);
                RuntimeShader shader;
                boolean slider = view.getClass().getName().equals("com.android.systemui.settings.brightness.BrightnessSliderView");
                boolean naturalCard = surface != null && surface.getClass().getName()
                        .equals("com.android.systemui.qs.CustomSmoothCornerDrawable");
                boolean notification = view.getClass().getName().endsWith(".NotificationBackgroundView");
                if (notification) {
                    Drawable notificationBackground = (Drawable) field(view, "mBackground");
                    float[] radii = (float[]) field(view, "mCornerRadii");
                    // Flyme blur drawables keep their own uniform radius; only
                    // layered notification backgrounds use per-corner roundness.
                    boolean uniform = !(notificationBackground instanceof LayerDrawable);
                    if (uniform) radius = ((Number) field(view, "mClipCornerRadius")).floatValue();
                    else {
                        uniform = true;
                        for (float corner : radii) if (corner != radii[0]) { uniform = false; break; }
                        radius = radii[0];
                    }
                    if (uniform) shader = round;
                    else {
                        // Different group corner radii remain dynamic when swipe clipping changes width.
                        // Rebuilding the old bitmap mask here would allocate and rasterize every frame.
                        shader = notificationRound;
                        if (configuredCorners[0] != radii[0] || configuredCorners[1] != radii[2]
                                || configuredCorners[2] != radii[4] || configuredCorners[3] != radii[6]) {
                            shader.setFloatUniform("u_notificationCorners",radii[0],radii[2],radii[4],radii[6]);
                            for (int i = 0; i < 4; i++) configuredCorners[i] = radii[i * 2];
                        }
                    }
                } else if (mediaGeometry != null) {
                    // The inward edge distance follows the exact native clip
                    // intersection, so neither an old full-width glow nor a
                    // guessed round-rect is cut by the carousel's round corners.
                    setNativeMask(mediaGeometry.path, bounds, 2f * view.getResources().getDisplayMetrics().density);
                    shader = nativeCurve;
                } else if (slider || naturalCard) {
                    shader = updateNativeMask(view, bounds, radius, surface, slider) ? nativeCurve : round;
                } else shader = round; // Native GradientDrawable/round-rect backgrounds retain their actual circular arcs.
                // GradientStrokeLineAdapter QS light/night templates, scaled from ColorOS's 6px stroke.
                float width = 2f * view.getResources().getDisplayMetrics().density;
                boolean dark = night(view);
                // RuntimeShader uniform writes discard its cached native shader even
                // when values match. Keep stable contour draws on the cached shader.
                if (configuredShader != shader || configuredWidth != bounds.width()
                        || configuredHeight != bounds.height() || configuredRadius != radius
                        || configuredStroke != width || configuredNight != dark) {
                    shader.setFloatUniform("u_size", bounds.width(), bounds.height());
                    shader.setFloatUniform("u_fadeDistance", ContourLightGeometry.fadeDistance(
                            bounds.width(), bounds.height(), radius, circularButton));
                    shader.setFloatUniform("uRatio", .5f + bounds.width()/(4f*(bounds.width()+bounds.height())));
                    shader.setFloatUniform("u_corner", radius); shader.setFloatUniform("u_weight", .2f);
                    shader.setColorUniform("uColor", Color.WHITE);
                    nearParams[0] = farParams[0] = width; nearParams[1] = .4f * (dark ? .4f : 1f);
                    farParams[1] = .2f * (dark ? .4f : 1f);
                    ContourInnerLight.configure(shader, view.getResources().getDisplayMetrics().density, dark);
                    shader.setFloatUniform("uNearLineParams", nearParams);
                    shader.setFloatUniform("uFarLineParams", farParams);
                    configuredShader = shader; configuredWidth = bounds.width(); configuredHeight = bounds.height();
                    configuredRadius = radius; configuredStroke = width; configuredNight = dark;
                    shaderGeneration++;
                }
                paint.setShader(shader);
                if (!notification && mediaGeometry == null && canvas.isHardwareAccelerated()) {
                    renderCache.draw(canvas, bounds, paint, shaderGeneration, alpha);
                } else {
                    paint.setAlpha(alpha); canvas.drawRect(bounds, paint);
                }
            } catch (Exception | LinkageError error) {
                if (view.getClass().getName().endsWith(".NotificationBackgroundView")) {
                    if (notificationErrors.add(view)) log.accept("Cannot render ColorOS notification contour", error);
                } else fail(view, error);
            } finally {
                if (mediaSave >= 0) canvas.restoreToCount(mediaSave);
            }
        }
        private Rect notificationBounds(View view,Canvas canvas,Rect background) throws ReflectiveOperationException {
            visibleBounds.set(background);
            if (!canvas.getClipBounds(canvasClip)) { visibleBounds.setEmpty(); return visibleBounds; }
            visibleBounds.left=Math.max(visibleBounds.left,canvasClip.left);
            visibleBounds.right=Math.min(visibleBounds.right,canvasClip.right);
            toAncestor.reset();
            for (View child=view; child.getParent() instanceof View ancestor; child=ancestor) {
                toAncestor.postConcat(child.getMatrix());
                toAncestor.postTranslate(child.getLeft()-ancestor.getScrollX(),child.getTop()-ancestor.getScrollY());
                if (!toAncestor.invert(fromAncestor)) { visibleBounds.setEmpty(); break; }
                // Native drawChild clips its child display list, independently of
                // clipToOutline/clipBounds. The child's RecordingCanvas therefore
                // cannot report this moving boundary through getClipBounds().
                if (notificationOutline.isInstance(ancestor)
                        && Boolean.TRUE.equals(childNeedsClipping.invoke(ancestor,child))) {
                    Path childPath = (Path)customChildClipPath.invoke(ancestor,child);
                    if (childPath == null) childPath = (Path)notificationClipPath.invoke(ancestor,false);
                    if (childPath != null) {
                        if (childPath.isEmpty()) { visibleBounds.setEmpty(); break; }
                        childPath.computeBounds(mappedClip,true);
                        intersectHorizontal(mappedClip.left,mappedClip.right,ancestor.getHeight());
                    }
                }
                Rect clip=ancestor.getClipBounds();
                if (clip!=null) intersectHorizontal(clip.left,clip.right,ancestor.getHeight());
                if (ancestor instanceof android.view.ViewGroup group) {
                    if (group.getClipChildren()) intersectHorizontal(0,ancestor.getWidth(),ancestor.getHeight());
                    if (group.getClipToPadding()) intersectHorizontal(ancestor.getPaddingLeft(),
                            ancestor.getWidth()-ancestor.getPaddingRight(),ancestor.getHeight());
                }
                if (ancestor.getClipToOutline() && ancestor.getOutlineProvider()!=null) {
                    ancestorOutline.setEmpty();
                    ancestor.getOutlineProvider().getOutline(ancestor,ancestorOutline);
                    if (ancestorOutline.getRect(outlineRect))
                        intersectHorizontal(outlineRect.left,outlineRect.right,ancestor.getHeight());
                    else if (outlinePath.get(ancestorOutline) instanceof Path path && !path.isEmpty()) {
                        path.computeBounds(mappedClip,true);
                        intersectHorizontal(mappedClip.left,mappedClip.right,ancestor.getHeight());
                    }
                }
                if (ancestor.getClass().getName().equals("com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout")
                        && (Boolean)field(ancestor,"mShouldUseRoundedRectClipping"))
                    intersectHorizontal(((Number)field(ancestor,"mRoundedRectClippingLeft")).floatValue(),
                            ((Number)field(ancestor,"mRoundedRectClippingRight")).floatValue(),ancestor.getHeight());
            }
            if (visibleBounds.left>=visibleBounds.right) visibleBounds.setEmpty();
            return visibleBounds;
        }
        private void intersectHorizontal(float left,float right,int height) {
            mappedClip.set(left,0,right,height);
            fromAncestor.mapRect(mappedClip);
            visibleBounds.left=Math.max(visibleBounds.left,(int)Math.ceil(mappedClip.left));
            visibleBounds.right=Math.min(visibleBounds.right,(int)Math.floor(mappedClip.right));
        }
        private boolean updateNativeMask(View view, Rect bounds, float radius, Drawable surface, boolean slider)
                throws ReflectiveOperationException {
            float stroke = 2f * view.getResources().getDisplayMetrics().density;
            boolean natural = true;
            ViewOutlineProvider provider = slider ? view.getOutlineProvider() : null;
            Path nativePath = null;
            float smoothness = .2f;
            if (!slider && surface != null) {
                smoothness = ((Number) field(surface, "smoothness")).floatValue();
                if (field(surface, "path") instanceof Path path) nativePath = path;
            }
            if (maskWidth == bounds.width() && maskHeight == bounds.height() && maskRadius == radius
                    && maskStroke == stroke && maskSmooth == natural && maskSource == nativePath
                    && maskGeneration == (nativePath == null ? 0 : nativePath.getGenerationId())
                    && maskSmoothness == smoothness && maskProvider == provider) return true;
            Path path;
            if (slider) {
                // The slider's rectangular track is clipped by this exact provider, not by a
                // separately generated rounded rectangle. Copy its current path and coordinates.
                if (provider == null) return false;
                Outline outline = new Outline();
                provider.getOutline(view, outline);
                Rect rect = new Rect();
                if (outline.getRect(rect)) {
                    path = new Path();
                    float outlineRadius = Math.max(0f, outline.getRadius());
                    path.addRoundRect(rect.left, rect.top, rect.right, rect.bottom,
                            outlineRadius, outlineRadius, Path.Direction.CW);
                } else if (outlinePath.get(outline) instanceof Path actual && !actual.isEmpty()) {
                    path = new Path(actual);
                } else return false;
            } else if (nativePath != null && !nativePath.isEmpty()) path = new Path(nativePath);
            else path = (Path) generatePath.invoke(null, 0f, 0f, (float) bounds.width(),
                    (float) bounds.height(), smoothness, radius, false);
            // Copy the native outline into a local mask; no compositor or background rendering is changed.
            setNativeMask(path, bounds, stroke);
            maskWidth = bounds.width(); maskHeight = bounds.height(); maskRadius = radius;
            maskStroke = stroke; maskSmooth = natural;
            maskSource = nativePath; maskSmoothness = smoothness; maskProvider = provider;
            maskGeneration = nativePath == null ? 0 : nativePath.getGenerationId();
            return true;
        }
        private void setNativeMask(Path path, Rect bounds, float stroke) {
            // invalidateOutline and native Path rebuilds can change generation IDs
            // without changing geometry. Keep the existing bitmap and GPU input in
            // that case; compare exact verbs/points, not an approximate bounding box.
            if (renderedMask.matches(path, bounds.width(), bounds.height(), stroke)) return;
            shaderGeneration++;
            Bitmap bitmap = Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.ARGB_8888);
            Canvas mask = new Canvas(bitmap);
            mask.clipPath(path);
            maskPaint.setStyle(Paint.Style.FILL);
            maskPaint.setColor(Color.RED);
            mask.drawPath(path, maskPaint);
            maskPaint.setStyle(Paint.Style.STROKE);
            // Red stores inward edge distance; alpha stores curve coverage. Keep ColorOS's original
            // fade-toward-center math instead of substituting a uniform-brightness stroke.
            for (float distance = stroke; distance > 0f; distance -= .5f) {
                int red = Math.round(255f * Math.min(1f, distance / stroke));
                maskPaint.setColor(Color.argb(255, red, 0, 0));
                maskPaint.setStrokeWidth(distance * 2f);
                mask.drawPath(path, maskPaint);
            }
            nativeCurve.setInputBuffer("uMask", new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
            renderedMask.remember(path, bounds.width(), bounds.height(), stroke);
        }
        public void setAlpha(int value) { if (alpha != value) { alpha = value; invalidateSelf(); } }
        public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
