"""Execute notification hooks with native swipe eligibility, release and lifecycle fixtures."""
from pathlib import Path
import ast
import subprocess
import tempfile

tree = ast.parse(Path('tests/media-swipe/test_hooks.py').read_text())
shared = next(ast.literal_eval(node.value) for node in tree.body if isinstance(node, ast.Assign)
              and any(isinstance(t, ast.Name) and t.id == 'sources' for t in node.targets))
sources = {name: text for name, text in shared.items() if name.startswith('android/')}
sources['android/view/View.java'] = sources['android/view/View.java'].replace(
    'public void addOnAttachStateChangeListener',
    'public void removeOnAttachStateChangeListener(OnAttachStateChangeListener l){listeners.remove(l);}'
    'public void addOnAttachStateChangeListener').replace(
    'for(var l:listeners)l.onViewDetachedFromWindow(this);',
    'for(var l:new ArrayList<>(listeners))l.onViewDetachedFromWindow(this);')
sources['android/view/MotionEvent.java'] = sources['android/view/MotionEvent.java'].replace(
    'public int getActionMasked()', 'public float getX(){return x;}public int getActionMasked()')
sources.update({
    'dev/rikumi/flymemod/SignalHooks.java': '''package dev.rikumi.flymemod;public class SignalHooks{
public interface Work{Object get(Object[] args)throws Throwable;}public interface Chain{Object proceed()throws Throwable;
Object proceed(Object[] args)throws Throwable;Object getThisObject();Object getArg(int i);}
public interface Hook{Object run(Chain c)throws Throwable;}public interface Installer{void hook(String c,String m,Hook h,Class<?>...types);}}''',
    'android/animation/Animator.java': 'package android.animation;public class Animator{}',
    'com/android/systemui/statusbar/notification/row/ExpandableNotificationRow.java': '''package com.android.systemui.statusbar.notification.row;
public class ExpandableNotificationRow extends android.view.View{public boolean dismissible=true;}''',
    'com/android/systemui/statusbar/notification/stack/NotificationStackScrollLayout.java': '''package com.android.systemui.statusbar.notification.stack;
public class NotificationStackScrollLayout{public static boolean canChildBeDismissed(android.view.View v){return v instanceof
com.android.systemui.statusbar.notification.row.ExpandableNotificationRow r&&r.dismissible;}}''',
    'com/android/systemui/SwipeHelper.java': '''package com.android.systemui;import android.view.*;
public class SwipeHelper{
public View mTouchedView;public boolean mIsSwiping,mLongPressSent,mMenuRowIntercepting;
public float mTranslation,mInitialTouchPos;public int progressUpdates;public float lastProgress;
public boolean onTouchEvent(MotionEvent e){return true;}public boolean onInterceptTouchEvent(MotionEvent e){return false;}
public boolean isSwipeDismissible(){return true;}public boolean isDismissGesture(MotionEvent e){return true;}
public void resetSwipeStates(boolean b){mTouchedView=null;mIsSwiping=false;}
public void updateSwipeProgressFromOffset(View v,boolean b){progressUpdates++;lastProgress=v.getTranslationX();}}''',
    'com/android/systemui/statusbar/notification/stack/NotificationSwipeHelper.java': '''package com.android.systemui.statusbar.notification.stack;
import android.view.*;import android.animation.*;public class NotificationSwipeHelper extends com.android.systemui.SwipeHelper{
public float releaseTranslation,lastMove,lastDelta;public int moves,setCalls,downs,dismissals,snaps;public boolean fast,falseTouch;
public float getTranslation(View v){return v.getTranslationX();}public void setTranslation(View v,float f){setCalls++;v.setTranslationX(f);}
public void onDownUpdate(View v,MotionEvent e){downs++;}public void onMoveUpdate(View v,MotionEvent e,float t,float d){moves++;lastMove=t;lastDelta=d;}
public void snapChild(View v,float target,float speed){snaps++;v.setTranslationX(target);}
public Animator getViewTranslationAnimator(View v,float target,ValueAnimator.AnimatorUpdateListener l){v.setTranslationX(target);return new Animator();}
public boolean handleUpEvent(MotionEvent e,View v,float speed,float t){return false;}}''',
    'dev/rikumi/flymemod/NotificationTest.java': r'''package dev.rikumi.flymemod;
import android.view.*;import android.animation.ValueAnimator;import java.util.*;
import com.android.systemui.statusbar.notification.stack.*;import com.android.systemui.statusbar.notification.row.*;
public class NotificationTest{
static final String BASE="com.android.systemui.SwipeHelper",HELPER=NotificationSwipeHelper.class.getName();
static Map<String,SignalHooks.Hook> hooks=new HashMap<>();static boolean enabled=true;static int checks,errors,deopt;
static NotificationSwipeHooks production;
static void check(boolean v){checks++;if(!v)throw new AssertionError("check "+checks);}static void near(float a,float b){check(Math.abs(a-b)<.02f);}
public static Object call(String c,String m,Object self,SignalHooks.Work original,Object...args)throws Throwable{
var hook=hooks.get(c+"#"+m);if(hook==null)return original.get(args);return hook.run(new SignalHooks.Chain(){
public Object proceed()throws Throwable{return original.get(args);}public Object proceed(Object[] changed)throws Throwable{return original.get(changed);}
public Object getThisObject(){return self;}public Object getArg(int i){return args[i];}});}
static void begin(NotificationSwipeHelper h,View row,float x)throws Throwable{
h.mTouchedView=row;h.mInitialTouchPos=x;h.mTranslation=row.getTranslationX();h.mIsSwiping=false;h.mLongPressSent=h.mMenuRowIntercepting=false;
call(HELPER,"onDownUpdate",h,args->{h.onDownUpdate((View)args[0],(MotionEvent)args[1]);return null;},row,new MotionEvent(0,x,20));}
static void move(NotificationSwipeHelper h,int action,float x)throws Throwable{
var event=new MotionEvent(action,x,20);call(BASE,"onTouchEvent",h,args->{View row=h.mTouchedView;
if(row==null)return true;if(action==2&&!h.mLongPressSent&&!h.mMenuRowIntercepting){float delta=x-h.mInitialTouchPos;
float translated=h.mTranslation+delta;if(!NotificationStackScrollLayout.canChildBeDismissed(row))translated=Math.copySign(Math.min(Math.abs(delta),30),delta);
float nativeValue=translated;
call(HELPER,"setTranslation",h,a->{h.setTranslation((View)a[0],(Float)a[1]);return null;},row,nativeValue);
h.updateSwipeProgressFromOffset(row,NotificationStackScrollLayout.canChildBeDismissed(row));
call(HELPER,"onMoveUpdate",h,a->{h.onMoveUpdate((View)a[0],(MotionEvent)a[1],(Float)a[2],(Float)a[3]);return null;},row,event,nativeValue,delta);
}else if(action==1||action==3){h.releaseTranslation=h.getTranslation(row);
boolean eligible=(Boolean)call(BASE,"isSwipeDismissible",h,a->h.fast||Math.abs(h.getTranslation(row))>.6f*row.width);
if(action==1&&eligible&&!h.falseTouch&&NotificationStackScrollLayout.canChildBeDismissed(row)){
h.dismissals++;call(HELPER,"getViewTranslationAnimator",h,a->h.getViewTranslationAnimator((View)a[0],(Float)a[1],null),row,
Math.copySign(row.width,h.getTranslation(row)),null);
}else call(HELPER,"snapChild",h,a->{h.snapChild((View)a[0],(Float)a[1],(Float)a[2]);return null;},row,0f,0f);
h.mIsSwiping=false;h.mTouchedView=null;}
return true;},event);}
static ExpandableNotificationRow row(){return new ExpandableNotificationRow();}
static void start(NotificationSwipeHelper h,View row)throws Throwable{begin(h,row,100);h.mIsSwiping=true;}
public static void main(String[] ignored)throws Throwable{
production=new NotificationSwipeHooks(NotificationTest.class.getClassLoader(),c->{},()->enabled,(m,e)->{errors++;throw new AssertionError(m,e);});
production.install((c,m,h,t)->hooks.put(c+"#"+m,h),m->deopt++);check(deopt==4);
var h=new NotificationSwipeHelper();var r=row();start(h,r);check(r.listeners.size()==1);
move(h,2,120);near(r.translation,4.4f);near(h.lastProgress,4.4f);near(h.lastMove,4.4f);check(r.haptics==0);
move(h,2,155);near(r.translation,12.1f);move(h,2,156);near(r.translation,12.1f);check(r.haptics==1);
ValueAnimator.advance(1);near(r.translation,56);near(h.lastProgress,56);move(h,2,170);near(r.translation,70);near(h.lastMove,70);
move(h,2,150);near(r.translation,70);check(r.haptics==2);ValueAnimator.advance(1);near(r.translation,11);
h.fast=true;move(h,1,150);near(h.releaseTranslation,11);check(h.dismissals==0);check(h.snaps==1);check(r.listeners.isEmpty());
check(r.lastHaptic==31022);check(r.context.vibrator.count==0);check(ValueAnimator.running.isEmpty());
// The threshold changes animation, while the original distance and falsing checks still decide deletion.
h=new NotificationSwipeHelper();r=row();start(h,r);move(h,2,170);ValueAnimator.advance(1);move(h,1,170);check(h.dismissals==0);
h=new NotificationSwipeHelper();r=row();start(h,r);move(h,2,300);ValueAnimator.advance(1);move(h,1,300);check(h.dismissals==1);near(h.releaseTranslation,200);
h=new NotificationSwipeHelper();r=row();h.falseTouch=true;h.fast=true;start(h,r);move(h,2,170);ValueAnimator.advance(1);move(h,1,170);check(h.dismissals==0);
// Leftward swipes and repeated return/cross transitions use the same reversible motion.
h=new NotificationSwipeHelper();r=row();start(h,r);move(h,2,40);ValueAnimator.advance(1);near(r.translation,-60);
move(h,2,80);ValueAnimator.advance(1);near(r.translation,-4.4f);move(h,2,100);near(r.translation,0);
move(h,2,160);ValueAnimator.advance(.5f);float half=r.translation;move(h,2,150);near(r.translation,half);ValueAnimator.advance(1);near(r.translation,11);
check(r.haptics==4);move(h,3,150);check(h.dismissals==0);check(ValueAnimator.running.isEmpty());
// Ongoing, non-notification, long-press and menu drags retain native movement and no threshold feedback.
h=new NotificationSwipeHelper();r=row();r.dismissible=false;start(h,r);move(h,2,180);near(r.translation,30);check(r.haptics==0);check(r.listeners.isEmpty());
h=new NotificationSwipeHelper();View other=new View();start(h,other);move(h,2,180);near(other.translation,30);check(other.haptics==0);
for(int kind=0;kind<2;kind++){h=new NotificationSwipeHelper();r=row();start(h,r);if(kind==0)h.mLongPressSent=true;else h.mMenuRowIntercepting=true;
move(h,2,180);near(r.translation,0);check(r.haptics==0);check(r.listeners.isEmpty());}
// Eligibility can change during a drag; do not keep applying dismissible-row displacement.
h=new NotificationSwipeHelper();r=row();start(h,r);move(h,2,160);ValueAnimator.advance(1);r.dismissible=false;
move(h,2,180);near(r.translation,30);check(ValueAnimator.running.isEmpty());check(r.listeners.isEmpty());
// Turning the feature off cancels catch-up and restores native displacement immediately.
h=new NotificationSwipeHelper();r=row();start(h,r);move(h,2,160);enabled=false;production.refresh();near(r.translation,60);
check(ValueAnimator.running.isEmpty());check(r.listeners.isEmpty());move(h,2,170);near(r.translation,70);check(r.haptics==1);move(h,1,170);enabled=true;
// Off from the start preserves even the native short-fling release decision.
h=new NotificationSwipeHelper();r=row();enabled=false;h.fast=true;start(h,r);move(h,2,120);near(r.translation,20);move(h,1,120);check(h.dismissals==1);check(r.haptics==0);enabled=true;
// Detachment/reset/programmatic snap cancels pending updates without affecting other rows.
for(int kind=0;kind<4;kind++){h=new NotificationSwipeHelper();r=row();start(h,r);move(h,2,160);check(!ValueAnimator.running.isEmpty());
NotificationSwipeHelper current=h;
if(kind==0)r.detach();if(kind==1)call(BASE,"resetSwipeStates",h,a->{current.resetSwipeStates((Boolean)a[0]);return null;},true);
if(kind==2)call(HELPER,"snapChild",h,a->{current.snapChild((View)a[0],(Float)a[1],(Float)a[2]);return null;},r,0f,0f);
if(kind==3)move(h,5,160);check(ValueAnimator.running.isEmpty());float saved=r.translation;ValueAnimator.advance(1);near(r.translation,saved);check(r.listeners.isEmpty());}
// Helpers and rows cannot share animation state; row reuse starts from a clean threshold.
h=new NotificationSwipeHelper();r=row();var second=new NotificationSwipeHelper();var r2=row();start(h,r);start(second,r2);
move(h,2,160);move(second,2,120);ValueAnimator.advance(1);near(r.translation,60);near(r2.translation,4.4f);check(r2.haptics==0);
move(h,3,160);move(second,3,120);start(h,r);move(h,2,120);near(r.translation,4.4f);check(r.listeners.size()==1);move(h,1,120);check(r.listeners.isEmpty());
check(errors==0);System.out.println("PASS: "+checks+" production notification eligibility/segments/native release/lifecycle checks");
}}
''',
})

with tempfile.TemporaryDirectory(dir='/private/tmp') as tmp:
    root = Path(tmp)
    for name in ('NotificationSwipeHooks', 'MediaSwipeMotion'):
        sources[f'dev/rikumi/flymemod/{name}.java'] = Path(
            f'app/src/main/java/dev/rikumi/flymemod/{name}.java').read_text()
    for name, text in sources.items():
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
    jdk = Path('/opt/homebrew/opt/openjdk@21/bin')
    subprocess.run([str(jdk / 'javac'), '-d', str(root / 'classes'),
                    *[str(p) for p in root.rglob('*.java')]], check=True)
    subprocess.run([str(jdk / 'java'), '-cp', str(root / 'classes'),
                    'dev.rikumi.flymemod.NotificationTest'], check=True)
