package dev.rikumi.flymemod;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/** Static, named settings glyphs: replace actual blue pixels without depending on a runtime shader. */
final class SettingsBlueIcon {
    private final Map<Drawable.ConstantState, Map<Integer, Bitmap>> cache = new WeakHashMap<>();

    synchronized Drawable map(Drawable original, Resources resources, int color, ThemeColorPalette palette) {
        Drawable.ConstantState state = original.getConstantState();
        if (state == null || original instanceof Animatable) return original;
        int width = original.getIntrinsicWidth(), height = original.getIntrinsicHeight();
        if (width <= 0 || height <= 0 || width > 512 || height > 512) return original;
        Map<Integer, Bitmap> colors = cache.computeIfAbsent(state, key -> new HashMap<>());
        Bitmap mapped = colors.get(color);
        if (mapped == null) {
            Drawable copy = state.newDrawable(resources).mutate();
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            copy.setBounds(0, 0, width, height);
            copy.draw(new Canvas(bitmap));
            int[] pixels = new int[width * height];
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
            boolean changed = false;
            for (int i = 0; i < pixels.length; i++) {
                int next = palette.replaceResource(pixels[i], true);
                changed |= next != pixels[i];
                pixels[i] = next;
            }
            if (!changed) { bitmap.recycle(); return original; }
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
            mapped = bitmap;
            // Keep one chosen color per source glyph; previous drawables retain their bitmap safely.
            colors.clear();
            colors.put(color, mapped);
        }
        return new BitmapDrawable(resources, mapped);
    }
}
