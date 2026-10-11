package dev.rikumi.flymemod;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.LruCache;
import android.util.Xml;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import org.xmlpull.v1.XmlPullParser;

/** Port of ColorOS UxIconPackLoader / util.d's resource catalog, without Oplus framework APIs. */
final class ColorOsIconPackCatalog {
    record Pack(String pkg, String name) {
        @Override public String toString() { return name; }
    }
    record Entry(String drawable, String name, List<String> components) {
        Entry(String drawable, String name) { this(drawable, name, List.of()); }
        boolean matches(String query) {
            return drawable.toLowerCase(Locale.ROOT).contains(query)
                    || name.toLowerCase(Locale.ROOT).contains(query);
        }
    }
    private final LruCache<String, Bitmap> images = new LruCache<>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getAllocationByteCount(); }
    };

    List<Pack> packs(Context ctx) throws Exception {
        PackageManager pm = ctx.getPackageManager();
        LinkedHashMap<String, Pack> found = new LinkedHashMap<>();
        // Flyme's AppIconPackLoader discovers these two actions. Extra standard
        // actions also allow packs whose launcher entry differs from ADW's.
        for (String action : new String[]{"org.adw.launcher.THEMES", "com.gau.go.launcherex.theme",
                "com.novalauncher.THEME", "com.anddoes.launcher.THEME"}) {
            for (ResolveInfo info : pm.queryIntentActivities(new Intent(action), 0)) {
                if (info.activityInfo == null) continue;
                String pkg = info.activityInfo.packageName;
                found.putIfAbsent(pkg, new Pack(pkg,
                        info.activityInfo.applicationInfo.loadLabel(pm).toString()));
            }
        }
        ArrayList<Pack> result = new ArrayList<>(found.values());
        result.addAll(FlymeThemeIconCatalog.packs(ctx));
        result.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        return result;
    }

    List<Entry> entries(Context ctx, String pkg) throws Exception {
        if (pkg.startsWith(FlymeThemeIconCatalog.PREFIX)) return FlymeThemeIconCatalog.entries(ctx, pkg);
        Resources resources = ctx.getPackageManager().getResourcesForApplication(pkg);
        LinkedHashMap<String, Entry> found = new LinkedHashMap<>();
        // ColorOS tries theme_iconpack first, then icon_pack, followed by
        // res/xml/appfilter or assets/appfilter.xml. Keep the same precedence.
        int array = resources.getIdentifier("theme_iconpack", "array", pkg);
        if (array == 0) array = resources.getIdentifier("icon_pack", "array", pkg);
        if (array != 0) for (String name : resources.getStringArray(array)) add(resources, pkg, found, name, null);
        readXml(resources, pkg, "appfilter", found);
        // drawable.xml includes manually selectable alternatives that have no
        // application mapping; expose those as well in the full-screen picker.
        readXml(resources, pkg, "drawable", found);
        ArrayList<Entry> result = new ArrayList<>(found.values());
        result.sort((a, b) -> a.drawable.compareToIgnoreCase(b.drawable));
        return result;
    }

    private void readXml(Resources resources, String pkg, String file, LinkedHashMap<String, Entry> found)
            throws Exception {
        int xmlId = resources.getIdentifier(file, "xml", pkg);
        if (xmlId != 0) {
            try (XmlResourceParser parser = resources.getXml(xmlId)) { parse(parser, resources, pkg, found); }
        } else {
            InputStream stream;
            try { stream = resources.getAssets().open(file + ".xml"); }
            catch (java.io.FileNotFoundException missing) { return; }
            try (InputStream input = stream) {
                XmlPullParser parser = Xml.newPullParser();
                parser.setInput(input, "UTF-8");
                parse(parser, resources, pkg, found);
            }
        }
    }

    private void parse(XmlPullParser parser, Resources resources, String pkg, LinkedHashMap<String, Entry> found)
            throws Exception {
        for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
            if (event == XmlPullParser.START_TAG && "item".equalsIgnoreCase(parser.getName())) {
                String title = parser.getAttributeValue(null, "name");
                add(resources, pkg, found, parser.getAttributeValue(null, "drawable"), title,
                        parser.getAttributeValue(null, "component"));
            }
        }
    }

    private void add(Resources res, String pkg, LinkedHashMap<String, Entry> found, String name, String title) {
        add(res, pkg, found, name, title, null);
    }

    private void add(Resources res, String pkg, LinkedHashMap<String, Entry> found, String name, String title, String component) {
        if (name == null || name.isBlank()) return;
        name = resourceName(name);
        if (res.getIdentifier(name, "drawable", pkg) == 0 && res.getIdentifier(name, "mipmap", pkg) == 0) return;
        if (title != null && title.startsWith("@string/")) {
            int id = res.getIdentifier(title.substring(8), "string", pkg);
            title = id == 0 ? null : res.getString(id);
        }
        Entry previous = found.get(name);
        List<String> components = new ArrayList<>(previous == null ? List.of() : previous.components);
        String normalized = normalizeComponent(component);
        if (normalized != null && !components.contains(normalized)) components.add(normalized);
        found.put(name, new Entry(name, previous == null ? (title == null || title.isBlank() ? name : title)
                : previous.name, List.copyOf(components)));
    }

    private static String normalizeComponent(String component) {
        if (component == null || component.isBlank()) return null;
        component = component.trim();
        if (component.startsWith("ComponentInfo{") && component.endsWith("}")) {
            component = component.substring(14, component.length() - 1);
        }
        int slash = component.indexOf('/');
        if (slash < 0) return component;
        String pkg = component.substring(0, slash), activity = component.substring(slash + 1);
        if (activity.startsWith(".")) activity = pkg + activity;
        return pkg + "/" + activity;
    }

    static java.util.Set<String> matching(List<Entry> entries, String component) {
        String normalized = normalizeComponent(component);
        if (normalized == null) return java.util.Set.of();
        String pkg = normalized.split("/", 2)[0];
        java.util.LinkedHashSet<String> exact = new java.util.LinkedHashSet<>(), samePackage = new java.util.LinkedHashSet<>();
        String fallback = normalized.toLowerCase(Locale.ROOT).replace('.', '_').replace('/', '_');
        for (Entry entry : entries) {
            if (entry.components.contains(normalized) || entry.drawable.equals(fallback)) exact.add(entry.drawable);
            for (String mapped : entry.components) {
                if (mapped.equals(pkg) || mapped.startsWith(pkg + "/")) samePackage.add(entry.drawable);
            }
        }
        return exact.isEmpty() ? samePackage : exact;
    }

    static String resourceName(String name) {
        int slash = name.lastIndexOf('/');
        return slash < 0 ? name : name.substring(slash + 1);
    }

    Bitmap bitmap(Context ctx, String pkg, String name, int size) throws Exception {
        String key = pkg + ":" + name + ":" + size;
        if (pkg.startsWith(FlymeThemeIconCatalog.PREFIX)) {
            key += ":" + (ctx.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                    + ":" + FlymeThemeIconCatalog.source(pkg).lastModified();
        }
        Bitmap cached = images.get(key);
        if (cached != null) return cached;
        if (pkg.startsWith(FlymeThemeIconCatalog.PREFIX)) {
            Bitmap bitmap = render(FlymeThemeIconCatalog.drawable(ctx, pkg, name), size);
            images.put(key, bitmap);
            return bitmap;
        }
        Resources resources = ctx.getPackageManager().getResourcesForApplication(pkg);
        int id = resources.getIdentifier(name, "drawable", pkg);
        if (id == 0) id = resources.getIdentifier(name, "mipmap", pkg);
        if (id == 0) throw new Resources.NotFoundException(pkg + ":" + name);
        Drawable drawable = resources.getDrawable(id, null);
        Bitmap bitmap = render(drawable, size);
        images.put(key, bitmap);
        return bitmap;
    }

    Bitmap bitmap(Context ctx, String pkg, String name, int size, boolean mask) throws Exception {
        Bitmap base = bitmap(ctx, pkg, name, size);
        if (!mask) return base;
        FlymeCurrentIconMask.Style style = FlymeCurrentIconMask.style(ctx);
        String key = pkg + ":" + name + ":" + size + ":mask:" + style.signature()
                + ":" + ctx.getResources().getConfiguration().uiMode;
        if (pkg.startsWith(FlymeThemeIconCatalog.PREFIX)) key += ":" + FlymeThemeIconCatalog.source(pkg).lastModified();
        Bitmap cached = images.get(key);
        if (cached != null) return cached;
        Bitmap result = FlymeCurrentIconMask.apply(ctx, base, size, style);
        images.put(key, result);
        return result;
    }

    static Bitmap render(Drawable drawable, int size) {
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        int width = Math.max(1, drawable.getIntrinsicWidth()), height = Math.max(1, drawable.getIntrinsicHeight());
        float scale = Math.min((float) size / width, (float) size / height);
        int w = Math.round(width * scale), h = Math.round(height * scale);
        drawable.setBounds((size - w) / 2, (size - h) / 2, (size + w) / 2, (size + h) / 2);
        drawable.draw(new Canvas(bitmap));
        return bitmap;
    }
}
