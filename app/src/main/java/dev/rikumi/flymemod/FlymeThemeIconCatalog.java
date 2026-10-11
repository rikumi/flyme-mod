package dev.rikumi.flymemod;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.BitmapFactory;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.TreeSet;
import java.util.zip.ZipFile;

/** Flyme ThemeIconPackLoader index, native MTPK icon modules and adaptive-icon naming. */
final class FlymeThemeIconCatalog {
    static final String PREFIX = "flyme-theme:";
    static final String SYNTHETIC = "flyme-synth:";
    private static final String SYSTEM = "com.meizu.theme.system";
    private static final String[] THEME_DIRECTORIES = {"/sdcard/Customize/Themes",
            "/system/customizecenter/theme/mtpks", "/custom/meizu/theme/mtpks"};

    static List<ColorOsIconPackCatalog.Pack> packs(Context ctx) throws Exception {
        LinkedHashMap<String, String> names = new LinkedHashMap<>();
        File[] index = new File("/data/customizecenter/icon_to_launcher").listFiles();
        if (index != null) for (File item : index) {
            String name = item.getName();
            if (name.endsWith(".png")) name = name.substring(0, name.length() - 4);
            int separator = name.indexOf('_');
            if (separator > 0) names.put(name.substring(0, separator), name.substring(separator + 1));
        }
        if (new File("/system/customizecenter/theme/icons").isFile()) {
            int title = ctx.getResources().getIdentifier("icon_pack_default_icon_name", "string", ctx.getPackageName());
            names.put(SYSTEM, title == 0 ? "系统默认" : ctx.getString(title));
        }
        ArrayList<ColorOsIconPackCatalog.Pack> result = new ArrayList<>();
        for (var item : names.entrySet()) {
            if (validTheme(item.getKey()) && source(PREFIX + item.getKey()).isFile()) result.add(new ColorOsIconPackCatalog.Pack(
                    PREFIX + item.getKey(), item.getValue() + "（主题）"));
        }
        return result;
    }

    private static boolean validTheme(String theme) {
        return theme.matches("[A-Za-z0-9][A-Za-z0-9._-]*") && !theme.contains("..");
    }

    static File source(String key) {
        String theme = key.substring(PREFIX.length());
        if (!validTheme(theme)) throw new IllegalArgumentException("无效的主题名称");
        if (SYSTEM.equals(theme)) return new File("/system/customizecenter/theme/icons");
        for (String directory : THEME_DIRECTORIES) {
            File candidate = new File(directory, theme + ".mtpk");
            if (candidate.isFile()) return candidate;
        }
        return new File(THEME_DIRECTORIES[0], theme + ".mtpk");
    }

    static synchronized File archive(Context ctx, String key) throws Exception {
        File source = source(key);
        if (!source.isFile()) throw new java.io.FileNotFoundException("主题文件不存在：" + source);
        if (SYSTEM.equals(key.substring(PREFIX.length()))) return source;
        File directory = new File(ctx.getCacheDir(), "flymemod-theme-icons");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new java.io.IOException("无法创建图标缓存");
        String theme = key.substring(PREFIX.length());
        File cached = new File(directory, theme + "-" + source.length() + "-" + source.lastModified() + ".zip");
        if (!cached.isFile()) {
            File temporary = File.createTempFile("icons-", ".tmp", directory);
            try {
                FlymeThemeArchive.extract(source, temporary);
                if (!temporary.renameTo(cached)) throw new java.io.IOException("无法保存图标缓存");
                File[] old = directory.listFiles();
                if (old != null) for (File item : old) {
                    if (item.getName().startsWith(theme + "-") && !item.equals(cached)) item.delete();
                }
            } finally { temporary.delete(); }
        }
        return cached;
    }

    static List<ColorOsIconPackCatalog.Entry> entries(Context ctx, String key) throws Exception {
        return entries(archive(ctx, key));
    }

    static List<ColorOsIconPackCatalog.Entry> withApplication(List<ColorOsIconPackCatalog.Entry> entries, String pkg) {
        if (entries.stream().anyMatch(entry -> entry.drawable().equals(pkg))) return entries;
        ArrayList<ColorOsIconPackCatalog.Entry> result = new ArrayList<>(entries);
        result.add(0, new ColorOsIconPackCatalog.Entry(SYNTHETIC + pkg, "合成图标"));
        return result;
    }

    static boolean featured(ColorOsIconPackCatalog.Entry entry, String pkg) {
        return entry.drawable().equals(pkg) || entry.drawable().equals(SYNTHETIC + pkg);
    }

