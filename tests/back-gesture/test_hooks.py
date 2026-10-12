"""Run the actual hook classes against Flyme 16260625 control-flow fixtures.

Admission, ML release, pending MOVE forwarding and confirmation bodies are reduced
from reference-apks/small-window-meizu22-12.6. Android rendering/Binder are omitted;
this verifies hook behavior and reflection contracts, not ART or hardware input.
"""
from pathlib import Path
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
JAVA = Path(os.environ.get('JAVA_HOME', '/opt/homebrew/opt/openjdk@21')) / 'bin'
SOURCES = {
'android/graphics/Point.java': 'package android.graphics; public class Point { public int x=1200,y=2670; }',
'android/os/VibrationEffect.java': 'package android.os; public class VibrationEffect { public static VibrationEffect get(int id){return new VibrationEffect();} }',
'android/os/Vibrator.java': 'package android.os; public class Vibrator { public int count; public void vibrate(VibrationEffect e){count++;} }',
'android/util/DisplayMetrics.java': 'package android.util; public class DisplayMetrics { public float density=3f; }',
'android/content/res/Resources.java': 'package android.content.res; public class Resources { public android.util.DisplayMetrics getDisplayMetrics(){return new android.util.DisplayMetrics();} }',
'android/content/Context.java': 'package android.content; public class Context { public android.content.res.Resources getResources(){return new android.content.res.Resources();} }',
'android/content/SharedPreferences.java': '''package android.content; public interface SharedPreferences {
boolean getBoolean(String key, boolean fallback); default int getInt(String key,int fallback){return dev.rikumi.flymemod.HookRegressionTest.widthDp;} }''',
'android/graphics/PointF.java': '''package android.graphics; public class PointF {
public float x,y; public PointF(){ } public PointF(float x,float y){this.x=x;this.y=y;} }''',
'android/view/InputEvent.java': 'package android.view; public class InputEvent {}',
'android/provider/DeviceConfig.java': '''package android.provider; public class DeviceConfig {
public static class Properties {} }''',
'android/view/MotionEvent.java': '''package android.view; public class MotionEvent extends InputEvent {
public static final int ACTION_DOWN=0,ACTION_UP=1,ACTION_MOVE=2,ACTION_CANCEL=3,ACTION_POINTER_DOWN=5;
public int action; public float x,y,offsetX,offsetY; public long time; public float[][] history=new float[0][];
public MotionEvent(int a,float x,float y,long t){action=a;this.x=x;this.y=y;time=t;}
public int getActionMasked(){return action;} public float getX(){return x;} public float getY(){return y;}
public float getRawX(){return x+offsetX;} public float getRawY(){return y+offsetY;}
public long getDownTime(){return time;} public int getHistorySize(){return history.length;}
public float getHistoricalX(int i){return history[i][0];} public float getHistoricalY(int i){return history[i][1];}
}''',
'dev/rikumi/flymemod/SignalHooks.java': '''package dev.rikumi.flymemod; import java.lang.reflect.Method;
class SignalHooks {
interface Chain { Object getThisObject(); Object getArg(int i); Object proceed() throws Throwable; }
interface Hook { Object intercept(Chain c) throws Throwable; }
interface Installer { void hook(String owner,String name,Hook hook,Class<?>... parameters); }
}''',
'dev/rikumi/flymemod/ModuleSettings.java': '''package dev.rikumi.flymemod; class ModuleSettings {
static final String BACK_GESTURE_REMOVE_SMART="smart",BACK_GESTURE_WIDTH_ENABLED="width",BACK_GESTURE_WIDTH_DP="dp"; static final int BACK_GESTURE_WIDTH_MAX_DEFAULT=64,BACK_GESTURE_WIDTH_DEFAULT=32; }''',
'dev/rikumi/flymemod/HookRuntime.java': '''package dev.rikumi.flymemod;
import java.util.*; import java.lang.reflect.*;
public class HookRuntime {
public interface Call { Object run() throws Throwable; }
static final Map<String,SignalHooks.Hook> hooks=new HashMap<>();
static final Set<String> deoptimized=new HashSet<>();
static void install(String owner,String name,SignalHooks.Hook hook,Class<?>... params){
try {Class.forName(owner).getDeclaredMethod(name,params);} catch(Exception e){throw new AssertionError(e);}
SignalHooks.Hook previous=hooks.get(owner+"#"+name);
hooks.put(owner+"#"+name,previous==null?hook:chain->hook.intercept(new SignalHooks.Chain(){
public Object getThisObject(){return chain.getThisObject();}public Object getArg(int i){return chain.getArg(i);}
public Object proceed()throws Throwable{return previous.intercept(chain);}})); }
public static Object call(Object owner,Class<?> type,String name,Object[] args,Call nativeBody){
try { SignalHooks.Hook hook=hooks.get(type.getName()+"#"+name);
if(hook==null)return nativeBody.run();
return hook.intercept(new SignalHooks.Chain(){public Object getThisObject(){return owner;}
public Object getArg(int i){return args[i];} public Object proceed()throws Throwable{return nativeBody.run();}});
} catch(Throwable t){throw new AssertionError(t);}}
}''',
'com/android/systemui/navigationbar/gestural/EdgeBackGestureHandler.java': '''package com.android.systemui.navigationbar.gestural;
import android.content.Context; import android.graphics.PointF; import android.view.*;
import com.flyme.systemui.navigationbar.gestural.EdgeBackView; import dev.rikumi.flymemod.HookRuntime;
public class EdgeBackGestureHandler {
public android.graphics.Point mDisplaySize=new android.graphics.Point(); public int mLeftInset,mRightInset; public boolean mEdgeBackVibrateFeedBack=true; public android.os.Vibrator mVibrator=new android.os.Vibrator();
public Context mContext=new Context(); public boolean mAllowGesture,mThresholdCrossed,mIsGestureHandlingEnabled=true,mUseMLModel=true,mIsOnLeftEdge,mIsTrackpadThreeFingerSwipe;
public PointF mDownPoint=new PointF(); public float mTouchSlop=18;
public Object mBackAnimation; public EdgeBackView mEdgeBackPlugin=new EdgeBackView(); public Ui mUiThreadContext=new Ui();
public EdgeBackGestureHandler(){mEdgeBackPlugin.onBack=()->{if(mEdgeBackVibrateFeedBack)mVibrator.vibrate(android.os.VibrationEffect.get(31025));};}
public int mEdgeWidthLeft=72,mEdgeWidthRight=72,mMLEnableWidth=36; public int predictions,pilfered;
public boolean nativeMLSetting=true,excluded,pip,desktop,overlay,wrongDisplay; public float score=0;
public Model model=new Model(); public Model released; public static class Model{public boolean isReleased;void release(){isReleased=true;}}
public static class Ui { public java.util.concurrent.Executor getExecutor(){return Runnable::run;} }
public void updateCurrentUserResources(){HookRuntime.call(this,getClass(),"updateCurrentUserResources",new Object[0],()->{mEdgeWidthLeft=72;mEdgeWidthRight=72;mMLEnableWidth=36;return null;});}
public void updateMLModelState(){HookRuntime.call(this,getClass(),"updateMLModelState",new Object[0],()->{
boolean z=mIsGestureHandlingEnabled && nativeMLSetting;
if(z==mUseMLModel)return null; mUseMLModel=z;
if(!z && model!=null){released=model;model.release();model=null;}
if(z && model==null)model=new Model(); return null;});}
public void finishLoading(Model candidate){if(!mUseMLModel)candidate.release();else model=candidate;}
public int getBackGesturePredictionsCategory(int x,int y,int app){return (Integer)HookRuntime.call(this,getClass(),
"getBackGesturePredictionsCategory",new Object[]{x,y,app},()->{if(model==null||app==-1)return -1;
predictions++; return score==-1?-1:score>=0.8f?1:0;});}
public synchronized boolean isWithinTouchRegion(MotionEvent e){return (Boolean)HookRuntime.call(this,getClass(),
"isWithinTouchRegion",new Object[]{e},()->{
int x=(int)e.getX(); boolean admitted=x<mEdgeWidthLeft || x>=1200-mEdgeWidthRight;
if(pip||desktop||overlay||wrongDisplay)return false;
if(admitted && x>=mMLEnableWidth && x<1200-mMLEnableWidth && mUseMLModel){
int result=getBackGesturePredictionsCategory(x,(int)e.getY(),1);if(result!=-1)admitted=result==1;}
return admitted && !excluded;});}
public boolean isWithinInsets(MotionEvent e){return e.getY()<2595&&(e.getX()<=mEdgeWidthLeft*2||e.getX()>=1200-mEdgeWidthRight*2);}
public void emit(MotionEvent e){onMotionEvent(e);}
private void onMotionEvent(MotionEvent e){HookRuntime.call(this,getClass(),"onMotionEvent",new Object[]{e},()->{
int a=e.getActionMasked(); if(a==0){mIsOnLeftEdge=e.x<=mEdgeWidthLeft;mEdgeBackPlugin.mIsLeftPanel=mIsOnLeftEdge;mAllowGesture=isWithinInsets(e)&&isWithinTouchRegion(e);
mDownPoint=new PointF(e.x,e.y);mThresholdCrossed=false;if(mAllowGesture)mEdgeBackPlugin.onMotionEvent(e);}
else if(mAllowGesture){if(a==5&&!mThresholdCrossed)cancelGesture(e);else {
if(a==2&&!mThresholdCrossed&&Math.abs(e.x-mDownPoint.x)>mTouchSlop){pilferPointers();mThresholdCrossed=true;}
mEdgeBackPlugin.onMotionEvent(e);}}
if(a==1||a==3)mAllowGesture=false;return null;});}
private void onInputEvent(InputEvent e){onMotionEvent((MotionEvent)e);}
public void lambda$updateGestureEvent$18(MotionEvent e){onMotionEvent(e);}
public void updateIsEnabledInner(){updateMLModelState();}
public void pilferPointers(){pilfered++;}
public void cancelGesture(MotionEvent e){mAllowGesture=false;mEdgeBackPlugin.onMotionEvent(new MotionEvent(3,e.x,e.y,e.time));}
public class SystemUIGestureListener {
public int onGesturePreTriggerBefore(int type,MotionEvent e){return (Integer)HookRuntime.call(this,getClass(),"onGesturePreTriggerBefore",new Object[]{type,e},()->isWithinInsets(e)&&isWithinTouchRegion(e)?0:-1);}
public void onGesturePreTrigger(int type,MotionEvent e){HookRuntime.call(this,getClass(),"onGesturePreTrigger",new Object[]{type,e},()->{emit(e);return null;});}
public void onGestureTriggered(int type,MotionEvent e){emit(e);}
public void onGestureCanceled(int type){HookRuntime.call(this,getClass(),"onGestureCanceled",new Object[]{type},()->{
cancelGesture(new MotionEvent(3,mDownPoint.x,mDownPoint.y,0));return null;});}
}
}''',
'com/android/systemui/navigationbar/gestural/EdgeBackGestureHandler$4.java': '''package com.android.systemui.navigationbar.gestural;
public class EdgeBackGestureHandler$4 {public void onPropertiesChanged(android.provider.DeviceConfig.Properties properties){} }''',
'com/flyme/systemui/navigationbar/gestural/EdgeBackView.java': '''package com.flyme.systemui.navigationbar.gestural;
import android.content.Context;import android.view.MotionEvent;import dev.rikumi.flymemod.HookRuntime;
public class EdgeBackView {
public float mStartX,mStartY,mPreviousTouchTranslation,mTotalTouchDelta,mSwipeThreshold=20,mMinDeltaForSwitch=32;
public Runnable onBack=()->{};
public boolean mTriggerBack,mDragSlopPassed,mIsLeftPanel=true;public int backs,cancels;public Context getContext(){return new Context();}
public void onMotionEvent(MotionEvent e){HookRuntime.call(this,getClass(),"onMotionEvent",new Object[]{e},()->{
if(e.action==0){mStartX=e.x;mStartY=e.y;mTriggerBack=false;mDragSlopPassed=false;mPreviousTouchTranslation=0;mTotalTouchDelta=0;}
else if(e.action==2)handleMoveEvent(e);else if(e.action==1){if(mTriggerBack)triggerBack();else cancelBack();}
else if(e.action==3){mTriggerBack=false;cancels++;}return null;});}
public void triggerBack(){HookRuntime.call(this,getClass(),"triggerBack",new Object[0],()->{backs++;onBack.run();return null;});}
public void cancelBack(){mTriggerBack=false;cancels++;}
private void handleMoveEvent(MotionEvent e){whetherTriggerBack(e);}
public void whetherTriggerBack(MotionEvent e){HookRuntime.call(this,getClass(),"whetherTriggerBack",new Object[]{e},()->{
float abs=Math.abs(e.x-mStartX),delta=abs-mPreviousTouchTranslation;
if(Math.abs(delta)>0){if(Math.signum(delta)==Math.signum(mTotalTouchDelta))mTotalTouchDelta+=delta;else mTotalTouchDelta=delta;}
mPreviousTouchTranslation=abs;
if(!mDragSlopPassed&&abs>mSwipeThreshold){mDragSlopPassed=true;mTriggerBack=true;}
if(Math.abs(mTotalTouchDelta)>mMinDeltaForSwitch)mTriggerBack=mTotalTouchDelta>0;return null;});}
}''',
'com/android/server/policy/gesture/GestureListenerBase.java': '''package com.android.server.policy.gesture;
public class GestureListenerBase {protected enum GestureState{IDLE,PENDING_CHECK,CANCELED,TRIGGERED}
protected android.content.Context mContext=new android.content.Context();
protected GestureState mGestureState=GestureState.IDLE;protected long mMotionDownTime;
public String state(){return mGestureState.name();}public void expire(){mMotionDownTime=0;}}
''',
'com/android/server/policy/gesture/RecentGestureListener.java': '''package com.android.server.policy.gesture;
import android.view.MotionEvent;import dev.rikumi.flymemod.HookRuntime;
import com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler.SystemUIGestureListener;
public class RecentGestureListener extends GestureListenerBase {
private int GESTURE_VALID_LEFT_OFFSET=72;
private int RECENT_GESTURE_TOUCH_SLOP=10,mDeviceBottomTouchSlop=15;
private enum RecentsDir{HORIZONTAL_LEFT,HORIZONTAL_RIGHT,HORIZONTAL_BOTTOM}
private RecentsDir mDir=RecentsDir.HORIZONTAL_LEFT;private float mRecentPanelDownPosX,mRecentPanelDownPosY;
public SystemUIGestureListener client;public int forwarded;
public int interceptMotionBeforeQueueing(MotionEvent e,int flags){return (Integer)HookRuntime.call(this,RecentGestureListener.class,
"interceptMotionBeforeQueueing",new Object[]{e,flags},()->{
if(e.action==0){mRecentPanelDownPosX=e.getRawX();mRecentPanelDownPosY=e.getRawY();mMotionDownTime=System.currentTimeMillis();
mDir=e.getRawX()<GESTURE_VALID_LEFT_OFFSET?RecentsDir.HORIZONTAL_LEFT:RecentsDir.HORIZONTAL_RIGHT;
boolean area=(e.getRawX()<GESTURE_VALID_LEFT_OFFSET||e.getRawX()>1200-GESTURE_VALID_LEFT_OFFSET)&&e.getRawY()>667&&e.getRawY()<=2557;
mGestureState=area&&client.onGesturePreTriggerBefore(type(),e)==0?GestureState.PENDING_CHECK:GestureState.IDLE;
if(mGestureState==GestureState.PENDING_CHECK)client.onGesturePreTrigger(type(),e);}
else if(e.action==2){if(mGestureState==GestureState.PENDING_CHECK)checkRecentsGesture(e);
if(mGestureState==GestureState.TRIGGERED){forwarded++;client.onGestureTriggered(type(),e);}}
else if(e.action==1||e.action==3){if(mGestureState==GestureState.TRIGGERED&&e.action==1)client.onGestureTriggered(type(),e);
else if(mGestureState==GestureState.CANCELED||mGestureState==GestureState.PENDING_CHECK)client.onGestureCanceled(type());
mGestureState=GestureState.IDLE;}
else if(e.action==5&&mGestureState!=GestureState.TRIGGERED)mGestureState=GestureState.CANCELED;
return mGestureState==GestureState.TRIGGERED?0:1;});}
private int type(){return mDir==RecentsDir.HORIZONTAL_LEFT?3:4;}
private void checkRecentsGesture(MotionEvent e){HookRuntime.call(this,RecentGestureListener.class,"checkRecentsGesture",new Object[]{e},()->{
if(System.currentTimeMillis()-mMotionDownTime>300){mGestureState=GestureState.CANCELED;return null;}
float dx=e.getRawX()-mRecentPanelDownPosX,dy=e.getRawY()-mRecentPanelDownPosY;
boolean inward=mDir==RecentsDir.HORIZONTAL_LEFT?dx>0:dx<0;
if(Math.abs(dx)>RECENT_GESTURE_TOUCH_SLOP)mGestureState=inward&&Math.toDegrees(Math.atan2(Math.abs(dy),Math.abs(dx)))<64
?GestureState.TRIGGERED:GestureState.CANCELED;return null;});}
}''',
'com/android/server/policy/gesture/LeftRightGestureListener.java': '''package com.android.server.policy.gesture;
public class LeftRightGestureListener extends RecentGestureListener {}''',
'com/android/server/policy/gesture/SystemGesture.java': '''package com.android.server.policy.gesture;
public class SystemGesture {public int interceptMotionBeforeQueueing(android.view.MotionEvent e,int flags){return 1;} }''',
'dev/rikumi/flymemod/HookRegressionTest.java': '''package dev.rikumi.flymemod;
import android.graphics.PointF;import android.view.MotionEvent;
import com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler;
import com.android.server.policy.gesture.LeftRightGestureListener;
public class HookRegressionTest {
public static int widthDp=40; static boolean enabled=true;static int checks;static long sequence=100;
static void require(boolean result,String name){checks++;if(!result)throw new AssertionError(name);}
static MotionEvent event(int a,float x,float y,long t){return new MotionEvent(a,x,y,t);}
static EdgeBackGestureHandler handler(){EdgeBackGestureHandler h=new EdgeBackGestureHandler();h.updateMLModelState();return h;}
static LeftRightGestureListener service(EdgeBackGestureHandler h){LeftRightGestureListener s=new LeftRightGestureListener();s.client=h.new SystemUIGestureListener();return s;}
static void emit(LeftRightGestureListener s,int action,float x,float y,long t){s.interceptMotionBeforeQueueing(event(action,x,y,t),0);}
public static void main(String[] args)throws Exception {
// Prove the native 12dp boundary only rejects when the classifier actually returns zero.
EdgeBackGestureHandler nativeH=new EdgeBackGestureHandler();
for(int x:new int[]{18,1182})require(nativeH.isWithinTouchRegion(event(0,x,1400,1)),"native inner 12dp");
for(int x:new int[]{36,54,1146,1163})require(!nativeH.isWithinTouchRegion(event(0,x,1400,2)),"native ML rejection outside 12dp");
nativeH.model=null;require(nativeH.isWithinTouchRegion(event(0,54,1400,3)),"native no prediction is geometrical fallback");
BackGestureSmartHooks hooks=new BackGestureSmartHooks(HookRegressionTest.class.getClassLoader(),c->{},()->enabled,(m,t)->{});
BackGestureWidthHooks widths=new BackGestureWidthHooks(HookRegressionTest.class.getClassLoader(),c->{},()->enabled,()->widthDp,()->enabled,(m,t)->{});
widths.install(HookRuntime::install,m->{});
hooks.install(HookRuntime::install,m->HookRuntime.deoptimized.add(m.getDeclaringClass().getName()+"#"+m.getName()));
BackGestureServiceHooks.install(HookRegressionTest.class.getClassLoader(),(k,d)->enabled,HookRuntime::install,
m->HookRuntime.deoptimized.add(m.getDeclaringClass().getName()+"#"+m.getName()),(m,t)->{});
EdgeBackGestureHandler h=handler();require(!h.mUseMLModel&&h.model==null&&h.released.isReleased,"ML released");
require(h.mIsGestureHandlingEnabled,"gesture handling restored after native model update");
EdgeBackGestureHandler.Model pending=new EdgeBackGestureHandler.Model();h.finishLoading(pending);
require(pending.isReleased&&h.model==null,"in-flight model cannot attach");
h.mUseMLModel=true;h.model=new EdgeBackGestureHandler.Model();require(h.getBackGesturePredictionsCategory(54,1400,1)==-1&&h.predictions==0,"defensive no prediction");
h.updateMLModelState();for(int x:new int[]{18,36,54,71,1182,1164,1146,1129})require(h.isWithinTouchRegion(event(0,x,1400,++sequence)),"all native edge positions");
require(h.mEdgeWidthLeft==120&&h.mEdgeWidthRight==120&&h.mMLEnableWidth==120,"fixed 40dp on both edges");
h.excluded=true;require(!h.isWithinTouchRegion(event(0,54,1400,++sequence)),"exclusion preserved");h.excluded=false;
h.pip=true;require(!h.isWithinTouchRegion(event(0,54,1400,++sequence)),"PiP preserved");h.pip=false;
h.desktop=true;require(!h.isWithinTouchRegion(event(0,54,1400,++sequence)),"desktop preserved");h.desktop=false;
h.overlay=true;require(!h.isWithinTouchRegion(event(0,54,1400,++sequence)),"overlay preserved");h.overlay=false;
h.wrongDisplay=true;require(!h.isWithinTouchRegion(event(0,54,1400,++sequence)),"display preserved");h.wrongDisplay=false;
// Sweep both bands and the inclusive +/-5 first-frame slopes; later slope is deliberately >5.
for(int x:new int[]{1,35,36,54,71,80,110,119,1199,1165,1164,1146,1129,1120,1090,1081})for(float ratio:new float[]{-5,-2,0,2,5}){
h=handler();LeftRightGestureListener s=service(h);long t=++sequence;float sign=x<600?1:-1;
emit(s,0,x,1400,t);emit(s,2,x+sign,1400+ratio,t);
require(s.forwarded==0&&s.state().equals("PENDING_CHECK"),"first pending MOVE not forwarded");
emit(s,2,x+24*sign,1400+24*ratio,t);require(s.state().equals("TRIGGERED"),"first verdict retained after later angle change");
emit(s,2,x+160*sign,1400+24*ratio,t);emit(s,1,x+160*sign,1400+24*ratio,t);require(h.mEdgeBackPlugin.backs==1,"service -> handler -> view -> back for slope "+ratio+" x="+x);
}
for(float ratio:new float[]{5.001f,-5.001f}){h=handler();LeftRightGestureListener s=service(h);long t=++sequence;
emit(s,0,54,1400,t);emit(s,2,74,1400+20*ratio,t);emit(s,2,120,1400,t);emit(s,1,120,1400,t);
require(h.mEdgeBackPlugin.backs==0,"invalid first angle cannot be repaired by later horizontal move");}
h=handler();LeftRightGestureListener s=service(h);long t=++sequence;emit(s,0,54,1400,t);emit(s,2,54,1400,t);
MotionEvent batched=event(2,56,1421,t);batched.history=new float[][]{{54,1400},{54,1402},{58,1420}};
s.interceptMotionBeforeQueueing(batched,0);emit(s,2,160,1421,t);emit(s,1,160,1421,t);require(h.mEdgeBackPlugin.backs==1,"earliest history beyond slop controls angle");
for(int x:new int[]{54,1146}){h=handler();s=service(h);t=++sequence;float sign=x<600?1:-1;
emit(s,0,x,1400,t);emit(s,2,x+4*sign,1420,t);emit(s,2,x,1450,t);emit(s,2,x+160*sign,1450,t);emit(s,1,x+160*sign,1450,t);
require(h.mEdgeBackPlugin.backs==1,"valid first inward direction cannot be rechecked at threshold");}
for(int x:new int[]{54,1146}){h=handler();s=service(h);t=++sequence;float sign=x<600?1:-1;
emit(s,0,x,1400,t);emit(s,2,x-20*sign,1400,t);emit(s,2,x+80*sign,1400,t);emit(s,1,x+80*sign,1400,t);
require(h.mEdgeBackPlugin.backs==0,"outward first direction cannot be repaired by later inward move");}
MotionEvent raw=event(2,2,3,t);raw.offsetX=100;raw.offsetY=200;raw.history=new float[][]{{1,1},{2,2}};
PointF first=BackGestureMotion.firstDisplacement(raw,101,201,true,0f);require(first.x==1&&first.y==1,"historical coordinates converted to raw");
// Direct input/CTS path and view must share one locked verdict.
h=handler();t=++sequence;h.emit(event(0,54,1400,t));h.emit(event(2,54,1402,t));h.emit(event(2,58,1420,t));h.emit(event(2,56,1421,t));h.emit(event(2,160,1421,t));h.emit(event(1,160,1421,t));
require(h.mEdgeBackPlugin.backs==1&&h.pilfered==1,"direct first angle retained, radial threshold crossed");
h=handler();t=++sequence;h.emit(event(0,54,1400,t));h.emit(event(2,58,1424,t));h.emit(event(2,130,1400,t));h.emit(event(1,130,1400,t));
require(h.mEdgeBackPlugin.backs==0,"direct invalid first frame canceled");
h=handler();s=service(h);t=++sequence;emit(s,0,54,1400,t);emit(s,2,134,1400,t);emit(s,2,74,1400,t);emit(s,1,74,1400,t);
require(h.mEdgeBackPlugin.backs==0,"returning inside edge cancels confirmed gesture");
// Tiny vertical/outward settling samples must not cancel either edge band.
for(int x:new int[]{18,54,1182,1146}){h=handler();s=service(h);t=++sequence;float sign=x<600?1:-1;
emit(s,0,x,1400,t);emit(s,2,x-sign*0.2f,1402.1f,t);
require(s.state().equals("PENDING_CHECK"),"sub-slop jitter stays pending");
emit(s,2,x+20*sign,1440,t);emit(s,2,x+160*sign,1440,t);emit(s,1,x+160*sign,1440,t);
require(h.mEdgeBackPlugin.backs==1,"settling jitter then valid diagonal returns");}
for(int dp:new int[]{32,64,0}) {
widthDp=dp;widths.refresh();h=handler();s=service(h);t=++sequence;
float x=dp==0?1:dp*3-1;
emit(s,0,x,1400,t);emit(s,2,x+80,1400,t);emit(s,1,x+80,1400,t);
require(h.mEdgeBackPlugin.backs==(dp==0?0:1),"configured width dp="+dp);
}
widthDp=40;widths.refresh();
for(int x:new int[]{54,1146}) {
h=handler();s=service(h);t=++sequence;float outside=x<600?121:1079,inside=x<600?119:1081;
emit(s,0,x,1400,t);emit(s,2,outside,1400,t);
require(h.mVibrator.count==1&&h.mEdgeBackPlugin.mTriggerBack,"first crossing vibrates and arms");
emit(s,2,outside,1400,t);require(h.mVibrator.count==1,"remaining outside does not repeat vibration");
emit(s,2,inside,1400,t);require(!h.mEdgeBackPlugin.mTriggerBack,"reentry disarms");
emit(s,2,outside,1400,t);require(h.mVibrator.count==2,"second crossing vibrates");
emit(s,1,inside,1400,t);require(h.mEdgeBackPlugin.backs==0&&h.mVibrator.count==2,"UP inside cancels without vibration");
h=handler();s=service(h);t=++sequence;emit(s,0,x,1400,t);emit(s,2,outside,1400,t);emit(s,1,outside,1400,t);
require(h.mEdgeBackPlugin.backs==1&&h.mVibrator.count==1,"UP outside returns without extra vibration");
}
// Both edges inside the native 12dp band use the same direction and boundary verdict.
for(int x:new int[]{1,18,35,1199,1182,1165}) {
float sign=x<600?1:-1,inside=x<600?119:1081,outside=x<600?121:1079;
h=handler();s=service(h);t=++sequence;emit(s,0,x,1400,t);emit(s,2,inside,1400,t);emit(s,1,inside,1400,t);
require(h.mEdgeBackPlugin.backs==0&&h.mVibrator.count==0,"inner 12dp stays unarmed inside region");
h=handler();s=service(h);t=++sequence;emit(s,0,x,1400,t);emit(s,2,x+sign,1430,t);emit(s,2,outside,1430,t);emit(s,1,outside,1430,t);
require(h.mEdgeBackPlugin.backs==0&&h.mVibrator.count==0,"inner 12dp vertical first move rejected");
h=handler();s=service(h);t=++sequence;emit(s,0,x,1400,t);emit(s,2,outside,1400,t);emit(s,2,inside,1400,t);emit(s,1,inside,1400,t);
require(h.mEdgeBackPlugin.backs==0&&h.mVibrator.count==1,"inner 12dp reentry cancels return");
h=handler();s=service(h);t=++sequence;emit(s,0,x,1400,t);emit(s,2,outside,1400,t);emit(s,1,outside,1400,t);
require(h.mEdgeBackPlugin.backs==1&&h.mVibrator.count==1,"inner 12dp valid crossing returns once");
}
// Explicit CANCEL still cancels a confirmed sequence.
h=handler();s=service(h);t=++sequence;emit(s,0,54,1400,t);emit(s,2,134,1400,t);emit(s,3,74,1400,t);
require(h.mEdgeBackPlugin.backs==0,"explicit CANCEL cancels confirmed gesture");
h=handler();s=service(h);t=++sequence;emit(s,0,54,1400,t);emit(s,2,55,1404,t);emit(s,1,55,1404,t);
require(h.mEdgeBackPlugin.backs==0,"minimum displacement remains");
h=handler();s=service(h);t=++sequence;emit(s,0,54,1400,t);s.expire();emit(s,2,100,1400,t);emit(s,1,100,1400,t);
require(h.mEdgeBackPlugin.backs==0,"300ms pending timeout remains");
h=handler();s=service(h);t=++sequence;emit(s,0,54,1400,t);emit(s,5,55,1400,t);emit(s,1,55,1400,t);
require(h.mEdgeBackPlugin.backs==0,"pending multi-finger cancellation remains");
h=handler();s=service(h);t=++sequence;emit(s,0,121,1400,t);emit(s,2,150,1400,t);emit(s,1,150,1400,t);
require(h.mEdgeBackPlugin.backs==0,"outside native band stays rejected");
h=handler();s=service(h);t=++sequence;emit(s,0,54,500,t);emit(s,2,150,500,t);emit(s,1,150,500,t);
require(h.mEdgeBackPlugin.backs==0,"native portrait upper limit remains");
h=handler();s=service(h);t=++sequence;emit(s,0,54,2600,t);emit(s,2,150,2600,t);emit(s,1,150,2600,t);
require(h.mEdgeBackPlugin.backs==0,"native bottom limit remains");
// Cancel service marker, then verify the next direct gesture does not inherit acceptance.
h=handler();s=service(h);t=++sequence;emit(s,0,54,1400,t);emit(s,3,54,1400,t);t=++sequence;
h.emit(event(0,54,1400,t));h.emit(event(2,58,1424,t));h.emit(event(1,55,1406,t));require(h.mEdgeBackPlugin.backs==0,"service marker cleared");
enabled=false;widths.refresh();hooks.refresh();require(h.mUseMLModel&&h.model!=null,"toggle off restores native model");
require(!h.isWithinTouchRegion(event(0,54,1400,++sequence))&&h.predictions>0,"toggle off restores native prediction rejection");
h=handler();s=service(h);t=++sequence;emit(s,0,18,1400,t);emit(s,2,38,1500,t);emit(s,1,38,1500,t);
require(h.mEdgeBackPlugin.backs==0,"toggle off restores native 64-degree server limit");
h=handler();s=service(h);t=++sequence;emit(s,0,18,1400,t);emit(s,2,98,1400,t);emit(s,2,38,1400,t);emit(s,1,38,1400,t);
require(h.mEdgeBackPlugin.backs==0,"toggle off restores native accumulated reversal cancellation");
enabled=true;hooks.refresh();require(!h.mUseMLModel&&h.model==null,"toggle on disables native model again");
for(String method:new String[]{"onMotionEvent","onInputEvent","lambda$updateGestureEvent$18","isWithinTouchRegion","updateIsEnabledInner"})
require(HookRuntime.deoptimized.contains(EdgeBackGestureHandler.class.getName()+"#"+method),"deoptimization "+method);
require(HookRuntime.deoptimized.contains(EdgeBackGestureHandler.class.getName()+"$4#onPropertiesChanged"),"ML config callback deoptimized");
System.out.println("PASS: "+checks+" actual-hook checks; active-model rejection branch reproduced, both edge bands and +/-1:5 accepted, boundary haptics and reentry cancellation, native exclusions and off state retained");
}
}''',
}


def test_hooks():
    with tempfile.TemporaryDirectory(prefix='flyme-back-hooks-') as folder:
        base = Path(folder)
        paths = []
        for name, source in SOURCES.items():
            target = base / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(source)
            paths.append(str(target))
        names = ['BackGestureDirectionPolicy', 'BackGestureMotion', 'BackGestureServiceHooks', 'BackGestureSmartHooks', 'BackGestureWidthHooks']
        paths += [str(ROOT / f'app/src/main/java/dev/rikumi/flymemod/{name}.java') for name in names]
        subprocess.run([str(JAVA / 'javac'), '-d', str(base / 'classes'), *paths], check=True)
        subprocess.run([str(JAVA / 'java'), '-cp', str(base / 'classes'), 'dev.rikumi.flymemod.HookRegressionTest'], check=True)


if __name__ == '__main__':
    test_hooks()
