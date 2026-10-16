package dev.rikumi.flymemod;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.Collections;
import java.util.WeakHashMap;
import java.util.function.Predicate;
import java.lang.reflect.Method;

/** Apply the ROM's night foreground palette without changing surface colors or content artwork. */
final class ControlCenterForegroundHooks {
    private final Predicate<Context> enabled;
    private final Predicate<View> inCenter;
    private final Map<Resources, Map<Integer, Integer>> palettes = new WeakHashMap<>();
    private final Map<TextView, Tint> texts = new WeakHashMap<>();
    private final Map<ImageView, Tint> icons = new WeakHashMap<>();
    private final Map<ImageView, Filter> filters = new WeakHashMap<>();
    private final Set<View> views = Collections.newSetFromMap(new WeakHashMap<>());
    private final Method filterColor, filterMode;

    ControlCenterForegroundHooks(Predicate<Context> enabled, Predicate<View> inCenter) throws ReflectiveOperationException {
        this.enabled = enabled; this.inCenter = inCenter;
        // Public in Flyme's framework, omitted from the SDK stubs.
        filterColor = PorterDuffColorFilter.class.getMethod("getColor");
        filterMode = PorterDuffColorFilter.class.getMethod("getMode");
    }

    void install(SignalHooks.Installer installer) {
        installer.hook("android.widget.TextView", "onDraw", chain -> {
            TextView view = (TextView) chain.getThisObject();
            boolean center = inCenter.test(view);
            if (center) views.add(view);
            boolean active = center && enabled.test(view.getContext());
            Tint saved = texts.get(view);
            if (active) {
                ColorStateList current = view.getTextColors();
                ColorStateList nativeColors = saved != null && current == saved.applied ? saved.original : current;
                int original = nativeColors.getColorForState(view.getDrawableState(), nativeColors.getDefaultColor());
                int target = color(view.getResources(), original);
                if (target != original) {
                    if (saved == null || current != saved.applied) saved = new Tint(current);
                    ColorStateList applied = saved.apply(target);
                    if (current != applied) view.setTextColor(applied);
                    texts.put(view, saved);
                } else {
                    if (saved != null && current == saved.applied) view.setTextColor(saved.original);
                    texts.remove(view);
                }
            } else if (saved != null) {
                if (view.getTextColors() == saved.applied) view.setTextColor(saved.original);
                texts.remove(view);
            }
            return chain.proceed();
        }, Canvas.class);
        installer.hook("android.widget.ImageView", "onDraw", chain -> {
            ImageView view = (ImageView) chain.getThisObject();
            boolean center = inCenter.test(view);
            if (center) views.add(view);
            boolean active = center && enabled.test(view.getContext());
            ColorStateList current = view.getImageTintList();
            Tint saved = icons.get(view);
            if (active && current != null) {
                ColorStateList nativeColors = saved != null && current == saved.applied ? saved.original : current;
                int original = nativeColors.getColorForState(view.getDrawableState(), nativeColors.getDefaultColor());
                int target = color(view.getResources(), original);
                if (target != original) {
                    if (saved == null || current != saved.applied) saved = new Tint(current);
                    ColorStateList applied = saved.apply(target);
                    if (current != applied) view.setImageTintList(applied);
                    icons.put(view, saved);
                } else {
                    if (saved != null && current == saved.applied) view.setImageTintList(saved.original);
                    icons.remove(view);
                }
            } else if (saved != null) {
                if (current == saved.applied) view.setImageTintList(saved.original);
                icons.remove(view);
            }
            // Native media buttons use ImageView color filters rather than tint lists.
            ColorFilter currentFilter = view.getColorFilter();
            Filter filter = filters.get(view);
            if (active && currentFilter instanceof PorterDuffColorFilter nativeFilter) {
                if (filter == null || (currentFilter != filter.applied && currentFilter != filter.original))
                    filter = new Filter(nativeFilter, (Integer) filterColor.invoke(nativeFilter),
                            (PorterDuff.Mode) filterMode.invoke(nativeFilter));
                int target = color(view.getResources(), filter.color);
                if (target != filter.color) {
                    if (filter.applied == null || filter.appliedColor != target) {
                        filter.applied = new PorterDuffColorFilter(target, filter.mode);
                        filter.appliedColor = target;
                    }
                    if (currentFilter != filter.applied) view.setColorFilter(filter.applied);
                } else {
                    if (currentFilter == filter.applied) view.setColorFilter(filter.original);
                }
                filters.put(view, filter);
            } else if (filter != null) {
                if (currentFilter == filter.applied) view.setColorFilter(filter.original);
                filters.remove(view);
            }
            return chain.proceed();
        }, Canvas.class);
    }

    private int color(Resources resources, int original) {
        Map<Integer, Integer> palette = palettes.get(resources);
        if (palette == null) {
            palette = new HashMap<>();
            for (String name : new String[]{"qs_tile_label_color", "qs_tile_secondlabel_color",
                    "qs_icon_inactive_color", "qs_icon_unavailable_color",
                    "qs_media_header_artist", "qs_meida_icon_inactive_color"}) {
                int day = resources.getIdentifier(name, "color", "com.android.systemui");
                int night = resources.getIdentifier(name + "_for_night", "color", "com.android.systemui");
                if (day != 0 && night != 0) palette.put(resources.getColor(day, null), resources.getColor(night, null));
            }
            palettes.put(resources, palette);
        }
        return palette.getOrDefault(original, original);
    }

    void refresh() {
        for (View view : new ArrayList<>(views)) view.invalidate();
    }

    private static final class Tint {
        final ColorStateList original;
        ColorStateList applied;
        Tint(ColorStateList original) { this.original = original; }
        ColorStateList apply(int color) {
            if (applied == null || applied.getDefaultColor() != color) applied = ColorStateList.valueOf(color);
            return applied;
        }
    }
    private static final class Filter {
        final PorterDuffColorFilter original;
        final int color;
        final PorterDuff.Mode mode;
        int appliedColor;
        PorterDuffColorFilter applied;
        Filter(PorterDuffColorFilter original, int color, PorterDuff.Mode mode) {
            this.original = original; this.color = color; this.mode = mode;
        }
    }
}
