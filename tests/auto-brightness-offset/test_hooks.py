#!/usr/bin/env python3
"""Execute production hooks against the two verified Flyme strategy signatures."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
JDK = Path('/opt/homebrew/opt/openjdk@21/bin')
STUBS = {
'android/content/SharedPreferences.java': '''package android.content;
public interface SharedPreferences { boolean getBoolean(String key, boolean fallback); int getInt(String key, int fallback); }''',
'io/github/libxposed/api/XposedInterface.java': '''package io.github.libxposed.api;
public interface XposedInterface {
 interface Chain { Object proceed() throws Throwable; Object getThisObject(); }
 interface Hooker { Object intercept(Chain chain) throws Throwable; }
}''',
'dev/rikumi/flymemod/SignalHooks.java': '''package dev.rikumi.flymemod;
class SignalHooks { interface Installer { void hook(String owner, String name,
 io.github.libxposed.api.XposedInterface.Hooker hook, Class<?>... parameters); } }''',
'dev/rikumi/flymemod/ModuleSettings.java': '''package dev.rikumi.flymemod;
class ModuleSettings { static final String AUTO_BRIGHTNESS_OFFSET_ENABLED="enabled", AUTO_BRIGHTNESS_OFFSET="offset";
static final int AUTO_BRIGHTNESS_OFFSET_DEFAULT=10; }''',
'com/android/server/display/AutomaticBrightnessController.java': '''package com.android.server.display;
public class AutomaticBrightnessController { public int mDisplayState=2, mDisplayPolicy=3; }''',
'com/android/server/display/DisplayPowerController.java': '''package com.android.server.display;
public class DisplayPowerController { private void updatePowerState() {} }''',
'com/android/server/display/brightness/BrightnessEvent.java': '''package com.android.server.display.brightness; public class BrightnessEvent {}''',
'com/android/server/display/brightness/StrategyExecutionRequest.java': '''package com.android.server.display.brightness; public class StrategyExecutionRequest {}''',
'dev/rikumi/flymemod/AutoBrightnessOffsetTest.java': '''package dev.rikumi.flymemod;
import java.util.*;
import android.content.SharedPreferences;
import com.android.server.display.AutomaticBrightnessController;
import io.github.libxposed.api.XposedInterface;
public class AutoBrightnessOffsetTest {
 static int checks; static boolean enabled; static int percent=20;
 static void check(boolean pass) { checks++; if(!pass) throw new AssertionError("check " + checks); }
 static void equal(float actual,float expected) { check(Float.floatToIntBits(actual)==Float.floatToIntBits(expected) || Math.abs(actual-expected)<0.000001f); }
 public static void main(String[] args) throws Throwable {
  Map<String,XposedInterface.Hooker> hooks=new HashMap<>();
  Set<String> deoptimized=new HashSet<>();
  AutoBrightnessOffsetHooks.install(AutoBrightnessOffsetTest.class.getClassLoader(),new SharedPreferences(){
   public boolean getBoolean(String key,boolean fallback){return enabled;}
   public int getInt(String key,int fallback){return percent;}
  },(owner,name,hook,parameters)->{check(parameters.length== (owner.endsWith("Strategy2")?1:2));hooks.put(owner,hook);},
  method->deoptimized.add(method.getName()));
  check(hooks.size()==2); check(deoptimized.size()==3);
  for(var entry:hooks.entrySet()) {
   Object strategy=Class.forName(entry.getKey()).getConstructor().newInstance();
   var field=strategy.getClass().getDeclaredField("mAutomaticBrightnessController");
   AutomaticBrightnessController controller=(AutomaticBrightnessController)field.get(strategy);
   int[] calls={0}; float[] raw={0.3f};
   XposedInterface.Chain chain=new XposedInterface.Chain(){
    public Object proceed(){calls[0]++;return raw[0];} public Object getThisObject(){return strategy;}
   };
   enabled=false; equal((Float)entry.getValue().intercept(chain),0.3f);
   enabled=true; percent=20; equal((Float)entry.getValue().intercept(chain),0.5f);
   equal((Float)entry.getValue().intercept(chain),0.5f); check(calls[0]==3);
   percent=50; equal((Float)entry.getValue().intercept(chain),0.8f);
   percent=-30; equal((Float)entry.getValue().intercept(chain),0f);
   percent=10; equal((Float)entry.getValue().intercept(chain),0.4f);
   percent=0; equal((Float)entry.getValue().intercept(chain),0.3f);
   percent=20;
   for(int state:new int[]{0,1,3,4,5,6}) {controller.mDisplayState=state; equal((Float)entry.getValue().intercept(chain),0.3f);}
   controller.mDisplayState=2; controller.mDisplayPolicy=1; equal((Float)entry.getValue().intercept(chain),0.3f);
   controller.mDisplayPolicy=3;
   raw[0]=0.95f; equal((Float)entry.getValue().intercept(chain),1f);
   for(float sentinel:new float[]{Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY,-1f,1.2f}) {
    raw[0]=sentinel; equal((Float)entry.getValue().intercept(chain),sentinel);
   }
   field.set(strategy,null); raw[0]=0.3f; equal((Float)entry.getValue().intercept(chain),0.3f);
   field.set(strategy,controller); enabled=false; equal((Float)entry.getValue().intercept(chain),0.3f);
  }
  for(int p=-30;p<=50;p+=5) equal(AutoBrightnessOffsetHooks.offset(0.4f,p),0.4f+p/100f);
  equal(AutoBrightnessOffsetHooks.offset(0.1f,-30),0f);
  equal(AutoBrightnessOffsetHooks.offset(0.3f,-5),0.25f);
  equal(AutoBrightnessOffsetHooks.offset(0.3f,100),0.8f);
  equal(AutoBrightnessOffsetHooks.offset(0.5f,-100),0.2f);
  System.out.println("PASS: "+checks+" hook and boundary checks");
 }
}'''
}
for suffix, params in [('2', 'BrightnessEvent event'), ('', 'BrightnessEvent event, boolean update')]:
    name = 'AutomaticBrightnessStrategy' + suffix
    STUBS[f'com/android/server/display/brightness/strategy/{name}.java'] = f'''package com.android.server.display.brightness.strategy;
import com.android.server.display.AutomaticBrightnessController;
import com.android.server.display.brightness.*;
public class {name} {{
 public AutomaticBrightnessController mAutomaticBrightnessController=new AutomaticBrightnessController();
 public float getAutomaticScreenBrightness({params}) {{return 0.3f;}}
 public boolean isAutoBrightnessValid() {{return true;}}
 public Object updateBrightness(StrategyExecutionRequest request) {{return null;}}
}}'''
with tempfile.TemporaryDirectory(prefix='auto-brightness-', dir='/private/tmp') as directory:
    root = Path(directory)
    for name, source in STUBS.items():
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(source)
    production = ROOT / 'app/src/main/java/dev/rikumi/flymemod/AutoBrightnessOffsetHooks.java'
    subprocess.run([str(JDK/'javac'), '-d', str(root), *map(str, root.rglob('*.java')), str(production)], check=True)
    subprocess.run([str(JDK/'java'), '-cp', str(root), 'dev.rikumi.flymemod.AutoBrightnessOffsetTest'], check=True)
