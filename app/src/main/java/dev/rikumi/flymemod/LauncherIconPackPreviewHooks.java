package dev.rikumi.flymemod;

import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.UserHandle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.*;

/** Replace third-party pack thumbnails with the pack's actual Settings icon. */
final class LauncherIconPackPreviewHooks {
    private static final String OWNER="com.meizu.flyme.launcher.view.MzIconPacksContainer";
    private final Class<?> container,pack;
    private final Field rows,packageName;
    private final Method drawableIcon,bitmapIcon;
    private final Consumer<Context> settings;
    private final BooleanSupplier enabled;
    private final BiConsumer<String,Throwable> log;
    private final Map<ImageView,Entry> entries=new WeakHashMap<>();
    private ContentObserver observer;

    LauncherIconPackPreviewHooks(ClassLoader loader,Consumer<Context> settings,BooleanSupplier enabled,
                                BiConsumer<String,Throwable> log) throws ReflectiveOperationException {
        this.settings=settings;this.enabled=enabled;this.log=log;
        container=loader.loadClass(OWNER);pack=loader.loadClass("com.meizu.flyme.launcher.iconpack.AppIconPackItem");
        rows=container.getDeclaredField("mAllIconPackItemViewList");rows.setAccessible(true);
        packageName=pack.getField("packageName");
        drawableIcon=pack.getMethod("getDrawableIconForPackage",Context.class,String.class,Drawable.class);
        bitmapIcon=pack.getMethod("getIconForPackage",Context.class,String.class,String.class,int.class,UserHandle.class,
                loader.loadClass("com.meizu.flyme.launcher.iconpack.AppIconPackItem$IconDealInterface"));
    }

    void install(SignalHooks.Installer installer,Consumer<Method> deoptimize) throws ReflectiveOperationException {
        installer.hook(OWNER,"inflateIconPackItemView",chain -> {
            Object result=chain.proceed();Object item=chain.getArg(2);
            if("SYSTEM_ICONS".equals(packageName.get(item)))return result;
            View host=(View)chain.getThisObject();Context context=host.getContext();settings.accept(context);
            int id=context.getResources().getIdentifier("icon_pack_item_img","id","com.meizu.flyme.launcher");
            for(Object candidate:(List<?>)rows.get(host))if(candidate instanceof View row && row.getTag()==item) {
                if(row.findViewById(id) instanceof ImageView image) {
                    entries.put(image,new Entry(item,image.getDrawable()));apply(image);observe(context);
                }
                break;
            }
            return result;
        },Context.class,LayoutInflater.class,pack);
        for(Method method:container.getDeclaredMethods())if(method.getName().contains("inflate")
                ||method.getName().equals("initIconPackList"))deoptimize.accept(method);
    }

    private void observe(Context context) {
        if(observer!=null)return;
        Context app=context.getApplicationContext();
        observer=new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override public void onChange(boolean selfChange) {
                settings.accept(app);
                for(ImageView image:new ArrayList<>(entries.keySet()))if(image!=null)apply(image);
            }
        };
        app.getContentResolver().registerContentObserver(ModuleSettings.URI,false,observer);
    }

    private void apply(ImageView image) {
        Entry entry=entries.get(image);if(entry==null)return;
        if(!enabled.getAsBoolean()) { image.setImageDrawable(entry.original);return; }
        try {
            if(entry.preview==null) {
                Context context=image.getContext();
                entry.preview=(Drawable)drawableIcon.invoke(entry.pack,context,"com.android.settings",null);
                if(entry.preview==null) {
                    int size=image.getLayoutParams().width;
                    if(size<=0)size=Math.round(48*context.getResources().getDisplayMetrics().density);
                    Bitmap bitmap=(Bitmap)bitmapIcon.invoke(entry.pack,context,"com.android.settings",
                            "ComponentInfo{com.android.settings/com.android.settings.Settings}",size,Process.myUserHandle(),null);
                    if(bitmap!=null)entry.preview=new android.graphics.drawable.BitmapDrawable(context.getResources(),bitmap);
                }
            }
            if(entry.preview!=null)image.setImageDrawable(entry.preview);
        } catch(ReflectiveOperationException | RuntimeException error) {
            if(!entry.errorLogged) { entry.errorLogged=true;log.accept("Cannot create Settings icon pack preview",error); }
        }
    }

    private static final class Entry {
        final Object pack;final Drawable original;
        Drawable preview;boolean errorLogged;
        Entry(Object pack,Drawable original){this.pack=pack;this.original=original;}
    }
}
