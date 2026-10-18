package dev.rikumi.flymemod;

import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.view.View;
import android.view.ViewParent;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.BiConsumer;
import android.app.Notification;
import java.util.function.IntSupplier;
import java.util.function.BooleanSupplier;
import android.view.WindowInsets;
import android.graphics.Rect;

/** Ticker presentation policy, leaving native alert/privacy decisions intact. */
final class TickerLyricHooks {
    private final Consumer<Context> settings;
    private final BiConsumer<Notification, Notification> copyOriginalIcon;
    private final IntSupplier displayMode;
    private final BooleanSupplier avoidLyrics;
    private final BooleanSupplier leftOnly;
    private final Map<Object,Object[]> cutoutArgs=new WeakHashMap<>();
    private Method cutoutMethod;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<Object, View> tickers = new WeakHashMap<>();
    // Keep hidden text suppressed through the complete native exit animation, even
    // after tickerDone sets the root GONE. Re-evaluate before the next entrance.
    private final Map<View, Boolean> exitingHidden = new WeakHashMap<>();
    private final Map<View, Float> textAlpha = new WeakHashMap<>();
    private final ThreadLocal<Object> promoted = new ThreadLocal<>();
    private Context context;
    private boolean lastLeftOnly;
    private List<StatusBarOverlayFrames.Frame> overlays = List.of();
    TickerLyricHooks(Consumer<Context> settings, BooleanSupplier avoidLyrics, IntSupplier displayMode, BooleanSupplier leftOnly,
            BiConsumer<Notification, Notification> copyOriginalIcon) {
        this.copyOriginalIcon=copyOriginalIcon;
        this.settings=settings; this.avoidLyrics=avoidLyrics; this.displayMode=displayMode; this.leftOnly=leftOnly;
    }
    void install(ClassLoader loader, SignalHooks.Installer installer, Consumer<Method> deoptimize)
            throws ReflectiveOperationException {
        String ticker="com.flyme.systemui.statusbar.ticker.FlymeMarqueeTicker";
        Class<?> type=loader.loadClass(ticker);
        var text=loader.loadClass("com.flyme.systemui.statusbar.ticker.MarqueeTicker").getField("mTextSwitcher");
        installer.hook(ticker,"setTickerView",chain->{
            Object result=chain.proceed();
            View view=(View)chain.getArg(0);
            tickers.put(chain.getThisObject(),view);
            view.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(l!=ol||t!=ot||r!=or||b!=ob)refresh();});
            if(context==null){
                context=view.getContext(); settings.accept(context);
                context.getContentResolver().registerContentObserver(Settings.Global.getUriFor(StatusBarOverlayFrames.KEY),false,
                    new ContentObserver(handler){@Override public void onChange(boolean self){refresh();}});
            }
            refresh(); return result;
        },View.class);
        for(String name:new String[]{"tickerStarting","tickerDone","tickerHalting","destroy"}){
            type.getDeclaredMethod(name);
            installer.hook(ticker,name,chain->{
                View textView=(View)text.get(chain.getThisObject());
                if(textView!=null){
                    textAlpha.putIfAbsent(textView,textView.getAlpha());
                    if(name.equals("tickerStarting")) exitingHidden.remove(textView);
                    else if(textView.getAlpha()==0f) exitingHidden.put(textView,true);
                }
                refresh();
                Object result=chain.proceed();
                refresh(); return result;
            });
            deoptimize.accept(type.getDeclaredMethod(name));
        }
        Class<?> entry=loader.loadClass("com.android.systemui.statusbar.notification.collection.NotificationEntry");
        Method sbn=entry.getMethod("getSbn");
        String provider="com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProviderImpl";
        var unclearableList=loader.loadClass(provider).getDeclaredField("mDisableHeadsUpUnclearableList");
        unclearableList.setAccessible(true);
        installer.hook(provider,"shouldHeadsUpWhenAwake",chain->{
            synchronized(chain.getThisObject()){
                Object list=unclearableList.get(chain.getThisObject());
                if(!(list instanceof PopupExclusions)){
                    unclearableList.set(chain.getThisObject(),new PopupExclusions((List<?>)list));
                }
            }
            Object previous=promoted.get();
            Object notification=chain.getArg(0);
            StatusBarNotification value=(StatusBarNotification)sbn.invoke(notification);
            boolean hasTicker=value.getNotification().tickerText!=null&&value.getNotification().tickerText.length()>0;
            boolean popup=displayMode.getAsInt()==2;
            if(displayMode.getAsInt()==1)return false;
            if(hasTicker&&popup)promoted.set(notification);
            try{return chain.proceed();}finally{if(previous==null)promoted.remove();else promoted.set(previous);}
        },entry,boolean.class);
        // Flyme chooses Ticker for packages whose native popup preference is off.
        // Override only this notification's presentation query; never persist a permission change.
        Method originalPackage=StatusBarNotification.class.getDeclaredMethod("getOrigPackageName");
        Method originalUid=StatusBarNotification.class.getDeclaredMethod("getOrigPkgUid");
        Class<?> notificationManager=loader.loadClass("android.app.INotificationManager$Stub$Proxy");
        Method allowPopup=notificationManager.getDeclaredMethod("getNotificationHeadsUpAllowForPackage",String.class,int.class);
        installer.hook(notificationManager.getName(),allowPopup.getName(),chain->{
            Object current=promoted.get();
            if(current!=null){
                Object currentSbn=sbn.invoke(current);
                if(java.util.Objects.equals(originalPackage.invoke(currentSbn),chain.getArg(0))
                        && java.util.Objects.equals(originalUid.invoke(currentSbn),chain.getArg(1)))return true;
            }
            return chain.proceed();
        },String.class,int.class);
        deoptimize.accept(allowPopup);
        installer.hook(entry.getName(),"getImportance",chain->{
            int nativeValue=(Integer)chain.proceed();
            return promoted.get()==chain.getThisObject()?Math.max(nativeValue,4):nativeValue;
        });
        installer.hook("com.flyme.systemui.statusbar.ticker.MarqueeTicker","addEntry",chain->{
            if(displayMode.getAsInt()==2)return null;
            return chain.proceed();
        },StatusBarNotification.class);
        cutoutMethod=loader.loadClass("com.flyme.systemui.statusbar.ticker.MarqueeTicker").getDeclaredMethod(
                "setDisplayCutout",boolean.class,int.class,int.class,int.class,boolean.class,boolean.class,int.class);
        installer.hook("com.flyme.systemui.statusbar.ticker.MarqueeTicker","setDisplayCutout",chain->{
            Object[] args=chain.getArgs().toArray();cutoutArgs.put(chain.getThisObject(),args.clone());
            if(leftOnly.getAsBoolean()){args[0]=false;args[5]=false;}
            return chain.proceed(args);
        },boolean.class,int.class,int.class,int.class,boolean.class,boolean.class,int.class);
        installer.hook("android.widget.LinearLayout","onMeasure",chain->{
            View view=(View)chain.getThisObject();
            if(!leftOnly.getAsBoolean()||!tickers.containsValue(view))return chain.proceed();
            WindowInsets insets=view.getRootWindowInsets();
            if(insets==null||insets.getDisplayCutout()==null)return chain.proceed();
            Rect hole=insets.getDisplayCutout().getBoundingRectTop();
            if(hole.isEmpty())return chain.proceed();
            int[] location=new int[2];view.getLocationInWindow(location);
            int spec=(Integer)chain.getArg(0);
            int width=Math.max(0,Math.min(View.MeasureSpec.getSize(spec),hole.left-location[0]));
            return chain.proceed(new Object[]{View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),chain.getArg(1)});
        },int.class,int.class);
        deoptimize.accept(cutoutMethod);
        Class<?> tickController=loader.loadClass("com.flyme.systemui.statusbar.ticker.NotificationTickController");
        // Intercept before both FlymeTickerEx's update filter and tick()'s ROM policy gates.
        Class<?> listener=loader.loadClass("com.flyme.systemui.statusbar.ticker.NotificationTickController$notifCollectionListener$1");
        var owner=listener.getDeclaredField("this$0");owner.setAccessible(true);
        var nativeTicker=tickController.getDeclaredField("ticker");nativeTicker.setAccessible(true);
        Method addEntry=loader.loadClass("com.flyme.systemui.statusbar.ticker.MarqueeTicker")
                .getDeclaredMethod("addEntry",StatusBarNotification.class);
        for(String event:new String[]{"onEntryAdded","onEntryUpdated"}){
            Method eventMethod=listener.getDeclaredMethod(event,entry);
            installer.hook(listener.getName(),event,chain->{
                if(displayMode.getAsInt()!=1)return chain.proceed();
                Object target=nativeTicker.get(owner.get(chain.getThisObject()));
                if(target!=null){
                    StatusBarNotification original=(StatusBarNotification)sbn.invoke(chain.getArg(0));
                    StatusBarNotification copy=original.clone();
                    copyOriginalIcon.accept(original.getNotification(),copy.getNotification());
                    copy.getNotification().tickerText=tickerText(copy);
                    addEntry.invoke(target,copy);
                }
                return null;
            },entry);
            deoptimize.accept(eventMethod);
        }
        installer.hook(provider,"checkHeadsUp",chain->{
            if(displayMode.getAsInt()==1)return false;
            return chain.proceed();
        },entry,boolean.class);
        deoptimize.accept(tickController.getDeclaredMethod("inflateTickerView",
                loader.loadClass("com.android.systemui.statusbar.phone.PhoneStatusBarView")));
        deoptimize.accept(tickController.getDeclaredMethod("updateUIForCutoutChanged",Rect.class,int.class,boolean.class,boolean.class));
        deoptimize.accept(entry.getDeclaredMethod("getImportance"));
        deoptimize.accept(loader.loadClass(provider).getDeclaredMethod("checkHeadsUp",entry,boolean.class));
        deoptimize.accept(loader.loadClass(provider).getDeclaredMethod("shouldHeadsUp",entry));
        deoptimize.accept(loader.loadClass(provider).getDeclaredMethod("shouldHeadsUpWhenAwake",entry,boolean.class));
    }
    private CharSequence tickerText(StatusBarNotification sbn){
        android.app.Notification notification=sbn.getNotification();
        if(notification.tickerText!=null&&notification.tickerText.length()>0)return notification.tickerText;
        android.os.Bundle extras=notification.extras;
        CharSequence title=extras==null?null:extras.getCharSequence(android.app.Notification.EXTRA_TITLE);
        CharSequence body=extras==null?null:extras.getCharSequence(android.app.Notification.EXTRA_TEXT);
        if((body==null||body.length()==0)&&extras!=null)body=extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT);
        if((body==null||body.length()==0)&&extras!=null){
            CharSequence[] lines=extras.getCharSequenceArray(android.app.Notification.EXTRA_TEXT_LINES);
            if(lines!=null)body=android.text.TextUtils.join(" · ",lines);
        }
        boolean hasTitle=title!=null&&title.length()>0,hasBody=body!=null&&body.length()>0;
        if(hasTitle&&hasBody)return title+"："+body;
        if(hasTitle)return title;
        if(hasBody)return body;
        if(context!=null){
            try{
                android.content.pm.PackageManager packages=context.getPackageManager();
                CharSequence label=packages.getApplicationLabel(packages.getApplicationInfo(sbn.getPackageName(),0));
                if(label!=null&&label.length()>0)return label;
            }catch(android.content.pm.PackageManager.NameNotFoundException ignored){ }
        }
        return sbn.getPackageName();
    }
    /** Preserve Flyme's list except within this thread's explicit Ticker-to-popup decision. */
    private final class PopupExclusions extends java.util.AbstractList<Object> {
        private final List<?> nativeList;
        PopupExclusions(List<?> nativeList){this.nativeList=nativeList;}
        @Override public Object get(int index){return nativeList.get(index);}
        @Override public int size(){return nativeList.size();}
        @Override public boolean contains(Object value){
            return promoted.get()==null&&nativeList.contains(value);
        }
    }
    private StatusBarOverlayFrames.Frame bar(View view){
        View root=view;
        for(ViewParent p=view.getParent();p instanceof View parent;p=parent.getParent()){
            if(parent.getClass().getName().equals("com.android.systemui.statusbar.phone.PhoneStatusBarView")){root=parent;break;}
        }
        if(!root.isAttachedToWindow()||!root.isShown()||root.getDisplay()==null)return null;
        int[] location=new int[2];root.getLocationOnScreen(location);
        return new StatusBarOverlayFrames.Frame(root.getDisplay().getDisplayId(),location[0],location[1],
                location[0]+root.getWidth(),location[1]+root.getHeight());
    }
    private boolean covered(){
        for(View view:tickers.values()){
            var bounds=bar(view);if(bounds==null)continue;
            for(var overlay:overlays)if(overlay.covers(bounds))return true;
        }
        return false;
    }
    void refresh(){
        if(Looper.myLooper()!=Looper.getMainLooper()){handler.post(this::refresh);return;}
        if(context==null)return;
        if(lastLeftOnly!=leftOnly.getAsBoolean()){
            lastLeftOnly=leftOnly.getAsBoolean();
            for(var item:new ArrayList<>(cutoutArgs.entrySet())){
                try{cutoutMethod.invoke(item.getKey(),item.getValue());}catch(ReflectiveOperationException error){throw new IllegalStateException(error);}
            }
            for(View view:tickers.values())view.requestLayout();
        }
        overlays=StatusBarOverlayFrames.parse(Settings.Global.getString(context.getContentResolver(),StatusBarOverlayFrames.KEY));
        boolean hideText=avoidLyrics.getAsBoolean()&&covered();
        for(var state:textAlpha.entrySet()){
            state.getKey().setAlpha(hideText||exitingHidden.containsKey(state.getKey())?0f:state.getValue());
        }
    }
}
