package dev.rikumi.flymemod;

import android.content.Context;
import android.content.ContextWrapper;
import android.database.ContentObserver;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.*;
import java.util.function.*;

final class LauncherPreviewContourHooks {
    private final Class<?> activity,fast,transition;
    private static final ThreadLocal<TextView> transitionHost=new ThreadLocal<>();
    private final ThreadLocal<Boolean> decorating=ThreadLocal.withInitial(() -> false);
    static boolean drawsTransitionIcon(){return transitionHost.get()!=null;}
    private final ClassLoader loader;
    private final Method fastInfo;
    private final Field fastPixels;
    private final ThreadLocal<Boolean> capturing=ThreadLocal.withInitial(() -> false);
    private final Consumer<Context> settings;
    private final BooleanSupplier enabled;
    private final BiConsumer<String,Throwable> log;
    private final Map<View,Map<Drawable,Frame>> frames=new WeakHashMap<>();
    private final Set<Drawable> panelDrawables=Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<View> hosts=Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<View> errors=Collections.newSetFromMap(new WeakHashMap<>());
    private ContentObserver observer;

    LauncherPreviewContourHooks(ClassLoader loader,Consumer<Context> settings,BooleanSupplier enabled,
                                BiConsumer<String,Throwable> log) throws ReflectiveOperationException {
        this.loader=loader;this.settings=settings;this.enabled=enabled;this.log=log;
        activity=loader.loadClass("com.meizu.flyme.launcher.LauncherIconSettingActivity");
        fast=loader.loadClass("com.android.launcher3.icons.FastBitmapDrawable");
        transition=loader.loadClass("com.meizu.flyme.launcher.graphics.TransitionDrawable");
        fastInfo=fast.getMethod("getBitmapInfo");
        fastPixels=loader.loadClass("com.android.launcher3.icons.BitmapInfo").getField("icon");

    }

    void install(SignalHooks.Installer installer,Consumer<Method> deoptimize) throws ReflectiveOperationException {
        String bubble="com.meizu.flyme.launcher.bubbletextview.FlymeAppBubbleTextView";
        Class<?> panel=loader.loadClass("com.meizu.flyme.launcher.view.MzLauncherIconPanelLayout");
        installer.hook(panel.getName(),"getAppIcon",chain -> {
            Object result=chain.proceed();if(result instanceof Drawable drawable)panelDrawables.add(drawable);return result;
        },panel.getClassLoader().loadClass("com.android.launcher3.model.data.WorkspaceItemInfo"),boolean.class);
        for(Method method:panel.getDeclaredMethods())if(method.getName().contains("updateCurrentPageIcons")
                ||method.getName().equals("getAppIcon"))deoptimize.accept(method);
        installer.hook(transition.getName(),"draw",chain -> {
            if(IconContourDrawScope.isRaw() || capturing.get())return chain.proceed();
            Drawable source=(Drawable)chain.getThisObject();
            TextView host=owner(source);
            if(host==null)host=transitionHost.get();
            if(host==null || !(previewContext(host.getContext()) || panelDrawable(source)))return chain.proceed();
            track(host);TextView previous=transitionHost.get();transitionHost.set(host);
            try{return chain.proceed();}finally{transitionHost.set(previous);}
        },Canvas.class);
        deoptimize.accept(transition.getDeclaredMethod("draw",Canvas.class));

        for(String name:new String[]{"android.graphics.drawable.BitmapDrawable",
                "android.graphics.drawable.AdaptiveIconDrawable","android.graphics.drawable.ScaleDrawable",
                "com.meizu.flyme.launcher.view.RoundCornerDrawableWrapper",fast.getName()}) {
            installer.hook(name,"draw",chain -> {
                if(IconContourDrawScope.isRaw() || capturing.get() || decorating.get())return chain.proceed();
                Drawable source=(Drawable)chain.getThisObject();
                if(wrapsTransition(source))return chain.proceed();
                TextView host=transitionHost.get();
                if(host==null)host=owner(source);
                if(host==null || fast.isInstance(source) && transitionHost.get()==null
                        || !(previewContext(host.getContext()) || panelDrawable(source) || transitionHost.get()!=null))return chain.proceed();
                track(host);decorating.set(true);
                try {
                    Object result=chain.proceed();
                    if(enabled.getAsBoolean())draw(host,source,(Canvas)chain.getArg(0),new RectF(source.getBounds()));
                    return result;
                } finally{decorating.set(false);}
            },Canvas.class);
            deoptimize.accept(loader.loadClass(name).getDeclaredMethod("draw",Canvas.class));
        }
        for(String effect:new String[]{"ScaleAndCrossFadeEffect","CrossFadeEffect"})
            for(Method method:loader.loadClass("com.meizu.flyme.launcher.graphics."+effect).getDeclaredMethods())
                if(method.getName().equals("onDraw"))deoptimize.accept(method);
        installer.hook("android.widget.ImageView","onDraw",chain -> {
            Object result=chain.proceed();ImageView host=(ImageView)chain.getThisObject();
            if(!selectorPreview(host))return result;
            track(host);Drawable source=host.getDrawable();
            if(source==null || !enabled.getAsBoolean())return result;
            Canvas canvas=(Canvas)chain.getArg(0);int saved=canvas.save();
            try {
                canvas.translate(host.getPaddingLeft(),host.getPaddingTop());canvas.concat(host.getImageMatrix());
                draw(host,source,canvas,new RectF(source.getBounds()));
            } finally {canvas.restoreToCount(saved);}
            return result;
        },Canvas.class);
        deoptimize.accept(loader.loadClass("com.android.launcher3.BubbleTextView").getDeclaredMethod("onDraw",Canvas.class));
        deoptimize.accept(loader.loadClass(bubble).getDeclaredMethod("onDraw",Canvas.class));
        for(Method method:TextView.class.getDeclaredMethods())if(method.getName().equals("onDraw"))deoptimize.accept(method);
        for(Method method:View.class.getDeclaredMethods())if(method.getName().equals("draw")
                ||method.getName().equals("updateDisplayListIfDirty"))deoptimize.accept(method);
    }

