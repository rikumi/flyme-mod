package dev.rikumi.flymemod;

import android.content.Context;
import android.animation.ValueAnimator;
import android.animation.AnimatorSet;
import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.util.FloatProperty;
import android.view.MotionEvent;
import android.view.animation.DecelerateInterpolator;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RecordingCanvas;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewGroup;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** ColorOS stack behavior reimplemented on Flyme 12.6 Quickstep's native pager. */
final class StackedRecentsHooks {
    private static final String RECENTS = "com.android.quickstep.views.RecentsView";
    private static final String TASK = "com.android.quickstep.views.TaskView";
    private static final String FACTORY = "com.android.launcher3.util.MultiPropertyFactory";
    private final Consumer<Context> settings;
    private final BooleanSupplier enabled;
    private final BiConsumer<String, Throwable> log;
    private final Class<?> taskType, alphaType, remoteHandlesType, groupedTaskType;
    private final Field overview, grid, fullscreen, endTarget, alphaTarget;
    private final Method gestureActive, runningIndex, nextPage, taskCount, scrollForPage, loadVisible, orientationHandler, primaryAxis, overScroll, snapImmediately, showIcons, showTaskIcon, dispatchScroll;
    private final Map<View, State> states = new WeakHashMap<>();
    private final Map<View, Card> cards = new WeakHashMap<>();
    private final Map<Object, SurfaceState> surfaces = new WeakHashMap<>();
    private final ThreadLocal<Object> submittingSimulator = new ThreadLocal<>();
    private final FloatProperty<View> nativeTranslationX = nativeTranslationProperty(true);
    private final FloatProperty<View> nativeTranslationY = nativeTranslationProperty(false);
    private final Method taskSimulator;
    private final Method thumbnailBounds, sizeAdjustment;
    private final Field surfaceMatrix, surfaceCrop, activityTitle, pressAnimator, pressUpRunnable, pendingAnimations;
    private final Method updateDragging;
    private final Method resetPageOffsetX, resetPageOffsetY, gestureTaskInfo, taskActivityType, showStatusBar;
    private final Class<?> launcherStateType;
    private final Object overviewState;
    private final Class<?> surfacePropertiesType, transformParamsType, remoteAnimationTargetType, builderProxyType;

    StackedRecentsHooks(ClassLoader loader, Consumer<Context> settings, BooleanSupplier enabled,
            BiConsumer<String, Throwable> log) throws ReflectiveOperationException {
        this.settings = settings; this.enabled = enabled; this.log = log;
        launcherStateType = loader.loadClass("com.android.launcher3.LauncherState");
        overviewState = launcherStateType.getField("OVERVIEW").get(null);
        Class<?> mzLauncher = loader.loadClass("com.meizu.flyme.launcher.MzLauncher");
        showStatusBar = mzLauncher.getMethod("showSystemStatusBar", boolean.class);
        mzLauncher.getDeclaredMethod("onStateSetStart", launcherStateType);
        Class<?> recents = loader.loadClass(RECENTS);
        taskType = loader.loadClass(TASK);
        thumbnailBounds = taskType.getMethod("getThumbnailBounds", Rect.class, boolean.class);
        sizeAdjustment = taskType.getMethod("getSizeAdjustment", boolean.class);
        activityTitle = field(taskType, "mActivityTitle");
        pressAnimator = field(taskType, "mTaskThumbScaleAnimator");
        pressUpRunnable = field(taskType, "mScaleUpRunnable");
        taskType.getDeclaredMethod("scaleDown");
        taskType.getDeclaredMethod("scaleUp", boolean.class);
        updateDragging = recents.getMethod("updateDraggingView", taskType);
        resetPageOffsetX = taskType.getDeclaredMethod("setTaskOffsetTranslationX", float.class);
        resetPageOffsetY = taskType.getDeclaredMethod("setTaskOffsetTranslationY", float.class);
        resetPageOffsetX.setAccessible(true); resetPageOffsetY.setAccessible(true);
        recents.getDeclaredMethod("updatePageOffsets");
        recents.getDeclaredMethod("updatePageOffsetsForFlyme");
        pendingAnimations = field(loader.loadClass("com.android.launcher3.anim.AnimatedPropertySetter"), "mAnim");
        recents.getDeclaredMethod("createTaskSpringAnimation", taskType, long.class, float.class);
        groupedTaskType = loader.loadClass("com.android.wm.shell.shared.GroupedTaskInfo");
        gestureTaskInfo = groupedTaskType.getMethod("getTaskInfo1");
        taskActivityType = gestureTaskInfo.getReturnType().getMethod("getActivityType");
        dispatchScroll = recents.getDeclaredMethod("dispatchScrollChanged");
        dispatchScroll.setAccessible(true);
        showIcons = recents.getMethod("setTaskIconVisible", boolean.class);
        showTaskIcon = taskType.getMethod("setIconVisibleForGesture", boolean.class);
        Class<?> handle = loader.loadClass("com.android.quickstep.RemoteTargetGluer$RemoteTargetHandle");
        remoteHandlesType = java.lang.reflect.Array.newInstance(handle, 0).getClass();
        taskSimulator = handle.getMethod("getTaskViewSimulator");
        Class<?> simulator = loader.loadClass("com.android.quickstep.util.TaskViewSimulator");
        surfaceMatrix = field(simulator, "mMatrix");
        surfaceCrop = field(simulator, "mTmpCropRect");
        surfacePropertiesType = loader.loadClass("com.android.quickstep.util.SurfaceTransaction$SurfaceProperties");
        surfacePropertiesType.getDeclaredMethod("setMatrix", Matrix.class);
        loader.loadClass("com.android.quickstep.util.SurfaceTransaction$RecordingSurfaceProperties")
                .getDeclaredMethod("setMatrix", Matrix.class);
        remoteAnimationTargetType = loader.loadClass("android.view.RemoteAnimationTarget");
        transformParamsType = loader.loadClass("com.android.quickstep.util.TransformParams");
        builderProxyType = loader.loadClass("com.android.quickstep.util.TransformParams$BuilderProxy");
        simulator.getDeclaredMethod("onBuildTargetParams", surfacePropertiesType, remoteAnimationTargetType, transformParamsType);
        alphaType = loader.loadClass("com.android.launcher3.util.MultiValueAlpha");
        overview = field(recents, "mOverviewStateEnabled");
        grid = field(recents, "mOverviewGridEnabled");
        fullscreen = field(recents, "mFullscreenProgress");
        endTarget = field(recents, "mCurrentGestureEndTarget");
        alphaTarget = field(loader.loadClass(FACTORY), "mTarget");
        gestureActive = recents.getMethod("isGestureActive");
        runningIndex = recents.getMethod("getRunningTaskIndex");
        nextPage = recents.getMethod("getNextPage");
        taskCount = recents.getMethod("getTaskViewCount");
        scrollForPage = recents.getMethod("getScrollForPage", int.class);
        snapImmediately = recents.getMethod("snapToPageImmediately", int.class);
        loadVisible = recents.getMethod("loadVisibleTaskData", int.class);
        orientationHandler = loader.loadClass("com.android.launcher3.PagedView")
                .getDeclaredMethod("getPagedOrientationHandler");
        orientationHandler.setAccessible(true);
        overScroll = recents.getDeclaredMethod("getUndampedOverScrollShift");
        overScroll.setAccessible(true);
        primaryAxis = loader.loadClass("com.android.launcher3.touch.PagedOrientationHandler")
                .getMethod("getPrimaryValue", int.class, int.class);
        // Validate the actual private property writers rather than intercepting View globally.
        taskType.getDeclaredMethod("applyScale");
        taskType.getDeclaredMethod("applyTranslationX");
        taskType.getDeclaredMethod("applyTranslationY");
        recents.getDeclaredMethod("dispatchDraw", Canvas.class);
        recents.getDeclaredMethod("onPrepareGestureEndAnimation", AnimatorSet.class, endTarget.getType(), remoteHandlesType);
        recents.getDeclaredMethod("lambda$loadVisibleTaskData$16", int.class, int.class,
                int.class, int.class, java.util.List.class, int.class, Integer.class, taskType);
    }

