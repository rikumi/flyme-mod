package dev.rikumi.flymemod;

import android.content.Context;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.*;

final class AddPanelHooks {
    private static final String PANEL="com.flyme.systemui.controlcenter.qs.customize.TileWidgetLayoutContainer";
    private static final String COMMIT=PANEL+"$updateTileViews$1$1";
    private static final String SQUARE="com.flyme.systemui.qs.tileimpl.SquareQSTileViewImpl";
    private final BooleanSupplier optimize,names;
    private final Consumer<Context> settings;
    private final BiConsumer<String,Throwable> log;
    private final Map<ViewGroup,Batch> batches=new WeakHashMap<>();
    private final Set<View> touching=Collections.newSetFromMap(new WeakHashMap<>());
    private final ThreadLocal<Boolean> labelScope=new ThreadLocal<>();
    private Field mapField,specField;
    private Method first,second,layout,header;
    AddPanelHooks(Consumer<Context> settings,BooleanSupplier optimize,BooleanSupplier names,BiConsumer<String,Throwable> log){
        this.settings=settings;this.optimize=optimize;this.names=names;this.log=log;
    }
    private static Field field(Class<?> type,String name)throws ReflectiveOperationException{
        Field f=type.getDeclaredField(name);f.setAccessible(true);return f;
    }
    void install(ClassLoader loader,SignalHooks.Installer installer,Consumer<Method> deoptimize)throws ReflectiveOperationException{
        Class<?> panel=loader.loadClass(PANEL),commit=loader.loadClass(COMMIT);
        Field owner=field(commit,"this$0"),adds=field(commit,"$newTileViews"),removes=field(commit,"$specsToRemove");
        mapField=field(panel,"tileViewMap");
        Class<?> record=loader.loadClass("com.android.systemui.qs.QSPanelControllerBase$TileRecord");
        specField=record.getField("spec");
        Class<?> pair=loader.loadClass("kotlin.Pair");first=pair.getMethod("component1");second=pair.getMethod("component2");
        layout=panel.getMethod("layoutTilesWithGridOccupancy");header=panel.getMethod("updateHeaderAndEmptyState");
        Object unit=loader.loadClass("kotlin.Unit").getField("INSTANCE").get(null);
        installer.hook(COMMIT,"invokeSuspend",chain->{
            ViewGroup view=(ViewGroup)owner.get(chain.getThisObject());settings.accept(view.getContext());
            if(!optimize.getAsBoolean() || chain.getArg(0)!=unit)return chain.proceed();
            cancel(view);
            Batch batch=new Batch(view,new ArrayList<>((List<?>)removes.get(chain.getThisObject())),
                    new ArrayList<>((List<?>)adds.get(chain.getThisObject())));
            batches.put(view,batch);view.postOnAnimation(batch);return unit;
        },Object.class);
        deoptimize.accept(commit.getDeclaredMethod("invokeSuspend",Object.class));
        for(String method:new String[]{"updateTileViews","clearTiles"})installer.hook(PANEL,method,chain->{
            cancel((ViewGroup)chain.getThisObject());return chain.proceed();
        });
        installer.hook(PANEL,"setVisibility",chain->{
            if((Integer)chain.getArg(0)!=View.VISIBLE)cancel((ViewGroup)chain.getThisObject());
            return chain.proceed();
        },int.class);
        installer.hook("android.widget.ScrollView","onTouchEvent",chain->{
            View scroll=(View)chain.getThisObject();MotionEvent event=(MotionEvent)chain.getArg(0);
            if(event.getActionMasked()==MotionEvent.ACTION_DOWN)touching.add(scroll);
            try{return chain.proceed();}finally{
                if(event.getActionMasked()==MotionEvent.ACTION_UP || event.getActionMasked()==MotionEvent.ACTION_CANCEL)touching.remove(scroll);
            }
        },MotionEvent.class);
        installer.hook("com.android.systemui.Prefs","getBoolean",chain->{
            if(Boolean.TRUE.equals(labelScope.get()) && names.getAsBoolean() && "QsShowTileLabel".equals(chain.getArg(1)))return true;
            return chain.proceed();
        },Context.class,String.class,boolean.class);
        installer.hook(PANEL,"createTileView",chain->{
            Boolean previous=labelScope.get();labelScope.set(true);
            try{return chain.proceed();}finally{restoreScope(previous);}
        },record);
        for(String method:new String[]{"onAttachedToWindow","updateLabelVisibility","onInit"}){
            installer.hook(SQUARE,method,chain->{
                View view=(View)chain.getThisObject();boolean inPanel=false;
                for(View v=view;v!=null;v=v.getParent() instanceof View p?p:null)if(panel.isInstance(v)){inPanel=true;break;}
                Boolean previous=labelScope.get();if(inPanel)labelScope.set(true);
                try{return chain.proceed();}finally{restoreScope(previous);}
            });
            deoptimize.accept(loader.loadClass(SQUARE).getDeclaredMethod(method));
        }
        deoptimize.accept(loader.loadClass("com.android.systemui.Prefs").getDeclaredMethod("getBoolean",Context.class,String.class,boolean.class));
    }
    private void restoreScope(Boolean previous){if(previous==null)labelScope.remove();else labelScope.set(previous);}
    private void cancel(ViewGroup view){Batch old=batches.remove(view);if(old!=null)view.removeCallbacks(old);}
    private final class Batch implements Runnable {
        final ViewGroup view;final List<?> removes,adds;int removeIndex,addIndex,lastY=Integer.MIN_VALUE;long idleSince;
        Batch(ViewGroup view,List<?> removes,List<?> adds){this.view=view;this.removes=removes;this.adds=adds;}
        @Override public void run(){
            if(batches.get(view)!=this)return;
            if(!view.isAttachedToWindow() || !view.isShown()){cancel(view);return;}
            View scroll=view;
            for(View v=view;v!=null;v=v.getParent() instanceof View p?p:null)if(v instanceof ScrollView){scroll=v;break;}
            long now=SystemClock.uptimeMillis();int y=scroll.getScrollY();
            if(touching.contains(scroll)||lastY!=y){lastY=y;idleSince=now;}
            if(now-idleSince<100){view.postOnAnimation(this);return;}
            try{
                @SuppressWarnings("unchecked") Map<Object,View> map=(Map<Object,View>)mapField.get(view);
                long deadline=System.nanoTime()+2_000_000L;int count=0;
                do{
                    if(removeIndex<removes.size()){
                        View child=map.remove(removes.get(removeIndex++));if(child!=null)view.removeView(child);
                    }else if(addIndex<adds.size()){
                        Object pair=adds.get(addIndex++),record=first.invoke(pair);View child=(View)second.invoke(pair);
                        Object key=specField.get(record);
                        if(!map.containsKey(key)){map.put(key,child);view.addView(child);}
                    }else break;
                }while(++count<2 && System.nanoTime()<deadline);

                layout.invoke(view);header.invoke(view);view.requestLayout();
                if(removeIndex<removes.size()||addIndex<adds.size())view.postOnAnimation(this);else batches.remove(view);
            }catch(Exception error){cancel(view);log.accept("Cannot apply add-panel tile batch",error);}
        }
    }
}
