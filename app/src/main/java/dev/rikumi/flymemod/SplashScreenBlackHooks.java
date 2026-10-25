package dev.rikumi.flymemod;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.Consumer;

final class SplashScreenBlackHooks {
    private static final String DRAWER="com.android.wm.shell.startingsurface.SplashscreenContentDrawer";
    private final SharedPreferences preferences;

    SplashScreenBlackHooks(SharedPreferences preferences){this.preferences=preferences;}

    private boolean enabled(Context context,ApplicationInfo app) {
        return preferences.getBoolean(ModuleSettings.DARK_SYSTEM_APP_BLACK_BACKGROUND, false)
                && (context.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES
                && app!=null && (app.flags&(ApplicationInfo.FLAG_SYSTEM|ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))!=0;
    }

    void install(ClassLoader loader,SignalHooks.Installer installer,Consumer<Method> deoptimize) throws ReflectiveOperationException {
        Class<?> builder=loader.loadClass(DRAWER+"$SplashViewBuilder");
        Field context=builder.getField("mContext"),activity=builder.getField("mActivityInfo"),
                color=builder.getField("mThemeColor"),overlay=builder.getField("mOverlayDrawable");
        installer.hook(builder.getName(),"build",chain -> {
            Object owner=chain.getThisObject();Context ctx=(Context)context.get(owner);
            ActivityInfo info=(ActivityInfo)activity.get(owner);
            if(!enabled(ctx,info==null?null:info.applicationInfo))return chain.proceed();
            int originalColor=color.getInt(owner);Object originalOverlay=overlay.get(owner);
            color.setInt(owner,Color.BLACK);
            if(originalOverlay!=null)overlay.set(owner,new ColorDrawable(Color.BLACK));
            try{return chain.proceed();}
            finally{color.setInt(owner,originalColor);overlay.set(owner,originalOverlay);}
        });
        installer.hook(DRAWER,"estimateTaskBackgroundColor",chain -> {
            Context ctx=(Context)chain.getArg(0);
            return enabled(ctx,ctx.getApplicationInfo())?Color.BLACK:chain.proceed();
        },Context.class);
        for(Method method:loader.loadClass(DRAWER).getDeclaredMethods())
            if(method.getName().equals("makeSplashScreenContentView") ||method.getName().equals("makeSimpleSplashScreenContentView"))deoptimize.accept(method);
    }
}