    void install(SignalHooks.Installer installer) {
        installer.hook("com.meizu.flyme.launcher.MzLauncher", "onStateSetStart", chain -> {
            if (enabled.getAsBoolean() && chain.getArg(0) == overviewState) {
                // Use Flyme's own immersive policy before any overview frames;
                // onStateSetEnd(NORMAL) still performs the native restoration.
                showStatusBar.invoke(chain.getThisObject(), false);
            }
            return chain.proceed();
        }, launcherStateType);
        for (String method : new String[]{"updatePageOffsets", "updatePageOffsetsForFlyme"}) {
            installer.hook(RECENTS, method, chain -> {
                View view = (View) chain.getThisObject();
                return states.containsKey(view) && active(view) ? null : chain.proceed();
            });
        }
        installer.hook(RECENTS, "createTaskSpringAnimation", chain -> {
            Object result = chain.proceed();
            View parent = (View) chain.getThisObject();
            if (result != null && states.containsKey(parent) && active(parent)) {
                rewriteSpringTranslations((Animator) pendingAnimations.get(result), (View) chain.getArg(0),
                        (Integer) primaryAxis.invoke(orientationHandler.invoke(parent), 1, 0) == 1);
            }
            return result;
        }, taskType, long.class, float.class);
        for (String method : new String[]{"scaleDown", "scaleUp"}) {
            installer.hook(TASK, method, chain -> {
                View task = (View) chain.getThisObject();
                if (!(task.getParent() instanceof ViewGroup parent)
                        || !states.containsKey(parent) || !active(parent)) return chain.proceed();
                task.removeCallbacks((Runnable) pressUpRunnable.get(task));
                Animator animator = (Animator) pressAnimator.get(task);
                if (animator != null) animator.cancel();
                pressAnimator.set(task, null);
                if (method.equals("scaleDown")) updateDragging.invoke(parent, task);
                return null;
            }, method.equals("scaleUp") ? new Class<?>[]{boolean.class} : new Class<?>[0]);
        }
        installer.hook(RECENTS, "setOverviewStateEnabled", chain -> {
            View view = (View) chain.getThisObject();
            settings.accept(view.getContext());
            boolean wasEnabled = overview.getBoolean(view);
            boolean entering = (Boolean) chain.getArg(0);
            if (!entering) restore(view, true);
            Object result = chain.proceed();
            if (entering && !wasEnabled && enabled.getAsBoolean() && !state(view).entryStarted) {
                state(view).pendingFocus = true;
                state(view).entryProgress = 0f;
                loadVisible.invoke(view, 1);
            }
            return result;
        }, boolean.class);
        installer.hook(RECENTS, "onGestureAnimationStart", chain -> {
            Object result = chain.proceed();
            View view = (View) chain.getThisObject();
            settings.accept(view.getContext());
            if (enabled.getAsBoolean()) {
                Object group = chain.getArg(0);
                Object task = group == null ? null : gestureTaskInfo.invoke(group);
                state(view).fromHome = task != null && (Integer) taskActivityType.invoke(task) == 2;
                loadVisible.invoke(view, 1);
            }
            return result;
        }, groupedTaskType);
        installer.hook(RECENTS, "startIconFadeInOnGestureComplete", chain -> {
            ViewGroup view = (ViewGroup) chain.getThisObject();
            State state = states.get(view);
            if (enabled.getAsBoolean() && state != null && state.entryStarted) {
                revealIcons(view);
                return null;
            }
            return chain.proceed();
        });
        installer.hook(RECENTS, "onPrepareGestureEndAnimation", chain -> {
            View view = (View) chain.getThisObject();
            settings.accept(view.getContext());
            Object target = chain.getArg(1);
            if (enabled.getAsBoolean() && target instanceof Enum<?> value
                    && "RECENTS".equals(value.name()) && !state(view).entryStarted) captureEntry((ViewGroup) view);
            Object result = chain.proceed();
            if (enabled.getAsBoolean() && target instanceof Enum<?> value
                    && "RECENTS".equals(value.name()) && !state(view).entryStarted) {
                state(view).pendingFocus = false;
                Object handles = chain.getArg(2);
                if (handles != null && !state(view).fromHome) for (int i = 0; i < java.lang.reflect.Array.getLength(handles); i++) {
                    Object handle = java.lang.reflect.Array.get(handles, i);
                    surfaces.put(taskSimulator.invoke(handle), new SurfaceState(view));
                }
                beginEntry(view, (AnimatorSet) chain.getArg(0));
            }
            return result;
        }, AnimatorSet.class, endTarget.getType(), remoteHandlesType);
        installer.hook(RECENTS, "onGestureAnimationEnd", chain -> {
            View view = (View) chain.getThisObject();
            Object target = endTarget.get(view);
            boolean entering = target instanceof Enum<?> value && "RECENTS".equals(value.name());
            Object result = chain.proceed();
            if (entering && states.containsKey(view)) state(view).nativeEntryEnded = true;
            if (entering && enabled.getAsBoolean() && !state(view).entryStarted) {
                state(view).pendingFocus = false;
                beginEntry(view);
            }
            if (entering && enabled.getAsBoolean() && state(view).entryStarted) render((ViewGroup) view);
            return result;
        });
        installer.hook(RECENTS, "onLayout", chain -> {
            Object result = chain.proceed();
            State state = states.get(chain.getThisObject());
            if (state != null) state.dirty = true;
            return result;
        }, boolean.class, int.class, int.class, int.class, int.class);
        installer.hook(RECENTS, "dispatchDraw", chain -> {
            ViewGroup view = (ViewGroup) chain.getThisObject();
            settings.accept(view.getContext());
            try {
                if (active(view)) {
                    // Advance native edge physics once, before mapping its displacement.
                    state(view).overScroll = ((Number) overScroll.invoke(view)).floatValue();
                    render(view);
                } else restore(view, false);
            } catch (ReflectiveOperationException | RuntimeException error) {
                restore(view, true);
                log.accept("Cannot render Flyme stacked recents", error);
            }
            State state = states.get(view);
            if (state == null || !active(view)) return chain.proceed();
            state.drawing = true;
            try { return chain.proceed(); }
            finally { state.drawing = false; }
        }, Canvas.class);
        installer.hook(RECENTS, "getUndampedOverScrollShift", chain -> {
            State state = states.get(chain.getThisObject());
            // RecentsView's dispatchDraw translates the whole canvas by this
            // value. It is already represented by our individual projections.
            return state != null && state.drawing ? 0f : chain.proceed();
        });
        installer.hook("android.view.ViewGroup", "drawChild", chain -> {
            ViewGroup parent = (ViewGroup) chain.getThisObject();
            Canvas canvas = (Canvas) chain.getArg(0);
            View child = (View) chain.getArg(1);
            Card card = cards.get(child);
            if (card == null || !active(parent)) return chain.proceed();
            if (card.hidden) return false;
            if (card.effect == null || !canvas.isHardwareAccelerated()) return chain.proceed();
            // Native drawChild preserves the task's matrix, alpha and cached
            // display list. Blur its output in a larger node, not the tight
            // task node whose layer allocation truncates the blur kernel.
            if (card.blurNode == null) card.blurNode = new RenderNode("Flyme stack task blur");
            card.bounds.set(0, 0, child.getWidth(), child.getHeight());
            child.getMatrix().mapRect(card.bounds);
            card.bounds.offset(child.getLeft(), child.getTop());
            int padding = (int) Math.ceil(card.blur * .5f * parent.getResources().getDisplayMetrics().density * 3f) + 2;
            int left = (int) Math.floor(card.bounds.left) - padding;
            int top = (int) Math.floor(card.bounds.top) - padding;
            int right = (int) Math.ceil(card.bounds.right) + padding;
            int bottom = (int) Math.ceil(card.bounds.bottom) + padding;
            card.blurNode.setPosition(left, top, right, bottom);
            card.blurNode.setClipToBounds(false);
            card.blurNode.setTranslationZ(child.getTranslationZ());
            card.blurNode.setRenderEffect(card.effect);
            RecordingCanvas recording = card.blurNode.beginRecording(right - left, bottom - top);
            Object result;
            try {
                recording.translate(-left, -top);
                result = chain.proceed(new Object[]{recording, child, chain.getArg(2)});
            } finally {
                card.blurNode.endRecording();
            }
            canvas.drawRenderNode(card.blurNode);
            return result;
        }, Canvas.class, View.class, long.class);
        installer.hook("com.android.quickstep.util.TransformParams", "createSurfaceParams", chain -> {
            Object simulator = chain.getArg(0);
            Object previous = submittingSimulator.get();
            if (surfaces.containsKey(simulator)) submittingSimulator.set(simulator);
            try { return chain.proceed(); }
            finally {
                if (previous == null) submittingSimulator.remove();
                else submittingSimulator.set(previous);
            }
        }, builderProxyType);
        for (String properties : new String[]{"SurfaceProperties", "RecordingSurfaceProperties"}) {
            boolean deferred = properties.equals("RecordingSurfaceProperties");
            installer.hook("com.android.quickstep.util.SurfaceTransaction$" + properties, "setMatrix", chain -> {
                Object simulator = submittingSimulator.get();
                SurfaceState surface = surfaces.get(simulator);
                ViewGroup parent = surface == null ? null : (ViewGroup) surface.owner.get();
                Matrix incoming = (Matrix) chain.getArg(0);
                // Correct at the actual transaction setter as well. The builder's
                // callback can be inlined or its matrix can be overwritten later.
                // Identity restricts this to the running app's simulator matrix;
                // wallpaper and home-target matrices remain native.
                if (parent == null || !active(parent) || incoming != surfaceMatrix.get(simulator)) return chain.proceed();
                if (surface.builderCorrecting) {
                    if (!deferred) return chain.proceed();
                    // Flyme queues a lambda capturing this Matrix reference. Keep
                    // the corrected frame immutable after the builder restores the
                    // simulator's native matrix, and after subsequent frames run.
                    surface.submissions++;
                    return chain.proceed(new Object[]{new Matrix(incoming)});
                }
                int index = (Integer) runningIndex.invoke(parent);
                View task = index >= 0 && index < parent.getChildCount() ? parent.getChildAt(index) : null;
                Card card = cards.get(task);
                if (card == null || card.hidden) return chain.proceed();
                incoming.getValues(surface.submitValues);
                surface.submitMatrix.setValues(surface.submitValues);
                surface.submitBounds.set((Rect) surfaceCrop.get(simulator));
                surface.submitMatrix.mapRect(surface.submitBounds);
                thumbnailBounds.invoke(task, surface.submitTarget, true);
                if (surface.submitTarget.isEmpty() || surface.submitBounds.width() <= 0f || surface.submitBounds.height() <= 0f) return chain.proceed();
                surface.submitMatrix.postScale(surface.submitTarget.width() / surface.submitBounds.width(),
                        surface.submitTarget.height() / surface.submitBounds.height(), surface.submitBounds.left, surface.submitBounds.top);
                surface.submitMatrix.postTranslate(surface.submitTarget.left - surface.submitBounds.left,
                        surface.submitTarget.top - surface.submitBounds.top);
                surface.submissions++;
                return chain.proceed(new Object[]{deferred ? new Matrix(surface.submitMatrix) : surface.submitMatrix});
            }, Matrix.class);
        }
        installer.hook("com.android.quickstep.util.TaskViewSimulator", "onBuildTargetParams", chain -> {
            SurfaceState surface = surfaces.get(chain.getThisObject());
            ViewGroup view = surface == null ? null : (ViewGroup) surface.owner.get();
            if (view == null || !active(view)) return chain.proceed();
            int index = (Integer) runningIndex.invoke(view);
            View task = index >= 0 && index < view.getChildCount() ? view.getChildAt(index) : null;
            Card card = cards.get(task);
            if (card == null || card.hidden) return chain.proceed();
            surface.frames++;
            Matrix matrix = (Matrix) surfaceMatrix.get(chain.getThisObject());
            matrix.getValues(surface.savedMatrix);
            surface.bounds.set((Rect) surfaceCrop.get(chain.getThisObject()));
            matrix.mapRect(surface.bounds);
            // The simulator has its own fullscreen/carousel interpolators. Map
            // its actual crop to the rendered snapshot, rather than multiplying
            // a task scale into an unrelated native surface trajectory.
            thumbnailBounds.invoke(task, surface.targetBounds, true);
            if (!surface.targetBounds.isEmpty() && surface.bounds.width() > 0f && surface.bounds.height() > 0f) {
                matrix.postScale(surface.targetBounds.width() / surface.bounds.width(),
                        surface.targetBounds.height() / surface.bounds.height(),
                        surface.bounds.left, surface.bounds.top);
                matrix.postTranslate(surface.targetBounds.left - surface.bounds.left,
                        surface.targetBounds.top - surface.bounds.top);
            }
            State state = states.get(view);
            if (state != null && state.entryProgress >= .95f) {
                int stage = !state.entryFinished ? 1 : state.handoffProgress < 1f ? 2 : 4;
                if ((surface.loggedStages & stage) == 0) {
                    surface.loggedStages |= stage;
                    surface.correctedBounds.set((Rect) surfaceCrop.get(chain.getThisObject()));
                    matrix.mapRect(surface.correctedBounds);
                    log.accept("StackedRecents surface stage=" + stage + " frames=" + surface.frames
                            + " submissions=" + surface.submissions + " progress=" + state.entryProgress
                            + " nativeSize=" + surface.bounds.width() + "x" + surface.bounds.height()
                            + " targetSize=" + surface.targetBounds.width() + "x" + surface.targetBounds.height()
                            + " correctedSize=" + surface.correctedBounds.width() + "x" + surface.correctedBounds.height()
                            + " taskScale=" + task.getScaleX() + "," + task.getScaleY()
                            + " parentScale=" + view.getScaleX() + "," + view.getScaleY(), null);
                }
            }
            boolean previousCorrection = surface.builderCorrecting;
            surface.builderCorrecting = true;
            try { return chain.proceed(); }
            finally {
                surface.builderCorrecting = previousCorrection;
                matrix.setValues(surface.savedMatrix);
            }
        }, surfacePropertiesType, remoteAnimationTargetType, transformParamsType);
        installer.hook(RECENTS, "getScrollOffset", chain -> {
            ViewGroup view = (ViewGroup) chain.getThisObject();
            State state = states.get(view);
            int index = (Integer) chain.getArg(0);
            if (state == null || !state.entryStarted || !active(view)
                    || index < 0 || index >= view.getChildCount()) return chain.proceed();
            Card card = cards.get(view.getChildAt(index));
            if (card == null) return chain.proceed();
            // The current app is a live surface during gesture completion.
            // Keep its simulator aligned with the same projected task position.
            return Math.round((Integer) scrollForPage.invoke(view, index)
                    - (state.horizontal ? view.getScrollX() : view.getScrollY())
                    + (state.horizontal ? card.offsetX : card.offsetY));
        }, int.class);
        installer.hook(RECENTS, "isTaskViewVisible", chain -> {
            ViewGroup view = (ViewGroup) chain.getThisObject();
            if (!active(view) || !states.containsKey(view) || states.get(view).step <= 0) return chain.proceed();
            return visible(view, (View) chain.getArg(0), 0f);
        }, taskType);
        installer.hook(RECENTS, "lambda$loadVisibleTaskData$16", chain -> {
            ViewGroup view = (ViewGroup) chain.getThisObject();
            if (!active(view) || !states.containsKey(view) || states.get(view).step <= 0) return chain.proceed();
            // Load only the retained stack, preserving native cache/privacy and
            // unload behavior for cards culled from either side.
            int index = (Integer) chain.getArg(6);
            boolean visible = visible(view, (View) chain.getArg(7), 0f);
            Object[] args = chain.getArgs().toArray();
            args[2] = visible ? index : index + 1;
            args[3] = visible ? index : index - 1;
            return chain.proceed(args);
        }, int.class, int.class, int.class, int.class, java.util.List.class,
                int.class, Integer.class, taskType);
        for (String method : new String[]{"reset", "onDetachedFromWindow"}) {
            installer.hook(RECENTS, method, chain -> {
                restore((View) chain.getThisObject(), true);
                return chain.proceed();
            });
        }
        for (String setter : new String[]{"setScaleX", "setScaleY"}) {
            boolean horizontal = setter.equals("setScaleX");
            installer.hook("android.view.View", setter, chain -> {
                View view = (View) chain.getThisObject();
                if (!taskType.isInstance(view) || !enabled.getAsBoolean()) return chain.proceed();
                Card card = cards.get(view);
                if (card != null && card.writingScale) return chain.proceed();
                State state = states.get(view.getParent());
                EntryPose pose = state == null ? null : state.entryPoses.get(view);
                boolean handoff = state != null && card != null && state.handoffProgress < 1f
                        && state.handoffPoses.containsKey(view);
                boolean entering = pose != null && state.entryStarted && !state.entryFinished;
                if (!handoff && !entering && (card == null || !(view.getParent() instanceof View parent) || !active(parent))) return chain.proceed();
                float nativeScale = (Float) chain.getArg(0);
                float desired = handoff ? (horizontal ? card.renderScaleX : card.renderScaleY) : !entering
                        ? nativeScale * (horizontal ? card.factorX : card.factorY) : horizontal
                        ? pose.scaleX + (pose.targetScaleX - pose.scaleX) * state.entryProgress
                        : pose.scaleY + (pose.targetScaleY - pose.scaleY) * state.entryProgress;
                if (pose != null) {
                    if (horizontal) pose.nativeScaleX = nativeScale;
                    else pose.nativeScaleY = nativeScale;
                }
                if (card != null) {
                    card.scaleRevision++;
                    if (horizontal) {
                        card.scaleX = nativeScale;
                        card.factorX = nativeScale == 0f ? 1f : desired / nativeScale;
                    } else {
                        card.scaleY = nativeScale;
                        card.factorY = nativeScale == 0f ? 1f : desired / nativeScale;
                    }
                }
                return chain.proceed(new Object[]{desired});
            }, float.class);
        }
        installer.hook("android.view.View", "setTranslationZ", chain -> {
            View task = (View) chain.getThisObject();
            Card card = cards.get(task);
            if (card == null || card.writingZ || !enabled.getAsBoolean()
                    || !(task.getParent() instanceof View parent) || !active(parent)) return chain.proceed();
            // Drag start, spring completion and resetViewTransforms all write
            // a flat native Z. Preserve our sibling order throughout the drag.
            return chain.proceed(new Object[]{card.projectedZ});
        }, float.class);
        for (String setter : new String[]{"setTranslationX", "setTranslationY"}) {
            boolean horizontal = setter.equals("setTranslationX");
            installer.hook("android.view.View", setter, chain -> {
                View task = (View) chain.getThisObject();
                Card card = cards.get(task);
                if (card == null || card.writingTranslation || !enabled.getAsBoolean()
                        || !(task.getParent() instanceof View parent) || !active(parent)) return chain.proceed();
                float value = (Float) chain.getArg(0);
                State state = states.get(parent);
                boolean handoff = state != null && state.handoffProgress < 1f && state.handoffPoses.containsKey(task);
                if (horizontal) {
                    if (handoff) card.offsetX += card.translationX - value;
                    card.translationX = value;
                } else {
                    if (handoff) card.offsetY += card.translationY - value;
                    card.translationY = value;
                }
                card.translationRevision++;
                return chain.proceed(new Object[]{value + (horizontal ? card.offsetX : card.offsetY)});
            }, float.class);
        }
        for (String method : new String[]{"applyScale", "applyTranslationX", "applyTranslationY"}) {
            boolean scale = method.equals("applyScale");
            boolean horizontal = method.equals("applyTranslationX");
            installer.hook(TASK, method, chain -> {
                Card before = cards.get(chain.getThisObject());
                long revision = before == null ? -1 : before.translationRevision;
                long scaleRevision = before == null ? -1 : before.scaleRevision;
                Object result = chain.proceed();
                View view = (View) chain.getThisObject();
                Card card = cards.get(view);
                if (card != null) {
                    if (scale) {
                        if (card.scaleRevision != scaleRevision) return result;
                        State state = states.get(view.getParent());
                        EntryPose pose = state == null ? null : state.entryPoses.get(view);
                        if (pose != null && !state.entryFinished) {
                            card.scaleX = pose.nativeScaleX; card.scaleY = pose.nativeScaleY;
                            float desiredX = pose.scaleX + (pose.targetScaleX - pose.scaleX) * state.entryProgress;
                            float desiredY = pose.scaleY + (pose.targetScaleY - pose.scaleY) * state.entryProgress;
                            card.factorX = card.scaleX == 0f ? 1f : desiredX / card.scaleX;
                            card.factorY = card.scaleY == 0f ? 1f : desiredY / card.scaleY;
                            writeScale(view, card, desiredX, desiredY);
                        } else if (state != null && state.handoffProgress < 1f && state.handoffPoses.containsKey(view)) {
                            writeScale(view, card, card.renderScaleX, card.renderScaleY);
                        } else {
                            card.scaleX = view.getScaleX(); card.scaleY = view.getScaleY();
                            writeScale(view, card, card.scaleX * card.factorX, card.scaleY * card.factorY);
                        }
                    } else {
                        if (card.translationRevision != revision) return result;
                        State state = states.get(view.getParent());
                        boolean handoff = state != null && state.handoffProgress < 1f && state.handoffPoses.containsKey(view);
                        if (horizontal) {
                            if (handoff) card.offsetX += card.translationX - view.getTranslationX();
                            card.translationX = view.getTranslationX();
                            writeTranslation(view, card, true, card.translationX + card.offsetX);
                        } else {
                            if (handoff) card.offsetY += card.translationY - view.getTranslationY();
                            card.translationY = view.getTranslationY();
                            writeTranslation(view, card, false, card.translationY + card.offsetY);
                        }
                    }
                }
                return result;
            });
        }
        installer.hook(FACTORY, "apply", chain -> {
            if (!alphaType.isInstance(chain.getThisObject())) return chain.proceed();
            Object target = alphaTarget.get(chain.getThisObject());
            Card card = cards.get(target);
            if (card == null) return chain.proceed();
            card.alpha = (Float) chain.getArg(0);
            return chain.proceed(new Object[]{card.alpha * card.opacity});
        }, float.class);
    }