    static List<ColorOsIconPackCatalog.Entry> entries(File archive) throws Exception {
        TreeSet<String> packages = new TreeSet<>();
        try (ZipFile zip = new ZipFile(archive)) {
            var items = zip.entries();
            while (items.hasMoreElements()) {
                var item = items.nextElement();
                if (item.isDirectory()) continue;
                String name = item.getName();
                if (!name.endsWith(".png") || name.contains("/")) continue;
                name = name.substring(0, name.length() - 4);
                if (name.endsWith("_night")) name = name.substring(0, name.length() - 6);
                if (name.endsWith("_bg")) continue;
                if (name.endsWith("_fg")) name = name.substring(0, name.length() - 3);
                if (name.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")) packages.add(name);
            }
        }
        ArrayList<ColorOsIconPackCatalog.Entry> result = new ArrayList<>();
        for (String pkg : packages) result.add(new ColorOsIconPackCatalog.Entry(pkg, pkg));
        return result;
    }

    static Drawable drawable(Context ctx, String key, String pkg) throws Exception {
        boolean dark = (ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        try (ZipFile zip = new ZipFile(archive(ctx, key))) {
            if (pkg.startsWith(SYNTHETIC)) return synthesize(ctx, zip, pkg.substring(SYNTHETIC.length()));
            Drawable icon = dark ? raw(ctx, zip, pkg, "_night.png") : null;
            if (icon == null) icon = raw(ctx, zip, pkg, ".png");
            if (icon == null) throw new Resources.NotFoundException(key + ":" + pkg);
            return icon;
        }
    }

    private static Drawable synthesize(Context ctx, ZipFile zip, String pkg) throws Exception {
        Class<?> helper = ctx.getClassLoader().loadClass("android.content.res.flymetheme.FlymeThemeHelper");
        var original = helper.getDeclaredMethod("getIconFromPM", Resources.class, String.class);
        original.setAccessible(true);
        Drawable icon = (Drawable) original.invoke(null, ctx.getResources(), pkg);
        if (icon == null) throw new Resources.NotFoundException("无法读取应用原始图标：" + pkg);
        android.graphics.Bitmap pixels;
        if (icon instanceof AdaptiveIconDrawable adaptive) {
            var flatten = helper.getDeclaredMethod("transformAdaptiveIconDrawableToBitmap", AdaptiveIconDrawable.class);
            flatten.setAccessible(true);
            pixels = (android.graphics.Bitmap) flatten.invoke(null, adaptive);
        } else if (icon instanceof BitmapDrawable bitmap) {
            pixels = bitmap.getBitmap();
        } else {
            pixels = ColorOsIconPackCatalog.render(icon, Math.max(1, Math.max(icon.getIntrinsicWidth(), icon.getIntrinsicHeight())));
        }
        // Each selected theme gets a separate native filter instance. Never
        // replace IconFilter's global singleton or the currently applied theme.
        var config = zip.getEntry("filter_config.xml");
        if (config != null) {
            Class<?> filterClass = ctx.getClassLoader().loadClass("android.content.res.flymetheme.iconfilter.IconFilter");
            var constructor = filterClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object filter = constructor.newInstance();
            try (var input = zip.getInputStream(config)) {
                filterClass.getMethod("setConfig", java.io.InputStream.class).invoke(filter, input);
            }
            pixels = pixels.copy(android.graphics.Bitmap.Config.ARGB_8888, true);
            pixels = (android.graphics.Bitmap) filterClass.getMethod("filter", android.graphics.Bitmap.class).invoke(filter, pixels);
        }
        Drawable mask = image(ctx, zip, "icon_mask.png");
        Drawable background = image(ctx, zip, "icon_background.png");
        Drawable border = image(ctx, zip, "icon_border.png");
        if (mask == null && background == null && border == null) {
            if (icon instanceof AdaptiveIconDrawable adaptive && config == null) {
                return (Drawable) helper.getMethod("setScaleAndCornerForAdaptiveIconDrawable", AdaptiveIconDrawable.class,
                        float.class, String.class).invoke(null, adaptive, 1f, "corner_size_1");
            }
            return (Drawable) helper.getMethod("makeSystemMaskIcon", Resources.class, android.graphics.Bitmap.class,
                    String.class).invoke(null, ctx.getResources(), pixels, "corner_size_1");
        }
        if (mask instanceof BitmapDrawable bitmap) {
            var applyMask = helper.getDeclaredMethod("makeMaskedBitmap", android.graphics.Bitmap.class, android.graphics.Bitmap.class);
            applyMask.setAccessible(true);
            pixels = (android.graphics.Bitmap) applyMask.invoke(null, bitmap.getBitmap(), pixels);
        }
        var layer = helper.getMethod("addBackguard", android.graphics.Bitmap.class, android.graphics.Bitmap.class, boolean.class);
        if (background instanceof BitmapDrawable bitmap) pixels = (android.graphics.Bitmap) layer.invoke(null, bitmap.getBitmap(), pixels, false);
        if (border instanceof BitmapDrawable bitmap) pixels = (android.graphics.Bitmap) layer.invoke(null, pixels, bitmap.getBitmap(), true);
        return new BitmapDrawable(ctx.getResources(), pixels);
    }

    private static Drawable image(Context ctx, ZipFile zip, String name) throws Exception {
        var entry = zip.getEntry(name);
        if (entry == null) return null;
        try (var input = zip.getInputStream(entry)) {
            var bitmap = BitmapFactory.decodeStream(input);
            if (bitmap == null) throw new java.io.IOException("无法读取主题图标：" + name);
            return new BitmapDrawable(ctx.getResources(), bitmap);
        }
    }

    private static Drawable raw(Context ctx, ZipFile zip, String pkg, String suffix) throws Exception {
        Drawable icon = image(ctx, zip, pkg + suffix);
        if (icon != null) return icon;
        Drawable foreground = image(ctx, zip, pkg + "_fg" + suffix);
        if (foreground == null) return null;
        Drawable background = image(ctx, zip, pkg + "_bg" + suffix);
        if (background == null) background = image(ctx, zip, "background" + suffix);
        if (background == null) return null;
        // Flyme's native constructor preserves the system squircle mask.
        return AdaptiveIconDrawable.class.getConstructor(Drawable.class, Drawable.class, String.class)
                .newInstance(background, foreground, "corner_size_1");
    }
}
