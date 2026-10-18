package dev.rikumi.flymemod;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.View;
import android.view.WindowManager;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Retain the native bar controllers by task instead of replacing the top-task singleton. */
final class WeltWindowHandles {
    private static final String BAR="com.flyme.systemuitools.windowmode.views.A";
    private final SharedPreferences preferences;
    private final BiConsumer<String,Throwable> log;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Map<Object,Entry> owners=new IdentityHashMap<>();
    private final Map<Integer,Entry> active=new HashMap<>();
    private final List<Entry> pool=new ArrayList<>();
    private final ThreadLocal<Entry> current=new ThreadLocal<>();
    private Context context;
    private Object extension;
    private Class<?> barClass;
    private Method initialize, toggle, update, virtualMode, bounds, restore, move, fling;
    private Field rootView, layoutParams, taskDisplay, taskConfiguration, windowConfiguration;
    private Method rawMode;
    private boolean clearing;
    private Field controllerMode;
    private Field nativePinBar,nativeWeltBar,barShown;
    private Method showTask;
    private boolean started, reported;
    private String lastState;
    private final Map<Integer,Integer> nativeTasks=new java.util.concurrent.ConcurrentHashMap<>();
    private final Runnable tick=new Runnable(){public void run(){
        try { refresh(); } catch(ReflectiveOperationException|RuntimeException error){
            if(!reported){reported=true;log.accept("Cannot update per-task welt handles",error);}
        }
    }};

