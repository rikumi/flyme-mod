"""Run the production hooks against touch-dispatch and animation lifecycle fixtures."""
from pathlib import Path
import subprocess
import tempfile

sources = {
    'android/content/Context.java': '''package android.content;
public class Context { public final android.os.Vibrator vibrator=new android.os.Vibrator();
public <T>T getSystemService(Class<T> type){return type.cast(vibrator);} }''',
    'android/content/res/Resources.java': '''package android.content.res;
public class Resources {public android.util.DisplayMetrics getDisplayMetrics(){return new android.util.DisplayMetrics();}}''',
    'android/util/DisplayMetrics.java': 'package android.util; public class DisplayMetrics {public float density=1;}',
    'android/graphics/Rect.java': '''package android.graphics;public class Rect{public int left,top,right,bottom;
public boolean contains(int x,int y){return x>=left&&x<right&&y>=top&&y<bottom;}}''',
    'android/os/SystemClock.java': 'package android.os;public class SystemClock{public static long uptimeMillis(){return 100;}}',
    'android/os/VibrationAttributes.java': '''package android.os;public class VibrationAttributes{public static final int USAGE_TOUCH=18;
public static class Builder{public Builder setUsage(int u){return this;}public VibrationAttributes build(){return new VibrationAttributes();}}}''',
    'android/os/VibrationEffect.java': '''package android.os;public class VibrationEffect{public long duration;public int amplitude;
public static VibrationEffect createOneShot(long d,int a){VibrationEffect e=new VibrationEffect();e.duration=d;e.amplitude=a;return e;}}''',
    'android/os/Vibrator.java': '''package android.os;public class Vibrator{public int count;public VibrationEffect last;
public boolean hasVibrator(){return true;}public void vibrate(VibrationEffect e,VibrationAttributes a){count++;last=e;}}''',
    'android/view/ViewParent.java': 'package android.view;public interface ViewParent{void requestDisallowInterceptTouchEvent(boolean value);}',
    'android/view/HapticFeedbackConstants.java': 'package android.view;public class HapticFeedbackConstants{public static final int FLYME_SLIDE_HORIZONTAL=31022;}',
    'android/view/View.java': '''package android.view;import java.util.*;public class View implements ViewParent{
public static final int VISIBLE=0;public int width=300,height=120,left=0,top=0,visibility=VISIBLE,scrollX,scrollY;
public float translation;public ViewGroup parent;public boolean disallow;public int clicks,cancels;public boolean childTarget;
public int haptics,lastHaptic;public boolean hapticEnabled=true;
public boolean performHapticFeedback(int constant){if(!hapticEnabled)return false;haptics++;lastHaptic=constant;return true;}
public final android.content.Context context=new android.content.Context();public final List<OnAttachStateChangeListener> listeners=new ArrayList<>();
public ViewParent getParent(){return parent;}public android.content.Context getContext(){return context;}
public android.content.res.Resources getResources(){return new android.content.res.Resources();}public int getWidth(){return width;}
public int getHeight(){return height;}public int getPaddingLeft(){return 0;}public int getPaddingRight(){return 0;}
public int getScrollX(){return scrollX;}public int getScrollY(){return scrollY;}public void scrollTo(int x,int y){scrollX=x;scrollY=y;}
public float getTranslationX(){return translation;}public void setTranslationX(float x){translation=x;}
public int getVisibility(){return visibility;}public boolean getGlobalVisibleRect(android.graphics.Rect r){r.left=left;r.top=top;r.right=left+width;r.bottom=top+height;return true;}
public void requestDisallowInterceptTouchEvent(boolean value){disallow=value;if(parent!=null)parent.requestDisallowInterceptTouchEvent(value);}
public void addOnAttachStateChangeListener(OnAttachStateChangeListener l){listeners.add(l);}
public interface OnAttachStateChangeListener{void onViewAttachedToWindow(View v);void onViewDetachedFromWindow(View v);}
public void detach(){for(var l:listeners)l.onViewDetachedFromWindow(this);}}''',
    'android/view/ViewGroup.java': '''package android.view;import java.util.*;public class ViewGroup extends View{
public List<View> children=new ArrayList<>();public int getChildCount(){return children.size();}public View getChildAt(int i){return children.get(i);}
public void add(View child){children.add(child);child.parent=this;}}''',
    'android/widget/SeekBar.java': 'package android.widget;public class SeekBar extends android.view.View{}',
    'android/view/ViewConfiguration.java': '''package android.view;public class ViewConfiguration{
public static ViewConfiguration get(android.content.Context c){return new ViewConfiguration();}public int getScaledTouchSlop(){return 8;}}''',
    'android/view/MotionEvent.java': '''package android.view;public class MotionEvent{
public static final int ACTION_DOWN=0,ACTION_UP=1,ACTION_MOVE=2,ACTION_CANCEL=3,ACTION_POINTER_DOWN=5,ACTION_POINTER_UP=6;
public int action;public float x,y;public MotionEvent(int a,float x,float y){action=a;this.x=x;this.y=y;}
public int getActionMasked(){return action;}public float getRawX(){return x;}public float getRawY(){return y;}
public static MotionEvent obtain(long d,long t,int a,float x,float y,int m){return new MotionEvent(a,x,y);}public void recycle(){}}''',
    'android/view/animation/DecelerateInterpolator.java': 'package android.view.animation;public class DecelerateInterpolator{}',
    'android/animation/ValueAnimator.java': '''package android.animation;import java.util.*;public class ValueAnimator{
public interface AnimatorUpdateListener{void onAnimationUpdate(ValueAnimator a);}public static final List<ValueAnimator> running=new ArrayList<>();
float from,to,current;AnimatorUpdateListener listener;public static ValueAnimator ofFloat(float a,float b){var v=new ValueAnimator();v.from=a;v.to=b;return v;}
public void setDuration(long d){}public void setInterpolator(Object o){}public void addUpdateListener(AnimatorUpdateListener l){listener=l;}
public Object getAnimatedValue(){return current;}public void start(){running.add(this);advanceOne(0);}public void cancel(){running.remove(this);}
void advanceOne(float f){current=from+(to-from)*f;listener.onAnimationUpdate(this);if(f==1)running.remove(this);}
public static void advance(float f){for(var v:new ArrayList<>(running))v.advanceOne(f);}}''',
    'com/android/wm/shell/shared/animation/PhysicsAnimator.java': '''package com.android.wm.shell.shared.animation;
public class PhysicsAnimator{public static int cancelled;public static PhysicsAnimator getInstance(Object o){return new PhysicsAnimator();}public void cancel(){cancelled++;}}''',
    'com/android/systemui/media/controls/ui/view/MediaScrollView.java': '''package com.android.systemui.media.controls.ui.view;
public class MediaScrollView extends android.view.ViewGroup{public float animationTargetX;public void setAnimationTargetX(float x){animationTargetX=x;}}''',
    'com/android/systemui/media/controls/ui/view/MediaCarouselScrollHandler.java': '''package com.android.systemui.media.controls.ui.view;
import android.view.*;public class MediaCarouselScrollHandler{
public final MediaScrollView scrollView=new MediaScrollView();public final ViewGroup mediaContent=new ViewGroup();
public boolean scrollingDisabled;public float contentTranslation,releaseTranslation;public int intercepts,touches,nativeScrolls,nativeFlings,releases;
public MediaCarouselScrollHandler(){scrollView.add(mediaContent);}public void setContentTranslation(float x){contentTranslation=x;mediaContent.setTranslationX(x);}
public void resetTranslation(boolean animated){setContentTranslation(0);}public boolean onInterceptTouch(MotionEvent e){intercepts++;return false;}
public boolean onTouch(MotionEvent e)throws Throwable{touches++;if(e.action==MotionEvent.ACTION_MOVE)
 dev.rikumi.flymemod.SwipeTest.call(getClass().getName(),"onScroll",this,()->{nativeScrolls++;return true;},e,e,1f);
if(e.action==MotionEvent.ACTION_UP){releaseTranslation=contentTranslation;releases++;dev.rikumi.flymemod.SwipeTest.call(getClass().getName(),"onFling",this,()->{nativeFlings++;return true;},2000f,0f);}
if(e.action==MotionEvent.ACTION_UP||e.action==MotionEvent.ACTION_CANCEL){setContentTranslation(0);scrollView.setAnimationTargetX(0);}return false;}
public boolean onScroll(MotionEvent a,MotionEvent b,float x){return false;}public boolean onFling(float x,float y){return false;}
public void setCarouselBounds(int w,int h){}public void setPlayerWidthPlusPadding(int w){}public void setScrollingDisabled(boolean d){scrollingDisabled=d;}}''',
    'com/android/systemui/media/controls/ui/view/MediaCarouselScrollHandler$gestureListener$1.java': '''package com.android.systemui.media.controls.ui.view;
public class MediaCarouselScrollHandler$gestureListener$1{public boolean onScroll(android.view.MotionEvent a,android.view.MotionEvent b,float x,float y){return false;}
public boolean onFling(android.view.MotionEvent a,android.view.MotionEvent b,float x,float y){return false;}}''',
    'dev/rikumi/flymemod/SignalHooks.java': '''package dev.rikumi.flymemod;public class SignalHooks{
public interface Work{Object get()throws Throwable;}public interface Chain{Object proceed()throws Throwable;Object getThisObject();Object getArg(int i);}
public interface Hook{Object run(Chain c)throws Throwable;}public interface Installer{void hook(String c,String m,Hook h,Class<?>...types);}}''',
    'dev/rikumi/flymemod/SwipeTest.java': r'''package dev.rikumi.flymemod;
import android.view.*;import android.animation.ValueAnimator;import android.widget.SeekBar;import java.util.*;
import com.android.systemui.media.controls.ui.view.*;
public class SwipeTest{
static Map<String,SignalHooks.Hook> hooks=new HashMap<>();static boolean enabled=true;static int checks,errors,originalDispatches,deoptimizations;
static final String H=MediaCarouselScrollHandler.class.getName(),S=MediaScrollView.class.getName();static MediaSwipeHooks production;
public static Object call(String c,String m,Object self,SignalHooks.Work nativeCall,Object...args)throws Throwable{
var hook=hooks.get(c+"#"+m);if(hook==null)return nativeCall.get();return hook.run(new SignalHooks.Chain(){
public Object proceed()throws Throwable{return nativeCall.get();}public Object getThisObject(){return self;}public Object getArg(int i){return args[i];}});}
static void check(boolean value){checks++;if(!value)throw new AssertionError("check "+checks);}
static void near(float actual,float expected){check(Math.abs(actual-expected)<.02f);}
static MediaCarouselScrollHandler create()throws Throwable{var h=new MediaCarouselScrollHandler();new ViewGroup().add(h.scrollView);
production.onCreated(h);return h;}
static void disallow(MediaCarouselScrollHandler h)throws Throwable{call("android.view.ViewGroup","requestDisallowInterceptTouchEvent",h.mediaContent,
()->{h.mediaContent.requestDisallowInterceptTouchEvent(true);return null;},true);}
static void event(MediaCarouselScrollHandler h,int action,float x,float y)throws Throwable{var e=new MotionEvent(action,x,y);var v=h.scrollView;
call("android.view.ViewGroup","dispatchTouchEvent",v,()->{originalDispatches++;
boolean intercepted=(action==MotionEvent.ACTION_DOWN||!v.disallow)&&(Boolean)call(S,"onInterceptTouchEvent",v,()->false,e);
if(action==MotionEvent.ACTION_DOWN){v.childTarget=true;disallow(h);}
if(intercepted&&v.childTarget){v.childTarget=false;v.cancels++;}
if(!v.childTarget)call(S,"onTouchEvent",v,()->false,e);
else if(action==MotionEvent.ACTION_UP)v.clicks++;
return true;},e);}
public static void main(String[] a)throws Throwable{
production=new MediaSwipeHooks(SwipeTest.class.getClassLoader(),c->{},()->enabled,(m,e)->{errors++;throw new AssertionError(m,e);});
production.install(SwipeTest.class.getClassLoader(),(c,m,h,t)->hooks.put(c+"#"+m,h),m->deoptimizations++);
check(deoptimizations==2);
// A clickable child keeps taps, but cannot prevent its parent from claiming a horizontal drag.
var h=create();event(h,0,100,20);check(!h.scrollView.disallow);check(h.scrollView.parent.disallow);
event(h,1,100,20);check(h.scrollView.clicks==1);check(h.scrollView.cancels==0);check(!h.scrollView.parent.disallow);
event(h,0,100,20);event(h,2,120,20);near(h.contentTranslation,4.4f);check(h.scrollView.cancels==1);check(h.nativeScrolls==0);
check(h.scrollView.haptics==0);event(h,2,155,20);near(h.contentTranslation,12.1f);
event(h,2,156,20);near(h.contentTranslation,12.1f);check(h.scrollView.haptics==1);
ValueAnimator.advance(1);near(h.contentTranslation,56);event(h,2,170,20);near(h.contentTranslation,70);
event(h,2,155,20);near(h.contentTranslation,70);check(h.scrollView.haptics==2);
ValueAnimator.advance(1);near(h.contentTranslation,12.1f);event(h,2,130,20);near(h.contentTranslation,6.6f);
event(h,1,130,20);near(h.releaseTranslation,6.6f);check(h.nativeFlings==0);check(h.releases==1);check(ValueAnimator.running.isEmpty());
check(h.scrollView.lastHaptic==31022);check(h.scrollView.context.vibrator.count==0);
// Cross again while catch-up is in flight, return to damping, then resume a full-follow release.
h=create();event(h,0,100,20);event(h,2,130,20);event(h,2,160,20);ValueAnimator.advance(.5f);
float current=h.contentTranslation;event(h,2,150,20);near(h.contentTranslation,current);ValueAnimator.advance(1);near(h.contentTranslation,11);
event(h,2,165,20);ValueAnimator.advance(1);event(h,1,170,20);near(h.releaseTranslation,70);check(h.nativeFlings==1);
// Reverse direction and return through the centre; damping is absolute, without accumulated drift.
h=create();event(h,0,150,20);event(h,2,90,20);ValueAnimator.advance(1);near(h.contentTranslation,-60);
event(h,2,120,20);ValueAnimator.advance(1);near(h.contentTranslation,-6.6f);event(h,2,150,20);near(h.contentTranslation,0);
event(h,2,210,20);ValueAnimator.advance(1);near(h.contentTranslation,60);check(h.scrollView.haptics==3);event(h,1,210,20);
// Native view haptic suppression is respected; movement remains independent of feedback.
h=create();h.scrollView.hapticEnabled=false;event(h,0,100,20);event(h,2,160,20);ValueAnimator.advance(1);
near(h.contentTranslation,60);check(h.scrollView.haptics==0);check(h.scrollView.context.vibrator.count==0);event(h,1,160,20);
// Paging and edge translation share the same displacement; another carousel is independent.
h=create();h.mediaContent.width=900;h.scrollView.scrollX=300;var other=create();
event(h,0,150,20);event(h,2,90,20);ValueAnimator.advance(1);check(h.scrollView.scrollX==360);near(h.contentTranslation,0);near(other.contentTranslation,0);
event(h,2,170,20);ValueAnimator.advance(1);check(h.scrollView.scrollX==296);near(h.contentTranslation,0);event(h,1,170,20);
// Vertical gestures release the outer shade and never move the media card.
h=create();event(h,0,100,20);event(h,2,104,40);check(!h.scrollView.parent.disallow);near(h.contentTranslation,0);
event(h,2,170,50);near(h.contentTranslation,0);check(h.scrollView.cancels==0);event(h,3,170,50);
// The entire visible progress-bar rectangle retains native touch/disallow behaviour.
h=create();SeekBar bar=new SeekBar();bar.left=20;bar.top=80;bar.width=260;bar.height=20;h.mediaContent.add(bar);
event(h,0,100,90);check(h.scrollView.disallow);check(h.intercepts==0);event(h,2,200,90);near(h.contentTranslation,0);check(h.scrollView.cancels==0);event(h,1,200,90);
// Hidden progress bars do not reserve a dead area.
h=create();bar=new SeekBar();bar.left=20;bar.top=80;bar.width=260;bar.height=20;bar.visibility=8;h.mediaContent.add(bar);
event(h,0,100,90);event(h,2,120,90);near(h.contentTranslation,4.4f);event(h,1,120,90);
// Cancellation, a second pointer, disabling and detachment all stop pending animation.
for(int mode=0;mode<4;mode++){
h=create();event(h,0,100,20);event(h,2,130,20);event(h,2,160,20);check(!ValueAnimator.running.isEmpty());
if(mode==0)event(h,3,160,20);if(mode==1)event(h,5,160,20);if(mode==2){enabled=false;production.refresh();}if(mode==3)h.scrollView.detach();
near(h.contentTranslation,0);near(h.scrollView.animationTargetX,0);check(ValueAnimator.running.isEmpty());check(!h.scrollView.parent.disallow);
int releases=h.releases;event(h,1,170,20);check(h.releases==releases);ValueAnimator.advance(1);near(h.contentTranslation,0);enabled=true;
}
// Native disabled/edit state and the feature-off state are untouched.
h=create();h.scrollingDisabled=true;event(h,0,100,20);event(h,2,180,20);check(h.intercepts==0);near(h.contentTranslation,0);
enabled=false;h=create();event(h,0,100,20);check(h.scrollView.disallow);event(h,2,180,20);check(h.intercepts==0);near(h.contentTranslation,0);
check(errors==0);System.out.println("PASS: "+checks+" production swipe dispatch/damping/haptics/paging/cancellation checks");
}}
''',
}

with tempfile.TemporaryDirectory(dir='/private/tmp') as tmp:
    root = Path(tmp)
    for name in ('MediaSwipeHooks', 'MediaSwipeMotion'):
        sources[f'dev/rikumi/flymemod/{name}.java'] = Path(
            f'app/src/main/java/dev/rikumi/flymemod/{name}.java').read_text()
    for name, source in sources.items():
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(source)
    jdk = Path('/opt/homebrew/opt/openjdk@21/bin')
    subprocess.run([str(jdk / 'javac'), '-d', str(root / 'classes'),
                    *[str(p) for p in root.rglob('*.java')]], check=True)
    subprocess.run([str(jdk / 'java'), '-cp', str(root / 'classes'),
                    'dev.rikumi.flymemod.SwipeTest'], check=True)
