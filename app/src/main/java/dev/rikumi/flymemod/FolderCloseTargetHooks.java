package dev.rikumi.flymemod;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import android.view.View;
import android.view.ViewGroup;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Return targets verified against Flyme Launcher 13000000; never change workspace pages. */
final class FolderCloseTargetHooks {
    private static final String LAUNCHER = "com.android.launcher3.Launcher";
    private final Consumer<Context> settings;
    private final BooleanSupplier enabled;
    private final BiConsumer<String, Throwable> log;
    private final Class<?> stableType, folderIconType, bubbleType, itemType;
    private final Method getOpen, getWorkspace, assistant, visiblePages, hotseat;
    private final Method currentPage, matches, targetPackage, iconBounds;
    private final Field content, contents, user, itemKind;
    private final Class<?> launcherType, iconResultType;
    private final Map<View, Boolean> pendingCloseSnapshots = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<View, Boolean> activeCloseSnapshots = Collections.synchronizedMap(new WeakHashMap<>());
    private final ThreadLocal<Boolean> hidingSnapshotBackground = new ThreadLocal<>();

    FolderCloseTargetHooks(ClassLoader loader, Consumer<Context> settings,
            BooleanSupplier enabled, BiConsumer<String, Throwable> log) throws ReflectiveOperationException {
        this.settings = settings;
        this.enabled = enabled;
        this.log = log;
        Class<?> launcher = loader.loadClass(LAUNCHER);
        launcherType = launcher;
        iconResultType = loader.loadClass("com.android.launcher3.views.FloatingIconView$IconLoadResult");
        Class<?> folder = loader.loadClass("com.android.launcher3.folder.Folder");
        Class<?> workspace = loader.loadClass("com.android.launcher3.Workspace");
        stableType = loader.loadClass("com.android.launcher3.util.StableViewInfo");
        folderIconType = loader.loadClass("com.android.launcher3.folder.FolderIcon");
        bubbleType = loader.loadClass("com.android.launcher3.BubbleTextView");
        itemType = loader.loadClass("com.android.launcher3.model.data.ItemInfo");
        getOpen = folder.getMethod("getOpen", loader.loadClass("com.android.launcher3.views.ActivityContext"));
        getWorkspace = launcher.getMethod("getWorkspace");
        assistant = launcher.getMethod("isInAssistantPage");
        visiblePages = workspace.getMethod("forEachVisiblePage", Consumer.class);
        hotseat = workspace.getMethod("getHotseat");
        currentPage = loader.loadClass("com.android.launcher3.PagedView").getMethod("getCurrentPage");
        matches = stableType.getMethod("matches", itemType);
        targetPackage = itemType.getMethod("getTargetPackage");
        iconBounds = bubbleType.getMethod("getIconBounds", Rect.class);
        content = folder.getDeclaredField("mContent");
        content.setAccessible(true);
        contents = loader.loadClass("com.android.launcher3.model.data.FolderInfo").getField("contents");
        user = itemType.getField("user");
        itemKind = itemType.getField("itemType");
    }

    void install(SignalHooks.Installer installer) {
        installSnapshotBackground(installer);
        installer.hook(LAUNCHER, "getFirstHomeElementForAppClose", chain -> {
            Object launcher = chain.getThisObject();
            settings.accept((Context) launcher);
            Object original = chain.proceed();
            if (!enabled.getAsBoolean()) return original;
            try {
                if (Boolean.TRUE.equals(assistant.invoke(launcher))) return original;
                Object stable = chain.getArg(0);
                String pkg = (String) chain.getArg(1);
                UserHandle profile = (UserHandle) chain.getArg(2);
                Object open = getOpen.invoke(null, launcher);
                if (open != null) {
                    ViewGroup pager = (ViewGroup) content.get(open);
                    int index = (Integer) currentPage.invoke(pager);
                    View page = pager.getChildAt(index);
                    // Search only the displayed page, including pages supplied by horizontal paging.
                    View target = findIcon(page, stable, pkg, profile, true);
                    if (target == null) target = findIcon(page, stable, pkg, profile, false);
                    return target;
                }
                Object workspace = getWorkspace.invoke(launcher);
                List<View> pages = new ArrayList<>();
                visiblePages.invoke(workspace, (Consumer<View>) pages::add);
                View dock = (View) hotseat.invoke(workspace);
                if (dock != null) pages.add(dock);
                if (stable != null) {
                    for (View page : pages) {
                        View target = findFolder(page, stable, pkg, profile, true);
                        if (target != null) return target;
                    }
                }
                // Retain ordinary desktop/widget targets; big-folder preview targets become the folder.
                if (original instanceof View view) {
                    for (View parent = view; parent != null;
                            parent = parent.getParent() instanceof View v ? v : null) {
                        if (folderIconType.isInstance(parent)) return parent;
                    }
                    return original;
                }
                for (View page : pages) {
                    View target = findFolder(page, stable, pkg, profile, false);
                    if (target != null) return target;
                }
                return original;
            } catch (ReflectiveOperationException | RuntimeException error) {
                log.accept("Cannot resolve folder app-close animation target", error);
                return original;
            }
        }, stableType, String.class, UserHandle.class);
    }