    WeltWindowHandles(SharedPreferences preferences,BiConsumer<String,Throwable> log){
        this.preferences=preferences;this.log=log;
    }
    void install(ClassLoader loader,SignalHooks.Installer installer,Consumer<Method> deoptimize) throws ReflectiveOperationException {
        barClass=loader.loadClass(BAR);
        controllerMode=barClass.getDeclaredField("q0");controllerMode.setAccessible(true);
        initialize=barClass.getDeclaredMethod("t0",Context.class,int.class);
        toggle=barClass.getDeclaredMethod("b1",boolean.class);
        update=barClass.getDeclaredMethod("h1",Rect.class);
        rootView=barClass.getDeclaredField("i");rootView.setAccessible(true);
        layoutParams=barClass.getDeclaredField("h");layoutParams.setAccessible(true);
        Class<?> info=loader.loadClass("android.app.TaskInfo");
        taskDisplay=info.getField("displayId");
        taskConfiguration=info.getField("configuration");
        windowConfiguration=android.content.res.Configuration.class.getField("windowConfiguration");
        rawMode=loader.loadClass("android.app.WindowConfiguration").getMethod("getWindowingMode");
        Class<?> ext=loader.loadClass("flyme.view.WindowManagerExt");
        virtualMode=ext.getMethod("getVirtualWindowingModeByTask",int.class,int.class);
        bounds=ext.getMethod("getWindowModeBound",int.class,int.class,int.class);
        restore=ext.getMethod("goBackPreviousWindowMode",int.class,int.class,int.class);
        move=ext.getMethod("moveWindowModeBound",int.class,int.class,int.class,Rect.class,float[].class,float[].class,boolean.class);
        fling=ext.getMethod("flingWindowMode",int.class,int.class,int.class,float.class,float.class,Point.class);
        Method instance=ext.getMethod("getInstance",Context.class);
        // WindowMode may already have initialized its bar before onPackageReady,
        // or its call to t0 may be AOT-inlined. Recover the native context directly.
        Class<?> windowMode=loader.loadClass("com.flyme.systemuitools.windowmode.WindowMode");
        Field nativeBar=windowMode.getDeclaredField("o");nativeBar.setAccessible(true);
        nativeWeltBar=nativeBar;
        nativePinBar=windowMode.getDeclaredField("m");nativePinBar.setAccessible(true);
        barShown=barClass.getDeclaredField("m");barShown.setAccessible(true);
        showTask=ext.getMethod("updateWindowModeShowState",int.class,int.class,int.class,boolean.class,boolean.class);
        Field barContext=barClass.getDeclaredField("f");barContext.setAccessible(true);
        main.post(new Runnable(){public void run(){
            if(started)return;
            try {
                Context ready=(Context)barContext.get(nativeBar.get(null));
                if(ready!=null){
                    context=ready;extension=instance.invoke(null,context);started=true;
                    log.accept("Welt handles started from native controller context",null);
                    main.removeCallbacks(tick);main.post(tick);return;
                }
            } catch(ReflectiveOperationException|RuntimeException error){
                if(!reported){reported=true;log.accept("Cannot initialize welt controller context",error);}
            }
            main.postDelayed(this,1000);
        }});
        for(Method method:windowMode.getDeclaredMethods())deoptimize.accept(method);
        Class<?> listener=loader.loadClass("com.flyme.systemuitools.windowmode.utils.h");
        Field listenerMode=listener.getDeclaredField("c");listenerMode.setAccessible(true);
        listener.getDeclaredMethod("invoke",Object.class,Method.class,Object[].class);
        installer.hook(listener.getName(),"invoke",chain->{
            Method callback=(Method)chain.getArg(1);Object[] args=(Object[])chain.getArg(2);
            int callbackMode=listenerMode.getInt(chain.getThisObject());
            if((callbackMode==12 || callbackMode==268) && args!=null
                    && callback.getName().equals("onStackWindowModeChanged")
                    && args.length==2 && args[1] instanceof android.os.Bundle data) {
                nativeTasks.put(callbackMode,Boolean.TRUE.equals(args[0])?data.getInt("taskId"):0);
                requestRefresh();
            }
            Object result=chain.proceed();
            if(callbackMode==12 || callbackMode==268)requestRefresh();
            return result;
        },Object.class,Method.class,Object[].class);
        // Scope every native callback, including animation callbacks that re-enter through
        // static synthetic methods, so native gestures never address the top task by mistake.
        for(Method method:barClass.getDeclaredMethods()) {
            installer.hook(BAR,method.getName(),chain->{
                Object owner=chain.getThisObject();
                if(owner==null && method.getParameterCount()>0 && method.getParameterTypes()[0]==barClass)
                    owner=chain.getArg(0);
                Entry entry=(enabled() || clearing)?owners.get(owner):null, previous=current.get();
                if(entry!=null)current.set(entry);
                try {
                    if(entry!=null && method.getName().equals("s0"))
                        return entry.task>0?new Rect(entry.bounds):null;
                    Object result=chain.proceed();
                    if(entry!=null && method.getName().equals("l0")
                            && owner!=nativePinBar.get(null) && owner!=nativeWeltBar.get(null)) {
                        // Preserve the original native handle registration; additional
                        // native controller instances have their own window identity.
                        ((WindowManager.LayoutParams)layoutParams.get(owner))
                                .setTitle("Flyme Mod Welt task "+entry.task);
                    }
                    if(entry==null && method.getName().equals("t0") && (Integer)chain.getArg(1)==268 && !started){
                        context=(Context)chain.getArg(0);extension=instance.invoke(null,context);
                        started=true;main.post(tick);
                        log.accept("Welt handles started from native initialization",null);
                    }
                    if(method.getParameterCount()==1 && method.getParameterTypes()[0]==android.content.Intent.class)
                        requestRefresh();
                    return result;
                } finally {if(previous==null)current.remove();else current.set(previous);}
            },method.getParameterTypes());
            deoptimize.accept(method);
        }
        // These are the native singleton dispatch points, all on the UI thread.
        // Reconcile task ownership instead of hiding/recreating the previous bar.
        installer.hook(windowMode.getName(),"M0",chain->{
            int mode=(Integer)chain.getArg(1);
            if(enabled() && started && (mode==12 || mode==268)){reconcile();requestRefresh();return null;}
            return chain.proceed();
        },Rect.class,int.class);
        installer.hook(windowMode.getName(),"m0",chain->{
            int mode=(Integer)chain.getArg(0);
            if(enabled() && started && (mode==12 || mode==268)){reconcile();requestRefresh();return null;}
            return chain.proceed();
        },int.class,boolean.class,long.class);
        installer.hook(windowMode.getName(),"o0",chain->{
            if(enabled() && started){requestRefresh();return null;}
            return chain.proceed();
        },Rect.class,Rect.class);
        installer.hook(ext.getName(),"updateWindowModeShowState",chain->{
            Entry entry=current.get();
            if(entry!=null && entry.task>0){
                showTask.invoke(extension,0,entry.task,entry.mode,chain.getArg(1),false);
                requestRefresh();return null;
            }
            return chain.proceed();
        },int.class,boolean.class);
        preferences.registerOnSharedPreferenceChangeListener(preferenceChanges);
        installer.hook(ext.getName(),"goBackPreviousWindowMode",chain->{
            Entry entry=current.get();
            if(entry!=null){
                current.remove();
                try {restore.invoke(extension,0,entry.task,entry.mode);return null;}
                finally {current.set(entry);}
            }
            return chain.proceed();
        },int.class,int.class,int.class);
        installer.hook(ext.getName(),"moveWindowModeBound",chain->{
            Entry entry=current.get();
            if(entry!=null){move.invoke(extension,0,entry.task,entry.mode,chain.getArg(1),null,null,chain.getArg(2));return null;}
            return chain.proceed();
        },int.class,Rect.class,boolean.class);
        installer.hook(ext.getName(),"flingWindowMode",chain->{
            Entry entry=current.get();
            if(entry!=null){fling.invoke(extension,0,entry.task,entry.mode,chain.getArg(0),chain.getArg(1),chain.getArg(2));return null;}
            return chain.proceed();
        },float.class,float.class,Point.class);
        installer.hook(ext.getName(),"flingWindowMode",chain->{
            Entry entry=current.get();
            if(entry!=null){fling.invoke(extension,0,entry.task,entry.mode,chain.getArg(1),chain.getArg(2),chain.getArg(3));return null;}
            return chain.proceed();
        },int.class,float.class,float.class,Point.class);
        log.accept("Persistent per-task native bar controllers registered",null);
    }

