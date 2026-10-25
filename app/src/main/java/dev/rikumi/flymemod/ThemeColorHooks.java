package dev.rikumi.flymemod;

import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.content.res.Configuration;
import android.view.View;
import android.widget.ImageView;
import android.widget.Button;
import android.widget.TextView;
import android.app.Dialog;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.os.Bundle;
import android.graphics.drawable.BitmapDrawable;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import java.util.HashSet;
import java.util.Set;
import android.util.TypedValue;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Locale;
import io.github.libxposed.api.XposedInterface;

final class ThemeColorHooks {
    interface Installer { void hook(Executable target, XposedInterface.Hooker hooker); }
    private static final int COLOR = 1, PRIMARY = 2, ICON = 4, BACKGROUND = 8, CUTOUT_ICON = 16, STORE_BACKGROUND = 32, RED_PROTECTED_ICON = 64, LEGACY_BACKGROUND = 128, INACTIVE_TAB_ICON = 256;
    private final SharedPreferences preferences;
    private final int legacyBackgroundFamily;
    private final String packageName;
    private final Map<View, Boolean> storePageBackgrounds = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<View, Boolean> dialogButtons = Collections.synchronizedMap(new WeakHashMap<>());
    private final BiConsumer<String, Throwable> log;
    private final WirelessDebugTextHooks wirelessDebugText;
    private final WirelessDebugPageHooks wirelessDebugPage;
    private final AtomicBoolean firstMatch = new AtomicBoolean();
    private final Map<Resources, Map<Integer, Integer>> targets = Collections.synchronizedMap(new WeakHashMap<>());
    private final ThreadLocal<Paint> sliderPaint = ThreadLocal.withInitial(Paint::new);
    private final ThreadLocal<Paint> cursorPaint = ThreadLocal.withInitial(Paint::new);
    private volatile android.graphics.ColorFilter cursorFilter;
    private final ThreadLocal<Boolean> preservingIconColors = ThreadLocal.withInitial(() -> false);
    private final ThreadLocal<Boolean> loadingControlTrack = ThreadLocal.withInitial(() -> false);
    private final ThreadLocal<Boolean> replacing = ThreadLocal.withInitial(() -> false);
    private volatile ThemeColorPalette palette = new ThemeColorPalette(false, 0);
    private volatile ThemeIconFilter iconFilters;
    private volatile boolean blackBackground;
    private volatile ThemeBackgroundFilter backgroundFilter;
    private volatile ThemeBackgroundFilter appManagementBackgroundFilter;
    private volatile ThemeBackgroundFilter storeBackgroundFilter;
    private volatile ThemeBackgroundFilter windowBackgroundFilter;
    private final android.graphics.ColorFilter sidebarBlack = new PorterDuffColorFilter(0xff000000, PorterDuff.Mode.SRC_IN);
    private final ThreadLocal<Paint> backgroundPaint = ThreadLocal.withInitial(Paint::new);
    private static java.lang.reflect.Field decorWindow;
    private boolean configured, enabled;
    private int selectedColor, installedHooks;
    private Method listStates, listColors, listCanApplyTheme;
    private final Map<Resources, android.util.SparseArray<String>> viewNames = new WeakHashMap<>();
    private final Map<Resources,Integer> controlAccentAttributes=Collections.synchronizedMap(new WeakHashMap<>());
    private final AtomicBoolean settingsSwitchMatch = new AtomicBoolean();
    private final AtomicBoolean controlFilterFailure=new AtomicBoolean();
    private record ColorSource(WeakReference<Resources> resources, int kind) {}
    private final Map<ColorStateList, ColorSource> themedLists = Collections.synchronizedMap(new WeakHashMap<>());
    private final SharedPreferences.OnSharedPreferenceChangeListener changes = (prefs, key) -> {
        if (key == null || ModuleSettings.DARK_SYSTEM_APP_BLACK_BACKGROUND.equals(key)) read();
    };

    ThemeColorHooks(String packageName, SharedPreferences preferences, BiConsumer<String, Throwable> log) {
        this.packageName = packageName;
        this.legacyBackgroundFamily = DarkBackgroundPolicy.legacyFamily(packageName);
        this.preferences = preferences;
        this.log = log;
        this.wirelessDebugText = new WirelessDebugTextHooks(packageName, preferences, log);
        this.wirelessDebugPage = new WirelessDebugPageHooks(preferences, log);
    }