    private void installSnapshotBackground(SignalHooks.Installer installer) {
        String floating = "com.android.launcher3.views.FloatingIconView";
        installer.hook(floating, "fetchIcon", chain -> {
            View view = (View) chain.getArg(1);
            boolean closing = enabled.getAsBoolean() && folderIconType.isInstance(view)
                    && !(Boolean) chain.getArg(3);
            if (closing) pendingCloseSnapshots.put(view, true);
            else pendingCloseSnapshots.remove(view);
            return chain.proceed();
        }, launcherType, View.class, itemType, boolean.class);
        // Icon loading runs on the model executor, then synchronously records folder layers
        // on the UI thread. Limit suppression to that closing snapshot's entire lifetime.
        installer.hook(floating, "getIconResult", chain -> {
            View view = (View) chain.getArg(1);
            boolean closing = pendingCloseSnapshots.remove(view) != null;
            if (closing) activeCloseSnapshots.put(view, true);
            try { return chain.proceed(); }
            finally { if (closing) activeCloseSnapshots.remove(view); }
        }, launcherType, View.class, itemType, RectF.class, Drawable.class, iconResultType);
        installer.hook("com.android.launcher3.dragndrop.FolderAdaptiveIcon", "initLayersOnUiThread", chain -> {
            View folder = (View) chain.getArg(0);
            if (!enabled.getAsBoolean() || !activeCloseSnapshots.containsKey(folder)) return chain.proceed();
            Boolean previous = hidingSnapshotBackground.get();
            hidingSnapshotBackground.set(true);
            try { return chain.proceed(); }
            finally {
                if (previous == null) hidingSnapshotBackground.remove();
                else hidingSnapshotBackground.set(previous);
            }
        }, folderIconType, int.class, Canvas.class, Canvas.class, Canvas.class);
        installer.hook("com.android.launcher3.folder.PreviewBackground", "drawBackground", chain ->
                Boolean.TRUE.equals(hidingSnapshotBackground.get()) ? null : chain.proceed(), Canvas.class);
    }

    private View findIcon(View view, Object stable, String pkg, UserHandle profile, boolean exact)
            throws ReflectiveOperationException {
        if (view == null) return null;
        if (bubbleType.isInstance(view) && matchesItem(view.getTag(), stable, pkg, profile, exact)
                && visibleIcon(view)) return view;
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                View result = findIcon(group.getChildAt(i), stable, pkg, profile, exact);
                if (result != null) return result;
            }
        }
        return null;
    }

    private View findFolder(View view, Object stable, String pkg, UserHandle profile, boolean exact)
            throws ReflectiveOperationException {
        if (view == null) return null;
        if (folderIconType.isInstance(view) && view.isAttachedToWindow()
                && view.getVisibility() == View.VISIBLE) {
            Object info = view.getTag();
            if (info != null && contents.getDeclaringClass().isInstance(info)) {
                for (Object item : (Iterable<?>) contents.get(info)) {
                    if (matchesItem(item, stable, pkg, profile, exact)) return view;
                }
            }
            return null;
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                View result = findFolder(group.getChildAt(i), stable, pkg, profile, exact);
                if (result != null) return result;
            }
        }
        return null;
    }

    private boolean matchesItem(Object item, Object stable, String pkg, UserHandle profile, boolean exact)
            throws ReflectiveOperationException {
        if (!itemType.isInstance(item) || profile == null || !profile.equals(user.get(item))) return false;
        if (exact) return stable != null && Boolean.TRUE.equals(matches.invoke(stable, item));
        return itemKind.getInt(item) == 0 && pkg != null && pkg.equals(targetPackage.invoke(item));
    }

    private boolean visibleIcon(View view) throws ReflectiveOperationException {
        Rect visible = new Rect();
        if (!view.getLocalVisibleRect(visible)) return false;
        Rect bounds = new Rect();
        iconBounds.invoke(view, bounds);
        return !bounds.isEmpty() && visible.contains(bounds);
    }
}
