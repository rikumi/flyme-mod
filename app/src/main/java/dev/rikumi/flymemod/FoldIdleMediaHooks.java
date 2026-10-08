package dev.rikumi.flymemod;

import android.app.ActivityManager;
import android.content.Context;
import android.media.session.MediaController;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Display-only compaction: never rewrite TilePositionManager's saved grid. */
final class FoldIdleMediaHooks {
    private static final String LAYOUT = "com.flyme.systemui.controlcenter.qs.UnifiedTileLayout";
    private static final String CONTROLLER = "com.flyme.systemui.qs.media.QsMediaController";
    private final Consumer<Context> settings;
    private final BooleanSupplier enabled;
    private final SplitNetworkCardHooks split;
    private final BiConsumer<String, Throwable> log;
    private final Class<?> networkClass, mediaClass, paramsClass;
    private final Field cellX, cellY, spanX, spanY, pageIndex, tempCoords, tmpCellX, tmpCellY, locked, x, y;
    private final Field cellWidth, cellHeight, horizontalGap, verticalGap, temporary;
    private final Field currentPackage, currentSession;
    private final Method controllerView;
    private final Class<?> panelClass;
    private final Map<ViewGroup, String> reasons = new WeakHashMap<>();
    private final Map<View, WeakReference<Object>> controllers = new WeakHashMap<>();
    private final Map<ViewGroup, Plan> plans = new WeakHashMap<>();
    private final Map<View, Integer> hidden = new WeakHashMap<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean polling;
    private long processCheck = -1000;
    private Set<String> runningPackages;

    FoldIdleMediaHooks(ClassLoader loader, Consumer<Context> settings, BooleanSupplier enabled,
                      SplitNetworkCardHooks split, BiConsumer<String, Throwable> log) throws ReflectiveOperationException {
        this.settings = settings; this.enabled = enabled; this.split = split; this.log = log;
        networkClass = loader.loadClass("com.flyme.systemui.qs.tileimpl.ConnectivityQSTileViewImpl");
        mediaClass = loader.loadClass("com.flyme.systemui.qs.media.QsMediaView");
        paramsClass = loader.loadClass("com.flyme.systemui.controlcenter.qs.CellLayoutLayoutParams");
        cellX = paramsClass.getField("mCellX"); cellY = paramsClass.getField("mCellY");
        spanX = paramsClass.getField("cellHSpan"); spanY = paramsClass.getField("cellVSpan");
        pageIndex = paramsClass.getField("pageIndex"); tempCoords = paramsClass.getField("useTmpCoords");
        tmpCellX = paramsClass.getField("mTmpCellX"); tmpCellY = paramsClass.getField("mTmpCellY");
        locked = paramsClass.getField("isLockedToGrid"); x = paramsClass.getField("x"); y = paramsClass.getField("y");
        Class<?> layout = loader.loadClass(LAYOUT);
        cellWidth = layout.getField("mCellWidth"); cellHeight = layout.getField("mCellHeight");
        horizontalGap = layout.getField("mCellMarginHorizontal"); verticalGap = layout.getField("mCellMarginVertical");
        temporary = layout.getField("isTemp");
        Class<?> controller = loader.loadClass(CONTROLLER);
        currentPackage = controller.getField("mCurPkgName"); controllerView = controller.getMethod("getView");
        currentSession = controller.getField("mMediaController");
        panelClass = loader.loadClass("com.flyme.systemui.controlcenter.phone.MzQSPanel");
        loader.loadClass(CONTROLLER + "$Factory").getMethod("create", panelClass);
    }

    void install(SignalHooks.Installer installer) {
        installer.hook(CONTROLLER + "$Factory", "create", chain -> {
            Object controller = chain.proceed();
            if (controller != null) rememberController(controller);
            return controller;
        }, panelClass);
        installer.hook("com.flyme.systemui.controlcenter.qs.PagedUnifiedTileLayout", "setEditMode", chain -> {
            Object result = chain.proceed(); refresh(); return result;
        }, boolean.class);
        installer.hook(CONTROLLER, "onViewAttached", chain -> {
            Object result = chain.proceed(); rememberController(chain.getThisObject()); return result;
        });
        installer.hook(CONTROLLER, "getQsMediaData", chain -> {
            Object result = chain.proceed(); rememberController(chain.getThisObject()); return result;
        }, boolean.class);
        installer.hook(LAYOUT, "onMeasure", chain -> {
            ViewGroup layout = (ViewGroup) chain.getThisObject();
            try { prepare(layout); }
            catch (ReflectiveOperationException | RuntimeException error) {
                log.accept("Cannot compact idle media row", error);
                restore(layout); plans.remove(layout);
            }
            Object result = chain.proceed();
            // Apply geometry after the entire native measure pass. Do not depend
            // on intercepting the final internal measureChild call, which ART can inline.
            try {
                for (int i = 0; i < layout.getChildCount(); i++) {
                    View child = layout.getChildAt(i);
                    if (child.getVisibility() != View.GONE) compact(layout, child);
                }
            } catch (ReflectiveOperationException | RuntimeException error) {
                log.accept("Cannot measure compacted media row", error);
            }
            return result;
        }, int.class, int.class);
        // Native layoutChild consumes display geometry, while all grid coordinates
        // remain unchanged for editing, pagination and persistence.
    }