    void install(SignalHooks.Installer installer, ClassLoader loader, Consumer<Method> deoptimizer)
            throws ReflectiveOperationException {
        install(installer);
        for (String type : new String[]{"com.meizu.flyme.launcher.MzLauncher", "com.android.launcher3.uioverrides.QuickstepLauncher"}) {
            deoptimizer.accept(loader.loadClass(type).getDeclaredMethod("onStateSetStart", launcherStateType));
        }
        // These private final writers and the visibility lambda are commonly
        // inlined by ART. Keep their callers going through the installed hooks.
        for (Method method : taskType.getDeclaredMethods()) {
            String name = method.getName();
            if ((name.startsWith("set") && (name.contains("Scale") || name.contains("Translation")))
                    || name.equals("onGridProgressChanged") || name.equals("resetViewTransforms")) {
                deoptimizer.accept(method);
            }
        }
        deoptimizer.accept(taskType.getDeclaredMethod("applyScale"));
        deoptimizer.accept(loader.loadClass(RECENTS).getDeclaredMethod("resetTaskVisuals"));
        deoptimizer.accept(loader.loadClass(RECENTS).getDeclaredMethod("updatePageOffsets"));
        deoptimizer.accept(loader.loadClass(RECENTS).getDeclaredMethod("updatePageOffsetsForFlyme"));
        for (Method method : loader.loadClass(RECENTS).getDeclaredMethods()) {
            if (method.getName().equals("createTaskDismissAnimation")) deoptimizer.accept(method);
        }
        try {
            Class<?> dismiss = loader.loadClass("com.android.launcher3.uioverrides.touchcontrollers.TaskViewDismissTouchController");
            deoptimizer.accept(dismiss.getDeclaredMethod("onDragStart", boolean.class, float.class));
            deoptimizer.accept(dismiss.getDeclaredMethod("clearState"));
        } catch (ReflectiveOperationException error) {
            log.accept("Cannot deoptimize task dismissal Z writers", error);
        }
        deoptimizer.accept(taskType.getDeclaredMethod("onClick"));
        deoptimizer.accept(loader.loadClass(RECENTS).getDeclaredMethod("onPageBeginTransition"));
        deoptimizer.accept(loader.loadClass(RECENTS).getDeclaredMethod("onPageEndTransition"));
        try {
            deoptimizer.accept(loader.loadClass(TASK + "$mSnapshotViewTouchHandler$1")
                    .getDeclaredMethod("onTouch", View.class, MotionEvent.class));
            deoptimizer.accept(loader.loadClass(TASK + "$mScaleUpRunnable$1").getDeclaredMethod("run"));
        } catch (ReflectiveOperationException error) {
            log.accept("Cannot deoptimize task press-scale callbacks", error);
        }
        for (Object property : new Object[]{View.SCALE_X, View.SCALE_Y, View.TRANSLATION_X, View.TRANSLATION_Y}) {
            for (Method method : property.getClass().getDeclaredMethods()) {
                if (method.getName().equals("setValue")) deoptimizer.accept(method);
            }
        }
        deoptimizer.accept(alphaType.getDeclaredMethod("apply", float.class));
        Class<?> property = loader.loadClass(FACTORY + "$MultiProperty");
        deoptimizer.accept(property.getDeclaredMethod("setValue", float.class));
        deoptimizer.accept(loadVisible);
        deoptimizer.accept(loader.loadClass(RECENTS).getDeclaredMethod("getScrollOffset"));
        deoptimizer.accept(dispatchScroll);
        // The simulator callback can be inlined into apply or its bridge even
        // when createSurfaceParams itself is deoptimized. Keep the real app's
        // transaction on the same corrected matrix as the task snapshot.
        Class<?> simulator = loader.loadClass("com.android.quickstep.util.TaskViewSimulator");
        for (Method method : simulator.getDeclaredMethods()) {
            if (method.getName().equals("apply") || method.getName().equals("onBuildTargetParams")) {
                deoptimizer.accept(method);
            }
        }
        deoptimizer.accept(transformParamsType.getDeclaredMethod("createSurfaceParams",
                loader.loadClass("com.android.quickstep.util.TransformParams$BuilderProxy")));
        deoptimizer.accept(loader.loadClass(RECENTS).getDeclaredMethod("dispatchDraw", Canvas.class));
        deoptimizer.accept(ViewGroup.class.getDeclaredMethod("dispatchDraw", Canvas.class));
        // Verified caller generated for Flyme RecentsView.loadVisibleTaskData.
        try {
            Class<?> visibility = loader.loadClass(RECENTS + "$$ExternalSyntheticLambda46");
            deoptimizer.accept(visibility.getDeclaredMethod("accept", Object.class, Object.class));
        } catch (ReflectiveOperationException error) {
            log.accept("Cannot deoptimize recent-thumbnail visibility bridge", error);
        }
    }

