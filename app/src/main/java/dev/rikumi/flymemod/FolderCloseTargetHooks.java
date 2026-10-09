package dev.rikumi.flymemod;

import android.content.Context;
import android.graphics.Rect;
import android.os.UserHandle;
import android.view.View;
import android.view.ViewGroup;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Restore app-close targets only on the displayed page of an open Flyme folder. */
final class FolderCloseTargetHooks {
    private static final String LAUNCHER = "com.android.launcher3.Launcher";
    private final Consumer<Context> settings;
    private final BooleanSupplier enabled;
    private final BiConsumer<String, Throwable> log;
    private final Class<?> stableType, bubbleType, itemType;
    private final Method getOpen, assistant;
    private final Method currentPage, matches, targetPackage, iconBounds;
    private final Field content, user, itemKind;

    FolderCloseTargetHooks(ClassLoader loader, Consumer<Context> settings,
            BooleanSupplier enabled, BiConsumer<String, Throwable> log) throws ReflectiveOperationException {
        this.settings = settings;
        this.enabled = enabled;
        this.log = log;
        Class<?> launcher = loader.loadClass(LAUNCHER);
        Class<?> folder = loader.loadClass("com.android.launcher3.folder.Folder");
        stableType = loader.loadClass("com.android.launcher3.util.StableViewInfo");
        bubbleType = loader.loadClass("com.android.launcher3.BubbleTextView");
        itemType = loader.loadClass("com.android.launcher3.model.data.ItemInfo");
        getOpen = folder.getMethod("getOpen", loader.loadClass("com.android.launcher3.views.ActivityContext"));
        assistant = launcher.getMethod("isInAssistantPage");
        currentPage = loader.loadClass("com.android.launcher3.PagedView").getMethod("getCurrentPage");
        matches = stableType.getMethod("matches", itemType);
        targetPackage = itemType.getMethod("getTargetPackage");
        iconBounds = bubbleType.getMethod("getIconBounds", Rect.class);
        content = folder.getDeclaredField("mContent");
        content.setAccessible(true);
        user = itemType.getField("user");
        itemKind = itemType.getField("itemType");
    }

    void install(SignalHooks.Installer installer) {
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
                return original;
            } catch (ReflectiveOperationException | RuntimeException error) {
                log.accept("Cannot resolve folder app-close animation target", error);
                return original;
            }
        }, stableType, String.class, UserHandle.class);
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