    void refresh() {
        processCheck = -1000;
        for (ViewGroup layout : new ArrayList<>(plans.keySet())) if (layout != null) layout.requestLayout();
        startPolling();
    }

    private void rememberController(Object controller) throws ReflectiveOperationException {
        View view = (View) controllerView.invoke(controller);
        if (view == null) return;
        controllers.put(view, new WeakReference<>(controller));
        if (!enabled.getAsBoolean()) return;
        view.post(() -> {
            for (View current = view; current != null;
                 current = current.getParent() instanceof View parent ? parent : null) {
                if (LAYOUT.equals(current.getClass().getName())) { current.requestLayout(); break; }
            }
        });
    }

    private void prepare(ViewGroup layout) throws ReflectiveOperationException {
        settings.accept(layout.getContext());
        Plan old = plans.get(layout);
        Plan next = enabled.getAsBoolean() ? findPlan(layout) : null;
        if (old != null && (next == null || next.mediaWrapper != old.mediaWrapper)) restore(layout);
        if (old != null && old.network != null && (next == null || next.network != old.network)) {
            split.setHorizontal(old.network, false);
        }
        plans.put(layout, next);
        if (next != null) {
            hidden.putIfAbsent(next.mediaWrapper, next.mediaWrapper.getVisibility());
            next.mediaWrapper.setVisibility(View.GONE);
            split.setHorizontal(next.network, true);
        }
        startPolling();
    }

    private void restore(ViewGroup layout) {
        for (int i = 0; i < layout.getChildCount(); i++) {
            View child = layout.getChildAt(i);
            Integer visibility = hidden.remove(child);
            if (visibility != null) child.setVisibility(visibility);
        }
    }

    private Plan findPlan(ViewGroup layout) throws ReflectiveOperationException {
        if (temporary.getBoolean(layout)) return reject(layout, "temporary edit page");
        View nw = null, mw = null, network = null, media = null;
        for (View current = layout; current != null;
             current = current.getParent() instanceof View parent ? parent : null) {
            try { if (current.getClass().getField("mIsInEditMode").getBoolean(current)) return reject(layout, "editing"); }
            catch (NoSuchFieldException ignored) { }
        }
        for (int i = 0; i < layout.getChildCount(); i++) {
            View child = layout.getChildAt(i);
            Object lp = child.getLayoutParams();
            if (!paramsClass.isInstance(lp)) continue;
            if (!locked.getBoolean(lp)) return reject(layout, "reorder animation still active");
            View n = find(child, networkClass), m = find(child, mediaClass);
            if (n != null) { nw = child; network = n; }
            if (m != null) { mw = child; media = m; }
        }
        if (nw == null || mw == null || nw == mw) return reject(layout, "network or media tile absent on this page");
        Object np = nw.getLayoutParams(), mp = mw.getLayoutParams();
        int row = row(np);
        if (row < 0 || row(mp) != row || pageIndex.getInt(np) != pageIndex.getInt(mp)
                || spanX.getInt(np) != 2 || spanY.getInt(np) != 2
                || spanX.getInt(mp) != 2 || spanY.getInt(mp) != 2
                || !((column(np) == 0 && column(mp) == 2)
                     || (column(np) == 2 && column(mp) == 0))) return reject(layout,
                        "not a shared 4x2 row: network=" + np + ", media=" + mp);
        // A different card spanning into these rows must keep its original geometry.
        for (int i = 0; i < layout.getChildCount(); i++) {
            View child = layout.getChildAt(i);
            if (child == nw || child == mw) continue;
            Object lp = child.getLayoutParams();
            if (!paramsClass.isInstance(lp) || child.getVisibility() == View.GONE) continue;
            int start = row(lp), end = start + spanY.getInt(lp);
            if (start < row + 2 && end > row) return reject(layout, "another tile overlaps the shared row");
        }
        WeakReference<Object> reference = controllers.get(media);
        Object controller = reference == null ? null : reference.get();
        if (controller == null) return reject(layout, "media controller not captured");
        MediaController session = (MediaController) currentSession.get(controller);
        String pkg = session == null ? (String) currentPackage.get(controller) : session.getPackageName();
        if (pkg != null && !pkg.isEmpty() && isRunning(layout.getContext(), pkg))
            return reject(layout, "music app running or process list unavailable: " + pkg);
        reason(layout, "compacted row=" + row + ", package=" + pkg);
        return new Plan(nw, mw, network, row);
    }

