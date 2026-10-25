package dev.rikumi.flymemod;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.res.ColorStateList;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ColorFilter;
import android.graphics.PorterDuff;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.LruCache;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.UnaryOperator;

final class SystemIconPackClient {
    private static final ThreadLocal<Integer> nativeDepth=ThreadLocal.withInitial(()->0);
    static boolean isNative() { return nativeDepth.get()>0; }
    static NativeScope nativeScope() { return new NativeScope(); }
    static final class NativeScope implements AutoCloseable {
        final int previous=nativeDepth.get();
        NativeScope() { nativeDepth.set(previous+1); }
        @Override public void close() { if(previous==0) nativeDepth.remove(); else nativeDepth.set(previous); }
    }
    private static final LruCache<String,Bitmap> images=new LruCache<>(16*1024*1024) {
        @Override protected int sizeOf(String key,Bitmap bitmap) { return bitmap.getAllocationByteCount(); }
    };
    private record Request(String pkg,String component,int user) {}
    private static final ExecutorService worker=Executors.newSingleThreadExecutor(task -> {
        Thread thread=new Thread(()-> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            task.run();
        },"FlymeIconPack");
        thread.setDaemon(true); return thread;
    });
    private static final Handler main=new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArrayList<Runnable> listeners=new CopyOnWriteArrayList<>();
    private static final Set<LiveIcon> live=Collections.newSetFromMap(new WeakHashMap<>());
    private static volatile SharedPreferences settings;
    private static final SharedPreferences.OnSharedPreferenceChangeListener changes=(preferences,key)-> {
        if(ModuleSettings.SYSTEM_ICON_PACK.equals(key)) invalidate();
    };
    private static final Runnable dispatch=()-> {
        ArrayList<LiveIcon> icons;
        synchronized(live) { icons=new ArrayList<>(live); }
        for(LiveIcon icon:icons) icon.refresh();
        for(Runnable listener:listeners) listener.run();
    };
    private static volatile Context application;
    // Accessed only by the worker. Observer registration itself must not make the UI do IPC.
    private static boolean observing;
    private static final ContentObserver observer=new ContentObserver(main) {
        @Override public void onChange(boolean selfChange) { invalidate(); }
    };
    private static final SystemIconPackCache<Request,Bitmap> cache=new SystemIconPackCache<>(
            worker,SystemClock::uptimeMillis,SystemIconPackClient::read,SystemIconPackClient::changed,16*1024*1024);
    static void addListener(Runnable listener) { listeners.addIfAbsent(listener); }
    static void configure(SharedPreferences preferences) {
        if(settings==preferences) return;
        if(settings!=null) settings.unregisterOnSharedPreferenceChangeListener(changes);
        settings=preferences;
        settings.registerOnSharedPreferenceChangeListener(changes);
    }
    static void invalidate() { cache.invalidate(); }
    private static void changed() {
        main.removeCallbacks(dispatch);
        main.postDelayed(dispatch,32);
    }
    static Drawable decorate(Context context,String pkg,String component,int user,int density,Drawable original) {
        return decorate(context,pkg,component,user,density,original,UnaryOperator.identity());
    }
    static Drawable decorateApi(Context context,String pkg,String component,int user,int density,Drawable original) {
        return decorateApi(context,pkg,component,user,density,original,UnaryOperator.identity());
    }
    static Drawable decorateApi(Context context,String pkg,String component,int user,int density,Drawable original,
                                UnaryOperator<Drawable> badge) {
        if (original == null || (original.getClass() != BitmapDrawable.class
                && original.getClass() != android.graphics.drawable.AdaptiveIconDrawable.class))
            return original;
        Drawable live = decorate(context,pkg,component,user,density,original,badge);
        return live == original ? original : SystemIconPackApiDrawable.wrap(context.getResources(),original,live);
    }
    static Drawable decorate(Context context,String pkg,String component,int user,int density,Drawable original,
                             UnaryOperator<Drawable> badge) {
        if(original==null || context.getApplicationContext()==null) return original;
        Drawable.ConstantState state=original.getConstantState();
        // Do not take over the callback of a native cached drawable without a clonable state.
        if(state==null) {
            Drawable icon=lookup(context,pkg,component,user,density);
            return icon==null?original:badge.apply(icon);
        }
        LiveIcon result=new LiveIcon(context.getApplicationContext(),pkg,component,user,density,
                state.newDrawable(context.getResources()).mutate(),badge);
        synchronized(live) { live.add(result); }
        result.refresh();
        return result;
    }

    private static final class LiveIcon extends DrawableWrapper {
        private final Context context;
        private final String pkg,component;
        private final int user,density;
        private final Drawable original;
        private final UnaryOperator<Drawable> badge;
        private Bitmap shown;
        private int alpha=255;
        private ColorFilter filter;
        private ColorStateList tint;
        private PorterDuff.Mode mode;
        private boolean filterSet,tintSet;
        LiveIcon(Context context,String pkg,String component,int user,int density,Drawable original,UnaryOperator<Drawable> badge) {
            super(original); this.context=context; this.pkg=pkg; this.component=component;
            this.user=user; this.density=density; this.original=original; this.badge=badge;
        }
        void refresh() {
            boolean enabled=settings!=null && settings.getBoolean(ModuleSettings.SYSTEM_ICON_PACK, false);
            Drawable next=enabled?lookup(context,pkg,component,user,density):null;
            Bitmap pixels=next instanceof BitmapDrawable?((BitmapDrawable)next).getBitmap():null;
            if(pixels==shown) return;
            shown=pixels;
            setDrawable(next==null?original:badge.apply(next));
            super.setAlpha(alpha);
            if(tintSet) super.setTintList(tint);
            if(mode!=null) super.setTintMode(mode);
            if(filterSet) super.setColorFilter(filter);
            invalidateSelf();
        }
        @Override public void setAlpha(int alpha) { this.alpha=alpha; super.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter filter) { this.filter=filter; filterSet=true; super.setColorFilter(filter); }
        @Override public void setTint(int color) { setTintList(ColorStateList.valueOf(color)); }
        @Override public void setTintList(ColorStateList tint) { this.tint=tint; tintSet=true; super.setTintList(tint); }
        @Override public void setTintMode(PorterDuff.Mode mode) { this.mode=mode; super.setTintMode(mode); }
    }
    static Drawable lookup(Context context,String pkg,String component,int user,int density) {
        if(context==null || pkg==null || isNative()) return null;
        if(application==null) {
            Context app=context.getApplicationContext();
            if(app==null) return null;
            application=app;
        }
        Bitmap bitmap=cache.get(new Request(pkg,component,user));
        if(bitmap==null) return null;
        BitmapDrawable drawable=new BitmapDrawable(context.getResources(),bitmap);
        if(density>0) drawable.setTargetDensity(density);
        return drawable.mutate();
    }
    private static SystemIconPackCache.Result<Bitmap> read(Request request) {
        Context context=application;
        try {
            if(!observing) {
                context.getContentResolver().registerContentObserver(SystemIconPackStore.URI,false,observer);
                observing=true;
            }
            Bundle args=new Bundle(); args.putString("component",request.component); args.putInt("user",request.user);
            Bundle result=context.getContentResolver().call(SystemIconPackStore.URI,"lookup",request.pkg,args);
            if(result==null) return new SystemIconPackCache.Result<>(null,1,1000);
            String file=result.getString("file");
            if(file==null) return new SystemIconPackCache.Result<>(null,1,result.containsKey("generation")?30000:1000);
            Bitmap bitmap=images.get(file);
            if(bitmap==null) {
                try(InputStream input=context.getContentResolver().openInputStream(SystemIconPackStore.URI.buildUpon().appendEncodedPath(file).build())) {
                    bitmap=BitmapFactory.decodeStream(input);
                }
                if(bitmap==null) return new SystemIconPackCache.Result<>(null,1,1000);
                bitmap.setDensity(result.getInt("density"));
                images.put(file,bitmap);
            }
            return new SystemIconPackCache.Result<>(bitmap,bitmap.getAllocationByteCount(),30000);
        } catch(Exception | LinkageError unavailable) { return new SystemIconPackCache.Result<>(null,1,1000); }
    }
    static Drawable applicationIcon(Context context,ApplicationInfo app) {
        configure(ModuleSettings.preferences(context));
        Drawable original=context.getPackageManager().getApplicationIcon(app);
        if(!settings.getBoolean(ModuleSettings.SYSTEM_ICON_PACK, false)) return original;
        return decorate(context,app.packageName,null,app.uid/100000,0,original,
                icon->context.getPackageManager().getUserBadgedIcon(icon,android.os.UserHandle.getUserHandleForUid(app.uid)));
    }
}
