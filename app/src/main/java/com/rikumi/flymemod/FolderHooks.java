package com.rikumi.flymemod;

import android.graphics.Point;
import android.graphics.Rect;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.IdentityHashMap;
import java.util.function.BooleanSupplier;
import io.github.libxposed.api.XposedInterface;

/** Folder paths verified against Flyme Launcher 13000000. */
final class FolderHooks {
    interface Installer {
        void hook(String name, String method, XposedInterface.Hooker hooker, Class<?>... parameters);
    }

    private static final String FOLDER = "com.android.launcher3.folder.";
    private static final String FLYME = "com.meizu.flyme.launcher.folder.";
    private static final int COLUMNS = 3;
    private static final int ROWS = 4;
    private static final int PAGE_SIZE = COLUMNS * ROWS;
    private final ClassLoader loader;
    private final Installer installer;
    private final BooleanSupplier paging;
    private final BooleanSupplier center;
    // Preview organizers retain their own geometry; only open-folder organizers change.
    private final Map<Object, Boolean> organizers = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<View, View> indicators = new WeakHashMap<>();
    private final Map<ViewGroup, HiddenPages> animationPages = new WeakHashMap<>();
    private final ThreadLocal<Map<Object, Integer>> pageOverrides = ThreadLocal.withInitial(WeakHashMap::new);
    private final ThreadLocal<Object> closingFolder = new ThreadLocal<>();

    FolderHooks(ClassLoader loader, Installer installer, BooleanSupplier paging, BooleanSupplier center) {
        this.loader = loader;
        this.installer = installer;
        this.paging = paging;
        this.center = center;
    }

