package dev.rikumi.flymemod;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import java.util.HashMap;

/** Applies the active launcher's decoration without looking up a replacement app icon. */
final class FlymeCurrentIconMask {
    private static final HashMap<String, Object> items = new HashMap<>();
    record Style(String theme, String pack, String corner, String signature) {}

    static Style style(Context ctx) throws Exception {
        ClassLoader loader = ctx.getClassLoader();
        String theme = (String) loader.loadClass("com.meizu.flyme.launcher.iconpack.ThemeIconPackLoader")
                .getMethod("getCurrentThemeIcon", Context.class).invoke(null, ctx);
        String pack = (String) loader.loadClass("com.meizu.flyme.launcher.iconpack.AppIconPackLoader")
                .getMethod("getCurrentIconPack", Context.class).invoke(null, ctx);
        String corner = (String) loader.loadClass("com.meizu.flyme.launcher.utils.ThemeIconUtils")
                .getMethod("getIconCornerSizeValue", int.class).invoke(null, loader.loadClass("com.meizu.flyme.launcher.utils.ThemeIconUtils")
                        .getMethod("getCurrentIconCornerSizeIndex", Context.class).invoke(null, ctx));
        String signature = theme + ":" + pack + ":" + corner;
        Class<?> utils = loader.loadClass("android.content.res.flymetheme.FlymeThemeUtils");
        String directory = (String) utils.getMethod("getThemePath").invoke(null);
        signature += ":" + new java.io.File(directory, "icons").lastModified();
        if ("com.meizu.theme.system".equals(theme) && !"SYSTEM_ICONS".equals(pack)) {
            signature += ":" + ctx.getPackageManager().getPackageInfo(pack, 0).lastUpdateTime;
        }
        return new Style(theme, pack, corner, signature);
    }

    static Bitmap apply(Context ctx, Bitmap source, int size, Style style) throws Exception {
        if ("com.meizu.theme.system".equals(style.theme) && !"SYSTEM_ICONS".equals(style.pack)) {
            Object item;
            synchronized (items) {
                item = items.get(style.signature);
                if (item == null) {
                    Class<?> type = ctx.getClassLoader().loadClass("com.meizu.flyme.launcher.iconpack.AppIconPackItem");
                    item = type.getConstructor().newInstance();
                    type.getField("packageName").set(item, style.pack);
                    type.getMethod("load", Context.class).invoke(item, ctx);
                    var backField = type.getDeclaredField("mBackImages");
                    var maskField = type.getDeclaredField("mMaskImages");
                    var frontField = type.getDeclaredField("mFrontImages");
                    backField.setAccessible(true); maskField.setAccessible(true); frontField.setAccessible(true);
                    java.util.List<Bitmap> back = (java.util.List<Bitmap>) backField.get(item);
                    // Native changeIcon skips the entire pipeline without an
                    // iconback. An empty background enables mask/overlay-only
                    // packs on this private instance without altering the pack.
                    if (back.isEmpty() && (!((java.util.List<?>) maskField.get(item)).isEmpty()
                            || !((java.util.List<?>) frontField.get(item)).isEmpty())) {
                        back.add(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888));
                    }
                    items.clear(); items.put(style.signature, item);
                }
            }
            synchronized (item) {
                Bitmap result = (Bitmap) item.getClass().getMethod("changeIcon", Context.class, Bitmap.class, int.class)
                        .invoke(item, ctx, source, size);
                if (result == null) throw new java.io.IOException("图标包遮罩合成失败");
                return result;
            }
        }
        Class<?> helper = ctx.getClassLoader().loadClass("android.content.res.flymetheme.FlymeThemeHelper");
        Drawable result;
        if (!"com.meizu.theme.system".equals(style.theme)) {
            // Native current-theme filter, mask, background and border pipeline.
            result = (Drawable) helper.getMethod("makeFlymeStyleIcon", Resources.class, Bitmap.class,
                    android.content.pm.ApplicationInfo.class).invoke(null, ctx.getResources(), source, null);
        } else {
            result = (Drawable) helper.getMethod("makeSystemMaskIcon", Resources.class, Bitmap.class, String.class)
                    .invoke(null, ctx.getResources(), source, style.corner);
        }
        return ColorOsIconPackCatalog.render(result, size);
    }
}