    private boolean active(View view) throws ReflectiveOperationException {
        State state = states.get(view);
        Object target = endTarget.get(view);
        boolean releasedToRecents = state != null && state.entryStarted
                && target instanceof Enum<?> value && "RECENTS".equals(value.name());
        return enabled.getAsBoolean() && !grid.getBoolean(view)
                && (overview.getBoolean(view) || releasedToRecents)
                && (!(Boolean) gestureActive.invoke(view) || releasedToRecents);
    }

    private boolean visible(ViewGroup view, View child, float buffer) throws ReflectiveOperationException {
        State state = states.get(view);
        if (child.getParent() != view || state == null || state.step <= 0) return false;
        Card card = cards.get(child);
        float fullscreenStrength = stackStrength(view, state);
        float strength = fullscreenStrength * state.entryProgress;
        // Launching a task must not reload every previously culled thumbnail.
        if (state.entryFinished && state.handoffProgress == 1f && fullscreenStrength < 1f && card != null) return !card.hidden;
        float translation = state.horizontal
                ? (card != null ? card.translationX : child.getTranslationX())
                : (card != null ? card.translationY : child.getTranslationY());
        int size = state.horizontal ? child.getWidth() : child.getHeight();
        int parentSize = state.horizontal ? view.getWidth() : view.getHeight();
        float baseScale = state.horizontal
                ? (card != null ? card.scaleX : child.getScaleX())
                : (card != null ? card.scaleY : child.getScaleY());
        float origin = state.horizontal ? child.getLeft() - view.getScrollX() : child.getTop() - view.getScrollY();
        float pivot = state.horizontal ? child.getPivotX() : child.getPivotY();
        float center = origin + translation + pivot + (size * .5f - pivot) * baseScale;
        float anchor = parentSize * .5f;
        EntryPose pose = state.entryPoses.get(child);
        float startCenter = pose == null ? center : (state.horizontal ? pose.x : pose.y);
        float pageScroll = card == null ? (Integer) scrollForPage.invoke(view, view.indexOfChild(child)) : card.scroll;
        float distance = (pageScroll - (state.entryStarted && !state.entryFinished
                ? state.entryTargetScroll : state.horizontal ? view.getScrollX() : view.getScrollY())
                + translation + state.overScroll) / state.step;
        float startScale = pose == null ? baseScale : (state.horizontal ? pose.scaleX : pose.scaleY);
        float scale = StackedRecentsGeometry.entryScale(baseScale, startScale,
                StackedRecentsGeometry.scale(distance), state.entryProgress, fullscreenStrength);
        if (pose != null && !state.entryFinished) scale = startScale
                + ((state.horizontal ? pose.targetScaleX : pose.targetScaleY) - startScale) * state.entryProgress;
        float target = pose != null && !state.entryFinished ? pose.targetCenter
                : anchor + StackedRecentsGeometry.offset(distance, size, anchor, 24f * state.density);
        float targetCenter = center + (target - center) * strength
                + (startCenter - center) * (1f - state.entryProgress);
        float left = targetCenter - size * scale * .5f;
        if (pose != null && !state.entryFinished) {
            float targetScale = state.horizontal ? pose.targetScaleX : pose.targetScaleY;
            return pose.targetAlpha > 0f && pose.targetCenter - size * targetScale * .5f < parentSize + 96f * state.density + buffer;
        }
        return distance > -3f && left < parentSize + 96f * state.density + buffer;
    }

