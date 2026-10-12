package dev.rikumi.flymemod;

import android.content.*;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.graphics.drawable.Drawable;
import android.os.*;
import android.view.View;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.*;

/** Launcher publishes the actual selection and substitutes only the returned recent-task icon. */
final class SystemIconPackLauncherHooks {
    private final SharedPreferences settings;
    private final BiConsumer<String,Throwable> log;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Set<View> cards=Collections.newSetFromMap(new WeakHashMap<>());
    private String published;
    private String requested;
    private boolean publishing;
    private final java.util.concurrent.ExecutorService publisher=java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
        Thread thread=new Thread(task,"FlymeIconSelection"); thread.setDaemon(true); return thread;
    });
    private ContentObserver observer;
    private SharedPreferences.OnSharedPreferenceChangeListener changes;
    SystemIconPackLauncherHooks(SharedPreferences settings,BiConsumer<String,Throwable> log) { this.settings=settings; this.log=log; }
    void install(ClassLoader loader,SignalHooks.Installer installer,Consumer<Method> deoptimize)throws ReflectiveOperationException {
        Class<?> packs=loader.loadClass("com.meizu.flyme.launcher.iconpack.AppIconPackLoader");
        Method current=packs.getDeclaredMethod("getCurrentIconPack",Context.class);
        packs.getDeclaredMethod("setCurrentIconPack",Context.class,String.class);
        installer.hook(packs.getName(),"getCurrentIconPack",chain -> {
            Object result=chain.proceed(); publish((Context)chain.getArg(0),(String)result); return result;
        },Context.class);
        installer.hook(packs.getName(),"setCurrentIconPack",chain -> {
            Object result=chain.proceed(); publish((Context)chain.getArg(0),(String)chain.getArg(1)); return result;
        },Context.class,String.class);
        installer.hook("com.meizu.flyme.launcher.LauncherApplication","onCreate",chain -> {
            Object result=chain.proceed(); Context context=(Context)chain.getThisObject();
            publish(context,(String)current.invoke(null,context));
            // Re-read on option changes too, rather than relying on a historical module-side selection.
            context.getContentResolver().registerContentObserver(ModuleSettings.URI,false,new ContentObserver(main) {
                @Override public void onChange(boolean selfChange) {
                    try { publish(context,(String)current.invoke(null,context)); }
                    catch(ReflectiveOperationException error) { log.accept("Cannot read selected Launcher icon pack",error); }
                }
            });
            return result;
        });
        Method reload=loader.loadClass("com.android.quickstep.views.TaskView").getMethod("onTaskListVisibilityChanged",boolean.class,int.class);
        installer.hook("com.android.quickstep.views.TaskView","onAttachedToWindow",chain -> {
            Object result=chain.proceed(); View card=(View)chain.getThisObject(); cards.add(card);
            if(observer==null) {
                Context context=card.getContext().getApplicationContext();
                observer=new ContentObserver(main) {
                    @Override public void onChange(boolean selfChange) {
                        // FLAG_UPDATE_ICON=1; do not request thumbnail pixels or geometry refresh.
                        for(View view:new ArrayList<>(cards)) if(view!=null && view.isAttachedToWindow()) {
                            try { reload.invoke(view,true,1); }
                            catch(ReflectiveOperationException error) { log.accept("Cannot refresh recent-task pack icon",error); }
                        }
                    }
                };
                // The client observes the provider off the UI thread and also reports completed loads.
                SystemIconPackClient.addListener(this::refreshCards);
                changes=(preferences,key)-> {
                    if(ModuleSettings.SYSTEM_ICON_PACK.equals(key)) main.post(this::refreshCards);
                };
                settings.registerOnSharedPreferenceChangeListener(changes);
            }
            return result;
        });
        Class<?> cache=loader.loadClass("com.android.quickstep.TaskIconCache");
        Field context=field(cache,"context");
        Class<?> task=loader.loadClass("com.android.systemui.shared.recents.model.Task");
        Field key=task.getField("key"), description=task.getField("taskDescription");
        Class<?> keyType=key.getType(); Field user=keyType.getField("userId");
        Method pkg=keyType.getMethod("getPackageName"), component=keyType.getMethod("getComponent");
        Method primary=android.app.ActivityManager.TaskDescription.class.getMethod("getPrimaryColor");
        Method bitmap=cache.getDeclaredMethod("getBitmapInfo",Drawable.class,int.class,int.class,boolean.class); bitmap.setAccessible(true);
        Method newIcon=loader.loadClass("com.android.launcher3.icons.BitmapInfo").getMethod("newIcon",Context.class);
        Class<?> entry=loader.loadClass("com.android.quickstep.TaskIconCache$TaskCacheEntry");
        Constructor<?> replacement=entry.getConstructor(Drawable.class,String.class,String.class);
        Method label=entry.getMethod("getContentDescription"), title=entry.getMethod("getTitle");
        installer.hook(cache.getName(),"getCacheEntry",chain -> {
            if(!settings.getBoolean(ModuleSettings.SYSTEM_ICON_PACK,false) || SystemIconPackClient.isNative()) return chain.proceed();
            Object original;
            try(SystemIconPackClient.NativeScope ignored=SystemIconPackClient.nativeScope()) { original=chain.proceed(); }
            if(!settings.getBoolean(ModuleSettings.SYSTEM_ICON_PACK,false) || original==null) return original;
            Object taskInfo=chain.getArg(0), taskKey=key.get(taskInfo);
            Context ctx=(Context)context.get(chain.getThisObject());
            ComponentName entrance=(ComponentName)component.invoke(taskKey);
            Drawable icon=SystemIconPackClient.lookup(ctx,(String)pkg.invoke(taskKey),
                    entrance==null?null:entrance.flattenToString(),user.getInt(taskKey),0);
            if(icon==null) return original;
            try {
                Object desc=description.get(taskInfo); int color=desc==null?0:(Integer)primary.invoke(desc);
                Object pixels=bitmap.invoke(chain.getThisObject(),icon,user.getInt(taskKey),color,false);
                Drawable badged=(Drawable)newIcon.invoke(pixels,ctx);
                // Native cache, labels, badges, task identity and screenshots stay separate.
                return replacement.newInstance(badged,label.invoke(original),title.invoke(original));
            } catch(ReflectiveOperationException | RuntimeException error) {
                log.accept("Cannot create recent-task icon pack result",error); return original;
            }
        },task);
        for(Class<?> type:new Class<?>[]{packs,cache,loader.loadClass("com.meizu.flyme.launcher.view.MzLauncherIconPanelLayout")})
            for(Method method:type.getDeclaredMethods()) if(type!=cache || method.getName().equals("getCacheEntry")
                    || method.getName().equals("getIcon") || method.getName().equals("getIconInBackground")) deoptimize.accept(method);
    }
    private static Field field(Class<?> type,String name)throws ReflectiveOperationException {
        Field result=type.getDeclaredField(name); result.setAccessible(true); return result;
    }
    private void publish(Context context,String pack) {
        if(pack==null) return;
        synchronized(this) {
            if(pack.equals(requested) && publishing || pack.equals(published) && !publishing) return;
            requested=pack;
            SystemIconPackClient.invalidate();
            if(publishing) return;
            publishing=true;
        }
        Context app=context.getApplicationContext();
        publisher.execute(()-> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            while(true) {
                String target;
                synchronized(this) { target=requested; }
                boolean success=false;
                try {
                    // Provider restart and disk-backed selection publication may take seconds.
                    app.getContentResolver().call(SystemIconPackStore.URI,"selection",target,null);
                    success=true;
                } catch(RuntimeException error) { log.accept("Cannot publish selected Launcher icon pack",error); }
                synchronized(this) {
                    if(success) published=target;
                    if(target.equals(requested)) { publishing=false; return; }
                }
            }
        });
    }
    private void refreshCards() { if(observer!=null) observer.onChange(false); }
}