    private synchronized void read() {
        try {
            // Custom system theme-color replacement was removed. This hook host remains

            boolean nextEnabled = false;
            int nextColor = 0;
            boolean nextBlack = preferences.getBoolean(ModuleSettings.DARK_SYSTEM_APP_BLACK_BACKGROUND, false);
            if (configured && enabled == nextEnabled && selectedColor == nextColor && blackBackground == nextBlack) return;
            blackBackground = nextBlack;
            backgroundFilter = null;
            appManagementBackgroundFilter = null;
            storeBackgroundFilter = null;
            windowBackgroundFilter = null;
            if (nextBlack) {
                try {
                    backgroundFilter = new ThemeBackgroundFilter(DarkBackgroundPolicy.applicationShades(packageName, legacyBackgroundFamily));
                    windowBackgroundFilter = new ThemeBackgroundFilter(true);
                    if ("com.meizu.safe".equals(packageName) || "com.android.settings".equals(packageName))
                        appManagementBackgroundFilter = new ThemeBackgroundFilter(AppManagementBackgroundPolicy.SHADES);
                    if ("com.meizu.mstore".equals(packageName)) storeBackgroundFilter = windowBackgroundFilter;
                }
                catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                    log.accept("Cannot create pure black background filter", error);
                }
            }
            palette = new ThemeColorPalette(false, 0, new int[0], 0);
            cursorFilter = nextEnabled ? new PorterDuffColorFilter(0xff000000 | (nextColor & 0xffffff), PorterDuff.Mode.SRC_IN) : null;
            iconFilters = null;
            if (nextEnabled) {
                try { iconFilters = new ThemeIconFilter(nextColor); }
                catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                    log.accept("Cannot create interface icon theme filter", error);
                }
            }
            enabled = nextEnabled;
            selectedColor = nextColor;
            configured = true;
            firstMatch.set(false);
            log.accept("Appearance background policy configured: package=" + packageName + ", black=" + blackBackground, null);
        } catch (RuntimeException error) {
            configured = false;
            blackBackground = false;
            backgroundFilter = null;
            appManagementBackgroundFilter = null;
            storeBackgroundFilter = null;
            windowBackgroundFilter = null;
            palette = new ThemeColorPalette(false, 0);
            cursorFilter = null;
            iconFilters = null;
            log.accept("Cannot read appearance background settings", error);
        }
    }

    void install(ClassLoader loader, Installer installer, Consumer<Executable> deoptimizer) {
        read();
        try {
            decorWindow=Class.forName("com.android.internal.policy.DecorView",false,loader).getDeclaredField("mWindow");
            decorWindow.setAccessible(true);
        } catch(ReflectiveOperationException | RuntimeException error) {
            log.accept("Cannot resolve native decor window owner",error);
        }
        preferences.registerOnSharedPreferenceChangeListener(changes);
        installCursorHooks(loader, installer, deoptimizer);
        wirelessDebugText.install(loader, installer);
        if ("com.android.settings".equals(packageName)) wirelessDebugPage.install(loader, installer, deoptimizer);
        if ("com.android.settings".equals(packageName))
        if ("com.android.settings".equals(packageName))
            new SettingsHomeGoogleHooks(preferences, log).install(loader, installer, deoptimizer);
        try {
            listStates = ColorStateList.class.getDeclaredMethod("getStates");
            listColors = ColorStateList.class.getDeclaredMethod("getColors");
            listCanApplyTheme = ColorStateList.class.getDeclaredMethod("canApplyTheme");
        } catch (ReflectiveOperationException error) { log.accept("Cannot resolve color state data", error); }
        if (settingsProcess()) {
            try {
                Class<?> switchType = Class.forName("com.meizu.common.widget.Switch", false, loader);
                Field thumbOn = switchType.getField("mThumbOnColor"), thumbOff = switchType.getField("mThumbOffColor");
                Field trackOn = switchType.getField("mTrackOnColorState"), trackOff = switchType.getField("mTrackOffColorState");
                // Keep cached thumb state lists native so turning the theme off restores the ROM colors.
                for (java.lang.reflect.Constructor<?> constructor : switchType.getDeclaredConstructors())
                    installer.hook(constructor, chain -> {
                        boolean previous = preservingIconColors.get();
                        preservingIconColors.set(true);
                        try { return chain.proceed(); }
                        finally { preservingIconColors.set(previous); }
                    });
                method(loader, installer, "com.meizu.common.widget.Switch", "getColorFromState", chain -> {
                    Object view = chain.getThisObject(), source = chain.getArg(0);
                    boolean thumb = source == thumbOn.get(view) || source == thumbOff.get(view);
                    boolean track = source == trackOn.get(view) || source == trackOff.get(view);
                    boolean previous = preservingIconColors.get();
                    preservingIconColors.set(true);
                    int original;
                    try { original = (int) chain.proceed(); }
                    finally { preservingIconColors.set(previous); }
                    if (thumb) return ThemeSwitchColorPolicy.thumb(original, enabled);
                    int mapped = track ? palette.replaceResource(original, true) : original;
                    if (mapped != original && settingsSwitchMatch.compareAndSet(false, true))
                        log.accept("Settings Switch native track color replaced", null);
                    return mapped;
                }, ColorStateList.class);
                // The ROM writes thumb/track colors on every position/state update; no drawable mutation is retained.
                deopt(loader, deoptimizer, "com.meizu.common.widget.Switch", "setThumbPosition", float.class);
                deopt(loader, deoptimizer, "com.meizu.common.widget.Switch", "draw", Canvas.class);
                deopt(loader, deoptimizer, "com.meizu.common.widget.Switch", "onDraw", Canvas.class);
            } catch (ReflectiveOperationException | LinkageError error) {
                log.accept("Cannot resolve native Settings Switch color slots", error);
            }
        }
        if (settingsProcess()) {
            for (String owner : new String[]{"android.widget.AbsSeekBar",
                    "com.meizu.common.widget.AbsSeekBar", "com.meizu.common.widget.StepSeekBar",
                    "com.meizu.common.widget.EnhanceSeekBar"}) {
                method(loader, installer, owner, "onDraw", chain -> {
                    ThemeIconFilter filters = iconFilters;
                    if (filters == null) return chain.proceed();
                    View view = (View) chain.getThisObject();
                    if (ThemeControlResources.spectralSlider(packageName, view.getClass())) return chain.proceed();
                    Canvas canvas = (Canvas) chain.getArg(0);
                    Paint paint = sliderPaint.get();
                    paint.setColorFilter(filters.blueParts);
                    int saved = canvas.saveLayer(0, 0, view.getWidth(), view.getHeight(), paint);
                    try { return chain.proceed(); }
                    finally { canvas.restoreToCount(saved); }
                }, Canvas.class);
                deopt(loader, deoptimizer, owner, "onDraw", Canvas.class);
            }
            // Boot-image/AOT callers can otherwise bypass hooks after inlining getters and drawing entries.
            deopt(loader, deoptimizer, "android.widget.ImageView", "onDraw", Canvas.class);
            deopt(loader, deoptimizer, "android.widget.ImageView", "updateDrawable", Drawable.class);
            deopt(loader, deoptimizer, "android.view.View", "draw", Canvas.class);
            deopt(loader, deoptimizer, "android.view.View", "draw", Canvas.class, ViewGroup.class, long.class);
            deopt(loader, deoptimizer, "android.view.View", "updateDisplayListIfDirty");
            deopt(loader, deoptimizer, "android.content.res.Resources", "getDrawableForDensity", int.class, int.class, Resources.Theme.class);
            deopt(loader, deoptimizer, "android.content.res.Resources", "getColorStateList", int.class, Resources.Theme.class);
            deopt(loader, deoptimizer, "android.content.res.TypedArray", "getColorStateList", int.class);
        }
        method(loader, installer, "android.app.Dialog", "show", chain -> {
            Object result = chain.proceed();
            Dialog dialog = (Dialog) chain.getThisObject();
            wirelessDebugText.shown(dialog);
            if (dialog.getWindow() != null) markDialogButtons(dialog.getWindow().getDecorView());
            return result;
        });
        // Gallery caches TypedValue.data directly, bypassing Resources.getColor.
        method(loader, installer, "android.content.res.Resources$Theme", "resolveAttribute", chain -> {
            Object result = chain.proceed();
            TypedValue value = (TypedValue) chain.getArg(1);
            if (!Boolean.TRUE.equals(result) || replacing.get() || preservingIconColors.get()
                    || value.type < TypedValue.TYPE_FIRST_COLOR_INT || value.type > TypedValue.TYPE_LAST_COLOR_INT) return result;
            Resources resources = ((Resources.Theme) chain.getThisObject()).getResources();
            try {
                String name = resources.getResourceEntryName((int) chain.getArg(0));
                if (ThemeTabPolicy.primaryAttribute(name)) value.data = palette.replaceResource(value.data, true);

            } catch (Resources.NotFoundException ignored) {}
            return result;
        }, int.class, TypedValue.class, boolean.class);
        if ("com.meizu.mstore".equals(packageName)) {

            method(loader,installer,"sg.c","a",chain -> {
                View sidebar=(View)chain.getThisObject();
                if(blackBackground && night(sidebar.getResources()) && storeSidebar(sidebar)
                        && normalActivityBackground(sidebar))return null;
                return chain.proceed();
            },Canvas.class,float.class,float.class,int.class);
            deopt(loader,deoptimizer,"sg.c","onDraw",Canvas.class);
            method(loader, installer, "com.meizu.mstore.page.base.BaseFragment", "onCreateView", chain -> {
                View root = (View) chain.proceed();
                if (root != null) storePageBackgrounds.put(root, Boolean.TRUE);
                try {
                    Object recycler = chain.getThisObject().getClass().getMethod("getRecyclerView").invoke(chain.getThisObject());
                    if (recycler instanceof View view) storePageBackgrounds.put(view, Boolean.TRUE);
                } catch (NoSuchMethodException ignored) {}
                return root;
            }, LayoutInflater.class, ViewGroup.class, Bundle.class);
        }
        // Track only named selectors that defer their theme-attribute resolution.
        method(loader, installer, "android.content.res.ColorStateList", "obtainForTheme", chain -> {
            ColorSource source = themedLists.get((ColorStateList) chain.getThisObject());
            ColorStateList result = (ColorStateList) chain.proceed();
            if (source != null && result != null) themedLists.put(result, source);
            return result;
        }, Resources.Theme.class);
        method(loader, installer, "android.content.res.ColorStateList", "getColorForState", chain ->
                stateColor((ColorStateList) chain.getThisObject(), (int) chain.proceed()), int[].class, int.class);
        method(loader, installer, "android.content.res.ColorStateList", "getDefaultColor", chain ->
                stateColor((ColorStateList) chain.getThisObject(), (int) chain.proceed()));
        method(loader, installer, "android.content.res.Resources", "getColor", chain -> {
            Resources resources = (Resources) chain.getThisObject();
            int kind = target(resources, (int) chain.getArg(0));
            if (!isColor(kind) || replacing.get()) return chain.proceed();
            replacing.set(true);
            try { return color((int) chain.proceed(), kind, resources); }
            finally { replacing.set(false); }
        }, int.class, Resources.Theme.class);
        method(loader, installer, "android.content.res.TypedArray", "getColor", chain -> {
            TypedArray array = (TypedArray) chain.getThisObject();
            int kind = target(array.getResources(), array.getResourceId((int) chain.getArg(0), 0));
            if (!isColor(kind) || replacing.get()) return chain.proceed();
            replacing.set(true);
            try { return color((int) chain.proceed(), kind, array.getResources()); }
            finally { replacing.set(false); }
        }, int.class, int.class);
        method(loader, installer, "android.content.res.Resources", "getColorStateList", chain -> {
            Resources resources = (Resources) chain.getThisObject();
            int kind = target(resources, (int) chain.getArg(0));
            if (!isColor(kind) || replacing.get()) return chain.proceed();
            replacing.set(true);
            try { return colorList((ColorStateList) chain.proceed(), kind, resources); }
            finally { replacing.set(false); }
        }, int.class, Resources.Theme.class);
        method(loader, installer, "android.content.res.TypedArray", "getColorStateList", chain -> {
            TypedArray array = (TypedArray) chain.getThisObject();
            int kind = target(array.getResources(), array.getResourceId((int) chain.getArg(0), 0));
            if (!isColor(kind) || replacing.get()) return chain.proceed();
            replacing.set(true);
            try { return colorList((ColorStateList) chain.proceed(), kind, array.getResources()); }
            finally { replacing.set(false); }
        }, int.class);

        method(loader, installer, "android.content.res.ResourcesImpl", "loadColorStateList", chain -> {
            Resources resources = (Resources) chain.getArg(0);
            int kind = target(resources, (int) chain.getArg(2));
            if (!isColor(kind) || replacing.get()) return chain.proceed();
            replacing.set(true);
            try { return colorList((ColorStateList) chain.proceed(), kind, resources); }
            finally { replacing.set(false); }
        }, Resources.class, TypedValue.class, int.class, Resources.Theme.class);
        method(loader, installer, "android.content.res.Resources", "getValue", chain -> {
            Object result = chain.proceed();
            if (!replacing.get()) value((Resources) chain.getThisObject(), (int) chain.getArg(0), (TypedValue) chain.getArg(1));
            return result;
        }, int.class, TypedValue.class, boolean.class);
        method(loader, installer, "android.content.res.TypedArray", "getValueAt", chain -> {
            Object result = chain.proceed();
            TypedValue value = (TypedValue) chain.getArg(1);
            if (Boolean.TRUE.equals(result) && !replacing.get())
                value(((TypedArray) chain.getThisObject()).getResources(), value.resourceId, value);
            return result;
        }, int.class, TypedValue.class);

        // drawables) to resolve their theme colors just like the rest of the control.
        method(loader, installer, "android.content.res.ResourcesImpl", "loadDrawable", chain -> {
            boolean previous=preservingIconColors.get(),previousTrack=loadingControlTrack.get();
            boolean track=previousTrack;
            if(!track)try {
                track=ThemeControlResources.sliderTrack(((Resources)chain.getArg(0)).getResourceEntryName((int)chain.getArg(2)));
            } catch(Resources.NotFoundException ignored) { }
            loadingControlTrack.set(track);preservingIconColors.set(!track);
            try {return chain.proceed();}
            finally{preservingIconColors.set(previous);loadingControlTrack.set(previousTrack);}
        }, Resources.class, TypedValue.class, int.class, int.class, Resources.Theme.class);
        method(loader, installer, "android.widget.ImageView", "onDraw", chain -> {
            boolean previous=preservingIconColors.get();preservingIconColors.set(true);
            Canvas canvas = (Canvas) chain.getArg(0);
            ImageView view = (ImageView) chain.getThisObject();
            int saved = -1;
            try {
                Paint paint = wirelessDebugPage.iconPaint(view);
                if (paint != null) saved = canvas.saveLayer(view.getScrollX(), view.getScrollY(),
                        view.getScrollX() + view.getWidth(), view.getScrollY() + view.getHeight(), paint);
                return chain.proceed();
            } finally {
                if (saved != -1) canvas.restoreToCount(saved);
                preservingIconColors.set(previous);
            }
        }, Canvas.class);
        method(loader, installer, "android.widget.TextView", "onDraw", chain -> {
            TextView view = (TextView) chain.getThisObject();
            ColorStateList original = view.getTextColors();
            ColorStateList mapped = wirelessDebugText.textColors(view, iconFilters == null ? null : selectedColor);
            if (mapped == null) mapped = original;
            ThemeIconFilter filter = iconFilters;
            boolean button = dialogButton(view);
            // Recolor only text states; a layer filter would also recolor compound icons.
            if (filter != null && (button || "com.meizu.media.gallery".equals(packageName)
                    && ThemeTabPolicy.galleryText(packageName, viewId(view))))
                mapped = colorList(mapped, PRIMARY, view.getResources());
            if(mapped==original)return chain.proceed();
            view.setTextColor(mapped);
            try {return chain.proceed();}finally{view.setTextColor(original);}
        }, Canvas.class);

        method(loader, installer, "android.view.View", "drawBackground", chain -> {
            View view = (View) chain.getThisObject();
            // MzBreakerSeekbar paints a five-color temperature spectrum as its View background.
            if (ThemeControlResources.spectralSlider(packageName, view.getClass())) return chain.proceed();

            ThemeIconFilter sliderColors=iconFilters;
            boolean input=sliderColors!=null && ThemeControlResources.inputView(view.getClass());
            boolean border=sliderColors!=null && "com.meizu.flyme.launcher".equals(packageName)
                    && ThemeControlResources.iconStyleBorder(packageName,viewId(view));
            boolean slider=sliderColors!=null && (settingsProcess() || "com.meizu.flyme.launcher".equals(packageName))
                    && ThemeControlResources.sliderView(view.getClass());
            if(sliderColors!=null && (input ||border ||slider) && view.getBackground()!=null) {
                Canvas canvas=(Canvas)chain.getArg(0);
                if(canvas.isHardwareAccelerated()) {
                    Paint paint=sliderPaint.get();paint.setColorFilter(input ||border
                            ?controlColorFilter(sliderColors,view):sliderColors.blueParts);
                    int saved=canvas.saveLayer(view.getScrollX(),view.getScrollY(),
                            view.getScrollX()+view.getWidth(),view.getScrollY()+view.getHeight(),paint);
                    try{return chain.proceed();}finally{canvas.restoreToCount(saved);}
                }
            }
            ThemeIconFilter dialogColors = iconFilters;
            if (dialogColors != null && !dialogButton(view)) dialogColors = null;
            if (dialogColors == null && !blackBackground) return chain.proceed();
            boolean sidebar = storeSidebar(view);
            boolean storeGlobal = "com.meizu.mstore".equals(packageName)
                    && (sidebar || storePageBackgrounds.containsKey(view)
                    || ThemeTabPolicy.storeGlobalBackground(packageName, viewId(view))
                    || view.getClass().getName().equals("com.ashokvarma.bottomnavigation.BottomNavigationBar"));

            boolean blackAllowed=normalActivityBackground(view);
            if(!blackAllowed && dialogColors==null)return chain.proceed();
            boolean windowSurface = "com.android.internal.policy.DecorView".equals(view.getClass().getName());
            ThemeBackgroundFilter filter = windowSurface ? windowBackgroundFilter
                    : storeGlobal ? storeBackgroundFilter
                    : appManagementSurface(view) ? appManagementBackgroundFilter : backgroundFilter;
            Drawable background = view.getBackground();
            if(dialogColors!=null && (background instanceof BitmapDrawable
                    || background instanceof android.graphics.drawable.VectorDrawable
                    || background instanceof android.graphics.drawable.AdaptiveIconDrawable))dialogColors=null;
            if (dialogColors == null && (!blackAllowed || !blackBackground || !sidebar && filter == null)
                    || background == null || dialogColors == null && background instanceof BitmapDrawable
                    || dialogColors == null && !night(view.getResources())) return chain.proceed();
            Canvas canvas = (Canvas) chain.getArg(0);
            Paint paint = backgroundPaint.get();
            paint.setColorFilter(sidebar ? sidebarBlack : filter.filter);
            int saved = canvas.saveLayer(view.getScrollX(), view.getScrollY(),
                    view.getScrollX() + view.getWidth(), view.getScrollY() + view.getHeight(), paint);
            try { return chain.proceed(); }
            finally { canvas.restoreToCount(saved); }
        }, Canvas.class);
        if(!settingsProcess()) {

            deopt(loader,deoptimizer,"android.view.View","draw",Canvas.class);
            deopt(loader,deoptimizer,"android.view.View","draw",Canvas.class,ViewGroup.class,long.class);
            deopt(loader,deoptimizer,"android.view.View","updateDisplayListIfDirty");
        }
        log.accept("Appearance background hooks registered: hooks=" + installedHooks, null);
    }

    private boolean settingsProcess() {
        return "com.android.settings".equals(packageName) || "com.meizu.connectivitysettings".equals(packageName);
    }

    private void installCursorHooks(ClassLoader loader, Installer installer, Consumer<Executable> deoptimizer) {
        try {
            Class<?> editor = Class.forName("android.widget.Editor", false, loader);
            java.lang.reflect.Field textView = editor.getDeclaredField("mTextView");
            java.lang.reflect.Field drawable = editor.getDeclaredField("mDrawableForCursor");
            java.lang.reflect.Field resource = TextView.class.getDeclaredField("mCursorDrawableRes");
            textView.setAccessible(true);
            drawable.setAccessible(true);
            resource.setAccessible(true);
            // Device framework: drawCursor draws only this drawable, with a vertical translation.
            method(loader, installer, "android.widget.Editor", "drawCursor", chain -> {
                android.graphics.ColorFilter filter = cursorFilter;
                if (filter == null) return chain.proceed();
                TextView view = (TextView) textView.get(chain.getThisObject());
                Drawable cursor = (Drawable) drawable.get(chain.getThisObject());
                if (!ThemeControlResources.inputView(view.getClass()) || inputError(view)
                        || cursor == null || errorCursor(view.getResources(), resource.getInt(view))) return chain.proceed();
                Rect bounds = cursor.getBounds();
                int offset = (int) chain.getArg(1);
                Canvas canvas = (Canvas) chain.getArg(0);
                Paint paint = cursorPaint.get();
                paint.setColorFilter(filter);
                int saved = canvas.saveLayer(bounds.left, bounds.top + offset,
                        bounds.right, bounds.bottom + offset, paint);
                try { return chain.proceed(); }
                finally { canvas.restoreToCount(saved); }
            }, Canvas.class, int.class);
            // Keep the cached native cursor intact so turning the setting off restores it.
            method(loader, installer, "android.widget.TextView", "getTextCursorDrawable", chain -> {
                boolean previous = preservingIconColors.get();
                preservingIconColors.set(true);
                try { return chain.proceed(); }
                finally { preservingIconColors.set(previous); }
            });
            method(loader, installer, "android.widget.TextView", "setTextCursorDrawable", chain -> {
                boolean previous = preservingIconColors.get();
                preservingIconColors.set(true);
                try { return chain.proceed(); }
                finally { preservingIconColors.set(previous); }
            }, int.class);
            deopt(loader, deoptimizer, "android.widget.Editor", "onDraw", Canvas.class, android.text.Layout.class,
                    java.util.List.class, java.util.List.class, android.graphics.Path.class, Paint.class, int.class);
            deopt(loader, deoptimizer, "android.widget.Editor", "loadCursorDrawable");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log.accept("Cannot resolve native input cursor drawing", error);
        }
    }

    private static boolean errorCursor(Resources resources, int id) {
        if (id == 0) return false;
        try { return resources.getResourceEntryName(id).toLowerCase(Locale.ROOT).contains("error"); }
        catch (Resources.NotFoundException ignored) { return true; }
    }

    private static boolean inputError(TextView view) {
        if (view.getError() != null) return true;
        // Flyme TextInputLayout keeps its error on the parent, not EditText.getError().
        android.view.ViewParent parent = view.getParent();
        for (int depth = 0; depth < 6 && parent instanceof View ancestor; depth++, parent = ancestor.getParent()) {
            for (Class<?> type = ancestor.getClass(); type != null; type = type.getSuperclass()) {
                if (!"com.meizu.textinputlayout.TextInputLayout".equals(type.getName())) continue;
                try {
                    Object error = type.getMethod("getError").invoke(ancestor);
                    return error instanceof CharSequence text && text.length() != 0;
                } catch (ReflectiveOperationException | RuntimeException ignored) { return true; }
            }
        }
        return false;
    }

    private int nativeControlAccent(View view) {
        Resources resources=view.getResources();
        int attr=controlAccentAttributes.computeIfAbsent(resources,ignored -> {
            int id=resources.getIdentifier("colorPrimary","attr",packageName);
            return id==0?android.R.attr.colorAccent:id;
        });
        boolean previous=preservingIconColors.get();preservingIconColors.set(true);
        try {
            TypedArray colors=view.getContext().obtainStyledAttributes(new int[]{attr,android.R.attr.colorAccent});
            try{return colors.getColor(0,colors.getColor(1,0xff206cff));}finally{colors.recycle();}
        } finally{preservingIconColors.set(previous);}
    }

    private android.graphics.ColorFilter controlColorFilter(ThemeIconFilter filters,View view) {
        try{return filters.forControlAccent(nativeControlAccent(view));}
        catch(ReflectiveOperationException | RuntimeException | LinkageError error) {
            if(controlFilterFailure.compareAndSet(false,true))log.accept("Cannot resolve native control background accent",error);
            return filters.blueParts;
        }
    }

    private void markDialogButtons(View view) {
        if (view instanceof Button) dialogButtons.put(view, Boolean.TRUE);
        else if (view instanceof TextView && view.isClickable()) {
            String id = viewId(view).toLowerCase(Locale.ROOT);
            if (id.contains("button") || id.contains("btn") || id.contains("agree")
                    || id.contains("allow") || id.contains("accept") || id.contains("consent")
                    || id.contains("confirm") || id.contains("cancel") || id.contains("deny")
                    || id.contains("positive") || id.contains("negative"))
                dialogButtons.put(view, Boolean.TRUE);
        }
        if (view instanceof ViewGroup group)
            for (int i = 0; i < group.getChildCount(); i++) markDialogButtons(group.getChildAt(i));
    }

    private boolean dialogButton(View view) {
        return dialogButtons.containsKey(view) || "com.android.permissioncontroller".equals(packageName)
                && "com.android.permissioncontroller.permission.ui.widget.SecureButton".equals(view.getClass().getName());
    }

    private void deopt(ClassLoader loader, Consumer<Executable> deoptimizer, String owner, String name, Class<?>... params) {
        try { deoptimizer.accept(Class.forName(owner, false, loader).getDeclaredMethod(name, params)); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log.accept("Cannot deopt theme caller " + owner + "#" + name, error);
        }
    }

    private View storeTabParent(View view) {
        android.view.ViewParent parent = view.getParent();
        for (int i = 0; i < 6 && parent instanceof View ancestor; i++, parent = ancestor.getParent()) {
            Class<?> type = ancestor.getClass();
            while (type != null) {
                if ("com.ashokvarma.bottomnavigation.BottomNavigationTab".equals(type.getName())) return ancestor;
                type = type.getSuperclass();
            }
        }
        return null;
    }

    private boolean selectedStoreTab(ImageView view) {
        View tab = storeTabParent(view);
        if (tab != null) {
            try {
                Object animation = tab.getClass().getField("r").get(tab);
                // The native animated branch doesn't clear ImageView.isSelected on deselection.
                if (animation != null) return animation == view.getDrawable();
            } catch (ReflectiveOperationException ignored) {}
        }
        return view.isSelected();
    }

    private boolean storeSidebar(View view) {
        if (!"com.meizu.mstore".equals(packageName)) return false;
        View current = view;
        for (int i = 0; i < 10 && current != null; i++) {
            if ("com.meizu.verticaltab.VerticalTabLayout".equals(current.getClass().getName())) return true;
            current = current.getParent() instanceof View parent ? parent : null;
        }
        return false;
    }

    private String viewId(View view) {
        int id = view.getId();
        if (id == View.NO_ID || id == 0) return "";
        Resources resources = view.getResources();
        synchronized (viewNames) {
            android.util.SparseArray<String> names = viewNames.computeIfAbsent(resources,
                    ignored -> new android.util.SparseArray<>());
            String name = names.get(id);
            if (name == null) {
                try { name = resources.getResourceEntryName(id); }
                catch (Resources.NotFoundException ignored) { name = ""; }
                names.put(id, name);
            }
            return name;
        }
    }

    private int target(Resources resources, int id) {
        if (id == 0) return 0;
        Map<Integer, Integer> cache;
        synchronized (targets) { cache = targets.computeIfAbsent(resources, key -> new HashMap<>()); }
        synchronized (cache) {
            Integer cached = cache.get(id);
            if (cached != null) return cached;
            int kind = 0;
            try {
                String pkg = resources.getResourcePackageName(id);
                if ("android".equals(pkg) || DarkBackgroundTargets.contains(pkg)) {
                    String name = resources.getResourceEntryName(id), type = resources.getResourceTypeName(id);
                    if ("color".equals(type)) {
                        if (DarkBackgroundPolicy.resource(name)) kind |= BACKGROUND;
                        if (legacyBackgroundFamily == 1 && "fd_sys_color_surface_bright_default".equals(name)
                                || legacyBackgroundFamily == 2 && "fd_sys_color_surface_container_lowest_default".equals(name))
                            kind |= LEGACY_BACKGROUND;
                        if ("com.meizu.mstore".equals(packageName) && "activity_background_color".equals(name)) kind |= STORE_BACKGROUND;
                    }
                }
            } catch (Resources.NotFoundException ignored) {}
            cache.put(id, kind);
            return kind;
        }
    }

    private boolean appManagementSurface(View view) {
        if (appManagementBackgroundFilter == null) return false;
        android.content.Context context = view.getContext();
        View root = view.getRootView();
        if (decorWindow != null && decorWindow.getDeclaringClass().isInstance(root)) {
            try {
                android.view.Window window = (android.view.Window) decorWindow.get(root);
                if (window != null) context = window.getContext();
            } catch (IllegalAccessException ignored) { }
        }
        for (int i = 0; i < 8 && context != null; i++) {
            if (context instanceof android.app.Activity activity) {
                String name = activity.getClass().getName();
                String fragment = "com.android.settings.SubSettings".equals(name)
                        ? activity.getIntent().getStringExtra(":settings:show_fragment") : null;
                return AppManagementBackgroundPolicy.page(packageName, name, fragment);
            }
            if (!(context instanceof android.content.ContextWrapper wrapper)) return false;
            android.content.Context base = wrapper.getBaseContext();
            if (base == context) return false;
            context = base;
        }
        return false;
    }

    private static boolean normalActivityBackground(View view) {
        if (AnrDialogHooks.nativeSurface(view)) return false;
        if(!(view.getRootView().getLayoutParams() instanceof android.view.WindowManager.LayoutParams params)
                ||params.type!=android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION)return false;
        for(View current=view;current!=null;current=current.getParent() instanceof View parent?parent:null) {
            for(Class<?> type=current.getClass();type!=null;type=type.getSuperclass()) {
                String name=type.getName();
                if(name.equals("com.android.launcher3.AbstractFloatingView") || name.contains("Popup")
                        ||name.contains("Dialog") ||name.contains("BottomSheet") ||name.contains("Floating"))return false;
            }
        }

        android.content.Context context=view.getContext();
        View root=view.getRootView();
        if(decorWindow!=null && decorWindow.getDeclaringClass().isInstance(root)) {
            try {
                android.view.Window window=(android.view.Window)decorWindow.get(root);
                if(window!=null)context=window.getContext();
            } catch(IllegalAccessException ignored) { }
        }
        for(int i=0;i<8 && context!=null;i++) {
            if(context instanceof android.app.Activity activity) {
                if(activity.getWindow().isFloating())return false;
                TypedArray attributes=activity.obtainStyledAttributes(new int[]{android.R.attr.windowIsTranslucent});
                try{return !attributes.getBoolean(0,false);}finally{attributes.recycle();}
            }
            if(!(context instanceof android.content.ContextWrapper wrapper))return false;
            android.content.Context base=wrapper.getBaseContext();if(base==context)return false;context=base;
        }
        return false;
    }

    private static boolean isColor(int kind) { return (kind & (COLOR | PRIMARY | BACKGROUND | STORE_BACKGROUND | LEGACY_BACKGROUND)) != 0; }
    private static boolean night(Resources resources) {
        return (resources.getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    private int color(int original, int kind, Resources resources) {
        if(preservingIconColors.get())return original;
        int mapped=original;
        if ((kind & (COLOR | PRIMARY)) != 0) mapped = palette.replaceResource(mapped, (kind & PRIMARY) != 0);
        if (original != mapped && firstMatch.compareAndSet(false, true))
            log.accept(String.format(Locale.ROOT, "Named theme resource first replacement: #%08X -> #%08X", original, mapped), null);
        return mapped;
    }

    private int stateColor(ColorStateList list, int original) {
        if (replacing.get() || preservingIconColors.get()) return original;
        ColorSource source = themedLists.get(list);
        Resources resources = source == null ? null : source.resources().get();
        return resources == null ? original : color(original, source.kind(), resources);
    }

    private ColorStateList colorList(ColorStateList original, int kind, Resources resources) throws ReflectiveOperationException {
        if (original == null || listStates == null || listColors == null) return original;
        if (listCanApplyTheme != null && Boolean.TRUE.equals(listCanApplyTheme.invoke(original))) {
            themedLists.put(original, new ColorSource(new WeakReference<>(resources), kind));
            // Preserve unresolved attribute arrays so the actual widget theme can still be applied.
            return original;
        }
        int[] colors = (int[]) listColors.invoke(original), mapped = null;
        for (int i = 0; i < colors.length; i++) {
            int next = color(colors[i], kind, resources);
            if (next != colors[i]) {
                if (mapped == null) mapped = colors.clone();
                mapped[i] = next;
            }
        }
        if (mapped == null) return original;
        int[][] states = (int[][]) listStates.invoke(original), copied = new int[states.length][];
        for (int i = 0; i < states.length; i++) copied[i] = states[i].clone();
        ColorStateList result = new ColorStateList(copied, mapped);
        ColorSource source = themedLists.get(original);
        if (source != null) themedLists.put(result, source);
        return result;
    }

    private void value(Resources resources, int id, TypedValue value) {
        if (value.type < TypedValue.TYPE_FIRST_COLOR_INT || value.type > TypedValue.TYPE_LAST_COLOR_INT) return;
        int kind = target(resources, id);
        if (isColor(kind)) value.data = color(value.data, kind, resources);
    }

    private void method(ClassLoader loader, Installer installer, String owner, String name,
                        XposedInterface.Hooker hooker, Class<?>... params) {
        try {
            installer.hook(Class.forName(owner, false, loader).getDeclaredMethod(name, params), hooker);
            installedHooks++;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log.accept("Cannot hook named theme resource " + owner + "#" + name, error);
        }
    }
}
