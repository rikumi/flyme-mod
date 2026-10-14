package dev.rikumi.flymemod;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.*;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.*;

/** Application icon APIs only, installed for every process LSPosed actually injects. */
final class SystemIconPackHooks {
    private final SharedPreferences settings;
    private final BiConsumer<String,Throwable> log;
    SystemIconPackHooks(SharedPreferences settings,BiConsumer<String,Throwable> log) {
        this.settings=settings; this.log=log;
        SystemIconPackClient.configure(settings);
    }
    private boolean active() { return settings.getBoolean(ModuleSettings.SYSTEM_ICON_PACK, false) && !SystemIconPackClient.isNative(); }
    void install(ClassLoader loader,SignalHooks.Installer installer,Consumer<Method> deoptimize)throws ReflectiveOperationException {
        Class<?> pm=loader.loadClass("android.app.ApplicationPackageManager");
        Field context=pm.getDeclaredField("mContext"); context.setAccessible(true);
        Field userIcon=PackageItemInfo.class.getDeclaredField("showUserIcon"); userIcon.setAccessible(true);
        Field archived=PackageItemInfo.class.getDeclaredField("isArchived"); archived.setAccessible(true);
        installer.hook(pm.getName(),"loadUnbadgedItemIcon",chain -> {
            if(!active()) return chain.proceed();
            PackageItemInfo item=(PackageItemInfo)chain.getArg(0);
            if(!(item instanceof ApplicationInfo || item instanceof ActivityInfo)
                    || userIcon.getInt(item)!=-10000 || archived.getBoolean(item)) return chain.proceed();
            ApplicationInfo app=(ApplicationInfo)chain.getArg(1);
            if(app==null) return chain.proceed();
            Context ctx=(Context)context.get(chain.getThisObject());
            Drawable original;
            try(SystemIconPackClient.NativeScope ignored=SystemIconPackClient.nativeScope()) { original=(Drawable)chain.proceed(); }
            // loadItemIcon still applies the platform's user badge to this unbadged source.
            return SystemIconPackClient.decorateApi(ctx,app.packageName,
                    item instanceof ActivityInfo?app.packageName+"/"+item.name:null,app.uid/100000,0,original);
        },PackageItemInfo.class,ApplicationInfo.class);
        installer.hook("android.content.pm.ResolveInfo","loadIcon",chain -> {
            if(!active()) return chain.proceed();
            ResolveInfo info=(ResolveInfo)chain.getThisObject(); ActivityInfo activity=info.activityInfo;
            if(activity==null || archived.getBoolean(activity) || userIcon.getInt(activity)!=-10000) return chain.proceed();
            PackageManager manager=(PackageManager)chain.getArg(0);
            Context ctx=(Context)context.get(manager);
            Drawable original;
            try(SystemIconPackClient.NativeScope ignored=SystemIconPackClient.nativeScope()) { original=(Drawable)chain.proceed(); }
            Drawable icon=SystemIconPackClient.decorateApi(ctx,activity.packageName,
                    activity.packageName+"/"+activity.name,activity.applicationInfo.uid/100000,0,original,
                    packed->manager.getUserBadgedIcon(packed,UserHandle.getUserHandleForUid(activity.applicationInfo.uid)));
            return icon;
        },PackageManager.class);
        Field launcherPm=LauncherActivityInfo.class.getDeclaredField("mPm"); launcherPm.setAccessible(true);
        installer.hook("android.content.pm.LauncherActivityInfo","getIcon",chain -> {
            if(!active()) return chain.proceed();
            LauncherActivityInfo activity=(LauncherActivityInfo)chain.getThisObject();
            if(archived.getBoolean(activity.getActivityInfo())) return chain.proceed();
            PackageManager manager=(PackageManager)launcherPm.get(activity);
            Drawable original;
            try(SystemIconPackClient.NativeScope ignored=SystemIconPackClient.nativeScope()) { original=(Drawable)chain.proceed(); }
            // getBadgedIcon invokes this method then performs native user badging itself.
            return SystemIconPackClient.decorateApi((Context)context.get(manager),activity.getComponentName().getPackageName(),
                    activity.getComponentName().flattenToString(),activity.getApplicationInfo().uid/100000,(Integer)chain.getArg(0),original);
        },int.class);
        for(Class<?> type:new Class<?>[]{pm,PackageItemInfo.class,ApplicationInfo.class,ActivityInfo.class,
                ResolveInfo.class,LauncherActivityInfo.class})
            for(Method method:type.getDeclaredMethods()) if(method.getName().equals("loadItemIcon")
                    || method.getName().equals("loadUnbadgedItemIcon") || method.getName().equals("loadIcon")
                    || method.getName().equals("loadUnbadgedIcon") || method.getName().equals("getApplicationIcon")
                    || method.getName().equals("getActivityIcon") || method.getName().equals("getIcon")
                    || method.getName().equals("getBadgedIcon")) deoptimize.accept(method);
    }
    void installSystemUi(ClassLoader loader,SignalHooks.Installer installer)throws ReflectiveOperationException {
        Class<?> controller=loader.loadClass("com.flyme.systemui.qs.media.QsMediaController");
        Method refresh=controller.getDeclaredMethod("refreshUI",String.class);
        Set<Object> attached=Collections.newSetFromMap(new WeakHashMap<>());
        installer.hook(controller.getName(),"onViewAttached",chain -> {
            Object result=chain.proceed(); attached.add(chain.getThisObject()); return result;
        });
        installer.hook(controller.getName(),"onViewDetached",chain -> {
            attached.remove(chain.getThisObject()); return chain.proceed();
        });
        SystemIconPackClient.addListener(()-> {
            for(Object item:new ArrayList<>(attached)) {
                try { refresh.invoke(item,"system icon pack changed"); }
                catch(ReflectiveOperationException | RuntimeException error) { log.accept("Cannot refresh media pack icon",error); }
            }
        });
    }
    void installSettings(ClassLoader loader,SignalHooks.Installer installer,Consumer<Method> deoptimize)throws ReflectiveOperationException {
        Class<?> entry=loader.loadClass("com.android.settingslib.applications.ApplicationsState$AppEntry");
        Field info=entry.getField("info");
        Method badge=loader.loadClass("com.android.settingslib.Utils").getMethod("getBadgedIcon",Context.class,Drawable.class,UserHandle.class);
        Method application=loader.loadClass("android.app.ActivityThread").getMethod("currentApplication");
        installer.hook("com.android.settingslib.applications.AppUtils","getIcon",chain -> {
            if(!active()) return chain.proceed();
            // Native UI cache always stores native sources, including when the option is on.
            Object result;
            try(SystemIconPackClient.NativeScope ignored=SystemIconPackClient.nativeScope()) { result=chain.proceed(); }
            if(!active() || chain.getArg(1)==null) return result;
            ApplicationInfo app=(ApplicationInfo)info.get(chain.getArg(1));
            if(app==null) return result;
            Context ctx=(Context)chain.getArg(0);
            return SystemIconPackClient.decorate(ctx,app.packageName,null,app.uid/100000,0,(Drawable)result,
                    packed->badged(badge,ctx,packed,app));
        },Context.class,entry);
        installer.hook("com.android.settingslib.applications.AppUtils","getIconFromCache",chain -> {
            Object result=chain.proceed();
            if(!active() || chain.getArg(0)==null) return result;
            ApplicationInfo app=(ApplicationInfo)info.get(chain.getArg(0));
            Context ctx=(Context)application.invoke(null);
            if(app==null || ctx==null) return result;
            return SystemIconPackClient.decorate(ctx,app.packageName,null,app.uid/100000,0,(Drawable)result,
                    packed->badged(badge,ctx,packed,app));
        },entry);
        for(Method method:loader.loadClass("com.android.settingslib.applications.AppUtils").getDeclaredMethods())
            if(method.getName().equals("getIcon") || method.getName().equals("getIconFromCache")) deoptimize.accept(method);
    }
    private Drawable badged(Method method,Context context,Drawable icon,ApplicationInfo app) {
        try { return (Drawable)method.invoke(null,context,icon,UserHandle.getUserHandleForUid(app.uid)); }
        catch(ReflectiveOperationException error) { log.accept("Cannot badge settings pack icon",error); return icon; }
    }
}
