package dev.rikumi.flymemod;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Predicate;

/** White foreground only on notifications displayed over the dark expanded shade. */
final class NotificationForegroundHooks {
    private final Class<?> row, stack, landscape, media;
    private final Field expanded, status;
    private final Predicate<Context> darken;
    private final Predicate<View> controlCenter;
    private final Map<TextView,ColorStateList> textColors=new WeakHashMap<>();
    private final Map<ImageView,ColorStateList> iconColors=new WeakHashMap<>();
    private final Map<Drawable,ColorFilter> drawableColors=new WeakHashMap<>();
    private final Map<TextView,Boolean> compoundStyled=new WeakHashMap<>();
    private static final ColorFilter WHITE_FILTER=new PorterDuffColorFilter(Color.WHITE,PorterDuff.Mode.SRC_IN);
    private static final ColorFilter BLACK_FILTER=new PorterDuffColorFilter(Color.BLACK,PorterDuff.Mode.SRC_IN);
    private static final ColorStateList BLACK=ColorStateList.valueOf(Color.BLACK);
    private static final ColorStateList WHITE=ColorStateList.valueOf(Color.WHITE);

    NotificationForegroundHooks(ClassLoader loader, Predicate<Context> darken, Predicate<View> controlCenter) throws ReflectiveOperationException {
        this.darken=darken; this.controlCenter=controlCenter;
        row=loader.loadClass("com.android.systemui.statusbar.notification.row.ExpandableNotificationRow");
        landscape=loader.loadClass("com.flyme.notification.view.LandscapeHeadsUpNotificationView");
        media=loader.loadClass("com.flyme.systemui.media.controls.ui.view.MediaCarouseTransitionLayout");
        stack=loader.loadClass("com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout");
        expanded=stack.getField("mIsExpanded"); status=stack.getField("mStatusBarState");
    }
    private ColorStateList foreground(View view) throws IllegalAccessException {
        if(controlCenter.test(view))return null;
        boolean night=(view.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        boolean notification=false;
        for(View current=view;current!=null;current=current.getParent() instanceof View parent?parent:null) {
            if(row.isInstance(current) || landscape.isInstance(current) || media.isInstance(current)) {
                notification=true;
                if(!night)return BLACK;
            }
            if(stack.isInstance(current))return notification && expanded.getBoolean(current)
                    && status.getInt(current)==0 && darken.test(view.getContext()) ? WHITE : null;
        }
        return null;
    }
    void install(SignalHooks.Installer installer) {
        installer.hook("android.widget.TextView","onDraw",chain -> {
            TextView text=(TextView)chain.getThisObject();
            ColorStateList target=foreground(text);
            if(target!=null) {
                if(text.getTextColors()!=target) {
                    if(text.getTextColors()!=WHITE && text.getTextColors()!=BLACK)textColors.put(text,text.getTextColors());
                    text.setTextColor(target);
                }
            } else {
                ColorStateList original=textColors.remove(text);
                if(original!=null && (text.getTextColors()==WHITE || text.getTextColors()==BLACK))text.setTextColor(original);
            }
            // TextView allocates a four-element array for this accessor. Most
            // SystemUI text is outside notifications and never needs this work.
            if(target!=null) {
                for(Drawable drawable:text.getCompoundDrawablesRelative())tintDrawable(drawable,target);
                compoundStyled.put(text,Boolean.TRUE);
            } else if(compoundStyled.remove(text)!=null) {
                for(Drawable drawable:text.getCompoundDrawablesRelative())tintDrawable(drawable,null);
            }
            return chain.proceed();
        },Canvas.class);
        installer.hook("android.widget.ImageView","onDraw",chain -> {
            ImageView icon=(ImageView)chain.getThisObject();
            // Tint functional glyphs already styled by the template; leave content images intact.
            ColorStateList target=foreground(icon);
            if(target!=null && icon.getImageTintList()!=null) {
                if(icon.getImageTintList()!=target) {
                    if(icon.getImageTintList()!=WHITE && icon.getImageTintList()!=BLACK)iconColors.put(icon,icon.getImageTintList());
                    icon.setImageTintList(target);
                }
            } else {
                ColorStateList original=iconColors.remove(icon);
                if(original!=null && (icon.getImageTintList()==WHITE || icon.getImageTintList()==BLACK))icon.setImageTintList(original);
            }
            Drawable drawable=icon.getDrawable();
            if(drawable!=null && (drawable.getColorFilter()!=null || drawableColors.containsKey(drawable)))
                tintDrawable(drawable,target);
            return chain.proceed();
        },Canvas.class);
    }
    private void tintDrawable(Drawable drawable,ColorStateList color) {
        if(drawable==null)return;
        ColorFilter target=color==null?null:color==BLACK?BLACK_FILTER:WHITE_FILTER;
        if(target!=null && drawable.getColorFilter()!=target) {
            if(drawable.getColorFilter()!=WHITE_FILTER && drawable.getColorFilter()!=BLACK_FILTER)
                drawableColors.put(drawable,drawable.getColorFilter());
            drawable.setColorFilter(target);
        } else if(target==null && drawableColors.containsKey(drawable)) {
            ColorFilter original=drawableColors.remove(drawable);
            if((drawable.getColorFilter()==WHITE_FILTER || drawable.getColorFilter()==BLACK_FILTER))drawable.setColorFilter(original);
        }
    }
}