    private TextView owner(Drawable source) {
        Drawable.Callback callback=source.getCallback();
        for(int i=0;i<8 && callback!=null;i++) {
            if(callback instanceof TextView text)return text;
            if(!(callback instanceof Drawable drawable))return null;callback=drawable.getCallback();
        }
        return null;
    }
    private boolean wrapsTransition(Drawable source) {
        for(int i=0;i<8 && source!=null;i++) {
            if(transition.isInstance(source))return true;
            if(!(source instanceof DrawableWrapper wrapper))return false;source=wrapper.getDrawable();
        }
        return false;
    }
    private boolean panelDrawable(Drawable source) {
        for(int i=0;i<8 && source!=null;i++) {
            if(panelDrawables.contains(source))return true;
            if(!(source instanceof DrawableWrapper wrapper))return false;source=wrapper.getDrawable();
        }
        return false;
    }
    private boolean previewContext(Context context) {
        for(int i=0;i<8;i++) {
            if(activity.isInstance(context))return true;
            if(!(context instanceof ContextWrapper wrapper))return false;
            Context base=wrapper.getBaseContext();if(base==context)return false;context=base;
        }
        return false;
    }
    private boolean selectorPreview(View host) {
        if(host.getId()==View.NO_ID)return false;
        try {return "icon_pack_item_img".equals(host.getResources().getResourceEntryName(host.getId()));}
        catch(android.content.res.Resources.NotFoundException ignored){return false;}
    }
    private void track(View host) {
        hosts.add(host);if(observer!=null)return;
        Context app=host.getContext().getApplicationContext();settings.accept(app);
        observer=new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override public void onChange(boolean selfChange) {
                settings.accept(app);frames.clear();
                for(View view:new ArrayList<>(hosts))if(view!=null)view.invalidate();
            }
        };
        app.getContentResolver().registerContentObserver(ModuleSettings.URI,false,observer);
    }
    private void draw(View host,Drawable source,Canvas canvas,RectF bounds) {
        if(bounds.isEmpty() || !canvas.isHardwareAccelerated())return;
        try {
            int generation=source instanceof BitmapDrawable bitmap?bitmap.getBitmap().getGenerationId():
                    fast.isInstance(source)?((Bitmap)fastPixels.get(fastInfo.invoke(source))).getGenerationId():0;
            Map<Drawable,Frame> cache=frames.computeIfAbsent(host,key -> new WeakHashMap<>());
            Frame frame=cache.get(source);
            if(frame==null || frame.source!=source || frame.generation!=generation
                    ||frame.width!=bounds.width() ||frame.height!=bounds.height()) {
                if(cache.size()>=4)cache.clear();
                Bitmap bitmap;
                if(source instanceof BitmapDrawable image)bitmap=image.getBitmap();
                else if(fast.isInstance(source))bitmap=(Bitmap)fastPixels.get(fastInfo.invoke(source));
                else {
                    int width=Math.max(1,source.getIntrinsicWidth()),height=Math.max(1,source.getIntrinsicHeight());
                    Rect original=new Rect(source.getBounds());bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
                    capturing.set(true);source.setBounds(0,0,width,height);
                    try(IconContourDrawScope ignored=IconContourDrawScope.enter()) {source.draw(new Canvas(bitmap));}
                    finally {source.setBounds(original);capturing.set(false);}
                }
                Bitmap readable=bitmap.getConfig()==Bitmap.Config.HARDWARE?bitmap.copy(Bitmap.Config.ARGB_8888,false):bitmap;
                int w=readable.getWidth(),h=readable.getHeight();int[] pixels=new int[w*h];
                readable.getPixels(pixels,0,w,0,0,w,h);
                float density=host.getResources().getDisplayMetrics().density;
                Bitmap mask=Bitmap.createBitmap(IconContourPixels.mask(pixels,w,h,2*density*w/bounds.width()),w,h,Bitmap.Config.ARGB_8888);
                for(int i=0;i<pixels.length;i++)pixels[i]=(pixels[i]>>>24)>=64?0xff000000:0;
                Bitmap shadow=Bitmap.createBitmap(IconInnerShadow.pixels(pixels,w,h,3*density*w/bounds.width()),w,h,Bitmap.Config.ARGB_8888);
                if(readable!=bitmap)readable.recycle();
                if(!(source instanceof BitmapDrawable) && !fast.isInstance(source))bitmap.recycle();
                frame=new Frame(source,generation,bounds.width(),bounds.height(),mask,shadow,new LauncherContourRenderer(host));
                cache.put(source,frame);
            }
            Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);paint.setAlpha(source.getAlpha());
            canvas.drawBitmap(frame.shadow,null,bounds,paint);
            frame.renderer.draw(host,canvas,bounds,0,source.getAlpha()/255f,frame.mask);
        } catch(Exception | LinkageError error){if(errors.add(host))log.accept("Cannot render launcher icon preview contour",error);}
    }
    private record Frame(Drawable source,int generation,float width,float height,Bitmap mask,Bitmap shadow,
                         LauncherContourRenderer renderer) {}
}