    private void refresh() throws ReflectiveOperationException {
        boolean enabled=preferences.getBoolean(ModuleSettings.SMALL_WINDOW_LIMIT_ENABLED,false);
        PowerManager power=context.getSystemService(PowerManager.class);
        KeyguardManager keyguard=context.getSystemService(KeyguardManager.class);
        if(!enabled || !power.isInteractive() || keyguard.isKeyguardLocked()) {clear();return;}
        int limit=Math.max(1,Math.min(ModuleSettings.SMALL_WINDOW_LIMIT_MAX,
                preferences.getInt(ModuleSettings.SMALL_WINDOW_LIMIT,ModuleSettings.SMALL_WINDOW_LIMIT_DEFAULT)));
        List<Integer> tasks=new ArrayList<>();
        Map<Integer,Rect> rectangles=new HashMap<>();
        Map<Integer,Integer> modes=new HashMap<>();
        Point screen=new Point();context.getSystemService(WindowManager.class).getDefaultDisplay().getRealSize(screen);
        ActivityManager manager=context.getSystemService(ActivityManager.class);
        for(ActivityManager.RunningTaskInfo task:manager.getRunningTasks(100)) {
            if(taskDisplay.getInt(task)!=0 || (Integer)rawMode.invoke(
                    windowConfiguration.get(taskConfiguration.get(task)))!=12)continue;
            // Ordinary pins moved offscreen remain virtual mode 12; 268 is a separate subtype.
            int mode=(Integer)virtualMode.invoke(extension,0,task.taskId);
            if(mode!=12 && mode!=268)continue;
            Rect rect=(Rect)bounds.invoke(extension,0,task.taskId,mode);
            if(rect==null || rect.isEmpty() || !isEdgeHidden(rect.left,rect.right,screen.x))continue;
            modes.put(task.taskId,mode);
            tasks.add(task.taskId);rectangles.put(task.taskId,new Rect(rect));
            if(tasks.size()>=limit)break;
        }
        String state="tasks="+tasks+", native="+nativeTasks+", limit="+limit;
        if(!state.equals(lastState)){lastState=state;log.accept("Welt handles: "+state,null);}
        for(Integer task:new ArrayList<>(active.keySet()))if(!tasks.contains(task))release(task);
        for(Integer task:tasks){
            Entry entry=active.get(task);
            if(entry==null){
                entry=acquire(modes.get(task));
                entry.task=task;entry.mode=modes.get(task);controllerMode.setInt(entry.controller,entry.mode);
                entry.bounds.set(rectangles.get(task));active.put(task,entry);
                if(!barShown.getBoolean(entry.controller))update.invoke(entry.controller,entry.bounds);
            } else if(entry.mode!=modes.get(task) || !entry.bounds.equals(rectangles.get(task))
                    || !barShown.getBoolean(entry.controller)){
                entry.mode=modes.get(task);controllerMode.setInt(entry.controller,entry.mode);
                entry.bounds.set(rectangles.get(task));update.invoke(entry.controller,entry.bounds);
            }
        }
        arrange(tasks);
    }
    private Entry acquire(int mode) throws ReflectiveOperationException {
        Object original=(mode==12?nativePinBar:nativeWeltBar).get(null);
        Entry known=owners.get(original);
        if(known==null){
            Entry entry=new Entry(original);owners.put(original,entry);return entry;
        }
        if(known.task==0){pool.remove(known);return known;}
        if(!pool.isEmpty())return pool.remove(pool.size()-1);
        Object controller=barClass.getDeclaredConstructor().newInstance();
        Entry entry=new Entry(controller);owners.put(controller,entry);
        initialize.invoke(controller,context,mode);
        return entry;
    }
    private boolean enabled(){return preferences.getBoolean(ModuleSettings.SMALL_WINDOW_LIMIT_ENABLED,false);}
    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceChanges=(prefs,key)->requestRefresh();
    private void requestRefresh(){
        main.post(()->{
            if(!started)return;
            main.removeCallbacks(tick);main.post(tick);
            // A native event can precede the final animation bounds transaction.
            main.removeCallbacks(settled);main.postDelayed(settled,350);
        });
    }
    private final Runnable settled=()->reconcile();
    private void reconcile(){
        try {refresh();}catch(ReflectiveOperationException|RuntimeException error){
            if(!reported){reported=true;log.accept("Cannot reconcile native task bars",error);}
        }
    }
    private void release(int task) throws ReflectiveOperationException {
        Entry entry=active.get(task);
        // Native hide refuses while its task is still offscreen. Mark this handle
        // inactive first so s0() returns null and native cleanup actually runs.
        entry.task=0;toggle.invoke(entry.controller,false);entry.bounds.setEmpty();
        active.remove(task);pool.add(entry);
    }
    private void clear() throws ReflectiveOperationException {
        clearing=true;
        try {for(Integer task:new ArrayList<>(active.keySet()))release(task);}
        finally {clearing=false;}
    }
    private void arrange(List<Integer> tasks) throws ReflectiveOperationException {
        Point screen=new Point();WindowManager wm=context.getSystemService(WindowManager.class);
        wm.getDefaultDisplay().getRealSize(screen);
        List<Rect> occupied=new ArrayList<>();
        int gap=Math.round(8*context.getResources().getDisplayMetrics().density);
        for(Integer task:tasks){
            Entry entry=active.get(task);View view=(View)rootView.get(entry.controller);
            if(view==null || !view.isAttachedToWindow())continue;
            WindowManager.LayoutParams params=(WindowManager.LayoutParams)view.getLayoutParams();
            int height=params.height;
            int y=findSlot(params.y,height,screen.y,gap,entry.bounds.left<0,occupied);
            if(params.y!=y){params.y=y;wm.updateViewLayout(view,params);}
            occupied.add(new Rect(entry.bounds.left,y,entry.bounds.right,y+height));
        }
    }
    static boolean isEdgeHidden(int left,int right,int screenWidth){
        return left>=screenWidth || right<=0;
    }
    static int findSlot(int preferred,int height,int screen,int gap,boolean left,List<Rect> occupied){
        int max=Math.max(gap,screen-height-gap),start=Math.max(gap,Math.min(max,preferred));
        for(int distance=0;distance<=screen;distance+=Math.max(1,height+gap)){
            for(int candidate:new int[]{start+distance,start-distance}){
                if(candidate<gap||candidate>max)continue;
                boolean free=true;
                for(Rect rect:occupied)if((rect.left<0)==left && candidate<rect.bottom+gap && candidate+height+gap>rect.top){free=false;break;}
                if(free)return candidate;
            }
        }
        return start;
    }
    private static final class Entry {
        final Object controller;final Rect bounds=new Rect();int task,mode;
        Entry(Object controller){this.controller=controller;}
    }
}
