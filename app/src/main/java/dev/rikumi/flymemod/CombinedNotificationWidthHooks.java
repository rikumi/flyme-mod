package dev.rikumi.flymemod;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.BiConsumer;

/** Independent merged-shade notification width and a ColorOS-style separator. */
final class CombinedNotificationWidthHooks {
    private static final String STACK="com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout";
    private final BooleanSupplier enabled;
    private final Consumer<Context> settings;
    private final BiConsumer<String,Throwable> log;
    private final Field expanded,shade,side,panel,stackController,stackView,barState,shared;
    private final Method classics,value,onKeyguard,filter,topPosition,setTopPosition;
    private final Map<Object,WeakReference<State>> states=new WeakHashMap<>();

    CombinedNotificationWidthHooks(ClassLoader loader,Consumer<Context> settings,BooleanSupplier enabled,
                                   BiConsumer<String,Throwable> log) throws ReflectiveOperationException {
        this.settings=settings;this.enabled=enabled;this.log=log;
        Class<?> stack=loader.loadClass(STACK);
        expanded=stack.getField("mIsExpanded");shade=stack.getField("mShadeInteractor");side=stack.getField("mSidePaddings");
        classics=shade.getType().getMethod("isClassicsMode");value=loader.loadClass("kotlinx.coroutines.flow.StateFlow").getMethod("getValue");
        onKeyguard=stack.getMethod("onKeyguard");filter=stack.getMethod("isFilterStackLayout");
        stack.getDeclaredMethod("setIsExpanded",boolean.class);
        Class<?> qs=loader.loadClass("com.android.systemui.shade.QuickSettingsControllerImpl");
        panel=qs.getField("mPanelView");barState=qs.getField("mBarState");
        stackController=qs.getField("mNotificationStackScrollLayoutController");
        stackView=stackController.getType().getField("mView");
        shared=qs.getField("mSharedNotificationContainerInteractor");
        topPosition=shared.getType().getMethod("getTopPosition");setTopPosition=shared.getType().getMethod("setTopPosition",float.class);
    }

    void install(SignalHooks.Installer installer) {
        installer.hook(STACK,"setIsExpanded",chain->{
            View stack=(View)chain.getThisObject();
            boolean changed=expanded.getBoolean(stack)!=(Boolean)chain.getArg(0);
            Object result=chain.proceed();
            if(changed&&enabled.getAsBoolean()) stack.requestLayout();
            return result;
        },boolean.class);
    }

    boolean applyWidth(View stack,int available) throws ReflectiveOperationException {
        if(!active(stack)||available<=0) return false;
        int content=HeadsUpWidthHooks.contentWidth(available,stack.getResources().getDisplayMetrics().density);
        side.setInt(stack,Math.max(0,(available-content)/2));
        return true;
    }
    private boolean active(View stack) throws ReflectiveOperationException {
        return enabled.getAsBoolean()&&expanded.getBoolean(stack)&&!(Boolean)onKeyguard.invoke(stack)
                &&!(Boolean)filter.invoke(stack)&&Boolean.TRUE.equals(value.invoke(classics.invoke(shade.get(stack))));
    }
    private static boolean content(View stack) {
        if(!(stack instanceof ViewGroup group)) return false;
        for(int i=0;i<group.getChildCount();i++) {
            View child=group.getChildAt(i);
            String name=child.getClass().getName();
            if(child.getVisibility()!=View.GONE&&(name.endsWith(".ExpandableNotificationRow")||name.endsWith(".MediaContainerView"))) return true;
        }
        return false;
    }

    float padding(Object controller,View controls,float original) throws ReflectiveOperationException {
        WeakReference<State> reference=states.get(controller);State state=reference==null?null:reference.get();
        if(state==null) {
            state=new State(controller,(View)panel.get(controller),(View)stackView.get(stackController.get(controller)));
            states.put(controller,new WeakReference<>(state));
        }
        state.controls=new WeakReference<>(controls);
        state.base=original;state.extra=state.extra();state.update();
        return original+state.extra;
    }
    void reset(Object controller) {
        WeakReference<State> reference=states.get(controller);State state=reference==null?null:reference.get();
        if(state!=null) state.extra=0;
    }
    void refresh() {
        for(WeakReference<State> reference:new ArrayList<>(states.values())) {
            State state=reference.get();if(state==null) continue;
            try {
                Object controller=state.controller.get();View stack=state.stack.get();View host=state.host.get();
                if(controller==null||stack==null||host==null) continue;
                Object sharedInteractor=shared.get(controller);
                float current=((Number)value.invoke(topPosition.invoke(sharedInteractor))).floatValue();
                state.base=current-state.extra;state.extra=state.extra();
                setTopPosition.invoke(sharedInteractor,state.base+state.extra);
                stack.requestLayout();host.requestLayout();state.update();
            } catch(ReflectiveOperationException|RuntimeException error) {log.accept("Cannot refresh merged notification layout",error);}
        }
    }

