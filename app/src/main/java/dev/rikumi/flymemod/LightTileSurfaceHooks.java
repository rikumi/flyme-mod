package dev.rikumi.flymemod;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import java.util.function.BooleanSupplier;
import java.util.Map;
import java.util.WeakHashMap;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import java.util.function.Predicate;

/** Tile foreground colors on the light darkened shade. */
final class LightTileSurfaceHooks {
    private final Class<?> tile, custom;
    private final Predicate<Context> enabled;
    private final BooleanSupplier split;
    private final Map<TextView,ColorStateList> textColors=new WeakHashMap<>();
    private final Map<ImageView,ColorStateList> iconColors=new WeakHashMap<>();
    private final Map<Resources,int[]> networkIds=new WeakHashMap<>();
    private static final ColorStateList BLACK=ColorStateList.valueOf(Color.BLACK);
    LightTileSurfaceHooks(ClassLoader loader,Predicate<Context> enabled,BooleanSupplier split) throws ReflectiveOperationException {
        this.enabled=enabled;this.split=split;
        tile=loader.loadClass("com.android.systemui.qs.tileimpl.QSTileViewImpl");
        custom=loader.loadClass("com.flyme.systemui.qs.tileimpl.FlymeCustomQSTileView");

    }
    void install(SignalHooks.Installer installer) {
        installer.hook("android.view.ViewGroup","dispatchDraw",chain -> {
            View view=(View)chain.getThisObject();
            boolean network=view.getClass().getName().equals("com.flyme.systemui.qs.tileimpl.ConnectivityQSTileViewImpl");
            boolean splitRow=false;
            if(split.getAsBoolean() && view.getParent() instanceof View parent
                    && parent.getClass().getName().equals("com.flyme.systemui.qs.tileimpl.ConnectivityQSTileViewImpl")) {
                int[] ids=networkIds.get(view.getResources());
                if(ids==null) {
                    ids=new int[]{view.getResources().getIdentifier("wifi_container","id","com.android.systemui"),
                            view.getResources().getIdentifier("bluetooth_container","id","com.android.systemui")};
                    networkIds.put(view.getResources(),ids);
                }
                splitRow=(ids[0]!=0 && view.getId()==ids[0]) || (ids[1]!=0 && view.getId()==ids[1]);
            }
            if(splitRow || ((tile.isInstance(view) || custom.isInstance(view)) && !(network && split.getAsBoolean()))) {
                boolean active=enabled.test(view.getContext());
                // A never-styled (or fully restored) shade needs no recursive walk.
                if(active || !textColors.isEmpty() || !iconColors.isEmpty())foreground(view,active);
            }
            return chain.proceed();
        },Canvas.class);
    }
    private void foreground(View view,boolean active) {
        if(view instanceof TextView text) {
            if(active && text.getTextColors()!=BLACK) {
                textColors.put(text,text.getTextColors());text.setTextColor(BLACK);
            } else if(!active) {
                ColorStateList original=textColors.remove(text);
                if(original!=null && text.getTextColors()==BLACK)text.setTextColor(original);
            }
        }
        if(view instanceof ImageView icon) {
            if(active && icon.getImageTintList()!=null && icon.getImageTintList()!=BLACK) {
                iconColors.put(icon,icon.getImageTintList());icon.setImageTintList(BLACK);
            } else if(!active) {
                ColorStateList original=iconColors.remove(icon);
                if(original!=null && icon.getImageTintList()==BLACK)icon.setImageTintList(original);
            }
        }
        if(view instanceof ViewGroup group)for(int i=0;i<group.getChildCount();i++)foreground(group.getChildAt(i),active);
    }
}
