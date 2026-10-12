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

/** Resource names, not arbitrary Paint RGB values, determine which theme colors are replaced. */
final class ThemeColorHooks {
    interface Installer { void hook(Executable target, XposedInterface.Hooker hooker); }
    private static final int COLOR = 1, PRIMARY = 2, ICON = 4, BACKGROUND = 8, CUTOUT_ICON = 16, STORE_BACKGROUND = 32, RED_PROTECTED_ICON = 64, LEGACY_BACKGROUND = 128, INACTIVE_TAB_ICON = 256;
    private final SharedPreferences preferences;
    private final int themeFamily;
    private final int legacyBackgroundFamily;
    private final String packageName;
    private final Map<View, Boolean> storePageBackgrounds = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<View, Boolean> dialogButtons = Collections.synchronizedMap(new WeakHashMap<>());
    private final BiConsumer<String, Throwable> log;
    private final AtomicBoolean firstMatch = new AtomicBoolean();
    private final Map<Resources, Map<Integer, Integer>> targets = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<Drawable, Integer> icons = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<Drawable.ConstantState, Integer> iconStates = Collections.synchronizedMap(new WeakHashMap<>());
    private final Set<Executable> iconDrawMethods = new HashSet<>();
    private final ThreadLocal<Boolean> drawingIcon = ThreadLocal.withInitial(() -> false);
    private final ThreadLocal<Paint> iconPaint = ThreadLocal.withInitial(Paint::new);
    private final ThreadLocal<Paint> sliderPaint = ThreadLocal.withInitial(Paint::new);
    private final ThreadLocal<Boolean> replacing = ThreadLocal.withInitial(() -> false);
    private volatile ThemeColorPalette palette = new ThemeColorPalette(false, 0);
    private volatile ThemeIconFilter iconFilters;
    private volatile boolean blackBackground;
    private volatile ThemeBackgroundFilter backgroundFilter;
    private volatile ThemeBackgroundFilter storeBackgroundFilter;
    private volatile ThemeBackgroundFilter windowBackgroundFilter;
    private final android.graphics.ColorFilter sidebarBlack = new PorterDuffColorFilter(0xff000000, PorterDuff.Mode.SRC_IN);
    private final ThreadLocal<Paint> backgroundPaint = ThreadLocal.withInitial(Paint::new);
    private boolean configured, enabled;
    private int selectedColor, installedHooks;
    private Method listStates, listColors, listCanApplyTheme;
    private final Map<ImageView, Integer> assignedIcons = Collections.synchronizedMap(new WeakHashMap<>());
    private final SettingsBlueIcon settingsBlueIcons = new SettingsBlueIcon();
    private final ThreadLocal<Boolean> assigningIcon = ThreadLocal.withInitial(() -> false);
    private final AtomicBoolean settingsSwitchMatch = new AtomicBoolean();
    private final AtomicBoolean settingsIconMatch = new AtomicBoolean();
    private record ColorSource(WeakReference<Resources> resources, int kind) {}
    private final Map<ColorStateList, ColorSource> themedLists = Collections.synchronizedMap(new WeakHashMap<>());
    private final SharedPreferences.OnSharedPreferenceChangeListener changes = (prefs, key) -> {
        if (key == null || ModuleSettings.DARK_SYSTEM_APP_BLACK_BACKGROUND.equals(key) || ModuleSettings.SYSTEM_THEME_COLOR_ENABLED.equals(key)
                || ModuleSettings.SYSTEM_THEME_COLOR.equals(key)) read();
    };

    ThemeColorHooks(String packageName, SharedPreferences preferences, BiConsumer<String, Throwable> log) {
        this.packageName = packageName;
        this.legacyBackgroundFamily = ThemeColorTargets.legacyBackgroundFamily(packageName);
        this.themeFamily = ThemeColorTargets.themeFamily(packageName);
        this.preferences = preferences;
        this.log = log;
    }

