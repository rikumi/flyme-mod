package dev.rikumi.flymemod;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/** Native notification/blur parameters; retain grouping, clipping and animated roundness. */
final class NotificationAppearanceHooks {
    // Flyme's native night-mode blur tint and opaque heads-up fallback.
    private static final int DARK_BLUR_COLOR = 0xB21A1A1A;
    private static final int DARK_SOLID_COLOR = 0xFF1A1A1A;
    private final Consumer<Context> settings;
    private final BooleanSupplier light, corners;
    private final IntSupplier opacity, percent;
    private final TileCornerHooks tileCorners;
    private final BiConsumer<String, Throwable> log;
    private final Class<?> rowClass, backgroundClass, mediaClass, landscapeClass, stackClass;
    private final Field stackExpanded;
    private final Method pinned, animatingAway;
    private final Field clipRadius, roundable, maxRadius, mediaRadius, pushBackground, flymeBackground, nativeBackground;
    private final Method rowBackground, mediaBackground, applyRoundness, tintNoBlur;
    private final Map<Object, Float> headerRadii = new WeakHashMap<>();
    private final Map<View, Float> imageRadii = new WeakHashMap<>();
    private final Map<View, Float> maskRadii = new WeakHashMap<>();
    private final Map<View, WeakReference<State>> states = new WeakHashMap<>();

