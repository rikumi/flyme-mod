package dev.rikumi.flymemod;

import android.content.Context;
import android.database.ContentObserver;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.*;

/** Edit-mode actions share the desktop icon contour switch and their native blur geometry. */
final class LauncherEditContourHooks {
    private final Class<?> top,bottom,frame,image;
    private final Method frameBlur,imageBlur,setRadius;
    private final Consumer<Context> settings;
    private final BooleanSupplier enabled;
    private final BiConsumer<String,Throwable> log;
    private final Map<View,Entry> entries=new WeakHashMap<>();
    private ContentObserver observer;

    LauncherEditContourHooks(ClassLoader loader,Consumer<Context> settings,BooleanSupplier enabled,
                             BiConsumer<String,Throwable> log) throws ReflectiveOperationException {
        this.settings=settings;this.enabled=enabled;this.log=log;
        top=loader.loadClass("com.meizu.flyme.launcher.view.FlymeTopFunctionGroupLayout");
        bottom=loader.loadClass("com.meizu.flyme.launcher.view.MzBottomFunctionGroupLayout");
        frame=loader.loadClass("com.meizu.flyme.launcher.view.BlurFrameLayout");
        image=loader.loadClass("com.meizu.flyme.launcher.view.BlurImageView");
        frameBlur=frame.getMethod("getBlurUtils");imageBlur=image.getMethod("getBlurUtils");
        setRadius=loader.loadClass("com.meizu.flyme.launcher.utils.BackgroundBlurUtils").getMethod("setCornerRadius",float.class);
    }

    void install(SignalHooks.Installer installer,Consumer<Method> deoptimize) {
        for(Class<?> type:new Class<?>[]{top,bottom})for(Method method:type.getDeclaredMethods())deoptimize.accept(method);
        for(Class<?> type:new Class<?>[]{frame,image})installer.hook(type.getName(),"init",chain -> {
            Object result=chain.proceed();View host=(View)chain.getThisObject();
            boolean isTop=ancestor(host,top);
            if(!isTop && !ancestor(host,bottom))return result;
            settings.accept(host.getContext());
            entries.put(host,new Entry(new Rect((Rect)chain.getArg(1)),(Float)chain.getArg(2),isTop));
            observe(host.getContext());updateRadius(host);host.invalidate();return result;
        },int.class,Rect.class,float.class,int.class,int.class);
        installer.hook("android.view.ViewGroup","dispatchDraw",chain -> {
            Object result=chain.proceed();View host=(View)chain.getThisObject();
            if(frame.isInstance(host))draw(host,(Canvas)chain.getArg(0));return result;
        },Canvas.class);
        installer.hook("android.widget.ImageView","onDraw",chain -> {
            Object result=chain.proceed();View host=(View)chain.getThisObject();
            if(image.isInstance(host))draw(host,(Canvas)chain.getArg(0));return result;
        },Canvas.class);
    }
    private boolean ancestor(View view,Class<?> type) {
        for(View parent=view;parent!=null;parent=parent.getParent() instanceof View next?next:null)
            if(type.isInstance(parent))return true;
        return false;
    }
    private void observe(Context context) {
        if(observer!=null)return;Context app=context.getApplicationContext();
        observer=new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override public void onChange(boolean selfChange) {
                settings.accept(app);
                for(View host:new ArrayList<>(entries.keySet()))if(host!=null){updateRadius(host);host.invalidate();}
            }
        };
        app.getContentResolver().registerContentObserver(ModuleSettings.URI,false,observer);
    }
    private float radius(View host,Entry entry) {
        return Math.min(Math.min(entry.bounds.width(),entry.bounds.height())/2f,
                entry.radius+(enabled.getAsBoolean() && entry.top?4*host.getResources().getDisplayMetrics().density:0));
    }
    private void updateRadius(View host) {
        Entry entry=entries.get(host);if(entry==null || !entry.top)return;
        try {
            float radius=radius(host,entry);
            Object blur=frameBlur.invoke(host);
            if(blur!=null)setRadius.invoke(blur,radius);
            else if(host.getBackground() instanceof GradientDrawable background){background.mutate();background.setCornerRadius(radius);}
        } catch(ReflectiveOperationException | RuntimeException error){report(host,entry,error);}
    }
    private void draw(View host,Canvas canvas) {
        Entry entry=entries.get(host);if(entry==null || !enabled.getAsBoolean() || !canvas.isHardwareAccelerated())return;
        try {
            if(entry.renderer==null)entry.renderer=new LauncherContourRenderer(host);
            entry.renderer.draw(host,canvas,new RectF(entry.bounds),radius(host,entry),1,null);
        } catch(Exception | LinkageError error){report(host,entry,error);}
    }
    private void report(View host,Entry entry,Throwable error) {
        if(!entry.error){entry.error=true;log.accept("Cannot render launcher edit action contour",error);}
    }
    private static final class Entry {
        final Rect bounds;final float radius;final boolean top;
        LauncherContourRenderer renderer;boolean error;
        Entry(Rect bounds,float radius,boolean top){this.bounds=bounds;this.radius=radius;this.top=top;}
    }
}
