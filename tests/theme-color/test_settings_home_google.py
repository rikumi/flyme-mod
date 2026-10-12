"""Exercise the real Google-entry hook, registration checks, native loader and queued UI updates."""
from pathlib import Path
import runpy
import subprocess
import tempfile
ROOT=Path(__file__).resolve().parents[2]
files=runpy.run_path(str(Path(__file__).with_name('test_wireless_debug_page.py')))['files'].copy()
files['dev/rikumi/flymemod/ModuleSettings.java']=files['dev/rikumi/flymemod/ModuleSettings.java'].replace('static final String WIRELESS_DEBUG_THEME_FIX=', 'static final String SETTINGS_HOME_FORCE_GOOGLE="settings_home_force_google";static final String WIRELESS_DEBUG_THEME_FIX=')
files['dev/rikumi/flymemod/ThemeColorHooks.java']=files['dev/rikumi/flymemod/ThemeColorHooks.java'].replace('class ThemeColorHooks','public class ThemeColorHooks').replace('interface Hooker','public interface Hooker').replace('interface Chain','public interface Chain')
files.update({
'android/content/ComponentName.java':'package android.content;public class ComponentName{public String pkg,name;public ComponentName(String p,String n){pkg=p;name=n;}}',
'android/content/Intent.java':'package android.content;public class Intent{public ComponentName component;public Intent setComponent(ComponentName c){component=c;return this;}}',
'android/content/pm/ApplicationInfo.java':'package android.content.pm;public class ApplicationInfo{public boolean enabled=true;}',
'android/content/pm/ComponentInfo.java':'package android.content.pm;public class ComponentInfo{public String packageName="com.google.android.gms",name="com.google.android.gms.app.settings.GoogleSettingsIALink";public android.os.Bundle metaData=new android.os.Bundle();}',
'android/content/pm/ActivityInfo.java':'package android.content.pm;public class ActivityInfo extends ComponentInfo{public CharSequence loadLabel(PackageManager pm){return "Google";}public boolean enabled=true;public ApplicationInfo applicationInfo=new ApplicationInfo();}',
'android/content/pm/PackageManager.java':'package android.content.pm;public class PackageManager{public static final int GET_META_DATA=128;public static class NameNotFoundException extends Exception{}public ActivityInfo info=new ActivityInfo();public int queries;public boolean installed=true;public ActivityInfo getActivityInfo(android.content.ComponentName c,int flags)throws NameNotFoundException{queries++;if(!installed)throw new NameNotFoundException();if(!c.pkg.equals(info.packageName)||!c.name.equals(info.name))throw new AssertionError();return info;}}',
'android/os/Bundle.java':'package android.os;public class Bundle{public boolean category=true;}',
'android/os/UserHandle.java':'package android.os;public class UserHandle{}',
'android/os/Process.java':'package android.os;public class Process{public static UserHandle myUserHandle(){return new UserHandle();}}',
'android/os/Looper.java':'package android.os;public class Looper{public static Looper getMainLooper(){return new Looper();}}',
'android/os/Handler.java':'package android.os;public class Handler{public static boolean defer;public static java.util.List<Runnable> queue=new java.util.ArrayList<>();public Handler(Looper l){}public boolean post(Runnable r){if(defer)queue.add(r);else r.run();return true;}public static void drain(){for(Runnable r:new java.util.ArrayList<>(queue))r.run();queue.clear();}}',
'android/content/Context.java':'package android.content;public class Context{public android.content.pm.PackageManager pm=new android.content.pm.PackageManager();public android.content.pm.PackageManager getPackageManager(){return pm;}public android.content.res.Resources getResources(){return new android.content.res.Resources();}public Object getTheme(){return null;}}',
'android/app/Activity.java':'package android.app;public class Activity extends android.content.Context{public android.view.Window window=new android.view.Window();public android.view.Window getWindow(){return window;}}',
'com/android/settingslib/drawer/Tile.java':'package com.android.settingslib.drawer;public class Tile{public String mTitle;public static dev.rikumi.flymemod.ThemeColorHooks.Hooker titleHook;public CharSequence getTitle(android.content.Context c){return mTitle;}public android.graphics.drawable.Icon mIcon;public String mComponentPackage="com.android.settings",mComponentName;public android.graphics.drawable.Drawable mDrawable;public int mImmersiveType=-1;}',
'com/android/settingslib/drawer/TileUtils.java':'package com.android.settingslib.drawer;public class TileUtils{public static int loads;public static void loadTile(android.content.Context c,android.os.UserHandle u,java.util.Map cache,String category,java.util.List out,android.content.Intent intent,android.os.Bundle metadata,android.content.pm.ComponentInfo info){loads++;if(!metadata.category)return;Tile t=new Tile();t.mComponentPackage=info.packageName;t.mComponentName=info.name;try{Object label=Tile.titleHook.intercept(new dev.rikumi.flymemod.ThemeColorHooks.Chain(){public Object getThisObject(){return t;}public Object getArg(int i){return c;}public Object proceed(){return t.getTitle(c);}});if(label==null)throw new AssertionError("dynamic title must recover");}catch(Throwable e){throw new AssertionError(e);}out.add(t);}}',
'com/meizu/settings/home/FlymeDashboardSummary.java':'package com.meizu.settings.home;public class FlymeDashboardSummary{public FlymeDashboardSummaryAdapter mAdapter=new FlymeDashboardSummaryAdapter();public android.app.Activity activity=new android.app.Activity();public android.view.View view=new android.view.View();public int reloads;public android.app.Activity getActivity(){return activity;}public android.view.View getView(){return view;}public void initDashboardTileList(boolean force){if(!force)throw new AssertionError();reloads++;}public final class LoadingTask{public Object doInBackground(Object[] args){return mAdapter;}}}',
'dev/rikumi/flymemod/GoogleHomeTest.java':r'''
package dev.rikumi.flymemod;
import com.meizu.settings.home.*;import com.android.settingslib.drawer.*;import android.os.Handler;import java.util.*;
public class GoogleHomeTest{
 static class Prefs implements android.content.SharedPreferences{boolean enabled;List<OnSharedPreferenceChangeListener> listeners=new ArrayList<>();public boolean getBoolean(String k,boolean d){return enabled;}public int getInt(String k,int d){return d;}public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l){listeners.add(l);}void changed(){for(var l:listeners)l.onSharedPreferenceChanged(this,ModuleSettings.SETTINGS_HOME_FORCE_GOOGLE);}}
 static class Chain implements ThemeColorHooks.Chain{FlymeDashboardSummary page;Object task;int calls;boolean keepNative;Chain(FlymeDashboardSummary p){page=p;task=p.new LoadingTask();}public Object getThisObject(){return task;}public Object proceed(){calls++;if(!keepNative)page.mAdapter.mTileList.clear();return null;}}
 static int checks;static void check(boolean b){checks++;if(!b)throw new AssertionError("check "+checks);}
 public static void main(String[] args)throws Throwable{
  Prefs prefs=new Prefs();Map<String,ThemeColorHooks.Hooker> hooks=new HashMap<>();var feature=new SettingsHomeGoogleHooks(prefs,(m,e)->{throw new AssertionError(m,e);});feature.install(GoogleHomeTest.class.getClassLoader(),(m,h)->hooks.put(m.getName(),h),m->{});check(hooks.size()==2&&hooks.containsKey("doInBackground"));Tile.titleHook=hooks.get("getTitle");var hook=hooks.get("doInBackground");FlymeDashboardSummary page=new FlymeDashboardSummary();Chain chain=new Chain(page);hook.intercept(chain);check(chain.calls==1&&page.mAdapter.mTileList.isEmpty()&&page.activity.pm.queries==0);
  Tile outside=new Tile();outside.mComponentPackage="com.google.android.gms";outside.mComponentName="com.google.android.gms.app.settings.GoogleSettingsIALink";
  check(Tile.titleHook.intercept(new ThemeColorHooks.Chain(){public Object getThisObject(){return outside;}public Object getArg(int i){return page.activity;}public Object proceed(){return null;}})==null);
  prefs.enabled=true;hook.intercept(new Chain(page));check(page.mAdapter.mTileList.size()==1);Tile row=page.mAdapter.mTileList.get(0);check(row.mComponentPackage.equals("com.google.android.gms"));check(row.mComponentName.equals("com.google.android.gms.app.settings.GoogleSettingsIALink"));check(row.mIcon.pkg.equals("com.android.settings"));check(row.mDrawable!=null&&row.mImmersiveType==3);check(row.mTitle.equals("Google"));check(page.mAdapter.notifications==1&&TileUtils.loads==1);
  chain=new Chain(page);chain.keepNative=true;hook.intercept(chain);check(page.mAdapter.mTileList.size()==1&&TileUtils.loads==1);
  prefs.enabled=false;prefs.changed();check(page.reloads==1);hook.intercept(new Chain(page));check(page.mAdapter.mTileList.isEmpty());
  prefs.enabled=true;page.activity.pm.installed=false;hook.intercept(new Chain(page));check(page.mAdapter.mTileList.isEmpty());page.activity.pm.installed=true;
  page.activity.pm.info.enabled=false;hook.intercept(new Chain(page));check(page.mAdapter.mTileList.isEmpty());page.activity.pm.info.enabled=true;
  page.activity.pm.info.applicationInfo.enabled=false;hook.intercept(new Chain(page));check(page.mAdapter.mTileList.isEmpty());page.activity.pm.info.applicationInfo.enabled=true;
  page.activity.pm.info.metaData.category=false;hook.intercept(new Chain(page));check(page.mAdapter.mTileList.isEmpty());page.activity.pm.info.metaData.category=true;
  Handler.defer=true;hook.intercept(new Chain(page));check(page.mAdapter.mTileList.isEmpty());prefs.enabled=false;Handler.drain();check(page.mAdapter.mTileList.isEmpty());
  prefs.enabled=true;hook.intercept(new Chain(page));page.mAdapter=new FlymeDashboardSummaryAdapter();Handler.drain();check(page.mAdapter.mTileList.isEmpty());Handler.defer=false;
  page.view=null;prefs.changed();check(page.reloads==1);
  System.out.println("PASS: "+checks+" actual Google home checks: default/off, real native entry/loader, exact icon, no duplicates, missing/disabled components, native category validation, live reload and stale queued updates");
 }
}
'''
})
files['android/content/res/Resources.java']=files['android/content/res/Resources.java'].replace('return n.equals("settings_button_no_background")?2:1;', 'return n.equals("google_settings_icon")?10:n.equals("settings_button_no_background")?2:1;')
files['android/graphics/drawable/Drawable.java']='package android.graphics.drawable;public class Drawable{}'
files['android/graphics/drawable/Icon.java']='package android.graphics.drawable;public class Icon{public int id;public String pkg="com.android.settings";public static Icon createWithResource(android.content.Context c,int id){Icon i=new Icon();i.id=id;return i;}public Drawable loadDrawable(android.content.Context c){return new Drawable();}}'
files['com/meizu/settings/home/FlymeDashboardSummaryAdapter.java']='package com.meizu.settings.home;public class FlymeDashboardSummaryAdapter{public int notifications;public void notifyDataSetChanged(){notifications++;}public java.util.List<com.android.settingslib.drawer.Tile> mTileList=new java.util.ArrayList<>();}'
with tempfile.TemporaryDirectory(prefix='flyme-google-home-',dir='/private/tmp') as directory:
    base=Path(directory)
    for name,data in files.items():
        path=base/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(data)
    actual=[ROOT/'app/src/main/java/dev/rikumi/flymemod'/f'{n}.java' for n in ['WirelessDebugTextHooks','WirelessDebugTextPolicy','WirelessDebugPageHooks','SettingsHomeGoogleHooks']]
    subprocess.run(['/opt/homebrew/opt/openjdk@21/bin/javac','-d',directory,*[str(base/n) for n in files],*map(str,actual)],check=True)
    subprocess.run(['/opt/homebrew/opt/openjdk@21/bin/java','-cp',directory,'dev.rikumi.flymemod.GoogleHomeTest'],check=True)
