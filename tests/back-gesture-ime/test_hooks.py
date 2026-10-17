#!/usr/bin/env python3
"""Run the production hook against native admission, IME visibility and geometry fixtures."""
from pathlib import Path
import subprocess
import tempfile
ROOT = Path(__file__).resolve().parents[2]
JAVA = Path('/opt/homebrew/opt/openjdk@21/bin')
STUBS = {
'android/content/Context.java': 'package android.content; public class Context {}',
'android/graphics/Rect.java': 'package android.graphics; public class Rect { public int left=0,top=0,right=1080,bottom=2400; }',
'android/graphics/Insets.java': 'package android.graphics; public class Insets { public int bottom=800; }',
'android/view/MotionEvent.java': 'package android.view; public class MotionEvent { public float x=1,y=1700; public float getX(){return x;} public float getY(){return y;} }',
'android/view/WindowInsets.java': '''package android.view; public class WindowInsets {
 public boolean visible=true; public android.graphics.Insets insets=new android.graphics.Insets();
 public boolean isVisible(int type){return visible;} public android.graphics.Insets getInsets(int type){return insets;}
 public static class Type { public static int ime(){return 8;} }
}''',
'android/view/WindowMetrics.java': '''package android.view; public class WindowMetrics {
 public WindowInsets insets=new WindowInsets(); public android.graphics.Rect bounds=new android.graphics.Rect();
 public WindowInsets getWindowInsets(){return insets;} public android.graphics.Rect getBounds(){return bounds;}
}''',
'android/view/WindowManager.java': '''package android.view; public class WindowManager {
 public WindowMetrics metrics=new WindowMetrics(); public int reads; public boolean fail;
 public WindowMetrics getCurrentWindowMetrics(){reads++;if(fail)throw new IllegalStateException();return metrics;}
}''',
'io/github/libxposed/api/XposedInterface.java': '''package io.github.libxposed.api; public interface XposedInterface {
 interface Chain { Object proceed() throws Throwable; Object getThisObject(); Object getArg(int n); }
 interface Hooker { Object intercept(Chain chain) throws Throwable; }
}''',
'dev/rikumi/flymemod/SignalHooks.java': '''package dev.rikumi.flymemod; class SignalHooks { interface Installer {
 void hook(String owner,String name,io.github.libxposed.api.XposedInterface.Hooker hook,Class<?>... parameters); }}''',
'com/android/systemui/navigationbar/gestural/EdgeBackGestureHandler.java': '''package com.android.systemui.navigationbar.gestural;
public class EdgeBackGestureHandler {
 public android.content.Context mContext=new android.content.Context(); public android.view.WindowManager mWindowManager=new android.view.WindowManager();
 public boolean isWithinTouchRegion(android.view.MotionEvent e){return true;}
 public void onMotionEvent(android.view.MotionEvent e){}
 public class SystemUIGestureListener { public int onGesturePreTriggerBefore(int side,android.view.MotionEvent e){return 0;} }
}''',
'dev/rikumi/flymemod/BackGestureImeTest.java': '''package dev.rikumi.flymemod;
import java.util.*; import android.view.*; import com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler;
import io.github.libxposed.api.XposedInterface;
public class BackGestureImeTest {
 static boolean enabled,nativeResult=true; static int checks,calls,logs,loads;
 static XposedInterface.Hooker hook;
 static void check(boolean p){checks++;if(!p)throw new AssertionError("check "+checks);}
 public static void main(String[] args)throws Throwable {
  Set<String> deopt=new HashSet<>();
  BackGestureImeHooks.install(BackGestureImeTest.class.getClassLoader(),c->loads++,()->enabled,(m,e)->logs++,
   (owner,name,h,params)->{check(name.equals("isWithinTouchRegion"));hook=h;},m->deopt.add(m.getName()));
  check(deopt.containsAll(List.of("onMotionEvent","onGesturePreTriggerBefore")));
  EdgeBackGestureHandler owner=new EdgeBackGestureHandler(); MotionEvent event=new MotionEvent();
  XposedInterface.Chain chain=new XposedInterface.Chain(){public Object proceed(){calls++;return nativeResult;}public Object getThisObject(){return owner;}public Object getArg(int n){return event;}};
  enabled=false;check((Boolean)hook.intercept(chain));check(owner.mWindowManager.reads==0);
  enabled=true;check(!(Boolean)hook.intercept(chain));
  event.x=1079;check(!(Boolean)hook.intercept(chain));
  event.y=1599;check((Boolean)hook.intercept(chain));
  event.y=1600;check(!(Boolean)hook.intercept(chain));
  event.y=2400;check((Boolean)hook.intercept(chain));
  event.y=2000;owner.mWindowManager.metrics.insets.visible=false;check((Boolean)hook.intercept(chain));
  owner.mWindowManager.metrics.insets.visible=true;owner.mWindowManager.metrics.insets.insets.bottom=0;check((Boolean)hook.intercept(chain));
  owner.mWindowManager.metrics.insets.insets.bottom=800;
  nativeResult=false;int before=owner.mWindowManager.reads;check(!(Boolean)hook.intercept(chain));check(before==owner.mWindowManager.reads);
  nativeResult=true;owner.mWindowManager.fail=true;check((Boolean)hook.intercept(chain));check(logs==1);
  enabled=false;check((Boolean)hook.intercept(chain));check(loads==calls);
  // Rotation and nonzero display bounds use the current metrics rather than cached screen height.
  check(BackGestureImeHooks.inKeyboard(10,900,0,0,2400,1080,true,400));
  check(!BackGestureImeHooks.inKeyboard(10,679,0,0,2400,1080,true,400));
  check(BackGestureImeHooks.inKeyboard(101,1300,100,300,1180,1500,true,200));
  check(!BackGestureImeHooks.inKeyboard(99,1400,100,300,1180,1500,true,200));
  check(!BackGestureImeHooks.inKeyboard(1180,1400,100,300,1180,1500,true,200));
  System.out.println("PASS: "+checks+" IME admission checks");
 }
}'''
}
with tempfile.TemporaryDirectory(prefix='back-ime-',dir='/private/tmp') as d:
    root=Path(d)
    for name, source in STUBS.items():
        p=root/name
        p.parent.mkdir(parents=True,exist_ok=True)
        p.write_text(source)
    production=ROOT/'app/src/main/java/dev/rikumi/flymemod/BackGestureImeHooks.java'
    subprocess.run([str(JAVA/'javac'),'-d',d,*map(str,root.rglob('*.java')),str(production)],check=True)
    subprocess.run([str(JAVA/'java'),'-cp',d,'dev.rikumi.flymemod.BackGestureImeTest'],check=True)
