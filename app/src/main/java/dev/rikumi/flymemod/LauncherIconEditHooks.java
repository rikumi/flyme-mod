package dev.rikumi.flymemod;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.json.JSONObject;

/** Flyme Launcher 13000000: native shortcut row and independent rendering overrides. */
final class LauncherIconEditHooks {
    private static final String POPUP = "com.android.launcher3.popup.PopupContainerWithArrow";
    private static final String CONTAINER = "com.meizu.flyme.view.SystemShortcutContainer";
    private static final String BUBBLE = "com.android.launcher3.BubbleTextView";
    private static final String ITEM = "com.android.launcher3.model.data.ItemInfoWithIcon";
    private final ClassLoader loader;
    private final Consumer<Context> settings;
    private final BooleanSupplier enabled;
    private final BiConsumer<String, Throwable> log;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ColorOsIconPackCatalog catalog = new ColorOsIconPackCatalog();
    private final ThreadLocal<View> menuIcon = new ThreadLocal<>();
    private final Field title, description, bitmap, user, itemType, iconPixels, bitmapFlags, bitmapColor;
    private final Method component, bitmapOf, cloneItem;
    private volatile Context context;
    private volatile SharedPreferences edits;
    private boolean lastEnabled;
    private ContentObserver observer;
    private final java.util.concurrent.ExecutorService saves = Executors.newSingleThreadExecutor();

    LauncherIconEditHooks(ClassLoader loader, Consumer<Context> settings, BooleanSupplier enabled,
            BiConsumer<String, Throwable> log) throws ReflectiveOperationException {
        this.loader = loader; this.settings = settings; this.enabled = enabled; this.log = log;
        Class<?> item = loader.loadClass(ITEM), info = loader.loadClass("com.android.launcher3.model.data.ItemInfo");
        title = info.getField("title");
        description = info.getField("contentDescription");
        user = info.getField("user"); itemType = info.getField("itemType"); bitmap = item.getField("bitmap");
        component = info.getMethod("getTargetComponent");
        cloneItem = item.getMethod("clone");
        item.getMethod("newIcon", Context.class, int.class);
        Class<?> bitmapInfo = loader.loadClass("com.android.launcher3.icons.BitmapInfo");
        bitmapOf = bitmapInfo.getMethod("of", Bitmap.class, int.class);
        iconPixels = bitmapInfo.getField("icon"); bitmapFlags = bitmapInfo.getField("flags"); bitmapColor = bitmapInfo.getField("color");
        loader.loadClass(POPUP).getDeclaredMethod("populateAndShowRowsFlyme",
                loader.loadClass("com.android.launcher3.Launcher"), loader.loadClass(BUBBLE), int.class);
        loader.loadClass(CONTAINER).getDeclaredMethod("setShortcuts", LayoutInflater.class, List.class, boolean.class);
    }