    private State state(View view) {
        return states.computeIfAbsent(view, ignored -> new State());
    }

    private float stackStrength(View view, State state) throws IllegalAccessException {
        // Gesture completion and mFullscreenProgress are separate native
        // callbacks. Never interpret the tail of entry as launching an app.
        float progress = fullscreen.getFloat(view);
        if (state.entryStarted && !state.entrySettled) {
            if (state.entryFinished && state.nativeEntryEnded && state.handoffProgress == 1f && progress <= .0001f) {
                state.entrySettled = true;
            } else return 1f;
        }
        return Math.max(0f, Math.min(1f, 1f - progress));
    }

    private void writeScale(View view, Card card, float x, float y) {
        card.renderScaleX = x; card.renderScaleY = y;
        if (view.getScaleX() == x && view.getScaleY() == y) return;
        card.writingScale = true;
        try { view.setScaleX(x); view.setScaleY(y); }
        finally { card.writingScale = false; }
    }

    private void writeZ(View view, Card card, float z) {
        card.projectedZ = z;
        if (view.getTranslationZ() == z) return;
        card.writingZ = true;
        try { view.setTranslationZ(z); }
        finally { card.writingZ = false; }
    }

    private void writeTranslation(View view, Card card, boolean horizontal, float value) {
        if ((horizontal ? view.getTranslationX() : view.getTranslationY()) == value) return;
        card.writingTranslation = true;
        try {
            if (horizontal) view.setTranslationX(value);
            else view.setTranslationY(value);
        } finally { card.writingTranslation = false; }
    }

    private FloatProperty<View> nativeTranslationProperty(boolean horizontal) {
        return new FloatProperty<>(horizontal ? "translationX" : "translationY") {
            @Override public Float get(View view) {
                Card card = cards.get(view);
                return card == null ? (horizontal ? view.getTranslationX() : view.getTranslationY())
                        : (horizontal ? card.translationX : card.translationY);
            }
            @Override public void setValue(View view, float value) {
                if (horizontal) view.setTranslationX(value);
                else view.setTranslationY(value);
            }
        };
    }

    private void rewriteSpringTranslations(Animator animation, View dragged, boolean horizontal) {
        if (animation instanceof AnimatorSet set) {
            for (Animator child : set.getChildAnimations()) rewriteSpringTranslations(child, dragged, horizontal);
        } else if (animation instanceof ObjectAnimator object && cards.containsKey(object.getTarget())) {
            // Read the animation's start in native coordinates: reading the
            // already projected task would otherwise add our offset twice.
            boolean x = "translationX".equals(object.getPropertyName()) || "translateX".equals(object.getPropertyName());
            boolean y = "translationY".equals(object.getPropertyName()) || "translateY".equals(object.getPropertyName());
            if (!x && !y) return;
            if (object.getTarget() != dragged && x == horizontal) {
                // Flyme's down-drag moves every other task by +/- mTaskWidth/5.5.
                // Those absolute native targets belong to the parallel layout;
                // the stack must retain its horizontal arrangement throughout.
                FloatProperty<View> baseline = x ? nativeTranslationX : nativeTranslationY;
                object.setProperty(new FloatProperty<View>(object.getPropertyName()) {
                    @Override public Float get(View view) { return baseline.get(view); }
                    @Override public void setValue(View view, float value) { }
                });
            } else object.setProperty(x ? nativeTranslationX : nativeTranslationY);
        }
    }

    private void revealIcons(ViewGroup view) throws ReflectiveOperationException {
        showIcons.invoke(view, true);
        for (int i = 0; i < view.getChildCount(); i++) {
            View child = view.getChildAt(i);
            if (taskType.isInstance(child)) showTaskIcon.invoke(child, true);
        }
    }

