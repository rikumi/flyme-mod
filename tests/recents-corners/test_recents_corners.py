"""Compile the actual settings and hooks with host platform/Launcher doubles, then drive registered hooks."""
from pathlib import Path
import subprocess
import tempfile
root = Path(__file__).resolve().parents[2]
sources = {
'android/content/Context.java': '''package android.content;
import android.content.res.Resources; import android.view.WindowManager;
public class Context { public Resources resources=new Resources(); public WindowManager manager=new WindowManager();
 public Resources getResources(){return resources;} public <T> T getSystemService(Class<T> c){return c.cast(manager);} }
''',
'android/content/res/Resources.java': '''package android.content.res;
public class Resources {public static class Metrics{public float density=482/160f;} public Metrics metrics=new Metrics(); public Metrics getDisplayMetrics(){return metrics;}}
''',
'android/content/SharedPreferences.java': '''package android.content; public interface SharedPreferences {
 boolean contains(String k); int getInt(String k,int d); Editor edit(); interface Editor {Editor putInt(String k,int v);boolean commit();}}
''',
'android/view/RoundedCorner.java': '''package android.view; public class RoundedCorner {int radius;public RoundedCorner(int r){radius=r;}public int getRadius(){return radius;}}''',
'android/view/WindowInsets.java': '''package android.view; public class WindowInsets {public int[] radii={130,130,130,130};public RoundedCorner getRoundedCorner(int i){return radii[i]<0?null:new RoundedCorner(radii[i]);}}''',
'android/view/WindowMetrics.java': '''package android.view; public class WindowMetrics {public WindowInsets insets=new WindowInsets();public float density=482/160f;public float getDensity(){return density;}public WindowInsets getWindowInsets(){return insets;}}''',
'android/view/WindowManager.java': '''package android.view; public class WindowManager {public WindowMetrics metrics=new WindowMetrics();public boolean fail;public WindowMetrics getMaximumWindowMetrics(){if(fail)throw new IllegalStateException();return metrics;}}''',
'android/graphics/Rect.java': 'package android.graphics;public class Rect {public int left,top,right,bottom;public Rect(int l,int t,int r,int b){left=l;top=t;right=r;bottom=b;}public int width(){return right-left;}}',
'com/android/launcher3/DeviceProfile.java': 'package com.android.launcher3;public class DeviceProfile {public int widthPx=1200;}',
'com/android/quickstep/views/RecentsViewContainer.java': 'package com.android.quickstep.views;public class RecentsViewContainer {public com.android.launcher3.DeviceProfile profile=new com.android.launcher3.DeviceProfile();public com.android.launcher3.DeviceProfile getDeviceProfile(){return profile;}}',
'android/view/View.java':
 '''package android.view; import android.content.Context; public class View {public Context context=new Context();public boolean attached=true;public int invalidations,width=780;public int getMeasuredWidth(){return width;}public Context getContext(){return context;}public boolean isAttachedToWindow(){return attached;}public void invalidate(){invalidations++;}public void invalidateOutline(){invalidations++;}}''',
'dev/rikumi/flymemod/ModuleSettings.java': '''package dev.rikumi.flymemod; public class ModuleSettings {public static final String RECENTS_CORNER_DP="dp";}''',
'dev/rikumi/flymemod/SignalHooks.java': '''package dev.rikumi.flymemod; public class SignalHooks {
 public interface Installer{void hook(String name,String method,Hooker h,Class<?>... p);}
 public interface Hooker{Object run(Chain c)throws Throwable;}public interface Body{Object run()throws Throwable;}
 public static class Chain {Object target;Object[] args;Body body;Chain(Object t,Object[] a,Body b){target=t;args=a;body=b;}public Object getThisObject(){return target;}public Object getArg(int i){return args[i];}public Object proceed()throws Throwable{return body.run();}}
}''',
'com/android/quickstep/FullscreenDrawParams.java': '''package com.android.quickstep;
import android.content.Context; import dev.rikumi.flymemod.RecentsCornerHostTest;
public class FullscreenDrawParams {private float taskCornerRadius=180,windowCornerRadius=130,currentCornerRadius,mCurrentNoScaleCornerRadius;public float observed;public boolean fail;
 public void updateCornerRadius(Context c){RecentsCornerHostTest.call(this,"updateCornerRadius",new Object[]{c},()->{taskCornerRadius=180;windowCornerRadius=130;return null;});}
 public void setProgress(float p,float parent,float scale){RecentsCornerHostTest.call(this,"setProgress",new Object[]{p,parent,scale},()->{observed=taskCornerRadius;if(fail)throw new IllegalStateException("native failure");mCurrentNoScaleCornerRadius=taskCornerRadius+p*(windowCornerRadius-taskCornerRadius);currentCornerRadius=Math.min(mCurrentNoScaleCornerRadius/parent/scale,taskCornerRadius);return null;});}
 public float task(){return taskCornerRadius;}public float window(){return windowCornerRadius;}public float current(){return currentCornerRadius;}public float noScale(){return mCurrentNoScaleCornerRadius;}public void nativeTask(float f){taskCornerRadius=f;}
}''',
'com/android/quickstep/DesktopFullscreenDrawParams.java': '''package com.android.quickstep; public class DesktopFullscreenDrawParams extends FullscreenDrawParams {}''',
'com/android/quickstep/views/TaskView.java': '''package com.android.quickstep.views;
import android.view.View; import com.android.quickstep.FullscreenDrawParams; import dev.rikumi.flymemod.RecentsCornerHostTest;
public class TaskView extends View {public FullscreenDrawParams params=new FullscreenDrawParams();public int updates;public RecentsViewContainer container=new RecentsViewContainer();
 protected final RecentsViewContainer getContainer(){return container;}
 protected void onLayout(boolean changed,int left,int top,int right,int bottom){RecentsCornerHostTest.call(this,"onLayout",new Object[]{changed,left,top,right,bottom},()->null);}
 public void layout(){onLayout(true,0,0,width,1000);}
 public void onAttachedToWindow(){RecentsCornerHostTest.call(this,"onAttachedToWindow",new Object[]{},()->null);}
 protected final FullscreenDrawParams getThumbnailFullscreenParams(){return params;}
 protected void updateFullscreenParams(){updates++;updateFullscreenParams(params);}
 protected void updateFullscreenParams(FullscreenDrawParams p){RecentsCornerHostTest.call(this,"updateFullscreenParams",new Object[]{p},()->{p.setProgress(0,1,1);return null;});}
}''',
'com/android/quickstep/views/DesktopTaskView.java': '''package com.android.quickstep.views;public class DesktopTaskView extends TaskView {}''',
'com/android/quickstep/util/TransformParams.java': 'package com.android.quickstep.util;public class TransformParams {}',
'com/android/quickstep/util/SurfaceTransaction.java': 'package com.android.quickstep.util;public class SurfaceTransaction {}',
'com/android/quickstep/util/TaskViewSimulator.java': '''package com.android.quickstep.util;
import android.content.Context;import com.android.quickstep.FullscreenDrawParams;import dev.rikumi.flymemod.RecentsCornerHostTest;
public class TaskViewSimulator {private com.android.launcher3.DeviceProfile mDp=new com.android.launcher3.DeviceProfile();private android.graphics.Rect mFullTaskSize=new android.graphics.Rect(0,0,780,1736);private Context mContext=new Context();private FullscreenDrawParams mCurrentFullscreenParams=new FullscreenDrawParams();public float current(){return mCurrentFullscreenParams.current();}
 public void apply(TransformParams t,SurfaceTransaction s){RecentsCornerHostTest.call(this,"apply",new Object[]{t,s},()->{mCurrentFullscreenParams.setProgress(0,1,1);return null;});}}''',

'dev/rikumi/flymemod/RecentsCornerHostTest.java': '''package dev.rikumi.flymemod;
import java.util.*;import android.content.*;import com.android.quickstep.*;import com.android.quickstep.views.*;
public class RecentsCornerHostTest {
 static Map<String,SignalHooks.Hooker> installed=new HashMap<>();static boolean enabled;static int dp=43,checks;
 public static Object call(Object obj,String method,Object[] args,SignalHooks.Body body){try{String type=obj instanceof TaskView?TaskView.class.getName():obj instanceof com.android.quickstep.util.TaskViewSimulator?obj.getClass().getName():FullscreenDrawParams.class.getName();SignalHooks.Hooker h=installed.get(type+method);return h==null?body.run():h.run(new SignalHooks.Chain(obj,args,body));}catch(RuntimeException e){throw e;}catch(Throwable t){throw new RuntimeException(t);}}
 static void check(boolean ok){checks++;if(!ok)throw new AssertionError("check "+checks);}static void near(float a,float b){if(Math.abs(a-b)>=.001f)throw new AssertionError("check "+(checks+1)+" actual="+a+" expected="+b);check(true);}
 static class Prefs implements SharedPreferences {Map<String,Integer> values=new HashMap<>();public boolean contains(String k){return values.containsKey(k);}public int getInt(String k,int d){return values.getOrDefault(k,d);}public Editor edit(){return new Editor(){public Editor putInt(String k,int v){values.put(k,v);return this;}public boolean commit(){return true;}};}}
 public static void main(String[] args)throws Exception {
  Context c=new Context();Prefs pref=new Prefs();RecentsCornerSettings.initialize(c,pref);check(pref.getInt("dp",0)==44);
  c.manager.metrics.insets.radii=new int[]{160,160,160,160};RecentsCornerSettings.initialize(c,pref);check(pref.getInt("dp",0)==44);
  pref.values.clear();c.manager.metrics.insets.radii=new int[]{300,130,0,-1};check(RecentsCornerSettings.radiusDp(c,pref)==44);
  pref.values.clear();c.manager.metrics.insets.radii=new int[]{0,0,-1,-1};check(RecentsCornerSettings.radiusDp(c,pref)==10);check(!pref.contains("dp"));
  c.manager.metrics.insets.radii=new int[]{130,130,130,130};check(RecentsCornerSettings.radiusDp(c,pref)==44);check(pref.contains("dp"));
  pref.values.clear();c.manager.fail=true;check(RecentsCornerSettings.radiusDp(c,pref)==10);check(!pref.contains("dp"));c.manager.fail=false;
  // Same hardware dp after resolution/density scaling; no second physical-size ratio.
  check(RecentsCornerGeometry.hardwareDefault(130,482/160f)==44);check(RecentsCornerGeometry.hardwareDefault(65,241/160f)==44);
  check(RecentsCornerGeometry.hardwareDefault(2,3)==10);check(RecentsCornerGeometry.hardwareDefault(1000,2)==90);
  check(RecentsCornerGeometry.hardwareDefault(42,1)==42);
  check(RecentsCornerGeometry.hardwareDefault(42.001f,1)==44);
  check(RecentsCornerGeometry.hardwareDefault(43,1)==44);
  check(RecentsCornerGeometry.hardwareDefault(44,1)==44);
  check(RecentsCornerGeometry.hardwareDefault(44.001f,1)==46);
  check(RecentsCornerGeometry.hardwareDefault(89,1)==90);
  check(RecentsCornerGeometry.hardwareDefault(90.001f,1)==90);
  // Default rounding never rewrites an existing odd or manually selected value.
  for(int selected=10;selected<=90;selected++) {
   pref.values.put("dp",selected);RecentsCornerSettings.initialize(c,pref);
   check(pref.getInt("dp",0)==selected);check(RecentsCornerSettings.radiusDp(c,pref)==selected);
  }
  check(RecentsCornerGeometry.hardwareDefault(130,0)==0);check(RecentsCornerGeometry.hardwareDefault(Float.NaN,3)==0);
  RecentsCornerHooks hooks=new RecentsCornerHooks(RecentsCornerHostTest.class.getClassLoader(),ctx->{},()->enabled,()->dp,(msg,t)->{throw new AssertionError(msg,t);});
  hooks.install((name,method,h,types)->installed.put(name+method,h),m->{});
  FullscreenDrawParams p=new FullscreenDrawParams();TaskView primary=new TaskView();primary.params=p;primary.onAttachedToWindow();p.updateCornerRadius(c);p.setProgress(0,1,1);near(p.current(),180);near(p.task(),180);
  enabled=true;p.setProgress(0,1,1);near(p.current(),43*482/160f*.65f);near(p.task(),180);near(p.window(),130);
  dp=10;p.setProgress(1,1,1);near(p.current(),130);near(p.noScale(),130);near(p.task(),180);
  for(int r=10;r<=90;r+=5){dp=r;for(int i=0;i<=100;i++){float progress=i/100f;p.setProgress(progress,1,1);near(p.noScale(),r*482/160f*.65f+progress*(130-r*482/160f*.65f));near(p.current(),p.noScale());near(p.task(),180);near(p.window(),130);}}
  dp=90;p.setProgress(0,.5f,1);near(p.current(),90*482/160f*.65f);dp=10;p.setProgress(1,1,1);near(p.current(),130);
  enabled=false;hooks.refresh();near(p.current(),180);p.setProgress(0,1,1);near(p.current(),180);near(p.observed,180);
  enabled=true;p.fail=true;try{p.setProgress(.2f,1,1);throw new AssertionError();}catch(IllegalStateException expected){}near(p.task(),180);p.fail=false;
  p.updateCornerRadius(c);p.setProgress(0,1,1);near(p.current(),dp*482/160f*.65f);near(p.task(),180);
  FullscreenDrawParams zero=new FullscreenDrawParams();zero.updateCornerRadius(c);zero.nativeTask(0);zero.setProgress(0,1,1);near(zero.current(),0);
  DesktopFullscreenDrawParams desktop=new DesktopFullscreenDrawParams();desktop.updateCornerRadius(c);desktop.setProgress(0,1,1);near(desktop.current(),180);
  TaskView card=new TaskView();card.params.updateCornerRadius(c);card.onAttachedToWindow();near(card.params.current(),dp*482/160f*.65f);check(card.updates==1);
  dp=60;hooks.refresh();near(card.params.current(),60*482/160f*.65f);check(card.invalidations>0);
  enabled=false;hooks.refresh();near(card.params.current(),180);near(card.params.task(),180);card.onAttachedToWindow();near(card.params.current(),180);
  // First attachment/apply also works when ART skipped the constructor's updateCornerRadius hook.
  enabled=true;dp=50;TaskView first=new TaskView();first.onAttachedToWindow();near(first.params.current(),50*482/160f*.65f);
  com.android.quickstep.util.TaskViewSimulator simulator=new com.android.quickstep.util.TaskViewSimulator();simulator.apply(null,null);near(simulator.current(),50*482/160f*.65f);
  enabled=false;hooks.refresh();near(first.params.current(),180);near(simulator.current(),180);
  // Match the logical application dp after card-to-screenshot inverse mapping, at
  // several layout scales/densities; animate only the remaining native scale once.
  for(float density:new float[]{1,2,482/160f,4}) for(float base:new float[]{.4f,.63f,.65f,.8f}) {
   float cardPx=RecentsCornerGeometry.cardPixels(44,density,1200*base,1200);
   near(cardPx/base,44*density);
   near(RecentsCornerGeometry.drawn(cardPx,130,0,1,1),cardPx);
   near(RecentsCornerGeometry.drawn(cardPx,130,1,1/base,1)/base,130);
  }
  enabled=true;dp=44;first.width=0;first.params.setProgress(0,1,1);near(first.params.current(),180);
  first.width=756;first.layout();near(first.params.current(),44*482/160f*.63f);
  first.width=960;first.layout();near(first.params.current(),44*482/160f*.8f);
  enabled=false;hooks.refresh();near(first.params.current(),180);
  check(Float.isNaN(RecentsCornerGeometry.cardPixels(44,3,0,1200)));
  System.out.println("PASS: "+checks+" actual settings/hook checks: hardware defaults, unit conversion, unavailable retry, range, native-off, reuse, live refresh, full-screen endpoint, zero providers, freeform exclusion, failure restoration");
 }
}''',
}
with tempfile.TemporaryDirectory(prefix='flyme-recents-corners-',dir='/private/tmp') as folder:
    base=Path(folder)
    for name, text in sources.items():
        p=base/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
    prod=root/'app/src/main/java/dev/rikumi/flymemod'
    java=[str(p) for p in base.rglob('*.java')]+[str(prod/f'{name}.java') for name in ['RecentsCornerGeometry','RecentsCornerSettings','RecentsCornerHooks']]
    subprocess.run(['/opt/homebrew/opt/openjdk@21/bin/javac','-d',str(base/'classes'),*java],check=True)
    subprocess.run(['/opt/homebrew/opt/openjdk@21/bin/java','-cp',str(base/'classes'),'dev.rikumi.flymemod.RecentsCornerHostTest'],check=True)