    private int row(Object lp) throws IllegalAccessException {
        return (tempCoords.getBoolean(lp) ? tmpCellY : cellY).getInt(lp);
    }

    private int column(Object lp) throws IllegalAccessException {
        return (tempCoords.getBoolean(lp) ? tmpCellX : cellX).getInt(lp);
    }

    private Plan reject(ViewGroup layout, String message) {
        reason(layout, message);
        return null;
    }

    private void reason(ViewGroup layout, String message) {
        if (!message.equals(reasons.put(layout, message))) {
            log.accept("Idle media compaction [" + Integer.toHexString(System.identityHashCode(layout))
                    + "]: " + message, null);
        }
    }

    private boolean isRunning(Context context, String pkg) {
        long now = SystemClock.uptimeMillis();
        if (now - processCheck >= 500) {
            processCheck = now;
            ActivityManager manager = context.getSystemService(ActivityManager.class);
            List<ActivityManager.RunningAppProcessInfo> processes = manager == null ? null : manager.getRunningAppProcesses();
            runningPackages = processes == null ? null : new HashSet<>();
            if (processes != null) for (ActivityManager.RunningAppProcessInfo process : processes) {
                if (process.pkgList != null) for (String name : process.pkgList) runningPackages.add(name);
                if (process.processName != null) runningPackages.add(process.processName.split(":", 2)[0]);
            }
        }
        return runningPackages == null || runningPackages.contains(pkg);
    }

    private void compact(ViewGroup layout, View child) throws ReflectiveOperationException {
        Plan plan = plans.get(layout);
        if (plan == null || child == plan.mediaWrapper) return;
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) child.getLayoutParams();
        if (child == plan.networkWrapper) {
            x.setInt(lp, lp.leftMargin);
            y.setInt(lp, plan.row * (cellHeight.getInt(layout) + verticalGap.getInt(layout)) + lp.topMargin);
            lp.width = 4 * cellWidth.getInt(layout) + 3 * horizontalGap.getInt(layout) - lp.leftMargin - lp.rightMargin;
            lp.height = cellHeight.getInt(layout) - lp.topMargin - lp.bottomMargin;
            child.measure(View.MeasureSpec.makeMeasureSpec(lp.width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(lp.height, View.MeasureSpec.EXACTLY));
        } else if (paramsClass.isInstance(lp) && row(lp) >= plan.row + 2) {
            y.setInt(lp, y.getInt(lp) - cellHeight.getInt(layout) - verticalGap.getInt(layout));
        }
    }

    private void startPolling() {
        if (polling || !enabled.getAsBoolean()) return;
        polling = true; handler.postDelayed(this::poll, 1000);
    }

    private void poll() {
        polling = false;
        if (!enabled.getAsBoolean()) return;
        boolean attached = false;
        for (ViewGroup layout : new ArrayList<>(plans.keySet())) {
            if (layout == null || !layout.isAttachedToWindow()) continue;
            attached = true;
            if (!layout.isShown()) continue;
            try {
                Plan old = plans.get(layout), next = findPlan(layout);
                if ((old == null) != (next == null) || (old != null && next != null
                        && (old.network != next.network || old.row != next.row))) layout.requestLayout();
            } catch (ReflectiveOperationException | RuntimeException error) {
                log.accept("Cannot refresh idle media row", error);
            }
        }
        if (attached) startPolling();
    }

    private View find(View view, Class<?> type) {
        if (type.isInstance(view)) return view;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            View found = find(group.getChildAt(i), type);
            if (found != null) return found;
        }
        return null;
    }

    private static final class Plan {
        final View networkWrapper, mediaWrapper, network;
        final int row;
        Plan(View networkWrapper, View mediaWrapper, View network, int row) {
            this.networkWrapper = networkWrapper; this.mediaWrapper = mediaWrapper; this.network = network; this.row = row;
        }
    }
}