    void install(SignalHooks.Installer installer, Consumer<Method> deoptimize) throws ReflectiveOperationException {
        installer.hook("com.meizu.flyme.launcher.LauncherApplication", "onCreate", chain -> {
            context = ((Context) chain.getThisObject()).getApplicationContext();
            edits = context.getSharedPreferences("flymemod_icon_edits", Context.MODE_PRIVATE);
            settings.accept(context); lastEnabled = enabled.getAsBoolean();
            if (observer == null) {
                observer = new ContentObserver(main) {
                    @Override public void onChange(boolean selfChange) {
                        settings.accept(context);
                        boolean current = enabled.getAsBoolean();
                        if (current == lastEnabled) return;
                        lastEnabled = current;
                        try {
                            Object model = model();
                            model.getClass().getMethod("forceReload").invoke(model);
                        }
                        catch (ReflectiveOperationException error) { log.accept("Cannot refresh icon editing switch", error); }
                    }
                };
                context.getContentResolver().registerContentObserver(ModuleSettings.URI, false, observer);
            }
            return chain.proceed();
        });
        installer.hook(POPUP, "populateAndShowRowsFlyme", chain -> {
            View view = (View) chain.getArg(1);
            settings.accept(view.getContext());
            if (!enabled.getAsBoolean() || !editable(view.getTag())) return chain.proceed();
            menuIcon.set(view);
            try { return chain.proceed(); } finally { menuIcon.remove(); }
        }, loader.loadClass("com.android.launcher3.Launcher"), loader.loadClass(BUBBLE), int.class);
        installer.hook(CONTAINER, "setShortcuts", chain -> {
            Object result = chain.proceed();
            View icon = menuIcon.get();
            if (icon != null) {
                try { addShortcut((ViewGroup) chain.getThisObject(), (LayoutInflater) chain.getArg(0), icon); }
                catch (ReflectiveOperationException | RuntimeException error) { log.accept("Cannot add icon editing shortcut", error); }
            }
            return result;
        }, LayoutInflater.class, List.class, boolean.class);
        installer.hook(BUBBLE, "applyLabel", chain -> {
            Object item = chain.getArg(0);
            if (enabled.getAsBoolean() && editable(item)) {
                Object copy = null;
                try {
                    LauncherIconEditor.Edit edit = read(item);
                    if (edit != null && edit.title() != null) {
                        copy = cloneItem.invoke(item);
                        title.set(copy, edit.title()); description.set(copy, edit.title());
                    }
                } catch (Exception error) {
                    log.accept("Cannot apply edited app name", error);
                    return chain.proceed();
                }
                // Native text wrapping, archived-app formatting and accessibility
                // operate on a copy. Model title and future database writes stay native.
                return copy == null ? chain.proceed() : chain.proceed(new Object[]{copy});
            }
            return chain.proceed();
        }, loader.loadClass("com.android.launcher3.model.data.ItemInfo"));
        installer.hook(ITEM, "newIcon", chain -> {
            Object item = chain.getThisObject();
            if (!enabled.getAsBoolean() || !editable(item) || edits == null) return chain.proceed();
            Object custom = customBitmap(item, bitmap.get(item));
            if (custom == null) return chain.proceed();
            Object copy = cloneItem.invoke(item);
            bitmap.set(copy, custom);
            // Retain the native disabled badge, work-profile badge, icon shape
            // and press animation, without ever modifying the original ItemInfo.
            return chain.proceedWith(copy);
        }, Context.class, int.class);
        String workspace = "com.android.launcher3.model.data.WorkspaceItemInfo";
        deoptimize.accept(loader.loadClass(CONTAINER).getDeclaredMethod("setShortcuts", LayoutInflater.class, List.class, boolean.class));
        for (Method method : loader.loadClass(CONTAINER).getDeclaredMethods())
            if (method.getName().equals("inflate")) deoptimize.accept(method);
        deoptimize.accept(loader.loadClass(POPUP).getDeclaredMethod("showForIcon", loader.loadClass(BUBBLE)));
        deoptimize.accept(loader.loadClass(BUBBLE).getDeclaredMethod("applyFromWorkspaceItem",
                loader.loadClass(workspace)));
        deoptimize.accept(loader.loadClass(BUBBLE).getDeclaredMethod("applyFromApplicationInfo",
                loader.loadClass("com.android.launcher3.model.data.AppInfo")));
        deoptimize.accept(loader.loadClass(BUBBLE).getDeclaredMethod("applyFromItemInfoWithIcon", loader.loadClass(ITEM)));
        deoptimize.accept(loader.loadClass(BUBBLE).getDeclaredMethod("applyIconAndLabel", loader.loadClass(ITEM)));
        // Folder previews and all-apps rows also create icons via this overload.
        deoptimize.accept(loader.loadClass(ITEM).getDeclaredMethod("newIcon", Context.class));
        deoptimize.accept(loader.loadClass(BUBBLE).getDeclaredMethod("setNonPendingIcon", loader.loadClass(ITEM)));
        deoptimize.accept(loader.loadClass("com.android.launcher3.folder.PreviewItemManager").getDeclaredMethod(
                "setDrawable", loader.loadClass("com.android.launcher3.folder.PreviewItemDrawingParams"),
                loader.loadClass("com.android.launcher3.model.data.ItemInfo")));
        log.accept("Flyme launcher icon/name editing hooks registered", null);
    }

    private boolean editable(Object item) {
        try { return item != null && bitmap.getDeclaringClass().isInstance(item)
                && itemType.getInt(item) == 0 && component.invoke(item) != null; }
        catch (ReflectiveOperationException error) { return false; }
    }
    private String key(Object item) throws ReflectiveOperationException {
        return ((ComponentName) component.invoke(item)).flattenToString() + "@" + user.get(item);
    }
    private LauncherIconEditor.Edit read(Object item) throws Exception {
        String stored = edits == null ? null : edits.getString(key(item), null);
        if (stored == null) return null;
        JSONObject json = new JSONObject(stored);
        return new LauncherIconEditor.Edit(json.optString("title", null), json.optString("pack", null),
                json.optString("drawable", null), json.optBoolean("mask", false));
    }
    private Object customBitmap(Object item, Object original) {
        if (original == null || context == null) return null;
        try {
            LauncherIconEditor.Edit edit = read(item);
            if (edit == null || edit.pack() == null || edit.drawable() == null) return null;
            Bitmap base = (Bitmap) iconPixels.get(original);
            int size = base == null || base.getWidth() < 32
                    ? Math.round(64 * context.getResources().getDisplayMetrics().density) : base.getWidth();
            Bitmap pixels = catalog.bitmap(context, edit.pack(), edit.drawable(), size, edit.mask());
            Object custom = bitmapOf.invoke(null, pixels, bitmapColor.getInt(original));
            bitmapFlags.setInt(custom, bitmapFlags.getInt(original));
            return custom;
        } catch (Exception error) {
            log.accept("Cannot load edited launcher icon; using native icon", error);
            return null;
        }
    }

