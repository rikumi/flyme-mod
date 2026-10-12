package dev.rikumi.flymemod;

import android.annotation.SuppressLint;
import android.graphics.Rect;
import android.graphics.RenderNode;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Consumer;

/** Expand clipping for the native 1.35x ripple without changing layout or touch bounds. */
final class MBackRippleHooks {
    private static final String BUTTON = "com.flyme.systemui.navigationbar.MBackButtonView";
    private static final float PRESSED_SCALE = GestureBarGeometry.PRESSED_SCALE;
    private final Field ripple, maxWidth, target, radius;
    private final Class<?> button;
    private final Method enterHardware, drawSoftware;
    private final BooleanSupplier widthEnabled;
    private final IntSupplier widthDp;
    private final Class<?> navigationBar;
    private final BiConsumer<String, Throwable> log;
    private final Map<View, WeakReference<State>> states = new WeakHashMap<>();

    // Resolve a private method for a libxposed hook inside SystemUI, not an ordinary app API call.
    @SuppressLint("BlockedPrivateApi")
    MBackRippleHooks(ClassLoader loader, BooleanSupplier widthEnabled, IntSupplier widthDp,
            BiConsumer<String, Throwable> log) throws ReflectiveOperationException {
        this.log = log;
        this.widthEnabled=widthEnabled; this.widthDp=widthDp;
        button = loader.loadClass(BUTTON);
        ripple = button.getField("mRipple");
        radius=button.getField("mRadius");
        Class<?> rippleClass=loader.loadClass("com.android.systemui.shared.navigationbar.KeyButtonRipple");
        maxWidth = rippleClass.getField("mMaxWidth"); target=rippleClass.getField("mTargetView");
        rippleClass.getDeclaredMethod("getRippleSize");
        enterHardware=rippleClass.getDeclaredMethod("enterHardware");
        drawSoftware=rippleClass.getDeclaredMethod("drawSoftware",android.graphics.Canvas.class);
        navigationBar = loader.loadClass("com.android.systemui.navigationbar.views.NavigationBarView");
        button.getDeclaredMethod("onAttachedToWindow");
        button.getDeclaredMethod("updateResources");
        View.class.getDeclaredMethod("getDrawableRenderNode",Drawable.class,RenderNode.class);
    }

    void install(SignalHooks.Installer installer, Consumer<Method> deoptimize) {
        installer.hook("com.android.systemui.shared.navigationbar.KeyButtonRipple","getRippleSize",chain -> {
            int nativeSize=(Integer)chain.proceed();
            Object view=target.get(chain.getThisObject());
            return button.isInstance(view)?rippleSize((View)view,nativeSize):nativeSize;
        });
        deoptimize.accept(enterHardware); deoptimize.accept(drawSoftware);
        // Flyme View.drawBackground records the drawable into a separate node.
        // Its recording viewport must also include the native overshooting capsule.
        installer.hook("android.view.View","getDrawableRenderNode",chain -> {
            RenderNode node=(RenderNode)chain.proceed();
            View view=(View)chain.getThisObject();
            Object drawable=chain.getArg(0);
            if(node==null || !button.isInstance(view) || drawable!=ripple.get(view))return node;
            Drawable background=(Drawable)drawable;
            Rect bounds=background.getBounds();
            int nativeSize=Math.min(Math.max(bounds.width(),bounds.height()),maxWidth.getInt(drawable));
            Rect clip=clippingBounds(bounds.width(),bounds.height(),rippleSize(view,nativeSize));
            if(clip.left==0 && clip.top==0 && clip.right==bounds.width() && clip.bottom==bounds.height())return node;
            var canvas=node.beginRecording(clip.width(),clip.height());
            canvas.translate(-bounds.left-clip.left,-bounds.top-clip.top);
            try { background.draw(canvas); }
            finally { node.endRecording(); }
            node.setPosition(bounds.left+clip.left,bounds.top+clip.top,bounds.left+clip.right,bounds.top+clip.bottom);
            node.setClipToBounds(false);
            return node;
        },Drawable.class,RenderNode.class);
        try { deoptimize.accept(View.class.getDeclaredMethod("drawBackground",android.graphics.Canvas.class)); }
        catch(NoSuchMethodException error) { log.accept("Cannot deoptimize mBack background recording",error); }
        installer.hook(BUTTON, "onAttachedToWindow", chain -> {
            Object result = chain.proceed();
            observe((View) chain.getThisObject()).update();
            return result;
        });
        installer.hook(BUTTON, "updateResources", chain -> {
            Object result = chain.proceed();
            View view = (View) chain.getThisObject();
            if (view.isAttachedToWindow()) observe(view).update();
            return result;
        });
    }

