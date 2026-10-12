package dev.rikumi.flymemod;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.*;

/** Draw into Flyme's foreground Surface so the native container controls fade and trajectory. */
final class LauncherIconSurfaceContourHooks {
    private static final String OWNER="com.meizu.flyme.launcher.floatingsurface.drawable.IconDrawable";
    private final Class<?> type;
    private final Field context, size;
    private final Consumer<Context> settings;
    private final BooleanSupplier enabled;
    private final BiConsumer<String,Throwable> log;
    private final Map<Object,Drawable> sources=Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<Object,Boolean> renderedEnabled=Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<Object,Frame> frames=Collections.synchronizedMap(new WeakHashMap<>());
    private final Set<Object> errors=Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    LauncherIconSurfaceContourHooks(ClassLoader loader,Consumer<Context> settings,BooleanSupplier enabled,
                                   BiConsumer<String,Throwable> log) throws ReflectiveOperationException {
        this.settings=settings; this.enabled=enabled; this.log=log;
        type=loader.loadClass(OWNER);
        context=field("appContext"); size=field("iconSize");
        type.getDeclaredMethod("recordOriginalBtv",Drawable.class);
        type.getDeclaredMethod("setIcon",Drawable.class,loader.loadClass("com.android.launcher3.model.data.ItemInfo"),String.class);
        type.getDeclaredMethod("onDrawForeground",Canvas.class);
    }

    private Field field(String name) throws NoSuchFieldException {
        Field result=type.getDeclaredField(name); result.setAccessible(true); return result;
    }

    void install(SignalHooks.Installer installer,Consumer<Method> deoptimize) throws ReflectiveOperationException {
        installer.hook(OWNER,"recordOriginalBtv",chain -> {
            Object result;
            try(IconContourDrawScope ignored=IconContourDrawScope.enter()) {
                result=chain.proceed();
            }
            updateSource(chain.getThisObject(),(Drawable)chain.getArg(0));
            return result;
        },Drawable.class);
        installer.hook(OWNER,"setIcon",chain -> {
            Object result;
            try(IconContourDrawScope ignored=IconContourDrawScope.enter()) {
                result=chain.proceed();
            }
            if(Boolean.TRUE.equals(result))updateSource(chain.getThisObject(),(Drawable)chain.getArg(0));
            return result;
        },Drawable.class,type.getClassLoader().loadClass("com.android.launcher3.model.data.ItemInfo"),String.class);
        installer.hook(OWNER,"onDrawForeground",chain -> {
            Object result=chain.proceed(); Object owner=chain.getThisObject();
            try {
                Context ctx=(Context)context.get(owner); settings.accept(ctx);
                renderedEnabled.put(owner,enabled.getAsBoolean());
                if(!enabled.getAsBoolean())return result;
                Canvas canvas=(Canvas)chain.getArg(0);
                // Surface rendering is hardware-backed; keep native software fallback usable.
                if(!canvas.isHardwareAccelerated())return result;
                Frame frame=frames.get(owner);
                if(frame==null) {
                    Drawable source=sources.get(owner);
                    if(source==null)return result;
                    frame=createFrame(ctx,source,(Point)size.get(owner)); frames.put(owner,frame);
                }
                synchronized(frame) {
                    canvas.drawBitmap(frame.shadow,null,frame.bounds,frame.paint);
                    frame.renderer.draw(ctx,canvas,frame.bounds,0,1,frame.mask);
                }
            } catch(Exception | LinkageError error) {
                if(errors.add(owner))log.accept("Cannot render animated launcher icon contour",error);
            }
            return result;
        },Canvas.class);
        for(Method method:type.getDeclaredMethods())deoptimize.accept(method);
        Class<?> delegate=type.getClassLoader().loadClass("com.meizu.flyme.launcher.floatingsurface.FloatingSurfaceIconDelegate");
        Field delegateIcon=delegate.getDeclaredField("iconDrawable"),delegateSource=delegate.getDeclaredField("btvDrawable");
        delegateIcon.setAccessible(true);delegateSource.setAccessible(true);
        Method drawFrame=delegate.getMethod("drawFrame");
        installer.hook(delegate.getName(),"onAttach",chain -> {
            Object result=chain.proceed();Object owner=chain.getThisObject(),drawable=delegateIcon.get(owner);
            if(drawable!=null) {
                settings.accept((Context)context.get(drawable));
                Boolean previous=renderedEnabled.get(drawable);
                if(previous!=null && previous!=enabled.getAsBoolean())drawFrame.invoke(owner);
            }
            return result;
        },type.getClassLoader().loadClass("com.meizu.flyme.launcher.view.FlymeFloatingIconView"));
        installer.hook(delegate.getName(),"onDrawForeground",chain -> {
            Object owner=chain.getThisObject(),drawable=delegateIcon.get(owner);
            // Initial content is posted inside onCreateLayer, before that method returns.
            if(drawable!=null && !sources.containsKey(drawable))updateSource(drawable,(Drawable)delegateSource.get(owner));
            return chain.proceed();
        },Canvas.class);
        installer.hook(delegate.getName(),"onCreateLayer",chain -> {
            Object result=chain.proceed();Object owner=chain.getThisObject();
            Object drawable=delegateIcon.get(owner);
            if(drawable!=null)updateSource(drawable,(Drawable)delegateSource.get(owner));
            return result;
        });
        for(Method method:delegate.getDeclaredMethods())if(method.getName().equals("onDrawForeground")
                ||method.getName().equals("onCreateLayer")||method.getName().equals("onAttach"))deoptimize.accept(method);
        for(Method method:type.getClassLoader().loadClass("com.meizu.flyme.launcher.floatingsurface.FloatingSurfaceManager").getDeclaredMethods())
            if(method.getName().contains("updateSurfaceContent"))deoptimize.accept(method);
        for(Method method:type.getClassLoader().loadClass("com.meizu.flyme.launcher.floatingsurface.FloatingSurfaceIconDelegate$UpdateSurfaceRunnable").getDeclaredMethods())
            if(method.getName().equals("run"))deoptimize.accept(method);
    }