    private void addShortcut(ViewGroup container, LayoutInflater inflater, View icon) throws ReflectiveOperationException {
        Field normal = container.getClass().getDeclaredField("mNormalLayout"); normal.setAccessible(true);
        ViewGroup rows = (ViewGroup) normal.get(container);
        int layout = container.getResources().getIdentifier("item_system_shortcut", "layout", context.getPackageName());
        if (layout == 0) throw new IllegalStateException("Missing Flyme item_system_shortcut");
        View row = inflater.inflate(layout, rows, false);
        TextView text = (TextView) row.getClass().getMethod("getTxtView").invoke(row);
        ImageView image = (ImageView) row.getClass().getMethod("getIcon").invoke(row);
        text.setText("编辑");
        image.setBackground(LauncherIconEditor.flymeDrawable(context, "mz_titlebar_ic_edit"));
        image.setBackgroundTintList(android.content.res.ColorStateList.valueOf(text.getCurrentTextColor()));
        row.setOnClickListener(view -> {
            try {
                Object item = icon.getTag();
                if (!editable(item)) return;
                settings.accept(icon.getContext());
                if (!enabled.getAsBoolean()) return;
                // Close the native popup before opening an Activity-owned editor.
                Object launcher = loader.loadClass("com.android.launcher3.Launcher")
                        .getMethod("getLauncher", Context.class).invoke(null, icon.getContext());
                loader.loadClass("com.android.launcher3.AbstractFloatingView")
                        .getMethod("closeAllOpenViews", loader.loadClass("com.android.launcher3.views.ActivityContext"))
                        .invoke(null, launcher);
                Object original = bitmap.get(item);
                Bitmap pixels = original == null ? null : (Bitmap) iconPixels.get(original);
                CharSequence nativeTitle = (CharSequence) title.get(item);
                String label = nativeTitle == null ? ((ComponentName) component.invoke(item)).getPackageName() : nativeTitle.toString();
                new LauncherIconEditor((Context) launcher, catalog, log).show(label, ((ComponentName) component.invoke(item)).getPackageName(), ((ComponentName) component.invoke(item)).flattenToString(), pixels, read(item),
                        (edit, done) -> save(item, edit, done));
            } catch (Exception error) {
                log.accept("Cannot open launcher icon editor", error);
                Toast.makeText(icon.getContext(), "打开编辑界面失败：" + error.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        rows.addView(row);
        Field views = container.getClass().getDeclaredField("mShortcutViews"); views.setAccessible(true);
        ((List<View>) views.get(container)).add(row);
    }

    private Object model() throws ReflectiveOperationException {
        Object state = loader.loadClass("com.android.launcher3.LauncherAppState")
                .getMethod("getInstance", Context.class).invoke(null, context);
        return state.getClass().getMethod("getModel").invoke(state);
    }
    private void save(Object item, LauncherIconEditor.Edit edit, Runnable done) {
        saves.execute(() -> {
            try {
                SharedPreferences.Editor writer = edits.edit();
                if (edit == null || (edit.title() == null && edit.pack() == null)) writer.remove(key(item));
                else writer.putString(key(item), new JSONObject().put("title", edit.title())
                        .put("pack", edit.pack()).put("drawable", edit.drawable()).put("mask", edit.mask()).toString());
                if (!writer.commit()) throw new IllegalStateException("无法保存图标设置");
                ComponentName target = (ComponentName) component.invoke(item);
                Object model = model();
                model.getClass().getMethod("onAppIconChanged", String.class, UserHandle.class)
                        .invoke(model, target.getPackageName(), user.get(item));
                main.post(() -> { Toast.makeText(context, "成功", Toast.LENGTH_SHORT).show(); done.run(); });
            } catch (Exception error) {
                log.accept("Cannot save launcher icon edit", error);
                main.post(() -> { Toast.makeText(context, "保存失败：" + error.getMessage(), Toast.LENGTH_LONG).show(); done.run(); });
            }
        });
    }
}
