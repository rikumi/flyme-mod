"""Exercise production SDKStage white-list read/write hooks with original writes retained."""
from pathlib import Path
import runpy,subprocess,tempfile
ROOT=Path(__file__).resolve().parents[2]
files=runpy.run_path(str(Path(__file__).with_name('test_night_blacklist_runtime.py')))['files'].copy()
files.update({
'android/content/Context.java':'package android.content;public class Context{public Object getContentResolver(){return null;}}',
'com/meizu/flyme/sdkstage/data/model/AppInfo.java':'package com.meizu.flyme.sdkstage.data.model;public class AppInfo{public String pkg;public boolean night;public String getPackageName(){return pkg;}public void setNightModeEnabled(boolean v){night=v;}}',
'com/meizu/flyme/sdkstage/common/d.java':'package com.meizu.flyme.sdkstage.common;public class d{public android.content.Context b=new android.content.Context();public boolean j(String pkg){return false;}}',
'com/meizu/flyme/sdkstage/common/a.java':'package com.meizu.flyme.sdkstage.common;public class a extends d{}',
'dev/rikumi/flymemod/WhitelistTest.java':'''package dev.rikumi.flymemod;import java.util.*;import android.provider.Settings;import com.meizu.flyme.sdkstage.data.model.AppInfo;public class WhitelistTest{
 static int checks;static void check(boolean b){checks++;if(!b)throw new AssertionError("check "+checks);}static class Chain implements SignalHooks.Chain{Object owner;Object[] args;int calls;Object result;Chain(Object o,Object...a){owner=o;args=a;}public Object getThisObject(){return owner;}public Object getArg(int i){return args[i];}public Object proceed(){calls++;Settings.Global.value="native black write preserved";return result;}}
 public static void main(String[]args)throws Throwable{boolean[] enabled={true};Map<String,SignalHooks.Hooker> hooks=new HashMap<>();NightModeWhitelistHooks.install(WhitelistTest.class.getClassLoader(),(k,d)->enabled[0],(c,m,h,p)->hooks.put(m,h));var manager=new com.meizu.flyme.sdkstage.common.a();Chain c=new Chain(manager,"com.target");check(Boolean.FALSE.equals(hooks.get("j").apply(c)));check(c.calls==0);c=new Chain(manager,"com.target",true);hooks.get("t").apply(c);check(c.calls==1);check(Settings.Global.value.equals("native black write preserved"));check(Settings.Global.whiteValue.equals("com.target"));check(Boolean.TRUE.equals(hooks.get("j").apply(new Chain(manager,"com.target"))));check(Boolean.FALSE.equals(hooks.get("j").apply(new Chain(manager,"com.tar"))));
 AppInfo a=new AppInfo();a.pkg="com.a";AppInfo b=new AppInfo();b.pkg="com.b";c=new Chain(manager,List.of(a,b),true);hooks.get("s").apply(c);check(c.calls==1);check(NightModeWhitelistPolicy.contains(Settings.Global.whiteValue,"com.a"));check(NightModeWhitelistPolicy.contains(Settings.Global.whiteValue,"com.b"));hooks.get("s").apply(new Chain(manager,List.of(a,b),false));check(!NightModeWhitelistPolicy.contains(Settings.Global.whiteValue,"com.a"));check(NightModeWhitelistPolicy.contains(Settings.Global.whiteValue,"com.target"));hooks.get("t").apply(new Chain(manager,"com.target",false));check(Settings.Global.whiteValue.isEmpty());
 enabled[0]=false;String old=Settings.Global.whiteValue;c=new Chain(manager,"com.target",true);hooks.get("t").apply(c);check(old.equals(Settings.Global.whiteValue));check(c.calls==1);c=new Chain(manager,"com.target");hooks.get("j").apply(c);check(c.calls==1);check(NightModeWhitelistPolicy.update("com.a,com.a,,com.b","com.a",false).equals("com.b"));check(NightModeWhitelistPolicy.update(null,"com.a",true).equals("com.a"));System.out.println("PASS: "+checks+" whitelist SDK checks: empty defaults, single/batch native+white writes, exact matching, mode off, duplicates");}}
'''})
with tempfile.TemporaryDirectory(prefix='flyme-night-white-',dir='/private/tmp') as d:
 ps=[]
 for n,s in files.items():
  p=Path(d)/n;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s);ps.append(str(p))
 subprocess.run(['/opt/homebrew/opt/openjdk@21/bin/javac','-d',d,*ps,*[str(ROOT/'app/src/main/java/dev/rikumi/flymemod'/f'{n}.java') for n in ['NightModeBlacklistHooks','NightModeWhitelistHooks','NightModeWhitelistPolicy']]],check=True)
 subprocess.run(['/opt/homebrew/opt/openjdk@21/bin/java','-cp',d,'dev.rikumi.flymemod.WhitelistTest'],check=True)