    void install() throws ReflectiveOperationException {
        Class<?> folderType = type(FOLDER + "Folder");
        Class<?> pagerType = type(FOLDER + "FolderPagedView");
        Class<?> scrollType = type(FLYME + "MzFolderScrollView");
        Class<?> itemType = type("com.android.launcher3.model.data.ItemInfo");
        Class<?> layoutType = type("com.android.launcher3.views.BaseDragLayer$LayoutParams");
        Class<?> dragType = type("com.android.launcher3.DropTarget$DragObject");

        installer.hook(FOLDER + "FolderPagedView", "setFolder", chain -> {
            Object result = chain.proceed();
            Object organizer = field(chain.getThisObject(), "mOrganizer");
            organizers.put(organizer, true);
            if (paging.getAsBoolean()) setGrid(organizer);
            return result;
        }, folderType);
        installer.hook(FOLDER + "FolderGridOrganizer", "setContentSize", chain -> {
            Object result = chain.proceed();
            if (isPagingOrganizer(chain.getThisObject())) setGrid(chain.getThisObject());
            return result;
        }, int.class);
        installer.hook(FOLDER + "FolderGridOrganizer", "getMaxItemsPerPage", chain ->
                isPagingOrganizer(chain.getThisObject()) ? PAGE_SIZE : chain.proceed());
        installer.hook(FOLDER + "FolderGridOrganizer", "getPosForRank", chain -> {
            if (!isPagingOrganizer(chain.getThisObject())) return chain.proceed();
            int rank = (Integer) chain.getArg(0) % PAGE_SIZE;
            return new Point(rank % COLUMNS, rank / COLUMNS);
        }, int.class);

        // FolderPagedView deliberately skips PagedView's paging implementation.
        // Clear those gates in the base entry points, after the folder overrides set them.
        for (String method : new String[]{"onTouchEvent", "onInterceptTouchEvent"}) {
            String gate = method.equals("onTouchEvent") ? "needOverOnTouchEvent" : "needOverOnInterceptTouchEvent";
            installer.hook("com.android.launcher3.PagedView", method, chain -> {
                if (paging.getAsBoolean() && pagerType.isInstance(chain.getThisObject())) {
                    setField(chain.getThisObject(), gate, false);
                }
                return chain.proceed();
            }, MotionEvent.class);
            installer.hook(FLYME + "MzFolderScrollView", method, chain ->
                    paging.getAsBoolean() ? false : chain.proceed(), MotionEvent.class);
        }
        installer.hook("com.android.launcher3.PagedView", "requestDisallowInterceptTouchEvent", chain -> {
            if (paging.getAsBoolean() && pagerType.isInstance(chain.getThisObject())) {
                setField(chain.getThisObject(), "needOverRequestDisallowInterceptTouchEvent", false);
            }
            return chain.proceed();
        }, boolean.class);
        installer.hook(FLYME + "MzFolderScrollView", "getDesiredHeight", chain ->
                paging.getAsBoolean() ? (Integer) call(chain.getThisObject(), "getCellHeight") * ROWS : chain.proceed());
        installer.hook(FLYME + "MzFolderPagedViewHelper", "recordRowCount", chain -> {
            return paging.getAsBoolean() ? chain.proceed(new Object[]{chain.getArg(0), ROWS}) : chain.proceed();
        }, pagerType, int.class);
        installer.hook(FLYME + "MzFolderPagedViewHelper", "setFixedSize", chain -> {
            if (!paging.getAsBoolean()) return chain.proceed();
            ViewGroup pager = (ViewGroup) chain.getArg(0);
            for (int i = 0; i < pager.getChildCount(); i++) {
                call(pager.getChildAt(i), "setFixedSize", new Class<?>[]{int.class, int.class}, chain.getArg(1), chain.getArg(2));
            }
            return null;
        }, pagerType, int.class, int.class);
        installer.hook(FLYME + "MzFolderPagedViewHelper", "reLayoutPreventVisionDiff", chain -> {
            if (!paging.getAsBoolean()) return chain.proceed();
            View page = (View) chain.getArg(0);
            if (page != null) {
                call(page, "setRow", new Class<?>[]{int.class}, ROWS);
                call(page, "setOpenFromAlarmRow", new Class<?>[]{int.class}, ROWS);
                page.getLayoutParams().height = (Integer) call(page, "getCellHeight") * ROWS;
            }
            return null;
        }, scrollType, int.class);
        installer.hook(FLYME + "MzFolderHelper", "initializeAutoScroll", chain ->
                paging.getAsBoolean() ? null : chain.proceed(), pagerType);
        installer.hook(FLYME + "MzFolderHelper", "autoScroll", chain -> {
            if (!paging.getAsBoolean()) return chain.proceed();
            Object drag = chain.getArg(3);
            View footer = (View) chain.getArg(1);
            setField(drag, "y", (Integer) field(drag, "y") - footer.getPaddingBottom());
            return false;
        }, pagerType, View.class, float.class, dragType);

        // addViewForRank computes a page index but Flyme always inserts into page zero.
        installer.hook(FOLDER + "FolderPagedView", "getPageAt", chain -> {
            Integer page = pageOverrides.get().get(chain.getThisObject());
            return page != null && (Integer) chain.getArg(0) == 0
                    ? chain.proceed(new Object[]{page}) : chain.proceed();
        }, int.class);
        installer.hook(FOLDER + "FolderPagedView", "addViewForRank", chain -> {
            if (!paging.getAsBoolean()) return chain.proceed();
            Object pager = chain.getThisObject();
            View view = (View) chain.getArg(0);
            if (view != null && view.getParent() instanceof ViewGroup container
                    && container.getParent() instanceof ViewGroup cellLayout) {
                // CellLayout clears its occupancy before the item's coordinates change.
                cellLayout.removeView(view);
            }
            Integer old = pageOverrides.get().put(pager, (Integer) chain.getArg(2) / PAGE_SIZE);
            try {
                return chain.proceed();
            } finally {
                if (old == null) pageOverrides.get().remove(pager);
                else pageOverrides.get().put(pager, old);
            }
        }, View.class, itemType, int.class);
        installer.hook(FOLDER + "FolderPagedView", "realTimeReorder", chain -> {
            if (!paging.getAsBoolean()) return chain.proceed();
            Object pager = chain.getThisObject();
            if (!(Boolean) field(pager, "mViewsBound")) return null;
            call(pager, "completePendingPageChanges");
            int from = (Integer) chain.getArg(0);
            int target = (Integer) chain.getArg(1);
            int step = Integer.compare(target, from);
            for (int rank = from; step != 0 && rank != target; rank += step) {
                View view = viewAtRank(pager, rank + step);
                if (view != null) call(pager, "addViewForRank",
                        new Class<?>[]{View.class, itemType, int.class}, view, view.getTag(), rank);
            }
            return null;
        }, int.class, int.class);
        installer.hook(FOLDER + "FolderPagedView", "getCurrentCellLayout", chain ->
                paging.getAsBoolean() ? currentPage(chain.getThisObject()) : chain.proceed());
        installer.hook(FOLDER + "FolderPagedView", "ensureEmptyCellsForDrag", chain -> {
            if (!paging.getAsBoolean()) return chain.proceed();
            Object drag = chain.getArg(0);
            if (!(Boolean) field(drag, "isBatchDragging")) return null;
            Object pager = chain.getThisObject();
            List<View> views = rankedViews(pager);
            int empty = Collections.frequency(views, null);
            int needed = (Integer) field(drag, "batchDraggingIconSize");
            if (needed > empty) {
                for (int i = empty; i < needed; i++) views.add(null);
                call(pager, "arrangeChildren", new Class<?>[]{List.class}, views);
                ((View) pager).requestLayout();
            }
            return null;
        }, dragType);
        installer.hook(FOLDER + "FolderPagedView", "rearrangeEmptyCellsForDrop", chain -> {
            if (!paging.getAsBoolean()) return chain.proceed();
            Object pager = chain.getThisObject();
            if (!(Boolean) field(pager, "mViewsBound")) return null;
            call(pager, "completePendingPageChanges");
            List<View> views = rankedViews(pager);
            List<View> occupied = new ArrayList<>();
            for (View view : views) if (view != null) occupied.add(view);
            int empty = views.size() - occupied.size();
            int target = Math.max(0, Math.min((Integer) chain.getArg(0), occupied.size()));
            for (int i = 0; i < empty; i++) occupied.add(target, null);
            call(pager, "arrangeChildren", new Class<?>[]{List.class}, occupied);
            return null;
        }, int.class);
        installer.hook(FLYME + "FlymeFolder", "getVisibleBubbleTextViews", chain -> {
            if (!paging.getAsBoolean()) return chain.proceed();
            Object page = currentPage(call(chain.getThisObject(), "getContent"));
            return page == null ? Collections.emptyList() : call(page, "getVisibleBubbleTextView");
        });
        installer.hook(FLYME + "FlymeFolder", "getCurrentScrollY", chain -> paging.getAsBoolean() ? 0 : chain.proceed());
        installer.hook(FLYME + "MzFolderIconHelper", "getPreviewItems", chain -> {
            Object folder = closingFolder.get();
            if (folder == null || field(chain.getThisObject(), "mFolder") != folder) return chain.proceed();
            Object pager = call(folder, "getContent");
            List<View> views = currentPageViews(pager);
            int limit = Math.max(0, Math.min((Integer) chain.getArg(0), views.size()));
            return new ArrayList<>(views.subList(0, limit));
        }, int.class);
        installer.hook("com.meizu.flyme.launcher.utils.MzFolderAnimationUtils", "getOpenOrCloseAnimator", chain -> {
            if (!paging.getAsBoolean() || (Boolean) chain.getArg(0)) return chain.proceed();
            Object folder = field(chain.getThisObject(), "folder");
            Object pager = call(folder, "getContent");
            if ((Integer) call(pager, "getNextPage") == 0) return chain.proceed();
            List<View> views = currentPageViews(pager);
            List<IconState> states = new ArrayList<>();
            for (View view : views) states.add(new IconState(view));
            Object previous = closingFolder.get();
            Animator animator;
            closingFolder.set(folder);
            try {
                // Reuse Flyme's icon translation, scale, label and fade animations.
                animator = (Animator) chain.proceed();
            } finally {
                if (previous == null) closingFolder.remove();
                else closingFolder.set(previous);
            }
            Map<ViewGroup, Boolean> clipping = new IdentityHashMap<>();
            for (View view : views) {
                for (Object parent = view.getParent(); parent instanceof ViewGroup group; parent = group.getParent()) {
                    clipping.putIfAbsent(group, group.getClipChildren());
                    group.setClipChildren(false);
                    if (group == folder) break;
                }
            }
            animator.addListener(new AnimatorListenerAdapter() {
                private boolean restored;

                private void restore() {
                    if (restored) return;
                    restored = true;
                    for (IconState state : states) state.restore();
                    for (Map.Entry<ViewGroup, Boolean> entry : clipping.entrySet()) {
                        entry.getKey().setClipChildren(entry.getValue());
                    }
                }

                @Override public void onAnimationEnd(Animator animation) { restore(); }
                @Override public void onAnimationCancel(Animator animation) { restore(); }
            });
            return animator;
        }, boolean.class);
        installer.hook("com.meizu.flyme.launcher.utils.MzFolderAnimationUtils", "reAssignVisibleRectFoderIn", chain -> {
            if (!paging.getAsBoolean()) return chain.proceed();
            Object[] args = chain.getArgs().toArray();
            args[2] = (Integer) args[2] % PAGE_SIZE;
            return chain.proceed(args);
        }, Rect.class, Rect.class, int.class);

        installer.hook(FOLDER + "FolderPagedView", "arrangeChildren", chain -> {
            Object result = chain.proceed();
            if (paging.getAsBoolean()) updateIndicator((ViewGroup) chain.getThisObject());
            return result;
        }, java.util.List.class);
        installer.hook(FOLDER + "FolderPagedView", "onScrollChanged", chain -> {
            Object result = chain.proceed();
            if (paging.getAsBoolean()) updateIndicator((ViewGroup) chain.getThisObject());
            return result;
        }, int.class, int.class, int.class, int.class);
        installer.hook(FOLDER + "FolderPagedView", "notifyPageSwitchListener", chain -> {
            Object result = chain.proceed();
            if (paging.getAsBoolean()) updateIndicator((ViewGroup) chain.getThisObject());
            return result;
        }, int.class);
        installer.hook(FOLDER + "FolderAnimationManager", "getAnimator", chain -> {
            Animator animator = (Animator) chain.proceed();
            if (!paging.getAsBoolean()) return animator;
            ViewGroup pager = (ViewGroup) field(chain.getThisObject(), "mContent");
            // Hide before Flyme seeks to the first animation frame, not just at start.
            HiddenPages old = animationPages.remove(pager);
            if (old != null) old.restore();
            HiddenPages hidden = new HiddenPages(pager, indicators.get(pager));
            animationPages.put(pager, hidden);
            animator.addListener(new AnimatorListenerAdapter() {
                private void restore() {
                    if (animationPages.get(pager) == hidden) {
                        animationPages.remove(pager);
                        hidden.restore();
                    }
                }

                @Override public void onAnimationEnd(Animator animation) { restore(); }
                @Override public void onAnimationCancel(Animator animation) { restore(); }
            });
            return animator;
        });
        installer.hook(FOLDER + "PreviewItemManager", "onFolderClose", chain -> {
            if (!paging.getAsBoolean()) return chain.proceed();
            // The preview organizer still uses Flyme's original page capacity.
            // Reset directly to page zero rather than sliding an empty page preview.
            Object result = chain.proceed(new Object[]{0});
            Object manager = chain.getThisObject();
            ((List<?>) field(manager, "mCurrentPageParams")).clear();
            setField(manager, "mCurrentPageItemsTransX", 0f);
            java.lang.reflect.Method update = manager.getClass().getDeclaredMethod("updatePreviewItems", boolean.class);
            update.setAccessible(true);
            update.invoke(manager, false);
            call(manager, "onParamsChanged");
            return result;
        }, int.class);

        // Both the initial layout and the folder-height spring animation call this.
        installer.hook(FLYME + "MzFolderHelper", "calculateFolderLocation", chain -> {
            Object result = chain.proceed();
            if (!center.getAsBoolean()) return result;
            View folder = (View) field(chain.getThisObject(), "mFolder");
            if (folder == null) return result;
            Object activity = field(folder, "mActivityContext");
            Object profile = call(activity, "getDeviceProfile");
            Rect insets = (Rect) call(profile, "getInsets");
            int height = (Integer) field(profile, "heightPx");
            ViewGroup.LayoutParams params = (ViewGroup.LayoutParams) chain.getArg(0);
            int available = height - insets.top - insets.bottom;
            int[] position = (int[]) result;
            position[1] = insets.top + Math.max(0, (available - params.height) / 2);
            return position;
        }, layoutType, int.class, int.class, int[].class);
        installer.hook(FLYME + "FlymeFolder", "centerAboutIcon", chain -> {
            Object result = chain.proceed();
            if (center.getAsBoolean()) {
                View folder = (View) chain.getThisObject();
                Object activity = field(folder, "mActivityContext");
                Object dragLayer = call(activity, "getDragLayer");
                Rect iconRect = new Rect();
                call(dragLayer, "getDescendantRectRelativeToSelf", new Class<?>[]{View.class, Rect.class},
                        call(folder, "getFolderIcon"), iconRect);
                Object params = folder.getLayoutParams();
                folder.setPivotX(iconRect.centerX() - (Integer) field(params, "x"));
                folder.setPivotY(iconRect.centerY() - (Integer) field(params, "y"));
            }
            return result;
        });
    }

