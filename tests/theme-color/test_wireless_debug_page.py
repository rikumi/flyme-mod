"""Run actual preference-binding hooks, live switch restoration and icon scope checks."""
from pathlib import Path
import runpy
import subprocess
import tempfile
ROOT=Path(__file__).resolve().parents[2]
files=runpy.run_path(str(Path(__file__).with_name('test_wireless_debug_text.py')))['files'].copy()
files.update({
'dev/rikumi/flymemod/ModuleSettings.java':'package dev.rikumi.flymemod;class ModuleSettings{static final String WIRELESS_DEBUG_THEME_FIX="wireless_debug_theme_fix",SYSTEM_THEME_COLOR_ENABLED="system_theme_color_enabled",SYSTEM_THEME_COLOR="system_theme_color";static final int SYSTEM_THEME_COLOR_DEFAULT=0xff206cff;}',
'android/util/DisplayMetrics.java':'package android.util;public class DisplayMetrics{public float density=1;}',
'android/content/res/Resources.java':'package android.content.res;public class Resources{public Configuration config=new Configuration();public Configuration getConfiguration(){return config;}public int getIdentifier(String n,String t,String p){return n.equals("settings_button_no_background")?2:1;}public int getColor(int id,Object theme){return 0xffe5e5e5;}public android.util.DisplayMetrics getDisplayMetrics(){return new android.util.DisplayMetrics();}}',
'android/R.java':'package android;public class R{public static class id{public static final int icon=1;}}',
'android/graphics/ColorFilter.java':'package android.graphics;public class ColorFilter{}',
'android/graphics/PorterDuff.java':'package android.graphics;public class PorterDuff{public enum Mode{SRC_IN}}',
'android/graphics/PorterDuffColorFilter.java':'package android.graphics;public class PorterDuffColorFilter extends ColorFilter{public int color;public PorterDuffColorFilter(int c,PorterDuff.Mode m){color=c;}}',
'android/graphics/Paint.java':'package android.graphics;public class Paint{public ColorFilter filter;public void setColorFilter(ColorFilter c){filter=c;}}',
'android/widget/ImageView.java':'package android.widget;public class ImageView extends android.view.View{}',
'android/view/View.java':'package android.view;public class View{public View root=this,parent;public int end=28,start=20;public java.util.Map<Integer,View> children=new java.util.HashMap<>();public android.content.res.Resources resources=new android.content.res.Resources();public View getRootView(){return root;}public View getParent(){return parent;}public android.content.res.Resources getResources(){return resources;}public android.content.Context getContext(){return new android.content.Context();}public View findViewById(int id){return children.get(id);}public int getPaddingStart(){return start;}public int getPaddingEnd(){return end;}public int getPaddingTop(){return 4;}public int getPaddingBottom(){return 4;}public void setPaddingRelative(int s,int t,int e,int b){start=s;end=e;}public boolean post(Runnable r){r.run();return true;}public void invalidate(){}}',
'androidx/preference/PreferenceViewHolder.java':'package androidx.preference;public class PreferenceViewHolder{public android.view.View itemView;public PreferenceViewHolder(android.view.View row){itemView=row;}}',
'androidx/preference/PreferenceGroupAdapter.java':'package androidx.preference;public class PreferenceGroupAdapter{public void onBindViewHolder(PreferenceViewHolder h,int n){}}',
'androidx/preference/Preference.java':'package androidx.preference;public class Preference{public String mKey;public void onBindViewHolder(PreferenceViewHolder holder){}}',
'com/android/settings/development/AdbPairedDevicePreference.java':'package com.android.settings.development;public class AdbPairedDevicePreference extends androidx.preference.Preference{public void onBindViewHolder(androidx.preference.PreferenceViewHolder holder){}}',
'dev/rikumi/flymemod/ThemeColorHooks.java':'package dev.rikumi.flymemod;class ThemeColorHooks{interface Chain{Object getThisObject();default Object getArg(int i){return null;}Object proceed() throws Throwable;}interface Hooker{Object intercept(Chain c) throws Throwable;}interface Installer{void hook(java.lang.reflect.Executable e,Hooker h);}}',
'dev/rikumi/flymemod/WirelessPageTest.java':r'''
package dev.rikumi.flymemod;
import android.view.View;import android.widget.ImageView;import androidx.preference.*;import java.util.*;
public class WirelessPageTest{
 static class Prefs implements android.content.SharedPreferences{boolean fix,theme;int color=0xffaabbcc;List<OnSharedPreferenceChangeListener> observers=new ArrayList<>();public boolean getBoolean(String k,boolean d){return ModuleSettings.WIRELESS_DEBUG_THEME_FIX.equals(k)?fix:theme;}public int getInt(String k,int d){return color;}public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l){observers.add(l);}void changed(){for(var o:observers)o.onSharedPreferenceChanged(this,null);}}
 static class Chain implements ThemeColorHooks.Chain{Object pref,holder;Chain(Object p,Object h){pref=p;holder=h;}public Object getThisObject(){return pref;}public Object getArg(int n){return holder;}public Object proceed(){return null;}}
 static int checks;static void check(boolean b){checks++;if(!b)throw new AssertionError("check "+checks);}
 public static void main(String[] args) throws Throwable{
  Prefs prefs=new Prefs();var page=new WirelessDebugPageHooks(prefs,(m,e)->{throw new AssertionError(m,e);});Map<String,ThemeColorHooks.Hooker> hooks=new HashMap<>();page.install(WirelessPageTest.class.getClassLoader(),(m,h)->hooks.put(m.getDeclaringClass().getName(),h),m->{});check(hooks.size()==2);
  View row=new View();ImageView icon=new ImageView(),gear=new ImageView();icon.parent=row;gear.parent=row;row.children.put(1,icon);row.children.put(2,gear);PreferenceViewHolder holder=new PreferenceViewHolder(row);
  Preference preference=new Preference();preference.mKey="adb_pair_method_qrcode_pref";var base=hooks.get("androidx.preference.Preference");base.intercept(new Chain(preference,holder));check(page.iconPaint(icon)==null);
  prefs.fix=true;prefs.changed();check(page.iconPaint(icon)==null);prefs.theme=true;prefs.changed();check(page.iconPaint(icon)==null);check(page.iconPaint(gear)==null);check(page.iconPaint(new ImageView())==null);
  prefs.color=0xff334455;prefs.changed();check(page.iconPaint(icon)==null);
  preference.mKey="adb_pair_method_code_pref";base.intercept(new Chain(preference,holder));check(page.iconPaint(icon)==null);
  preference.mKey="unrelated_icon";base.intercept(new Chain(preference,holder));check(page.iconPaint(icon)==null);
  var paired=new com.android.settings.development.AdbPairedDevicePreference();base.intercept(new Chain(paired,holder));hooks.get(paired.getClass().getName()).intercept(new Chain(paired,holder));check(page.iconPaint(gear)==null);check(page.iconPaint(icon)==null);check(row.end==16&&row.start==20);
  prefs.fix=false;prefs.changed();check(row.end==28);check(page.iconPaint(gear)==null);prefs.fix=true;prefs.changed();check(row.end==16);
  base.intercept(new Chain(preference,holder));check(row.end==28);check(page.iconPaint(gear)==null);
  row.end=8;base.intercept(new Chain(paired,holder));hooks.get(paired.getClass().getName()).intercept(new Chain(paired,holder));check(row.end==8);prefs.fix=false;prefs.changed();check(row.end==8);
  check(!WirelessDebugTextPolicy.pageIconPreference(null));check(!WirelessDebugTextPolicy.pageIconPreference("adb_wifi_enabled"));
  System.out.println("PASS: "+checks+" actual wireless page checks: both switches, explicit icon binding, native restoration, row recycling, accent changes and gear spacing");
 }
}
'''
})
with tempfile.TemporaryDirectory(prefix='flyme-wireless-page-',dir='/private/tmp') as directory:
    base=Path(directory)
    for name,data in files.items():
        path=base/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(data)
    actual=[ROOT/'app/src/main/java/dev/rikumi/flymemod'/f'{n}.java' for n in ['WirelessDebugTextHooks','WirelessDebugTextPolicy','WirelessDebugPageHooks']]
    subprocess.run(['/opt/homebrew/opt/openjdk@21/bin/javac','-d',directory,*[str(base/n) for n in files],*map(str,actual)],check=True)
    subprocess.run(['/opt/homebrew/opt/openjdk@21/bin/java','-cp',directory,'dev.rikumi.flymemod.WirelessPageTest'],check=True)