    private int rippleSize(View view,int nativeSize) throws IllegalAccessException {
        if(!widthEnabled.getAsBoolean() || view.getWidth()<=0 || view.getHeight()<=0)return nativeSize;
        float length=ModuleSettings.gestureBarWidth(widthDp.getAsInt())*view.getResources().getDisplayMetrics().density;
        return GestureBarGeometry.rippleSize(nativeSize,length,Math.min(view.getWidth(),view.getHeight()),radius.getInt(view)*2f);
    }
    void refresh() {
        for(WeakReference<State> reference:states.values()) {
            State state=reference.get();
            if(state!=null) { state.update(); View view=state.owner.get(); if(view!=null)view.invalidate(); }
        }
    }

    private State observe(View view) {
        WeakReference<State> reference = states.get(view);
        State state = reference == null ? null : reference.get();
        if (state == null) {
            state = new State(view);
            states.put(view, new WeakReference<>(state));
            view.addOnAttachStateChangeListener(state);
            if (view.isAttachedToWindow()) state.onViewAttachedToWindow(view);
        }
        return state;
    }

    static Rect clippingBounds(int width, int height, int rippleSize) {
        boolean horizontal = width > height;
        int extent = horizontal ? width : height;
        // Same size and scale as KeyButtonRipple.enterHardware/drawSoftware.
        int extra = Math.max(0, (int) Math.ceil((rippleSize * PRESSED_SCALE - extent) / 2f));
        return horizontal ? new Rect(-extra, 0, width + extra, height)
                : new Rect(0, -extra, width, height + extra);
    }

    private final class State implements View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
        final WeakReference<View> owner;
        ViewTreeObserver observer;
        Rect originalClip, appliedClip;
        boolean failed;
        State(View view) { owner = new WeakReference<>(view); }
        @Override public void onViewAttachedToWindow(View view) {
            originalClip = view.getClipBounds();
            observer = view.getViewTreeObserver();
            observer.addOnPreDrawListener(this);
        }
        @Override public void onViewDetachedFromWindow(View view) {
            if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(this);
            observer = null;
            if (appliedClip != null && appliedClip.equals(view.getClipBounds())) view.setClipBounds(originalClip);
            appliedClip = null;
            failed = false;
        }
        @Override public boolean onPreDraw() { update(); return true; }
        void update() {
            View view = owner.get();
            if (view == null || failed || !view.isAttachedToWindow() || view.getWidth() <= 0 || view.getHeight() <= 0) return;
            try {
                Object drawable = ripple.get(view);
                if (drawable == null) return;
                int nativeSize=Math.min(Math.max(view.getWidth(),view.getHeight()),maxWidth.getInt(drawable));
                Rect clip = clippingBounds(view.getWidth(), view.getHeight(), rippleSize(view,nativeSize));
                if (originalClip != null) {
                    if (view.getWidth() > view.getHeight()) {
                        clip.left = Math.min(clip.left, originalClip.left);
                        clip.right = Math.max(clip.right, originalClip.right);
                        clip.top = originalClip.top; clip.bottom = originalClip.bottom;
                    } else {
                        clip.top = Math.min(clip.top, originalClip.top);
                        clip.bottom = Math.max(clip.bottom, originalClip.bottom);
                        clip.left = originalClip.left; clip.right = originalClip.right;
                    }
                }
                // clipChildren also clips a child's RenderNode to its layout width.
                // Restore the reference navigation layout's overflow policy along
                // the mBack ancestry, including rebuilt Aicy layouts.
                for (var parent = view.getParent(); parent instanceof ViewGroup group; parent = group.getParent()) {
                    if (group.getClipChildren()) group.setClipChildren(false);
                    if (group.getClipToPadding()) group.setClipToPadding(false);
                    if (navigationBar.isInstance(group)) break;
                }
                if (!clip.equals(view.getClipBounds())) view.setClipBounds(clip);
                appliedClip = clip;
            } catch (IllegalAccessException | RuntimeException error) {
                failed = true; log.accept("Cannot expand mBack ripple clipping", error);
            }
        }
    }
}