    NotificationAppearanceHooks(ClassLoader loader, Consumer<Context> settings, BooleanSupplier light,
            IntSupplier opacity, BooleanSupplier corners, IntSupplier percent, TileCornerHooks tileCorners,
            BiConsumer<String, Throwable> log) throws ReflectiveOperationException {
        this.settings=settings; this.light=light; this.opacity=opacity; this.corners=corners;
        this.percent=percent; this.tileCorners=tileCorners; this.log=log;
        rowClass=loader.loadClass("com.android.systemui.statusbar.notification.row.ExpandableNotificationRow");
        landscapeClass=loader.loadClass("com.flyme.notification.view.LandscapeHeadsUpNotificationView");
        stackClass=loader.loadClass("com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout");
        stackExpanded=stackClass.getField("mIsExpanded");
        pinned=rowClass.getMethod("isPinned"); animatingAway=rowClass.getMethod("isHeadsUpAnimatingAway");
        backgroundClass=loader.loadClass("com.android.systemui.statusbar.notification.row.NotificationBackgroundView");
        mediaClass=loader.loadClass("com.flyme.systemui.media.controls.ui.view.MediaCarouseTransitionLayout");
        clipRadius=rowClass.getField("mClipCornerRadius"); roundable=rowClass.getField("mRoundableState");
        maxRadius=loader.loadClass("com.android.systemui.statusbar.notification.RoundableState").getField("maxRadius");
        mediaRadius=mediaClass.getField("mClipCornerRadius");
        pushBackground=rowClass.getField("mPushNotificationBg"); flymeBackground=rowClass.getField("mBackgroundFlyme");
        nativeBackground=backgroundClass.getField("mBackground");
        rowBackground=rowClass.getMethod("setBackground"); mediaBackground=mediaClass.getMethod("setBackground");
        applyRoundness=rowClass.getMethod("applyRoundnessAndInvalidate");
        tintNoBlur=backgroundClass.getMethod("setTintForNoBlur",int.class);
    }
    private int color() {
        return Color.argb(Math.round(Math.max(0,Math.min(ModuleSettings.LIGHT_OPACITY_MAX,opacity.getAsInt()))*255f/100f),255,255,255);
    }
    float radius(View view) {
        float side=tileCorners.cellSide(view);
        return side*Math.max(ModuleSettings.TILE_CORNER_PERCENT_MIN,Math.min(ModuleSettings.TILE_CORNER_PERCENT_MAX,percent.getAsInt()))/100f;
    }
    private boolean target(View view) {
        return backgroundClass.isInstance(view) || mediaClass.isInstance(view) || landscapeClass.isInstance(view);
    }
    private boolean floating(View view) throws ReflectiveOperationException {
        if(landscapeClass.isInstance(view))return true;
        for(View current=view;current!=null;current=current.getParent() instanceof View parent?parent:null) {
            if(!rowClass.isInstance(current))continue;
            if(!((Boolean)pinned.invoke(current) || (Boolean)animatingAway.invoke(current)))return false;
            for(View ancestor=current.getParent() instanceof View parent?parent:null;ancestor!=null;
                    ancestor=ancestor.getParent() instanceof View parent?parent:null)
                if(stackClass.isInstance(ancestor))return !stackExpanded.getBoolean(ancestor);
            return true;
        }
        return false;
    }
    private int backgroundColor(View view,int nativeColor,boolean blur) throws ReflectiveOperationException {
        if(floating(view))return blur?DARK_BLUR_COLOR:DARK_SOLID_COLOR;
        return light.getAsBoolean()?color():nativeColor;
    }
    void install(SignalHooks.Installer installer) {
        installer.hook("com.android.systemui.statusbar.notification.row.ActivatableNotificationView", "setBackground", chain -> {
            View view=(View)chain.getThisObject();
            if (!rowClass.isInstance(view)) return chain.proceed();
            settings.accept(view.getContext()); State state=observe(view); state.prepare();
            Object result=chain.proceed(); state.finish(); return result;
        });
        installer.hook(backgroundClass.getName(), "setBlurBackground", chain -> {
            View view=(View)chain.getThisObject(); settings.accept(view.getContext());
            Object[] args=chain.getArgs().toArray();
            args[1]=backgroundColor(view,(Integer)args[1],true);
            if (corners.getAsBoolean()) args[2]=Math.round(radius(view));
            return chain.proceed(args);
        },boolean.class,int.class,int.class);
        installer.hook(backgroundClass.getName(), "setTintForNoBlur", chain -> {
            View view=(View)chain.getThisObject(); settings.accept(view.getContext());
            return chain.proceed(new Object[]{backgroundColor(view,(Integer)chain.getArg(0),false)});
        },int.class);
        installer.hook(landscapeClass.getName(), "setBackground", chain -> {
            View view=(View)chain.getThisObject();
            Object result=chain.proceed();
            Drawable drawable=view.getBackground();
            if(drawable!=null && !drawable.getClass().getName().endsWith("BackgroundBlurDrawable"))
                drawable.mutate().setTint(DARK_SOLID_COLOR);
            return result;
        });
        installer.hook(mediaClass.getName(), "setBackground", chain -> {
            View view=(View)chain.getThisObject(); settings.accept(view.getContext());
            State state=observe(view); state.prepare(); Object result=chain.proceed(); state.finish(); return result;
        });
        // Both blur implementations already own their alpha/fade; change the foreground tint only.
        installer.hook("com.flyme.systemui.utils.MzBlurUtils", "setBackgroundBlurDrawable", chain -> {
            View view=(View)chain.getArg(0);
            if (!target(view)) return chain.proceed();
            settings.accept(view.getContext()); Object[] args=chain.getArgs().toArray();
            args[4]=backgroundColor(view,(Integer)args[4],true);
            if (corners.getAsBoolean() && !landscapeClass.isInstance(view)) args[3]=radius(view);
            return chain.proceed(args);
        },View.class,int.class,float.class,float.class,int.class,boolean.class,int.class);
        installer.hook("com.flyme.systemui.wallpaper.WallpaperBlurDrawableManager", "addBlurDrawableTo", chain -> {
            View view=(View)chain.getArg(0);
            if (!target(view)) return chain.proceed();
            settings.accept(view.getContext()); Object[] args=chain.getArgs().toArray();
            args[1]=backgroundColor(view,(Integer)args[1],true);
            if (corners.getAsBoolean() && !landscapeClass.isInstance(view)) args[2]=radius(view);
            return chain.proceed(args);
        },View.class,int.class,float.class);
        installer.hook("com.flyme.systemui.wallpaper.WallpaperBlurDrawableManager", "setAllForegroundColor", chain -> {
            View view=(View)chain.getArg(0);
            if (!target(view)) return chain.proceed();
            settings.accept(view.getContext());
            return chain.proceed(new Object[]{view,backgroundColor(view,(Integer)chain.getArg(1),true)});
        },View.class,int.class);
    }
    private State observe(View view) {
        WeakReference<State> reference=states.get(view); State state=reference==null?null:reference.get();
        if (state==null) {
            state=new State(view); states.put(view,new WeakReference<>(state));
            view.addOnAttachStateChangeListener(state);
            if(view.isAttachedToWindow()) state.onViewAttachedToWindow(view);
        }
        return state;
    }
    void configureHeader(Object wrapper, View content) throws ReflectiveOperationException {
        Object state=wrapper.getClass().getField("mRoundableState").get(wrapper);
        if(state==null)return;
        if(corners.getAsBoolean()) {
            headerRadii.putIfAbsent(wrapper,maxRadius.getFloat(state));
            state.getClass().getMethod("setMaxRadius",float.class).invoke(state,radius(content));
        } else {
            Float original=headerRadii.remove(wrapper);
            if(original!=null)state.getClass().getMethod("setMaxRadius",float.class).invoke(state,original);
        }
    }
    float imageRadius(View view,float nativeRadius) {
        if(corners.getAsBoolean()) { imageRadii.put(view,nativeRadius); return radius(view); }
        imageRadii.remove(view); return nativeRadius;
    }
    void configureMediaMask(View view) {
        if(view.getBackground() instanceof GradientDrawable gradient) {
            if(corners.getAsBoolean()) {
                maskRadii.putIfAbsent(view,gradient.getCornerRadius());
                gradient.mutate(); gradient.setCornerRadius(radius(view));
            } else {
                Float original=maskRadii.remove(view); if(original!=null)gradient.setCornerRadius(original);
            }
        }
    }
    void refresh() {
        try {
            for(Object wrapper:new ArrayList<>(headerRadii.keySet()))
                configureHeader(wrapper,(View)wrapper.getClass().getField("mView").get(wrapper));
            for(View view:new ArrayList<>(maskRadii.keySet())) configureMediaMask(view);
            for(View view:new ArrayList<>(imageRadii.keySet())) {
                float target=corners.getAsBoolean()?radius(view):imageRadii.remove(view);
                // Calling the public setter passes through our hook; preserve its native input.
                float original=imageRadii.getOrDefault(view,target);
                view.getClass().getMethod("setRound",float.class).invoke(view,original);
            }
        } catch(ReflectiveOperationException | RuntimeException error) {
            log.accept("Cannot refresh notification header or media mask corners",error);
        }
        for (WeakReference<State> reference:new ArrayList<>(states.values())) {
            State state=reference.get(); if(state!=null) state.update();
        }
    }
    private final class State implements View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
        final WeakReference<View> owner;
        final boolean media;
        boolean saved, running, failed;
        float originalRadius, originalMax;
        int lastColor=Integer.MIN_VALUE;
        float lastRadius=-1;
        boolean lastLight, lastCorners, lastFloating;
        ViewTreeObserver observer;
        ViewOutlineProvider originalOutline;
        boolean originalClip;
        final ViewOutlineProvider outline=new ViewOutlineProvider() {
            @Override public void getOutline(View view,Outline result) {
                result.setRoundRect(0,0,view.getWidth(),view.getHeight(),radius(view));
            }
        };
        State(View view) { owner=new WeakReference<>(view); media=mediaClass.isInstance(view); }
        @Override public void onViewAttachedToWindow(View view) {
            observer=view.getViewTreeObserver(); observer.addOnPreDrawListener(this);
        }
        @Override public void onViewDetachedFromWindow(View view) {
            if(observer!=null && observer.isAlive()) observer.removeOnPreDrawListener(this);
            observer=null; failed=false;
        }
        @Override public boolean onPreDraw() { update(); return true; }
        void prepare() throws ReflectiveOperationException {
            View view=owner.get(); if(view==null)return;
            if(corners.getAsBoolean()) {
                float targetRadius=radius(view);
                if(!saved) {
                    originalRadius=media?mediaRadius.getFloat(view):clipRadius.getInt(view);
                    if(!media && roundable.get(view)!=null) originalMax=maxRadius.getFloat(roundable.get(view));
                    originalOutline=view.getOutlineProvider(); originalClip=view.getClipToOutline(); saved=true;
                }
                if(media) {
                    mediaRadius.setFloat(view,targetRadius);
                    view.setOutlineProvider(outline); view.setClipToOutline(true);
                } else {
                    clipRadius.setInt(view,Math.round(targetRadius));
                    Object state=roundable.get(view); if(state!=null)maxRadius.setFloat(state,targetRadius);
                }
            } else if(saved) {
                if(media) {
                    mediaRadius.setFloat(view,originalRadius);
                    if(view.getOutlineProvider()==outline) { view.setOutlineProvider(originalOutline); view.setClipToOutline(originalClip); }
                } else {
                    clipRadius.setInt(view,Math.round(originalRadius));
                    Object state=roundable.get(view); if(state!=null)maxRadius.setFloat(state,originalMax);
                }
                saved=false;
            }
        }
        void finish() throws ReflectiveOperationException {
            View view=owner.get(); if(view==null)return;
            if(corners.getAsBoolean() || lastCorners) {
                if(media) view.invalidateOutline();
                else if(flymeBackground.get(view)!=null)applyRoundness.invoke(view);
            }
            boolean isFloating=floating(view);
            if(light.getAsBoolean() || isFloating) {
                if(media) {
                    Drawable drawable=view.getBackground();
                    if(drawable!=null && !drawable.getClass().getName().endsWith("BackgroundBlurDrawable")
                            && !drawable.getClass().getName().endsWith("WallpaperBlurDrawable")) drawable.mutate().setTint(color());
                } else if(isFloating || pushBackground.get(view)==null) {
                    Object background=flymeBackground.get(view);
                    if(background!=null && nativeBackground.get(background) instanceof Drawable drawable
                            && !drawable.getClass().getName().endsWith("BackgroundBlurDrawable")
                            && !drawable.getClass().getName().endsWith("WallpaperBlurDrawable"))
                        tintNoBlur.invoke(background,isFloating?DARK_SOLID_COLOR:color());
                }
            }
            lastColor=color(); lastLight=light.getAsBoolean(); lastCorners=corners.getAsBoolean();
            lastFloating=isFloating;
            lastRadius=lastCorners?radius(view):-1f;
        }
        void update() {
            View view=owner.get(); if(view==null || running || failed || !view.isAttachedToWindow())return;
            boolean currentCorners=corners.getAsBoolean(), currentLight=light.getAsBoolean();
            boolean currentFloating;
            try { currentFloating=floating(view); }
            catch(ReflectiveOperationException error) {
                failed=true; log.accept("Cannot resolve floating notification state",error); return;
            }
            boolean changed=currentFloating!=lastFloating || currentCorners!=lastCorners || currentLight!=lastLight
                    || (currentLight && color()!=lastColor) || (currentCorners && radius(view)!=lastRadius);
            if(!changed)return;
            running=true;
            try { if(media)mediaBackground.invoke(view); else rowBackground.invoke(view); view.requestLayout(); }
            catch(ReflectiveOperationException | RuntimeException error) {
                failed=true; log.accept("Cannot update notification appearance",error);
            } finally { running=false; }
        }
    }
}
