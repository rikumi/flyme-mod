"""Compile the actual wireless dialog hooks/policy against host Android mocks."""
from pathlib import Path
import subprocess
import tempfile

ROOT=Path(__file__).resolve().parents[2]
source=(ROOT/'app/src/main/java/dev/rikumi/flymemod/ThemeColorHooks.java').read_text()
start=source.index('            TextView view = (TextView) chain.getThisObject();',source.index('"android.widget.TextView", "onDraw"'))
end=source.index('        }, Canvas.class);',start)
draw=source[start:end]
files={
'android/content/SharedPreferences.java':'package android.content;public interface SharedPreferences{boolean getBoolean(String k,boolean d);int getInt(String k,int d);void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l);interface OnSharedPreferenceChangeListener{void onSharedPreferenceChanged(SharedPreferences p,String k);}}',
'android/view/ViewGroup.java':'package android.view;public class ViewGroup extends View{public int getChildCount(){return 0;}public View getChildAt(int i){return null;}}',
'dev/rikumi/flymemod/ModuleSettings.java':'package dev.rikumi.flymemod;class ModuleSettings{static final String WIRELESS_DEBUG_THEME_FIX="wireless_debug_theme_fix";} ',
'android/content/res/Configuration.java':'package android.content.res;public class Configuration{public static final int UI_MODE_NIGHT_MASK=48,UI_MODE_NIGHT_YES=32;public int uiMode=32;}',
'android/content/res/ColorStateList.java':'package android.content.res;public class ColorStateList{int[][] states;int[] colors;public ColorStateList(int[][] s,int[] c){states=s;colors=c;}public int[][] getStates(){return states;}public int[] getColors(){return colors;}}',
'android/content/res/Resources.java':'package android.content.res;public class Resources{public Configuration config=new Configuration();public Configuration getConfiguration(){return config;}public int getIdentifier(String n,String t,String p){return 1;}public int getColor(int id,Object theme){return 0xffe5e5e5;}}',
'android/content/Context.java':'package android.content;public class Context{public Object getTheme(){return null;}}',
'android/view/View.java':'package android.view;public class View{public View root=this;public android.content.res.Resources resources=new android.content.res.Resources();public View getRootView(){return root;}public boolean post(Runnable r){r.run();return true;}public void invalidate(){}public android.content.res.Resources getResources(){return resources;}public android.content.Context getContext(){return new android.content.Context();}}',
'android/view/Window.java':'package android.view;public class Window{public View root=new View();public View getDecorView(){return root;}}',
'android/widget/TextView.java':'package android.widget;import android.content.res.ColorStateList;public class TextView extends android.view.View{public String error;public ColorStateList colors=new ColorStateList(new int[][]{{1},{}},new int[]{0xff000000,0x61000000});public CharSequence getError(){return error;}public ColorStateList getTextColors(){return colors;}public void setTextColor(ColorStateList c){colors=c;}}',
'android/widget/Button.java':'package android.widget;public class Button extends TextView{}',
'android/widget/EditText.java':'package android.widget;public class EditText extends TextView{}',
'android/os/Bundle.java':'package android.os;public class Bundle{}',
'android/app/Activity.java':'package android.app;public class Activity{public android.view.Window window=new android.view.Window();public android.view.Window getWindow(){return window;}}',
'android/app/Dialog.java':'package android.app;public class Dialog{public android.view.Window window=new android.view.Window();public android.view.Window getWindow(){return window;}}',
'com/android/settings/development/AdbWirelessDialog.java':'package com.android.settings.development;public class AdbWirelessDialog extends android.app.Dialog{}',
'com/android/systemui/wifi/WifiDebuggingActivity.java':'package com.android.systemui.wifi;public class WifiDebuggingActivity extends android.app.Activity{public void onCreate(android.os.Bundle b){}}',
'com/android/systemui/wifi/WifiDebuggingSecondaryUserActivity.java':'package com.android.systemui.wifi;public class WifiDebuggingSecondaryUserActivity extends android.app.Activity{public void onCreate(android.os.Bundle b){}}',
'dev/rikumi/flymemod/ThemeColorHooks.java':'package dev.rikumi.flymemod;class ThemeColorHooks{interface Chain{Object getThisObject();Object proceed() throws Throwable;}interface Hooker{Object intercept(Chain c) throws Throwable;}interface Installer{void hook(java.lang.reflect.Executable e,Hooker h);}}',
'dev/rikumi/flymemod/WirelessDebugTest.java':r'''
package dev.rikumi.flymemod;
import android.widget.TextView;import android.content.res.ColorStateList;import java.util.*;
public class WirelessDebugTest{
 static class Prefs implements android.content.SharedPreferences{boolean enabled=true;public boolean getBoolean(String k,boolean d){return enabled;}public int getInt(String k,int d){return d;}public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l){}}
 static Prefs prefs=new Prefs();static WirelessDebugTextHooks wirelessDebugText;
 static Object iconFilters;static String packageName="com.android.settings";static int PRIMARY=2,selectedColor=0xff333333;
 static class ThemeIconFilter{}static boolean dialogButton(TextView v){return false;}static String viewId(TextView v){return "";}
 static class ThemeTabPolicy{static boolean galleryText(String p,String id){return false;}}
 static ColorStateList colorList(ColorStateList c,int type,Object r){return c;}
 static class Chain implements ThemeColorHooks.Chain{Object view;boolean fail;Chain(Object v){view=v;}public Object getThisObject(){return view;}public Object proceed(){if(fail)throw new IllegalStateException();return view instanceof TextView t?t.colors:null;}}
 static Object draw(Chain chain) throws Throwable{DRAW}
 static int checks;static void check(boolean b){checks++;if(!b)throw new AssertionError("check "+checks);}
 public static void main(String[] args) throws Throwable{
  var hooks=new HashMap<String,ThemeColorHooks.Hooker>();var loader=WirelessDebugTest.class.getClassLoader();
  wirelessDebugText=new WirelessDebugTextHooks(packageName,prefs,(m,e)->{throw new AssertionError(m,e);});wirelessDebugText.install(loader,(m,h)->hooks.put(m.getDeclaringClass().getName(),h));
  check(hooks.isEmpty());var dialog=new com.android.settings.development.AdbWirelessDialog();wirelessDebugText.shown(dialog);
  TextView text=new TextView();text.root=dialog.window.root;ColorStateList original=text.colors;
  ColorStateList mapped=wirelessDebugText.textColors(text,null);check(mapped.getColors()[0]==0xffe5e5e5);check(mapped.getColors()[1]==0x61e5e5e5);check(mapped.getStates()==original.getStates());check(text.colors==original);
  prefs.enabled=false;check(wirelessDebugText.textColors(text,null)==null);check(draw(new Chain(text))==original);prefs.enabled=true;
  check(draw(new Chain(text)) instanceof ColorStateList);check(text.colors==original);
  Chain failed=new Chain(text);failed.fail=true;try{draw(failed);}catch(IllegalStateException expected){}check(text.colors==original);
  text.resources.config.uiMode=16;check(wirelessDebugText.textColors(text,null)==null);check(draw(new Chain(text))==original);text.resources.config.uiMode=32;
  text.error="invalid";check(wirelessDebugText.textColors(text,null)==null);text.error=null;
  var input=new android.widget.EditText();input.root=text.root;check(wirelessDebugText.textColors(input,null)==null);
  check(wirelessDebugText.textColors(new TextView(),null)==null);
  var other=new android.app.Dialog();wirelessDebugText.shown(other);text.root=other.window.root;check(wirelessDebugText.textColors(text,null)==null);
  text.root=dialog.window.root;text.colors=new ColorStateList(new int[][]{{}},new int[]{0xff206cff});check(wirelessDebugText.textColors(text,null)==null);
  var button=new android.widget.Button();button.root=dialog.window.root;button.colors=new ColorStateList(new int[][]{{}},new int[]{0xff333333});check(wirelessDebugText.textColors(button,0xff333333)==null);check(wirelessDebugText.textColors(button,null)!=null);
  var auth=new WirelessDebugTextHooks("com.android.systemui",prefs,(m,e)->{throw new AssertionError(m,e);});auth.install(loader,(m,h)->hooks.put(m.getDeclaringClass().getName(),h));check(hooks.size()==2);
  var activity=new com.android.systemui.wifi.WifiDebuggingActivity();hooks.get(activity.getClass().getName()).intercept(new Chain(activity));text=new TextView();text.root=activity.window.root;check(auth.textColors(text,null)!=null);text.resources.config.uiMode=16;check(auth.textColors(text,null)==null);
  var otherApp=new WirelessDebugTextHooks("com.meizu.battery",prefs,(m,e)->{throw new AssertionError(m,e);});otherApp.install(loader,(m,h)->{throw new AssertionError();});otherApp.shown(dialog);check(otherApp.textColors(new TextView(),null)==null);
  for(int alpha=0;alpha<256;alpha++){
   for(int rgb:new int[]{0,0x333333,0x3c3c43,0x777777}){int nativeColor=alpha<<24|rgb;int result=WirelessDebugTextPolicy.readable(nativeColor,0xffeeeeee);check(result>>>24==alpha);check(alpha==0?result==nativeColor:(result&0xffffff)==0xeeeeee);}
   for(int rgb:new int[]{0x206cff,0xe42d22,0x35c44f,0xffbe0a,0xeeeeee,0x999999}){int c=alpha<<24|rgb;check(WirelessDebugTextPolicy.readable(c,0xffeeeeee)==c);}
  }
  check(!WirelessDebugTextPolicy.pairingDialog("com.android.systemui",dialog.getClass().getName()));check(!WirelessDebugTextPolicy.pairingDialog(packageName,"android.app.AlertDialog"));
  System.out.println("PASS: "+checks+" actual wireless debug text checks: scope/night, all alpha values, text states, native restoration, accent/error preservation and authorization registration");
 }
}
'''.replace('DRAW',draw).replace('static Object iconFilters','static ThemeIconFilter iconFilters')
}
with tempfile.TemporaryDirectory(prefix='flyme-wireless-text-',dir='/private/tmp') as directory:
    base=Path(directory)
    for name,data in files.items():
        path=base/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(data)
    actual=[ROOT/'app/src/main/java/dev/rikumi/flymemod'/f'{n}.java' for n in ['WirelessDebugTextHooks','WirelessDebugTextPolicy']]
    subprocess.run(['/opt/homebrew/opt/openjdk@21/bin/javac','-d',directory,*[str(base/n) for n in files],*map(str,actual)],check=True)
    subprocess.run(['/opt/homebrew/opt/openjdk@21/bin/java','-cp',directory,'dev.rikumi.flymemod.WirelessDebugTest'],check=True)