    private synchronized void read() {
        try {
            boolean nextEnabled = preferences.getBoolean(ModuleSettings.SYSTEM_THEME_COLOR_ENABLED, false)
                    && !ThemeColorTargets.colorExcluded(packageName);
            int nextColor = preferences.getInt(ModuleSettings.SYSTEM_THEME_COLOR, ModuleSettings.SYSTEM_THEME_COLOR_DEFAULT);
            boolean nextBlack = preferences.getBoolean(ModuleSettings.DARK_SYSTEM_APP_BLACK_BACKGROUND, false);
            if (configured && enabled == nextEnabled && selectedColor == nextColor && blackBackground == nextBlack) return;
            blackBackground = nextBlack;
            backgroundFilter = null;
            storeBackgroundFilter = null;
            windowBackgroundFilter = null;
            if (nextBlack) {
                try {
                    backgroundFilter = new ThemeBackgroundFilter(DarkBackgroundPolicy.legacyShades(legacyBackgroundFamily));
                    windowBackgroundFilter = new ThemeBackgroundFilter(true);
                    if ("com.meizu.mstore".equals(packageName)) storeBackgroundFilter = windowBackgroundFilter;
                }
                catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                    log.accept("Cannot create pure black background filter", error);
                }
            }
            palette = new ThemeColorPalette(nextEnabled, nextColor, ThemeColorTargets.colors(), themeFamily);
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
            log.accept("Named theme resources configured: package=" + packageName + ", enabled=" + enabled
                    + ", color=" + String.format(Locale.ROOT, "#%08X", selectedColor), null);
        } catch (RuntimeException error) {
            configured = false;
            blackBackground = false;
            backgroundFilter = null;
            storeBackgroundFilter = null;
            windowBackgroundFilter = null;
            palette = new ThemeColorPalette(false, 0);
            iconFilters = null;
            log.accept("Cannot read system theme color", error);
        }
    }

    void install(ClassLoader loader, Installer installer, Consumer<Executable> deoptimizer) {
        read();
        preferences.registerOnSharedPreferenceChangeListener(changes);
        try {
            listStates = ColorStateList.class.getDeclaredMethod("getStates");
            listColors = ColorStateList.class.getDeclaredMethod("getColors");
            listCanApplyTheme = ColorStateList.class.getDeclaredMethod("canApplyTheme");
        } catch (ReflectiveOperationException error) { log.accept("Cannot resolve color state data", error); }
        if (settingsProcess()) {
            method(loader, installer, "com.meizu.common.widget.Switch", "getColorFromState", chain -> {
                int original = (int) chain.proceed();
                int mapped = palette.replaceResource(original, true);
                if (mapped != original && settingsSwitchMatch.compareAndSet(false, true))
                    log.accept("Settings Switch native track color replaced", null);
                return mapped;
            }, ColorStateList.class);
            method(loader, installer, "android.widget.ImageView", "setImageResource", chain -> {
                ImageView view = (ImageView) chain.getThisObject();
                int kind = target(view.getResources(), (int) chain.getArg(0));
                Object result = chain.proceed();
                int mode = iconKindMode(kind);
                if (mode == ThemeIconPolicy.NONE) assignedIcons.remove(view);
                else assignedIcons.put(view, mode);
                applySettingsIcon(view, mode);
                return result;
            }, int.class);
            method(loader, installer, "android.widget.ImageView", "setImageDrawable", chain -> {
                if (assigningIcon.get()) return chain.proceed();
                ImageView view = (ImageView) chain.getThisObject();
                int mode = iconMode((Drawable) chain.getArg(0));
                Object result = chain.proceed();
                if (mode == ThemeIconPolicy.NONE) assignedIcons.remove(view);
                else assignedIcons.put(view, mode);
                applySettingsIcon(view, mode);
                return result;
            }, Drawable.class);
            method(loader, installer, "android.widget.ImageView", "setImageTintList", chain -> {
                ImageView view = (ImageView) chain.getThisObject();
                if (!assignedIcons.containsKey(view) || !enabled) return chain.proceed();
                return chain.proceed(new Object[]{colorList((ColorStateList) chain.getArg(0), PRIMARY, view.getResources())});
            }, ColorStateList.class);
            // Switch.getColorFromState is final and called from this animation method; deopt the caller too.
            deopt(loader, deoptimizer, "com.meizu.common.widget.Switch", "setThumbPosition", float.class);
            deopt(loader, deoptimizer, "com.meizu.common.widget.Switch", "draw", Canvas.class);
            deopt(loader, deoptimizer, "com.meizu.common.widget.Switch", "onDraw", Canvas.class);
        }
        if (settingsProcess()) {
            for (String owner : new String[]{"android.widget.AbsSeekBar",
                    "com.meizu.common.widget.AbsSeekBar", "com.meizu.common.widget.StepSeekBar",
                    "com.meizu.common.widget.EnhanceSeekBar"}) {
                method(loader, installer, owner, "onDraw", chain -> {
                    ThemeIconFilter filters = iconFilters;
                    if (filters == null) return chain.proceed();
                    View view = (View) chain.getThisObject();
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
            if (dialog.getWindow() != null) markDialogButtons(dialog.getWindow().getDecorView());
            return result;
        });
        // Gallery caches TypedValue.data directly, bypassing Resources.getColor.
        method(loader, installer, "android.content.res.Resources$Theme", "resolveAttribute", chain -> {
            Object result = chain.proceed();
            TypedValue value = (TypedValue) chain.getArg(1);
            if (!Boolean.TRUE.equals(result) || replacing.get()
                    || value.type < TypedValue.TYPE_FIRST_COLOR_INT || value.type > TypedValue.TYPE_LAST_COLOR_INT) return result;
            Resources resources = ((Resources.Theme) chain.getThisObject()).getResources();
            try {
                String name = resources.getResourceEntryName((int) chain.getArg(0));
                if (ThemeTabPolicy.primaryAttribute(name)) value.data = palette.replaceResource(value.data, true);
                if (legacyBackgroundFamily == 1 && "colorSurfaceBright".equals(name)
                        || legacyBackgroundFamily == 2 && "colorSurfaceContainerLowest".equals(name))
                    value.data = DarkBackgroundPolicy.replaceLegacy(value.data, blackBackground, night(resources), legacyBackgroundFamily);
            } catch (Resources.NotFoundException ignored) {}
            return result;
        }, int.class, TypedValue.class, boolean.class);
        if ("com.meizu.mstore".equals(packageName)) {
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
        // Public Resources and TypedArray both reach this verified framework entry.
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
        method(loader, installer, "android.content.res.ResourcesImpl", "loadDrawable", chain -> {
            Resources resources = (Resources) chain.getArg(0);
            int kind = target(resources, (int) chain.getArg(2));
            Drawable original = (Drawable) chain.proceed();
            if (original != null && (kind == ICON || kind == CUTOUT_ICON || kind == RED_PROTECTED_ICON || kind == INACTIVE_TAB_ICON)) {
                int mode = kind == CUTOUT_ICON ? ThemeIconPolicy.WHITE_CUTOUT :
                        kind == RED_PROTECTED_ICON ? ThemeIconPolicy.PRESERVE_RED :
                        kind == INACTIVE_TAB_ICON ? ThemeIconPolicy.INACTIVE_TAB : ThemeIconPolicy.COLOR_PARTS;
                icons.put(original, mode);
                Drawable.ConstantState state = original.getConstantState();
                if (state != null) iconStates.put(state, mode);
                installIconDraw(original, installer);
            }
            return original;
        }, Resources.class, TypedValue.class, int.class, int.class, Resources.Theme.class);
        // Preferences may copy their cached BitmapDrawable via ConstantState before ImageView sees it.
        method(loader, installer, "android.widget.ImageView", "onDraw", chain -> {
            ImageView view = (ImageView) chain.getThisObject();
            ThemeIconFilter filters = iconFilters;
            int mode = assignedIcons.getOrDefault(view, iconMode(view.getDrawable()));
            String id = viewId(view);
            if (ThemeTabPolicy.galleryIcon(packageName, id)) mode = ThemeIconPolicy.COLOR_PARTS;
            if (ThemeTabPolicy.storeIcon(packageName, id) || "com.meizu.mstore".equals(packageName)
                    && "icon".equals(id) && storeTabParent(view) != null)
                mode = selectedStoreTab(view) ? ThemeIconPolicy.WHITE_CUTOUT : ThemeIconPolicy.INACTIVE_TAB;
            if (ThemeTabPolicy.installerUpgradeIcon(packageName, id)) mode = ThemeIconPolicy.WHITE_CUTOUT;
            if (filters == null || drawingIcon.get() || mode == ThemeIconPolicy.NONE) return chain.proceed();
            Canvas canvas = (Canvas) chain.getArg(0);
            Paint paint = iconPaint.get();
            paint.setColorFilter(filters.forMode(mode, night(view.getResources())));
            int saved = canvas.saveLayer(0, 0, view.getWidth(), view.getHeight(), paint);
            drawingIcon.set(true);
            try { return chain.proceed(); }
            finally {
                drawingIcon.set(false);
                canvas.restoreToCount(saved);
            }
        }, Canvas.class);
        method(loader, installer, "android.widget.TextView", "onDraw", chain -> {
            TextView view = (TextView) chain.getThisObject();
            ThemeIconFilter filter = iconFilters;
            boolean button = dialogButton(view);
            if (filter == null || !button && !ThemeTabPolicy.galleryText(packageName, viewId(view))) return chain.proceed();
            Canvas canvas = (Canvas) chain.getArg(0);
            Paint paint = iconPaint.get();
            paint.setColorFilter(button && themeFamily != 3 ? filter.preserveRedParts : filter.colorParts);
            int saved = canvas.saveLayer(0, 0, view.getWidth(), view.getHeight(), paint);
            try { return chain.proceed(); }
            finally { canvas.restoreToCount(saved); }
        }, Canvas.class);
        // Verified on the device framework: this entry draws only mBackground, including RenderNodes.
        method(loader, installer, "android.view.View", "drawBackground", chain -> {
            View view = (View) chain.getThisObject();
            ThemeIconFilter dialogColors = dialogButton(view) ? iconFilters : null;
            boolean sidebar = storeSidebar(view);
            boolean storeGlobal = sidebar || storePageBackgrounds.containsKey(view)
                    || ThemeTabPolicy.storeGlobalBackground(packageName, viewId(view))
                    || "com.meizu.mstore".equals(packageName)
                    && view.getClass().getName().equals("com.ashokvarma.bottomnavigation.BottomNavigationBar");
            // The decor only draws the window surface. Its old and new dark shades can all
            // become black without changing similarly colored list and group backgrounds.
            boolean windowSurface = "com.android.internal.policy.DecorView".equals(view.getClass().getName());
            ThemeBackgroundFilter filter = windowSurface ? windowBackgroundFilter
                    : storeGlobal ? storeBackgroundFilter : backgroundFilter;
            Drawable background = view.getBackground();
            if (dialogColors == null && (!blackBackground || !sidebar && filter == null)
                    || background == null || dialogColors == null && background instanceof BitmapDrawable
                    || dialogColors == null && !night(view.getResources())) return chain.proceed();
            Canvas canvas = (Canvas) chain.getArg(0);
            Paint paint = backgroundPaint.get();
            paint.setColorFilter(dialogColors != null
                    ? (themeFamily == 3 ? dialogColors.colorParts : dialogColors.preserveRedParts)
                    : sidebar ? sidebarBlack : filter.filter);
            int saved = canvas.saveLayer(view.getScrollX(), view.getScrollY(),
                    view.getScrollX() + view.getWidth(), view.getScrollY() + view.getHeight(), paint);
            try { return chain.proceed(); }
            finally { canvas.restoreToCount(saved); }
        }, Canvas.class);
        log.accept("Named theme resource hooks registered: hooks=" + installedHooks, null);
    }

    private boolean settingsProcess() {
        return "com.android.settings".equals(packageName) || "com.meizu.connectivitysettings".equals(packageName);
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

    private static int iconKindMode(int kind) {
        return kind == ICON ? ThemeIconPolicy.COLOR_PARTS : kind == CUTOUT_ICON ? ThemeIconPolicy.WHITE_CUTOUT :
                kind == RED_PROTECTED_ICON ? ThemeIconPolicy.PRESERVE_RED :
                kind == INACTIVE_TAB_ICON ? ThemeIconPolicy.INACTIVE_TAB : ThemeIconPolicy.NONE;
    }

    private void applySettingsIcon(ImageView view, int mode) {
        Drawable original = view.getDrawable();
        if (!enabled || mode == ThemeIconPolicy.NONE || original == null || assigningIcon.get()) return;
        assigningIcon.set(true);
        try {
            boolean wasDrawing = drawingIcon.get();
            Drawable mapped;
            drawingIcon.set(true);
            try { mapped = settingsBlueIcons.map(original, view.getResources(), selectedColor, palette); }
            finally { drawingIcon.set(wasDrawing); }
            if (mapped != original) {
                view.setImageDrawable(mapped);
                if (settingsIconMatch.compareAndSet(false, true)) log.accept("Settings named blue glyph pixels replaced", null);
            }
            ColorStateList tint = view.getImageTintList();
            if (tint != null) view.setImageTintList(colorList(tint, PRIMARY, view.getResources()));
        } catch (ReflectiveOperationException | RuntimeException error) {
            log.accept("Cannot replace settings blue glyph", error);
        } finally { assigningIcon.set(false); }
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

    private static String viewId(View view) {
        try { return view.getResources().getResourceEntryName(view.getId()); }
        catch (Resources.NotFoundException ignored) { return ""; }
    }

    private int iconMode(Drawable drawable) {
        if (drawable == null) return ThemeIconPolicy.NONE;
        Integer mode = icons.get(drawable);
        if (mode != null) return mode;
        Drawable.ConstantState state = drawable.getConstantState();
        return state == null ? ThemeIconPolicy.NONE : iconStates.getOrDefault(state, ThemeIconPolicy.NONE);
    }

    private synchronized void installIconDraw(Drawable drawable, Installer installer) {
        try {
            // Resolve the actual implementation, preserving Drawable subclasses and native state behavior.
            Method draw = drawable.getClass().getMethod("draw", Canvas.class);
            if (iconDrawMethods.contains(draw)) return;
            installer.hook(draw, chain -> {
                ThemeIconFilter filters = iconFilters;
                Drawable current = (Drawable) chain.getThisObject();
                int mode = iconMode(current);
                if (filters == null || drawingIcon.get() || mode == ThemeIconPolicy.NONE) return chain.proceed();
                Canvas canvas = (Canvas) chain.getArg(0);
                Rect bounds = current.getDirtyBounds();
                Paint paint = iconPaint.get();
                paint.setColorFilter(filters.forMode(mode, night(Resources.getSystem())));
                int saved = canvas.saveLayer(bounds.left, bounds.top, bounds.right, bounds.bottom, paint);
                drawingIcon.set(true);
                try { return chain.proceed(); }
                finally {
                    drawingIcon.set(false);
                    canvas.restoreToCount(saved);
                }
            });
            iconDrawMethods.add(draw);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log.accept("Cannot hook named icon drawable " + drawable.getClass().getName(), error);
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
                if ("android".equals(pkg) || ThemeColorTargets.contains(pkg)) {
                    String name = resources.getResourceEntryName(id), type = resources.getResourceTypeName(id);
                    if ("color".equals(type)) {
                        if (ThemeColorTargets.isColorResource(pkg, name))
                            kind = ThemeColorTargets.isPrimaryResource(pkg, name) ? PRIMARY : COLOR;
                        if (DarkBackgroundPolicy.resource(name)) kind |= BACKGROUND;
                        if (legacyBackgroundFamily == 1 && "fd_sys_color_surface_bright_default".equals(name)
                                || legacyBackgroundFamily == 2 && "fd_sys_color_surface_container_lowest_default".equals(name))
                            kind |= LEGACY_BACKGROUND;
                        if ("com.meizu.mstore".equals(packageName) && "activity_background_color".equals(name)) kind |= STORE_BACKGROUND;
                    }
                    else if ("drawable".equals(type) || "mipmap".equals(type)) {
                        int mode = ThemeIconPolicy.mode(pkg, name);
                        kind = mode == ThemeIconPolicy.COLOR_PARTS ? ICON : mode == ThemeIconPolicy.WHITE_CUTOUT ? CUTOUT_ICON :
                                mode == ThemeIconPolicy.PRESERVE_RED ? RED_PROTECTED_ICON :
                                mode == ThemeIconPolicy.INACTIVE_TAB ? INACTIVE_TAB_ICON : 0;
                    }
                }
            } catch (Resources.NotFoundException ignored) {}
            cache.put(id, kind);
            return kind;
        }
    }

    private static boolean isColor(int kind) { return (kind & (COLOR | PRIMARY | BACKGROUND | STORE_BACKGROUND | LEGACY_BACKGROUND)) != 0; }
    private static boolean night(Resources resources) {
        return (resources.getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    private int color(int original, int kind, Resources resources) {
        int mapped = (kind & BACKGROUND) == 0 ? original : DarkBackgroundPolicy.replace(original, blackBackground, night(resources));
        if ((kind & LEGACY_BACKGROUND) != 0) mapped = DarkBackgroundPolicy.replaceLegacy(mapped, blackBackground, night(resources), legacyBackgroundFamily);
        if ((kind & STORE_BACKGROUND) != 0) mapped = DarkBackgroundPolicy.replaceStoreGlobal(mapped, blackBackground, night(resources));
        if ((kind & (COLOR | PRIMARY)) != 0) mapped = palette.replaceResource(mapped, (kind & PRIMARY) != 0);
        if (original != mapped && firstMatch.compareAndSet(false, true))
            log.accept(String.format(Locale.ROOT, "Named theme resource first replacement: #%08X -> #%08X", original, mapped), null);
        return mapped;
    }

    private int stateColor(ColorStateList list, int original) {
        if (replacing.get()) return original;
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