    private final class State implements ViewTreeObserver.OnPreDrawListener,View.OnAttachStateChangeListener {
        final WeakReference<Object> controller;
        final WeakReference<View> host,stack;
        WeakReference<View> controls=new WeakReference<>(null);
        final Separator separator=new Separator();
        final Matrix untranslated=new Matrix();
        final float[] animatedPoint=new float[2],restPoint=new float[2];
        ViewTreeObserver observer;
        float base,extra;
        boolean added;
        State(Object controller,View host,View stack) {
            this.controller=new WeakReference<>(controller);this.host=new WeakReference<>(host);this.stack=new WeakReference<>(stack);
            host.addOnAttachStateChangeListener(this);
            if(host.isAttachedToWindow())onViewAttachedToWindow(host);
        }
        float extra() throws ReflectiveOperationException {
            View view=stack.get();Object qs=controller.get();
            if(view==null||qs==null||!active(view)||barState.getInt(qs)!=0||!content(view)
                    ||view.getResources().getConfiguration().orientation!=Configuration.ORIENTATION_PORTRAIT) return 0;
            // The native QS expansion height already includes the upper spacing.
            // Add only the separator height and the spacing below it.
            return (16f+4f)*view.getResources().getDisplayMetrics().density;
        }
        @Override public void onViewAttachedToWindow(View host) {
            observer=host.getViewTreeObserver();observer.addOnPreDrawListener(this);
        }
        @Override public void onViewDetachedFromWindow(View host) {
            if(observer!=null&&observer.isAlive())observer.removeOnPreDrawListener(this);
            observer=null;host.getOverlay().remove(separator);added=false;
        }
        @Override public boolean onPreDraw() {update();return true;}
        void update() {
            View panel=host.get(),notifications=stack.get(),qs=controls.get();
            if(panel==null||notifications==null) return;
            try {
                boolean mode=enabled.getAsBoolean()&&!(Boolean)onKeyguard.invoke(notifications)
                        &&!(Boolean)filter.invoke(notifications)
                        &&Boolean.TRUE.equals(value.invoke(classics.invoke(shade.get(notifications))))
                        &&notifications.getResources().getConfiguration().orientation==Configuration.ORIENTATION_PORTRAIT;
                boolean show=(extra>0||added)&&mode&&content(notifications)&&panel.isShown()&&qs!=null&&qs.isShown();
                if(!show) {if(added)panel.getOverlay().remove(separator);added=false;return;}
                float density=panel.getResources().getDisplayMetrics().density;
                float width=44f*density,height=4f*density;
                animatedPoint[0]=animatedPoint[1]=restPoint[0]=restPoint[1]=0;
                float opacity=1f;
                for(View child=qs;child!=panel;child=child.getParent() instanceof View parent?parent:panel) {
                    opacity*=child.getAlpha();
                    child.getMatrix().mapPoints(animatedPoint);
                    untranslated.set(child.getMatrix());
                    untranslated.postTranslate(-child.getTranslationX(),-child.getTranslationY());
                    untranslated.mapPoints(restPoint);
                    float x=child.getLeft(),y=child.getTop();
                    if(child.getParent() instanceof View parent) {x-=parent.getScrollX();y-=parent.getScrollY();}
                    animatedPoint[0]+=x;animatedPoint[1]+=y;restPoint[0]+=x;restPoint[1]+=y;
                }
                // Follow the actual controls/ancestor transforms; notification translation
                // and alpha have different native animation factors and must not drive the bar.
                float left=(panel.getWidth()-width)/2f+animatedPoint[0]-restPoint[0];
                float top=base+animatedPoint[1]-restPoint[1];
                int alpha=Math.round(51f*Math.max(0f,Math.min(1f,opacity)));
                separator.update(left,top,width,height,alpha);
                if(!added) {panel.getOverlay().add(separator);added=true;}
            } catch(ReflectiveOperationException|RuntimeException error) {log.accept("Cannot position merged notification separator",error);}
        }
    }
    private static final class Separator extends Drawable {
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF rect=new RectF();
        Separator(){paint.setColor(Color.WHITE);paint.setAlpha(51);}
        void update(float left,float top,float width,float height,int alpha) {
            if(rect.left==left&&rect.top==top&&rect.width()==width&&rect.height()==height&&paint.getAlpha()==alpha) return;
            rect.set(left,top,left+width,top+height);paint.setAlpha(alpha);
            setBounds((int)Math.floor(left),(int)Math.floor(top),(int)Math.ceil(left+width),(int)Math.ceil(top+height));
            invalidateSelf();
        }
        @Override public void draw(Canvas canvas){canvas.drawRoundRect(rect,rect.height()/2f,rect.height()/2f,paint);}
        @Override public void setAlpha(int alpha){paint.setAlpha(alpha);invalidateSelf();}
        @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}
        @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
}