    private void captureEntry(ViewGroup view) throws ReflectiveOperationException {
        State state = state(view);
        state.entryPoses.clear();
        for (int i = 0; i < view.getChildCount(); i++) {
            View child = view.getChildAt(i);
            if (taskType.isInstance(child)) state.entryPoses.put(child, new EntryPose(
                    child.getLeft() - view.getScrollX() + child.getTranslationX() + child.getPivotX()
                            + (child.getWidth() * .5f - child.getPivotX()) * child.getScaleX(),
                    child.getTop() - view.getScrollY() + child.getTranslationY() + child.getPivotY()
                            + (child.getHeight() * .5f - child.getPivotY()) * child.getScaleY(),
                    child.getScaleX(), child.getScaleY()));
        }
    }

    private void beginEntry(View view) throws ReflectiveOperationException {
        beginEntry(view, null);
    }

    private void beginEntry(View view, AnimatorSet nativeAnimation) throws ReflectiveOperationException {
        beginEntry(view, nativeAnimation, state(view).fromHome);
    }

    private void beginEntry(View view, AnimatorSet nativeAnimation, boolean fromHome) throws ReflectiveOperationException {
        if (grid.getBoolean(view)) return;
        State state = state(view);
        state.entryStarted = true;
        state.entrySettled = false;
        state.loggedStages = 0;
        if (nativeAnimation == null) state.nativeEntryEnded = true;
        if (state.entryAnimator != null) state.entryAnimator.cancel();
        boolean horizontal = (Integer) primaryAxis.invoke(orientationHandler.invoke(view), 1, 0) == 1;
        if (state.entryPoses.isEmpty()) captureEntry((ViewGroup) view);
        for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View child = ((ViewGroup) view).getChildAt(i);
            if (taskType.isInstance(child)) (horizontal ? resetPageOffsetX : resetPageOffsetY).invoke(child, 0f);
        }
        int running = (Integer) runningIndex.invoke(view);
        int target = StackedRecentsGeometry.previousPage(running, running, (Integer) taskCount.invoke(view));
        state.entryPage = target;
        state.entryTargetScroll = target >= 0 ? (Integer) scrollForPage.invoke(view, target)
                : (horizontal ? view.getScrollX() : view.getScrollY());
        float density = view.getResources().getDisplayMetrics().density;
        for (Map.Entry<View, EntryPose> item : state.entryPoses.entrySet()) {
            View child = item.getKey(); EntryPose pose = item.getValue();
            int size = horizontal ? child.getWidth() : child.getHeight();
            if (fromHome) {
                float start = -size * .5f * (horizontal ? pose.scaleX : pose.scaleY) - 24f * density;
                if (horizontal) pose.x = Math.min(pose.x, start);
                else pose.y = Math.min(pose.y, start);
            }
            // updatePageScales settles the carousel at scale 1, not the
            // fullscreen/non-grid size correction used during the gesture.
            float finalScale = ((Number) sizeAdjustment.invoke(child, false)).floatValue();
            Card card = cards.get(child);
            float translationX = card == null ? child.getTranslationX() : card.translationX;
            float translationY = card == null ? child.getTranslationY() : card.translationY;
            float anchor = (horizontal ? view.getWidth() : view.getHeight()) * .5f;
            int step = size;
            if ((Integer) taskCount.invoke(view) > 1) step = Math.abs(
                    (Integer) scrollForPage.invoke(view, 1) - (Integer) scrollForPage.invoke(view, 0));
            // Page scrolls already include each task's width/alignment. The running
            // task still carries gesture translations and noncentral pivots here;
            // its transient view center is not its final page position.
            int index = ((ViewGroup) view).indexOfChild(child);
            float distance = ((Integer) scrollForPage.invoke(view, index) - state.entryTargetScroll) / Math.max(1, step);
            pose.targetCenter = anchor + StackedRecentsGeometry.offset(distance, size, anchor, 24f * density);
            pose.targetCrossCenter = horizontal
                    ? child.getTop() - view.getScrollY() + translationY + child.getHeight() * .5f
                    : child.getLeft() - view.getScrollX() + translationX + child.getWidth() * .5f;
            pose.nativeScaleX = card == null ? child.getScaleX() : card.scaleX;
            pose.nativeScaleY = card == null ? child.getScaleY() : card.scaleY;
            pose.targetScaleX = finalScale * StackedRecentsGeometry.scale(distance);
            pose.targetScaleY = finalScale * StackedRecentsGeometry.scale(distance);
            pose.targetAlpha = StackedRecentsGeometry.alpha(distance) * StackedRecentsGeometry.leftEdgeVisibility(distance);
        }
        state.entryFinished = false;
        state.blurProgress = 0f;
        if (state.blurAnimator != null) state.blurAnimator.cancel();
        loadVisible.invoke(view, 15);
        revealIcons((ViewGroup) view);
        state.entryProgress = 0f;
        WeakReference<View> owner = new WeakReference<>(view);
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        state.entryAnimator = animator;
        animator.setDuration(nativeAnimation != null && nativeAnimation.getDuration() > 0
                ? nativeAnimation.getDuration() : 340L);
        animator.setInterpolator(nativeAnimation != null && nativeAnimation.getInterpolator() != null
                ? nativeAnimation.getInterpolator() : new DecelerateInterpolator(1.5f));
        animator.addUpdateListener(animation -> {
            View current = owner.get();
            if (current == null || states.get(current) != state || !enabled.getAsBoolean()) {
                animation.cancel();
                return;
            }
            state.entryProgress = (Float) animation.getAnimatedValue();
            try {
                // Update before the native surface transaction in this frame;
                // dispatchDraw alone can leave the live app one frame behind.
                render((ViewGroup) current);
                dispatchScroll.invoke(current);
            } catch (ReflectiveOperationException error) {
                log.accept("Cannot update stacked-recents entry frame", error);
            }
            if (state.entryProgress == 1f && !state.entryFinished) {
                state.handoffPoses.clear();
                ViewGroup group = (ViewGroup) current;
                for (int i = 0; i < group.getChildCount(); i++) {
                    View child = group.getChildAt(i);
                    if (taskType.isInstance(child)) state.handoffPoses.put(child, new EntryPose(
                            child.getLeft() - group.getScrollX() + child.getTranslationX() + child.getPivotX()
                                    + (child.getWidth() * .5f - child.getPivotX()) * child.getScaleX(),
                            child.getTop() - group.getScrollY() + child.getTranslationY() + child.getPivotY()
                                    + (child.getHeight() * .5f - child.getPivotY()) * child.getScaleY(),
                            child.getScaleX(), child.getScaleY(), child.getAlpha()));
                }
                state.handoffProgress = 0f;
                for (Card card : state.items) {
                    View child = card.view.get();
                    View title = card.title == null ? null : card.title.get();
                    EntryPose pose = state.handoffPoses.get(child);
                    if (pose != null && title != null) pose.titleAlpha = title.getAlpha();
                }
                state.entryFinished = true;
                try {
                    if (state.entryPage >= 0) snapImmediately.invoke(current, state.entryPage);
                } catch (ReflectiveOperationException error) {
                    log.accept("Cannot finalize stacked-recents page", error);
                }
                state.entryPoses.clear();
                try {
                    render((ViewGroup) current);
                    dispatchScroll.invoke(current);
                } catch (ReflectiveOperationException error) {
                    log.accept("Cannot synchronize stacked-recents handoff", error);
                }
                ValueAnimator blurAnimator = ValueAnimator.ofFloat(0f, 1f);
                state.blurAnimator = blurAnimator;
                blurAnimator.setDuration(200L);
                blurAnimator.setInterpolator(new DecelerateInterpolator(1f));
                blurAnimator.addUpdateListener(blurAnimation -> {
                    View parent = owner.get();
                    if (parent == null || states.get(parent) != state || !enabled.getAsBoolean()) {
                        blurAnimation.cancel();
                        return;
                    }
                    state.blurProgress = (Float) blurAnimation.getAnimatedValue();
                    state.handoffProgress = state.blurProgress;
                    try {
                        render((ViewGroup) parent);
                        dispatchScroll.invoke(parent);
                    } catch (ReflectiveOperationException error) {
                        log.accept("Cannot settle stacked-recents geometry", error);
                    }
                    if (state.handoffProgress == 1f) state.handoffPoses.clear();
                    parent.invalidate();
                });
                blurAnimator.start();
            }
            current.invalidate();
        });
        if (nativeAnimation == null) animator.start();
        else nativeAnimation.play(animator);
    }

    private void render(ViewGroup view) throws ReflectiveOperationException {
        State state = state(view);
        if (state.pendingFocus && endTarget.get(view) == null && !(Boolean) gestureActive.invoke(view)) {
            state.pendingFocus = false;
            // dispatchDraw follows layout: capture and apply the offscreen
            // start now instead of exposing a native frame before a posted start.
            beginEntry(view, null, true);
            return;
        }
        float density = view.getResources().getDisplayMetrics().density;
        if (state.dirty || state.childCount != view.getChildCount() || state.density != density) {
            restoreCards(state);
            state.density = density;
            state.horizontal = (Integer) primaryAxis.invoke(orientationHandler.invoke(view), 1, 0) == 1;
            state.effects = new RenderEffect[25];
            state.childCount = view.getChildCount();
            for (int i = 0; i < state.childCount; i++) {
                View child = view.getChildAt(i);
                if (!taskType.isInstance(child) || (state.horizontal ? child.getWidth() : child.getHeight()) <= 0) continue;
                Card card = new Card(child, (Integer) scrollForPage.invoke(view, i));
                EntryPose pose = state.entryPoses.get(child);
                if (pose != null && !state.entryFinished) {
                    card.scaleX = pose.nativeScaleX; card.scaleY = pose.nativeScaleY;
                }
                cards.put(child, card); state.items.add(card);
            }
            state.step = state.items.size() > 1
                    ? Math.abs(state.items.get(1).scroll - state.items.get(0).scroll) : (state.horizontal ? view.getWidth() : view.getHeight());
            state.dirty = state.items.isEmpty() || state.step <= 0;
            if (!state.dirty) loadVisible.invoke(view, 15);
        }
        if (state.dirty) return;
        if (!state.clippingChanged) {
            state.clipChildren = view.getClipChildren();
            state.clipPadding = view.getClipToPadding();
            state.clippingChanged = true;
            // Also clears clipToBounds on each task's RenderNode, allowing the
            // blur kernel to extend beyond the task rather than truncate there.
            view.setClipChildren(false);
            view.setClipToPadding(false);
        }
        // Fade our geometry out as Quickstep goes fullscreen, retaining its own
        // launch/dismiss translations, scale channels and thumbnail privacy blur.
        float fullscreenStrength = stackStrength(view, state);
        float strength = fullscreenStrength * state.entryProgress;
        boolean visibilityChanged = false;
        boolean launching = state.entryFinished && state.handoffProgress == 1f && fullscreenStrength < 1f;
        for (Card card : state.items) {
            View child = card.view.get();
            if (child == null || (launching && card.hidden)) continue;
            View title = card.title == null ? null : card.title.get();
            if (title == null) {
                title = (View) activityTitle.get(child);
                if (title != null) {
                    card.title = new WeakReference<>(title);
                    card.titleAlpha = title.getAlpha();
                }
            }
            int size = state.horizontal ? child.getWidth() : child.getHeight();
            float origin = state.horizontal ? child.getLeft() - view.getScrollX() : child.getTop() - view.getScrollY();
            float pivot = state.horizontal ? child.getPivotX() : child.getPivotY();
            float baseScale = state.horizontal ? card.scaleX : card.scaleY;
            float center = origin + (state.horizontal ? card.translationX : card.translationY)
                    + pivot + (size * .5f - pivot) * baseScale;
            float anchor = (state.horizontal ? view.getWidth() : view.getHeight()) * .5f;
            EntryPose pose = state.entryPoses.get(child);
            float startCenter = pose == null ? center : (state.horizontal ? pose.x : pose.y);
            float primaryTranslation = state.horizontal ? card.translationX : card.translationY;
            float distance = (card.scroll - (state.entryStarted && !state.entryFinished
                    ? state.entryTargetScroll : state.horizontal ? view.getScrollX() : view.getScrollY())
                    + primaryTranslation + state.overScroll) / state.step;
            float target = anchor + StackedRecentsGeometry.offset(distance, size, anchor, 24f * density);
            float projectedScale = baseScale * StackedRecentsGeometry.scale(distance);
            boolean hidden = pose != null && !state.entryFinished
                    ? pose.targetAlpha <= 0f || pose.targetCenter - size * (state.horizontal ? pose.targetScaleX : pose.targetScaleY) * .5f
                            > (state.horizontal ? view.getWidth() : view.getHeight()) + 96f * density
                    : !launching && strength == 1f && state.handoffProgress == 1f && (distance <= -3f
                            || target - size * projectedScale * .5f > (state.horizontal ? view.getWidth() : view.getHeight()) + 96f * density);
            visibilityChanged |= hidden != card.hidden;
            if (hidden) {
                card.opacity = 0f;
                if (!card.hidden) {
                    child.setAlpha(0f);
                    if (title != null) title.setAlpha(0f);
                    child.setClipToOutline(card.clipOutline);
                    card.effect = null;
                    card.blur = 0;
                    if (card.blurNode != null) card.blurNode.discardDisplayList();
                }
                card.hidden = true;
                continue;
            }
            card.hidden = false;
            float stackScale = StackedRecentsGeometry.scale(distance);
            float scaleX = StackedRecentsGeometry.entryScale(card.scaleX,
                    pose == null ? card.scaleX : pose.scaleX, stackScale, state.entryProgress, fullscreenStrength);
            float scaleY = StackedRecentsGeometry.entryScale(card.scaleY,
                    pose == null ? card.scaleY : pose.scaleY, stackScale, state.entryProgress, fullscreenStrength);
            if (pose != null && !state.entryFinished) {
                scaleX = pose.scaleX + (pose.targetScaleX - pose.scaleX) * state.entryProgress;
                scaleY = pose.scaleY + (pose.targetScaleY - pose.scaleY) * state.entryProgress;
            }
            card.factorX = card.scaleX == 0f ? 1f : scaleX / card.scaleX;
            card.factorY = card.scaleY == 0f ? 1f : scaleY / card.scaleY;
            float animatedTarget = pose != null && !state.entryFinished ? pose.targetCenter : target;
            float stackOffset = (animatedTarget - center) * strength
                    - (size * .5f - pivot) * ((state.horizontal ? scaleX : scaleY) - baseScale)
                    + (startCenter - center) * (1f - state.entryProgress);
            // Native updatePageScales changes both pivots during entry. Keep
            // the cross-axis center fixed instead of scaling from that pivot.
            float crossSize = state.horizontal ? child.getHeight() : child.getWidth();
            float crossPivot = state.horizontal ? child.getPivotY() : child.getPivotX();
            float crossScale = state.horizontal ? scaleY : scaleX;
            float nativeCrossScale = state.horizontal ? card.scaleY : card.scaleX;
            float crossCenter = (state.horizontal ? child.getTop() - view.getScrollY() + card.translationY
                    : child.getLeft() - view.getScrollX() + card.translationX)
                    + crossPivot + (crossSize * .5f - crossPivot) * nativeCrossScale;
            card.crossCenterShift = pose != null && !state.entryFinished
                    ? (state.horizontal ? pose.y : pose.x)
                            + (pose.targetCrossCenter - (state.horizontal ? pose.y : pose.x)) * state.entryProgress
                            - crossCenter : 0f;
            float crossOffset = card.crossCenterShift
                    - (crossSize * .5f - crossPivot) * (crossScale - nativeCrossScale);
            card.offsetX = state.horizontal ? stackOffset : crossOffset;
            card.offsetY = state.horizontal ? crossOffset : stackOffset;
            card.opacity = 1f + ((pose != null && !state.entryFinished ? pose.targetAlpha
                    : StackedRecentsGeometry.alpha(distance) * StackedRecentsGeometry.leftEdgeVisibility(distance)) - 1f) * strength;
            EntryPose handoff = state.handoffPoses.get(child);
            if (handoff != null && state.handoffProgress < 1f) {
                float progress = state.handoffProgress;
                float goalX = child.getLeft() - view.getScrollX() + card.translationX + card.offsetX
                        + child.getPivotX() + (child.getWidth() * .5f - child.getPivotX()) * scaleX;
                float goalY = child.getTop() - view.getScrollY() + card.translationY + card.offsetY
                        + child.getPivotY() + (child.getHeight() * .5f - child.getPivotY()) * scaleY;
                scaleX = handoff.scaleX + (scaleX - handoff.scaleX) * progress;
                scaleY = handoff.scaleY + (scaleY - handoff.scaleY) * progress;
                card.offsetX = handoff.x + (goalX - handoff.x) * progress
                        - (child.getLeft() - view.getScrollX() + card.translationX + child.getPivotX()
                                + (child.getWidth() * .5f - child.getPivotX()) * scaleX);
                card.offsetY = handoff.y + (goalY - handoff.y) * progress
                        - (child.getTop() - view.getScrollY() + card.translationY + child.getPivotY()
                                + (child.getHeight() * .5f - child.getPivotY()) * scaleY);
                float desiredAlpha = handoff.alpha + (card.alpha * card.opacity - handoff.alpha) * progress;
                card.opacity = card.alpha == 0f ? 1f : desiredAlpha / card.alpha;
                card.factorX = card.scaleX == 0f ? 1f : scaleX / card.scaleX;
                card.factorY = card.scaleY == 0f ? 1f : scaleY / card.scaleY;
            }
            writeTranslation(child, card, true, card.translationX + card.offsetX);
            writeTranslation(child, card, false, card.translationY + card.offsetY);
            writeScale(child, card, card.scaleX * card.factorX, card.scaleY * card.factorY);
            child.setAlpha(card.alpha * card.opacity);
            if (title != null) {
                float titleAlpha = card.titleAlpha * (1f + (StackedRecentsGeometry.titleVisibility(distance) - 1f) * strength);
                if (handoff != null && state.handoffProgress < 1f && Float.isFinite(handoff.titleAlpha)) {
                    titleAlpha = handoff.titleAlpha + (titleAlpha - handoff.titleAlpha) * state.handoffProgress;
                }
                title.setAlpha(titleAlpha);
            }
            writeZ(child, card, card.z + Math.max(0f, Math.min(8f, distance + 4f)) * density * strength);
            // Keep the existing kernel during launch; animating its radius
            // reallocates blur layers while the large app surface is scaling.
            int blur = launching ? card.blur : Math.round(StackedRecentsGeometry.blurDp(distance) * fullscreenStrength * state.blurProgress * 2f);
            if (card.blur != blur) {
                card.blur = blur;
                if (blur == 0) card.effect = null;
                child.setClipToOutline(blur == 0 && card.clipOutline);
                if (blur > 0) {
                    if (state.effects[blur] == null) {
                        float radius = blur * .5f * density;
                        state.effects[blur] = RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.DECAL);
                    }
                    card.effect = state.effects[blur];
                }
            }
        }
        if (state.entryStarted && state.entryProgress >= .95f) {
            int stage = !state.entryFinished ? 1 : state.handoffProgress < 1f ? 2 : 4;
            if ((state.loggedStages & stage) == 0) {
                state.loggedStages |= stage;
                int index = (Integer) runningIndex.invoke(view);
                View running = index >= 0 && index < view.getChildCount() ? view.getChildAt(index) : null;
                Card runningCard = cards.get(running);
                if (runningCard != null) {
                    log.accept("StackedRecents card stage=" + stage + " progress=" + state.entryProgress
                            + " page=" + state.entryPage + " scroll=" + (state.horizontal ? view.getScrollX() : view.getScrollY())
                            + " pageScroll=" + runningCard.scroll
                            + " nativeScale=" + runningCard.scaleX + "," + runningCard.scaleY
                            + " renderedScale=" + running.getScaleX() + "," + running.getScaleY()
                            + " parentScale=" + view.getScaleX() + "," + view.getScaleY()
                            + " size=" + running.getWidth() + "x" + running.getHeight()
                            + " fullscreen=" + fullscreen.getFloat(view), null);
                }
            }
        }
        if (visibilityChanged) loadVisible.invoke(view, 15);
    }

    private void restore(View view, boolean remove) {
        State state = states.get(view);
        if (state == null) return;
        if (state.entryAnimator != null) {
            state.entryAnimator.cancel();
            state.entryAnimator = null;
        }
        state.entryProgress = 1f;
        state.entryStarted = false;
        state.fromHome = false;
        state.nativeEntryEnded = false;
        state.entrySettled = false;
        state.entryPoses.clear();
        state.handoffPoses.clear();
        state.handoffProgress = 1f;
        surfaces.values().removeIf(surface -> surface.owner.get() == view);
        state.entryFinished = true;
        state.blurProgress = 1f;
        if (state.blurAnimator != null) {
            state.blurAnimator.cancel();
            state.blurAnimator = null;
        }
        restoreCards(state);
        if (state.clippingChanged && view instanceof ViewGroup group) {
            group.setClipChildren(state.clipChildren);
            group.setClipToPadding(state.clipPadding);
            state.clippingChanged = false;
        }
        state.dirty = true;
        if (remove) states.remove(view);
    }

    private void restoreCards(State state) {
        for (Card card : state.items) {
            View view = card.view.get();
            if (view == null) continue;
            writeTranslation(view, card, true, card.translationX);
            writeTranslation(view, card, false, card.translationY);
            writeScale(view, card, card.scaleX, card.scaleY);
            cards.remove(view);
            view.setAlpha(card.alpha); writeZ(view, card, card.z);
            View title = card.title == null ? null : card.title.get();
            if (title != null) title.setAlpha(card.titleAlpha);
            if (card.blur > 0) view.setClipToOutline(card.clipOutline);
            if (card.blurNode != null) card.blurNode.discardDisplayList();
        }
        state.items.clear();
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field result = type.getDeclaredField(name); result.setAccessible(true); return result;
    }

    private static final class State {
        final ArrayList<Card> items = new ArrayList<>();
        RenderEffect[] effects = new RenderEffect[25];
        boolean dirty = true, pendingFocus, fromHome, horizontal = true;
        boolean clippingChanged, clipChildren, clipPadding, drawing, entryStarted, nativeEntryEnded, entrySettled;
        float overScroll, entryTargetScroll;
        int entryPage = -1;
        boolean entryFinished = true;
        float entryProgress = 1f, blurProgress = 1f, handoffProgress = 1f;
        ValueAnimator entryAnimator, blurAnimator;
        final Map<View, EntryPose> entryPoses = new WeakHashMap<>();
        final Map<View, EntryPose> handoffPoses = new WeakHashMap<>();
        int childCount, loggedStages;
        float step, density;
    }

    private static final class EntryPose {
        float x, y;
        final float scaleX, scaleY, alpha;
        float targetCenter, targetCrossCenter, targetScaleX, targetScaleY, targetAlpha, nativeScaleX, nativeScaleY;
        float titleAlpha = Float.NaN;
        EntryPose(float x, float y, float scaleX, float scaleY) {
            this(x, y, scaleX, scaleY, 1f);
        }
        EntryPose(float x, float y, float scaleX, float scaleY, float alpha) {
            this.x = x; this.y = y; this.scaleX = scaleX; this.scaleY = scaleY;
            this.alpha = alpha;
            nativeScaleX = scaleX; nativeScaleY = scaleY;
        }
    }

    private static final class SurfaceState {
        final WeakReference<View> owner;
        final float[] savedMatrix = new float[9];
        final RectF bounds = new RectF();
        final Rect targetBounds = new Rect();
        final RectF correctedBounds = new RectF();
        int frames, submissions, loggedStages;
        boolean builderCorrecting;
        final float[] submitValues = new float[9];
        final Matrix submitMatrix = new Matrix();
        final RectF submitBounds = new RectF();
        final Rect submitTarget = new Rect();
        SurfaceState(View owner) { this.owner = new WeakReference<>(owner); }
    }

    private static final class Card {
        final WeakReference<View> view;
        WeakReference<View> title;
        float titleAlpha;
        final int scroll;
        final float z;
        final boolean clipOutline;
        float translationX, translationY, scaleX, scaleY, renderScaleX, renderScaleY, alpha, projectedZ;
        float offsetX, offsetY, crossCenterShift, factorX = 1f, factorY = 1f, opacity = 1f;
        int blur;
        boolean hidden, writingScale, writingZ, writingTranslation;
        long translationRevision, scaleRevision;
        RenderEffect effect;
        RenderNode blurNode;
        final RectF bounds = new RectF();
        Card(View view, int scroll) {
            this.view = new WeakReference<>(view); this.scroll = scroll;
            clipOutline = view.getClipToOutline();
            translationX = view.getTranslationX(); translationY = view.getTranslationY(); scaleX = view.getScaleX(); scaleY = view.getScaleY();
            renderScaleX = scaleX; renderScaleY = scaleY;
            alpha = view.getAlpha(); z = view.getTranslationZ();
            projectedZ = z;
        }
    }
}
