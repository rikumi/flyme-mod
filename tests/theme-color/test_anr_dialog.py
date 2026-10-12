"""Run exact system-server ANR hooks against host lifecycle/window/state mocks."""
from pathlib import Path
import runpy
import subprocess
import tempfile
ROOT=Path(__file__).resolve().parents[2]
files=runpy.run_path(str(Path(__file__).with_name('test_wireless_debug_text.py')))['files'].copy()
files.update({
'android/R.java':'package android;public class R{public static class attr{public static final int windowBackground=1;}}',
'dev/rikumi/flymemod/ModuleSettings.java':'package dev.rikumi.flymemod;class ModuleSettings{static final String WIRELESS_DEBUG_THEME_FIX="wireless_debug_theme_fix",SYSTEM_THEME_COLOR_ENABLED="system_theme_color_enabled",SYSTEM_THEME_COLOR="system_theme_color";static final int SYSTEM_THEME_COLOR_DEFAULT=0xff206cff;}',
'android/graphics/drawable/Drawable.java':'package android.graphics.drawable;public class Drawable{public int color,radius=16;public Drawable(int c){color=c;}}',
'android/content/res/TypedArray.java':'package android.content.res;public class TypedArray{Resources resources;public TypedArray(Resources r){resources=r;}public android.graphics.drawable.Drawable getDrawable(int n){return new android.graphics.drawable.Drawable(resources.config.uiMode==32?0xff1c1c1e:0xffffffff);}public void recycle(){}}',
'android/content/res/Resources.java':'package android.content.res;public class Resources{public Configuration config=new Configuration();public Configuration getConfiguration(){return config;}public int getIdentifier(String n,String t,String p){return n.equals("aerr_wait")?22:0;}public int getColor(int id,Object theme){return 0xffe5e5e5;}}',
'android/content/Context.java':'package android.content;public class Context{public android.content.res.Resources resources=new android.content.res.Resources();public Object getTheme(){return null;}public android.content.res.Resources getResources(){return resources;}public android.content.res.TypedArray obtainStyledAttributes(int[] attrs){return new android.content.res.TypedArray(resources);}}',
'android/view/View.java':'package android.view;public class View{public View root=this;public boolean forceDark=true;public android.content.res.Resources resources=new android.content.res.Resources();public View getRootView(){return root;}public android.content.res.Resources getResources(){return resources;}public android.content.Context getContext(){return new android.content.Context();}public boolean post(Runnable r){r.run();return true;}public void invalidate(){}public void setForceDarkAllowed(boolean b){forceDark=b;}}',
'android/view/Window.java':'package android.view;public class Window{public View root=new View();public android.graphics.drawable.Drawable background=new android.graphics.drawable.Drawable(0xff000000);public View getDecorView(){return root;}public void setBackgroundDrawable(android.graphics.drawable.Drawable d){background=d;}}',
'android/app/AlertDialog.java':'package android.app;public class AlertDialog extends Dialog{public android.content.Context context=new android.content.Context();public java.util.Map<Integer,android.widget.Button> buttons=new java.util.HashMap<>();public java.util.Map<Integer,android.view.View> fields=new java.util.HashMap<>();public android.content.Context getContext(){return context;}public android.widget.Button getButton(int n){return buttons.get(n);}public android.view.View findViewById(int n){return fields.get(n);}}',
'com/android/server/am/BaseErrorDialog.java':'package com.android.server.am;public class BaseErrorDialog extends android.app.AlertDialog{public void onStart(){}public void onStop(){}}',
'com/android/server/am/AppNotRespondingDialog.java':'package com.android.server.am;public class AppNotRespondingDialog extends BaseErrorDialog{public void onCreate(android.os.Bundle b){}}',
'dev/rikumi/flymemod/AnrTest.java':r'''
package dev.rikumi.flymemod;
import android.widget.*;import android.content.res.ColorStateList;import com.android.server.am.*;import java.util.*;
public class AnrTest{
 static class Prefs implements android.content.SharedPreferences{boolean enabled;int selected=0x1222aa77;List<OnSharedPreferenceChangeListener> observers=new ArrayList<>();public boolean getBoolean(String k,boolean d){return enabled;}public int getInt(String k,int d){return selected;}public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l){observers.add(l);}void change(){for(var o:observers)o.onSharedPreferenceChanged(this,null);}}
 static class Chain implements ThemeColorHooks.Chain{Object dialog;Chain(Object d){dialog=d;}public Object getThisObject(){return dialog;}public Object proceed(){return null;}}
 static int checks;static void check(boolean b){checks++;if(!b)throw new AssertionError("check "+checks);}
 public static void main(String[] args) throws Throwable{
  Prefs prefs=new Prefs();var adapter=new AnrDialogHooks((m,e)->{throw new AssertionError(m,e);});Map<String,ThemeColorHooks.Hooker> hooks=new HashMap<>();adapter.install(AnrTest.class.getClassLoader(),(m,h)->hooks.put(m.getName(),h));check(hooks.size()==3);check(!hooks.containsKey("show"));
  AppNotRespondingDialog dialog=new AppNotRespondingDialog();Button wait=new Button();wait.root=dialog.window.root;wait.colors=new ColorStateList(new int[][]{{1},{}},new int[]{0xff1368fb,0x401368fb});ColorStateList nativeWait=wait.colors;dialog.buttons.put(-2,wait);
  Button close=new Button();close.root=dialog.window.root;close.colors=new ColorStateList(new int[][]{{}},new int[]{0xffe42d22});ColorStateList nativeDanger=close.colors;dialog.buttons.put(-1,close);
  TextView report=new TextView();report.root=dialog.window.root;report.colors=nativeWait;dialog.fields.put(22,report);
  hooks.get("onCreate").intercept(new Chain(dialog));check(dialog.window.background.color==0xff1c1c1e&&dialog.window.background.radius==16);check(!dialog.window.root.forceDark);check(AnrDialogHooks.nativeSurface(wait));check(wait.colors==nativeWait&&close.colors==nativeDanger);
  dialog.context.resources.config.uiMode=16;hooks.get("onStart").intercept(new Chain(dialog));check(dialog.window.background.color==0xffffffff&&dialog.window.background.radius==16);check(wait.colors==nativeWait);
  BaseErrorDialog other=new BaseErrorDialog();hooks.get("onStart").intercept(new Chain(other));check(other.window.background.color==0xff000000&&other.window.root.forceDark);check(!AnrDialogHooks.nativeSurface(other.window.root));
  hooks.get("onStop").intercept(new Chain(dialog));check(!AnrDialogHooks.nativeSurface(dialog.window.root));
  System.out.println("PASS: "+checks+" actual ANR checks: exact lifecycle scope, native night/day backgrounds and corners, alpha/states, red danger preservation, toggle/change/stop restoration and CTS clickable text");
 }
}
'''
})
with tempfile.TemporaryDirectory(prefix='flyme-anr-',dir='/private/tmp') as directory:
    base=Path(directory)
    for name,data in files.items():
        path=base/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(data)
    actual=[ROOT/'app/src/main/java/dev/rikumi/flymemod'/f'{n}.java' for n in ['WirelessDebugTextHooks','WirelessDebugTextPolicy','AnrDialogHooks','AnrDialogPolicy','ThemeColorPalette']]
    subprocess.run(['/opt/homebrew/opt/openjdk@21/bin/javac','-d',directory,*[str(base/n) for n in files],*map(str,actual)],check=True)
    subprocess.run(['/opt/homebrew/opt/openjdk@21/bin/java','-cp',directory,'dev.rikumi.flymemod.AnrTest'],check=True)
