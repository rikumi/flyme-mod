package dev.rikumi.flymemod;

import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/** A faint inner 1dp border follows Flyme's actual thumbnail outline. */
final class RecentsThumbnailBorderHooks {
    private final SharedPreferences preferences;
    private final Class<?> legacy, modern;
    private final Field fullscreen, modernPath;
    private final Method corner, modernCorner, outlinePath;
    private final Object outlines;
    private final Paint stroke=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Map<View,Boolean> views=Collections.synchronizedMap(new WeakHashMap<>());
    private final SharedPreferences.OnSharedPreferenceChangeListener changes=(prefs,key) -> {
        if(key!=null && !ModuleSettings.DARK_SYSTEM_APP_BLACK_BACKGROUND.equals(key))return;
        ArrayList<View> current;
        synchronized(views) { current=new ArrayList<>(views.keySet()); }
        for(View view:current)if(view!=null)view.postInvalidate();
    };

    RecentsThumbnailBorderHooks(ClassLoader loader,SharedPreferences preferences) throws ReflectiveOperationException {
        this.preferences=preferences;
        legacy=loader.loadClass("com.android.quickstep.views.TaskThumbnailViewDeprecated");
        modern=loader.loadClass("com.android.quickstep.task.thumbnail.TaskThumbnailView");
        legacy.getDeclaredMethod("onDraw",Canvas.class);
        fullscreen=legacy.getDeclaredField("mFullscreenParams"); fullscreen.setAccessible(true);
        corner=fullscreen.getType().getMethod("getCurrentCornerRadius");
        modernPath=modern.getDeclaredField("outlinePath"); modernPath.setAccessible(true);
        modernCorner=modern.getMethod("getCornerRadius");
        Class<?> paths=loader.loadClass("com.meizu.flyme.launcher.quickstep.util.TaskViewOutlines");
        outlines=paths.getField("INSTANCE").get(null);
        outlinePath=paths.getMethod("getPath",int.class,int.class,int.class,int.class,float.class,float.class);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setColor(Color.argb(20,255,255,255));
        preferences.registerOnSharedPreferenceChangeListener(changes);
    }

    void install(SignalHooks.Installer installer,Consumer<Method> deoptimize) throws ReflectiveOperationException {
        installer.hook(legacy.getName(),"onDraw",chain -> {
            Object result=chain.proceed();
            draw((View)chain.getThisObject(),(Canvas)chain.getArg(0),false);
            return result;
        },Canvas.class);
        // Refactored thumbnails inherit FrameLayout's dispatchDraw rather than onDraw.
        installer.hook("android.view.ViewGroup","dispatchDraw",chain -> {
            Object result=chain.proceed();
            if(modern.isInstance(chain.getThisObject()))
                draw((View)chain.getThisObject(),(Canvas)chain.getArg(0),true);
            return result;
        },Canvas.class);
        deoptimize.accept(View.class.getDeclaredMethod("draw",Canvas.class));
        deoptimize.accept(View.class.getDeclaredMethod("updateDisplayListIfDirty"));
    }

    private void draw(View view,Canvas canvas,boolean refactored) throws ReflectiveOperationException {
        views.put(view,true);
        if(!preferences.getBoolean(ModuleSettings.DARK_SYSTEM_APP_BLACK_BACKGROUND, OneClickPreset.defaultSwitch(ModuleSettings.DARK_SYSTEM_APP_BLACK_BACKGROUND))
                || view.getWidth()<=0 || view.getHeight()<=0)return;
        Path path;
        if(refactored) {
            if((Float)modernCorner.invoke(view)<=0f)return;
            path=(Path)modernPath.get(view);
        }
        else {
            Object params=fullscreen.get(view);
            if(params==null)return;
            float radius=(Float)corner.invoke(params);
            if(radius<=0f)return;
            path=(Path)outlinePath.invoke(outlines,0,0,view.getWidth(),view.getHeight(),.2f,radius);
        }
        if(path==null || path.isEmpty())return;
        int save=canvas.save();
        try {
            // Half the stroke falls outside the outline. Clip that half so exactly
            // 1dp remains inside, preserving the native smoothed-corner path.
            stroke.setStrokeWidth(2f*view.getResources().getDisplayMetrics().density);
            canvas.clipPath(path);
            canvas.drawPath(path,stroke);
        } finally { canvas.restoreToCount(save); }
    }
}
