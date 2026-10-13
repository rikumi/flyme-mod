#!/usr/bin/env python3
"""Run the production interception code; this does not measure device frame time."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'app/src/main/java/dev/rikumi/flymemod'
JDK = Path('/opt/homebrew/opt/openjdk@21/bin')
STUBS = {
 'android/content/Context.java': 'package android.content; public class Context {}',
 'io/github/libxposed/api/XposedInterface.java': '''package io.github.libxposed.api;
public interface XposedInterface {
 interface Chain {Object getThisObject(); Object getArg(int n); Object proceed() throws Throwable;}
 interface Hooker {Object intercept(Chain chain) throws Throwable;}
}''',
 'dev/rikumi/flymemod/SignalHooks.java': '''package dev.rikumi.flymemod;
class SignalHooks {interface Installer {
 void hook(String owner,String method,io.github.libxposed.api.XposedInterface.Hooker hook,Class<?>... params);
}}''',
 'com/android/quickstep/views/TaskView.java': '''package com.android.quickstep.views;
public class TaskView {public void onTaskListVisibilityChanged(boolean visible,int changes){}}''',
 'com/android/quickstep/views/RecentsView.java': '''package com.android.quickstep.views;
public class RecentsView {
 public void loadVisibleTaskData(int changes){}
 public void reloadIfNeeded(){}
 public void lambda$loadVisibleTaskData$16(int a,int b,int c,int d,java.util.List list,int mask,Integer index,TaskView task){}
}''',
 'com/android/quickstep/views/RecentsView$$ExternalSyntheticLambda46.java': '''package com.android.quickstep.views;
public class RecentsView$$ExternalSyntheticLambda46 {public void accept(Object index,Object task){}}''',
 'com/meizu/flyme/launcher/quickstep/util/TaskLoadState.java': '''package com.meizu.flyme.launcher.quickstep.util;
public class TaskLoadState {}''',
 'com/android/quickstep/RecentTasksList.java': '''package com.android.quickstep;
public class RecentTasksList {
 public int generation=1, invalidations;
 private void invalidateLoadedTasks(){generation++;invalidations++;}
 public void loadTasksInBackground(){}
 public void getTasks(){}
 public void lambda$getTasks$7(){}
}''',
 'com/android/quickstep/RecentsModel.java': '''package com.android.quickstep;
public class RecentsModel {public boolean isTaskListValid(int generation){return true;}}''',
 'dev/rikumi/flymemod/RecentsGestureJankTest.java': r'''package dev.rikumi.flymemod;
import com.android.quickstep.*;
import com.android.quickstep.views.*;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;
import java.util.*;

public class RecentsGestureJankTest {
 static final String RV="com.android.quickstep.views.RecentsView", TV="com.android.quickstep.views.TaskView";
 static final String UPDATE="lambda$loadVisibleTaskData$16", VIS="onTaskListVisibilityChanged";
 static int checks;
 interface Action {Object run() throws Throwable;}
 static void check(boolean value){checks++;if(!value)throw new AssertionError("check "+checks);}
 static class Registry implements SignalHooks.Installer, RecentsHooks.Installer {
  Map<String,XposedInterface.Hooker> hooks=new HashMap<>();
  public void hook(String owner,String name,XposedInterface.Hooker hook,Class<?>... params){
   check(hooks.put(owner+"#"+name,hook)==null);
  }
  Object call(String owner,String name,Object self,Object[] args,Action action)throws Throwable{
   return hooks.get(owner+"#"+name).intercept(new XposedInterface.Chain(){
    public Object getThisObject(){return self;}
    public Object getArg(int n){return args[n];}
    public Object proceed()throws Throwable{return action.run();}
   });
  }
 }
 static Registry r=new Registry();static boolean[] enabled={false};static int calls;
 static Object visible(TaskView task,boolean visible,int mask)throws Throwable{
  return r.call(TV,VIS,task,new Object[]{visible,mask},()->{calls++;return "native";});
 }
 static Object scope(TaskView task,Action action)throws Throwable{
  return r.call(RV,UPDATE,new RecentsView(),new Object[]{0,0,0,0,List.of(),15,0,task},action);
 }
 public static void main(String[] args)throws Throwable{
  List<Method> deopt=new ArrayList<>();
  new RecentsGestureJankHooks(RecentsGestureJankTest.class.getClassLoader(),()->enabled[0]).install(r,deopt::add);
  check(deopt.size()==3&&deopt.stream().anyMatch(m->m.getName().equals(UPDATE)));
  for(boolean on:new boolean[]{false,true})for(int mask=1;mask<16;mask++){
   enabled[0]=on;TaskView task=new TaskView();calls=0;final int m=mask;
   Object result=scope(task,()->{
    check("native".equals(visible(task,true,m)));
    Object second=visible(task,true,m);check(on?second==null:"native".equals(second));
    return "callback";
   });
   check("callback".equals(result));check(calls==(on?1:2));
   // A new callback must start a new load, even with the same task/mask.
   scope(task,()->visible(task,true,m));check(calls==(on?2:3));
   visible(task,true,m);check(calls==(on?3:4));
  }
  enabled[0]=true;TaskView a=new TaskView(),b=new TaskView();calls=0;
  scope(a,()->{
   visible(a,true,15);visible(a,false,15);visible(a,true,15);visible(a,true,1);
   visible(b,true,1);visible(b,true,1);return null;
  });check(calls==6); // unloading, changed masks and other cards pass through
  calls=0;scope(a,()->{
   visible(a,true,15);
   scope(b,()->{visible(b,true,15);visible(b,true,15);return null;});
   visible(a,true,15);return null;
  });check(calls==2); // nested callbacks restore outer state
  calls=0;scope(a,()->{
   scope(a,()->{visible(a,true,15);visible(a,true,15);return null;});
   visible(a,true,15);visible(a,true,15);return null;
  });check(calls==2); // each callback owns its own request, including the same card
  calls=0;scope(a,()->{
   r.call(TV,VIS,a,new Object[]{true,15},()->{calls++;visible(a,true,15);return null;});
   return null;
  });check(calls==2); // recursion inside the original call is not swallowed
  calls=0;scope(a,()->{
   try{r.call(TV,VIS,a,new Object[]{true,15},()->{throw new IllegalStateException("native");});}
   catch(IllegalStateException expected){}
   visible(a,true,15);return null;
  });check(calls==1); // failed native requests can be retried
  try{scope(a,()->{visible(a,true,15);throw new IllegalStateException("callback");});}
  catch(IllegalStateException expected){}
  calls=0;visible(a,true,15);visible(a,true,15);check(calls==2); // no leaked scope
  enabled[0]=false;calls=0;scope(a,()->{visible(a,true,15);visible(a,true,15);return null;});check(calls==2);
  cacheTests();
  System.out.println("PASS: "+checks+" production-hook checks: duplicate requests, masks, unloads, nested calls, failures, defaults and native cache generations");
 }
 static void cacheTests()throws Throwable{
  Registry cache=new Registry();boolean[] filter={false},stacked={true};String owner="com.android.quickstep.RecentTasksList";
  new RecentsHooks(cache,()->filter[0],()->true,()->stacked[0],(message,error)->{throw new AssertionError(message,error);})
    .install(RecentsGestureJankTest.class.getClassLoader(),method->{});
  RecentTasksList list=new RecentTasksList();
  check(valid(cache,list,list.generation));
  filter[0]=true;check(!valid(cache,list,list.generation));
  load(cache,list);check(list.invalidations==1);
  for(int i=0;i<20;i++)check(valid(cache,list,list.generation));
  check(list.invalidations==1); // repeated reloadIfNeeded now reuses the native list
  int old=list.generation;list.generation++;check(!valid(cache,list,old)); // real task change
  load(cache,list);check(list.invalidations==2&&valid(cache,list,list.generation));
  check(!valid(cache,list,-1));load(cache,list);check(list.invalidations==3); // next overview entry still refreshes
  filter[0]=false;check(!valid(cache,list,list.generation));
  load(cache,list);check(list.invalidations==4&&valid(cache,list,list.generation));
  load(cache,list);check(list.invalidations==4); // native-off no longer forces invalidation
  filter[0]=true;check(!valid(cache,list,list.generation));load(cache,list);check(list.invalidations==5);
  stacked[0]=false;check(!valid(cache,list,list.generation)); // stack off restores original forced refresh
  load(cache,list);check(list.invalidations==6&&!valid(cache,list,list.generation));
  stacked[0]=true;check(valid(cache,list,list.generation)); // no stale projection on re-enable
  filter[0]=false;check(!valid(cache,list,list.generation));load(cache,list);
  stacked[0]=false;check(valid(cache,list,list.generation));
 }
 static boolean valid(Registry cache,RecentTasksList list,int generation)throws Throwable{
  return (Boolean)cache.call("com.android.quickstep.RecentTasksList","isTaskListValid",list,new Object[]{generation},()->list.generation==generation);
 }
 static void load(Registry cache,RecentTasksList list)throws Throwable{
  cache.call("com.android.quickstep.RecentTasksList","getTasks",list,new Object[0],()->null);
 }
}''',
}
for name in ('SettingsProvider.java', 'ModuleSettings.java', 'SettingsCatalog.kt', 'XposedInit.java'):
    assert 'FIX_RECENTS_GESTURE_JANK' not in (SOURCE / name).read_text()
xposed = (SOURCE / 'XposedInit.java').read_text()
assert 'new RecentsGestureJankHooks(loader, () -> stackedRecentsEnabled)' in xposed
assert '() -> recentsHideNotRunningEnabled, () -> recentsSwipeUpKillEnabled, () -> stackedRecentsEnabled' in xposed
assert 'setDeferResume' not in (SOURCE / 'RecentsGestureJankHooks.java').read_text()
with tempfile.TemporaryDirectory(prefix='flyme-recents-jank-tests-', dir='/private/tmp') as directory:
    files = []
    for name, body in STUBS.items():
        path = Path(directory) / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body)
        files.append(str(path))
    files += [str(SOURCE / name) for name in ('RecentsGestureJankHooks.java', 'RecentsHooks.java')]
    subprocess.run([str(JDK / 'javac'), '-d', directory, *files], check=True)
    subprocess.run([str(JDK / 'java'), '-cp', directory, 'dev.rikumi.flymemod.RecentsGestureJankTest'], check=True)
print('PASS: both fixes follow stacked recents; no new setting or speculative lifecycle hook')