    private void updateSource(Object owner,Drawable source) {
        if(source!=null)sources.put(owner,source);
        frames.remove(owner);
    }

    private Frame createFrame(Context context,Drawable source,Point size) throws Exception {
        int width=Math.max(1,size.x),height=Math.max(1,size.y);
        Drawable copy=source.getConstantState()==null?source:source.getConstantState().newDrawable(context.getResources());
        Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        Rect original=new Rect(copy.getBounds());
        synchronized(copy) {
            copy.setBounds(0,0,width,height);
            try(IconContourDrawScope ignored=IconContourDrawScope.enter()) { copy.draw(new Canvas(bitmap)); }
            finally { copy.setBounds(original); }
        }
        int[] pixels=new int[width*height];bitmap.getPixels(pixels,0,width,0,0,width,height);bitmap.recycle();
        float density=context.getResources().getDisplayMetrics().density;
        Bitmap mask=Bitmap.createBitmap(IconContourPixels.mask(pixels,width,height,2*density),width,height,Bitmap.Config.ARGB_8888);
        for(int i=0;i<pixels.length;i++)pixels[i]=(pixels[i]>>>24)>=64?0xff000000:0;
        Bitmap shadow=Bitmap.createBitmap(IconInnerShadow.pixels(pixels,width,height,3*density),width,height,Bitmap.Config.ARGB_8888);
        return new Frame(new LauncherContourRenderer(context),mask,shadow,new RectF(0,0,width,height));
    }

    private static final class Frame {
        final LauncherContourRenderer renderer;
        final Bitmap mask,shadow;
        final RectF bounds;
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        Frame(LauncherContourRenderer renderer,Bitmap mask,Bitmap shadow,RectF bounds) {
            this.renderer=renderer;this.mask=mask;this.shadow=shadow;this.bounds=bounds;
        }
    }
}