    private void updateIndicator(ViewGroup pager) throws ReflectiveOperationException {
        if (!(pager.getParent() instanceof RelativeLayout parent)) return;
        View dots = indicators.get(pager);
        if (dots == null) {
            dots = (View) type("com.meizu.flyme.launcher.view.indicator.FlymeWorkspaceIndicatorView")
                    .getConstructor(Context.class).newInstance(pager.getContext());
            dots.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            // Use the same concrete adapter as the desktop, with ordinary page types.
            Constructor<?> constructor = type("com.meizu.flyme.launcher.view.MzIconPageIndicator$WorkspaceIndicatorAdapter")
                    .getDeclaredConstructor(List.class);
            constructor.setAccessible(true);
            Object adapter = constructor.newInstance(new ArrayList<Integer>());
            call(dots, "setAdapter", new Class<?>[]{type("com.meizu.flyme.launcher.view.indicator.Adapter")}, adapter);
            call(dots, "setIndicatorColor", new Class<?>[]{int.class}, 0x59FFFFFF);
            call(dots, "setScrollIndicatorColor", new Class<?>[]{int.class}, 0xFFFFFFFF);
            int height = Math.round(18 * pager.getResources().getDisplayMetrics().density);
            RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height);
            params.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
            parent.addView(dots, params);
            pager.setPadding(pager.getPaddingLeft(), pager.getPaddingTop(), pager.getPaddingRight(), pager.getPaddingBottom() + height);
            indicators.put(pager, dots);
        }
        int pages = pager.getChildCount();
        int page = (Integer) call(pager, "getNextPage");
        Object adapter = call(dots, "getAdapter");
        @SuppressWarnings("unchecked")
        List<Integer> screens = (List<Integer>) field(adapter, "mScreens");
        if (screens.size() != pages) {
            screens.clear();
            for (int i = 0; i < pages; i++) screens.add(i);
            call(dots, "onDataSetChanged");
        }
        call(dots, "setActiveMarker", new Class<?>[]{int.class}, page);
        dots.setVisibility(pages > 1 && !animationPages.containsKey(pager) ? View.VISIBLE : View.INVISIBLE);
    }

    private static final class HiddenPages {
        private final Map<View, Integer> visibility = new IdentityHashMap<>();

        HiddenPages(ViewGroup pager, View indicator) throws ReflectiveOperationException {
            if (indicator != null) {
                visibility.put(indicator, indicator.getVisibility());
                indicator.setVisibility(View.INVISIBLE);
            }
            int current = (Integer) call(pager, "getNextPage");
            for (int i = 0; i < pager.getChildCount(); i++) {
                if (i == current) continue;
                View page = pager.getChildAt(i);
                visibility.put(page, page.getVisibility());
                // Keep page geometry intact for icon animation coordinates.
                page.setVisibility(View.INVISIBLE);
            }
        }

        void restore() {
            for (Map.Entry<View, Integer> entry : visibility.entrySet()) {
                entry.getKey().setVisibility(entry.getValue());
            }
        }
    }

    private static final class IconState {
        private final View view;
        private final float alpha, x, y, scaleX, scaleY, pivotX, pivotY;
        private final int scrollY;

        IconState(View view) {
            this.view = view;
            alpha = view.getAlpha();
            x = view.getTranslationX();
            y = view.getTranslationY();
            scaleX = view.getScaleX();
            scaleY = view.getScaleY();
            pivotX = view.getPivotX();
            pivotY = view.getPivotY();
            scrollY = view.getScrollY();
        }

        void restore() {
            view.setAlpha(alpha);
            view.setTranslationX(x);
            view.setTranslationY(y);
            view.setScaleX(scaleX);
            view.setScaleY(scaleY);
            view.setPivotX(pivotX);
            view.setPivotY(pivotY);
            view.setScrollY(scrollY);
        }
    }

    private boolean isPagingOrganizer(Object organizer) {
        return paging.getAsBoolean() && organizers.containsKey(organizer);
    }

    private void setGrid(Object organizer) throws ReflectiveOperationException {
        setField(organizer, "mCountX", COLUMNS);
        setField(organizer, "mCountY", ROWS);
    }

    private Object currentPage(Object pager) throws ReflectiveOperationException {
        return call(pager, "getPageAt", new Class<?>[]{int.class}, call(pager, "getNextPage"));
    }

    private View viewAtRank(Object pager, int rank) throws ReflectiveOperationException {
        if (rank < 0 || rank / PAGE_SIZE >= ((ViewGroup) pager).getChildCount()) return null;
        Object page = call(pager, "getPageAt", new Class<?>[]{int.class}, rank / PAGE_SIZE);
        int local = rank % PAGE_SIZE;
        return (View) call(page, "getChildAt", new Class<?>[]{int.class, int.class}, local % COLUMNS, local / COLUMNS);
    }

    private List<View> currentPageViews(Object pager) throws ReflectiveOperationException {
        int start = (Integer) call(pager, "getNextPage") * PAGE_SIZE;
        List<View> views = new ArrayList<>();
        for (int rank = start; rank < start + PAGE_SIZE; rank++) {
            View view = viewAtRank(pager, rank);
            if (view != null) views.add(view);
        }
        return views;
    }

    private List<View> rankedViews(Object pager) throws ReflectiveOperationException {
        int size = (Integer) field(pager, "mAllocatedContentSize");
        List<View> views = new ArrayList<>(size);
        for (int rank = 0; rank < size; rank++) views.add(viewAtRank(pager, rank));
        return views;
    }

    private Class<?> type(String name) throws ClassNotFoundException {
        return Class.forName(name, false, loader);
    }

    private static Field findField(Object object, String name) throws NoSuchFieldException {
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field result = type.getDeclaredField(name);
                result.setAccessible(true);
                return result;
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    private static Object field(Object object, String name) throws ReflectiveOperationException {
        return findField(object, name).get(object);
    }

    private static void setField(Object object, String name, Object value) throws ReflectiveOperationException {
        findField(object, name).set(object, value);
    }

    private static Object call(Object object, String name) throws ReflectiveOperationException {
        return call(object, name, new Class<?>[0]);
    }

    private static Object call(Object object, String name, Class<?>[] types, Object... args) throws ReflectiveOperationException {
        return object.getClass().getMethod(name, types).invoke(object, args);
    }
}
