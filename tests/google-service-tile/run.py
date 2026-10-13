#!/usr/bin/env python3
"""Run the production tile and receiver with deterministic Android lifecycle doubles.

These checks cover state and write authorization, not actual Flyme service freezing.
"""
from pathlib import Path
import subprocess
import tempfile

repo = Path(__file__).resolve().parents[2]
stubs = {
    'android/app/Activity.java': 'package android.app; public class Activity { public static final int RESULT_OK=-1, RESULT_CANCELED=0, RESULT_FIRST_USER=1; }',
    'android/app/Application.java': 'package android.app; public class Application { public static String process="com.android.systemui"; public static String getProcessName(){return process;} }',
    'android/app/KeyguardManager.java': 'package android.app; public class KeyguardManager { public boolean locked; public boolean isDeviceLocked(){return locked;} }',
    'android/os/Looper.java': 'package android.os; public class Looper { public static Looper getMainLooper(){return new Looper();} }',
    'android/os/Handler.java': 'package android.os; public class Handler { public Handler(Looper l){} }',
    'android/os/Build.java': 'package android.os; public class Build { public static class VERSION { public static int SDK_INT=36; } }',
    'android/net/Uri.java': 'package android.net; public record Uri(String key) {}',
    'android/content/Intent.java': '''package android.content;
public class Intent {
 public static final String ACTION_CLOSE_SYSTEM_DIALOGS="android.intent.action.CLOSE_SYSTEM_DIALOGS";
 public final String action; public String pkg,reason; public boolean toggle;
 public Intent(String a){action=a;} public String getAction(){return action;}
 public Intent setPackage(String p){pkg=p;return this;}
 public Intent putExtra(String k,boolean v){toggle=v;return this;}
 public Intent putExtra(String k,String v){reason=v;return this;}
 public boolean getBooleanExtra(String k,boolean d){return toggle;}
}''',
    'android/content/IntentFilter.java': 'package android.content; public record IntentFilter(String action) {}',
    'android/content/BroadcastReceiver.java': '''package android.content;
public abstract class BroadcastReceiver {
 public int code; public boolean ordered=true;
 public abstract void onReceive(Context c,Intent i);
 public int getResultCode(){return code;} public void setResultCode(int c){code=c;}
 public boolean isOrderedBroadcast(){return ordered;}
}''',
    'android/content/pm/PackageManager.java': '''package android.content.pm;
public class PackageManager {
 public boolean installed=true;
 public Object getApplicationInfo(String p,int f) throws NameNotFoundException {
  if(!installed)throw new NameNotFoundException();return new Object();
 }
 public static class NameNotFoundException extends Exception {}
}''',
    'android/database/ContentObserver.java': '''package android.database;
public abstract class ContentObserver { public ContentObserver(android.os.Handler h){} public void onChange(boolean b){} }''',
    'android/content/ContentResolver.java': '''package android.content;
import android.database.ContentObserver; import android.net.Uri;
public class ContentResolver {
 public int value,writes; public boolean writeOk=true,fail; public ContentObserver observer;
 public void registerContentObserver(Uri u,boolean descendants,ContentObserver o){observer=o;}
 public void unregisterContentObserver(ContentObserver o){if(observer!=o)throw new AssertionError();observer=null;}
 public void external(int v){value=v;if(observer!=null)observer.onChange(false);}
}''',
    'android/provider/Settings.java': '''package android.provider;
import android.content.ContentResolver; import android.net.Uri;
public class Settings { public static class Global {
 public static int getInt(ContentResolver r,String k,int d){return r.value;}
 public static boolean putInt(ContentResolver r,String k,int v){
  if(r.fail)throw new SecurityException();if(!r.writeOk)return false;r.writes++;r.external(v);return true;
 }
 public static Uri getUriFor(String k){return new Uri(k);}
} }''',
    'android/content/Context.java': '''package android.content;
import android.app.KeyguardManager; import android.content.pm.PackageManager; import android.os.Handler;
public class Context {
 public static final int RECEIVER_EXPORTED=2;
 public static BroadcastReceiver backend; public static String permission;
 public final ContentResolver resolver=new ContentResolver();
 public final PackageManager pm=new PackageManager(); public final KeyguardManager keyguard=new KeyguardManager();
 public BroadcastReceiver callback; public Intent intent;
 public int collapses; public Runnable onCollapse;
 public ContentResolver getContentResolver(){return resolver;} public PackageManager getPackageManager(){return pm;}
 public <T>T getSystemService(Class<T> c){return c.cast(keyguard);}
 public Intent registerReceiver(BroadcastReceiver b,IntentFilter f,String p,Handler h,int flags){
  if(flags!=RECEIVER_EXPORTED)throw new AssertionError();backend=b;permission=p;return null;
 }
 public void sendOrderedBroadcast(Intent i,String p,BroadcastReceiver b,Handler h,int code,String data,Object extras){
  if(!"com.android.systemui".equals(i.pkg))throw new AssertionError();intent=i;callback=b;b.code=code;
 }
 public void sendBroadcast(Intent i){
  if(!Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(i.action)||!"com.android.systemui".equals(i.pkg))throw new AssertionError();
  collapses++;if(onCollapse!=null)onCollapse.run();
 }
 public void deliver(){BroadcastReceiver b=callback;callback=null;
  if(backend!=null){backend.code=b.code;backend.onReceive(this,intent);b.code=backend.code;}b.onReceive(this,intent);
 }
 public String getString(int id){return Integer.toString(id);}
}''',
    'android/graphics/drawable/Icon.java': 'package android.graphics.drawable; public class Icon { public static Icon createWithResource(android.content.Context c,int id){return new Icon();} }',
    'android/service/quicksettings/Tile.java': '''package android.service.quicksettings;
public class Tile {
 public static final int STATE_UNAVAILABLE=0,STATE_INACTIVE=1,STATE_ACTIVE=2;
 public int state; public String label,subtitle,description;
 public void setState(int s){state=s;} public void setIcon(android.graphics.drawable.Icon i){}
 public void setLabel(String s){label=s;} public void setSubtitle(String s){subtitle=s;}
 public void setContentDescription(String s){description=s;} public void updateTile(){}
}''',
    'android/service/quicksettings/TileService.java': '''package android.service.quicksettings;
public class TileService extends android.content.Context {
 public Tile tile=new Tile(); public Runnable unlock;
 public void onStartListening(){} public void onStopListening(){} public void onDestroy(){} public void onClick(){}
 public Tile getQsTile(){return tile;} public boolean isLocked(){return keyguard.locked;}
 public void unlockAndRun(Runnable r){unlock=r;}
}''',
    'android/widget/Toast.java': '''package android.widget;
public class Toast { public static final int LENGTH_LONG=1; public static int shown; public static String last;
 public static Toast makeText(android.content.Context c,String s,int length){last=s;return new Toast();}
 public void show(){shown++;}
}''',
    'com/android/systemui/SystemUIApplication.java': 'package com.android.systemui; public class SystemUIApplication { public void onCreate(){} }',
    'dev/rikumi/flymemod/SignalHooks.java': '''package dev.rikumi.flymemod;
public class SignalHooks {
 public interface Chain { Object proceed(); Object getThisObject(); }
 public interface Hook { Object apply(Chain c); }
 public interface Installer { void hook(String c,String m,Hook h,Class<?>... p); }
}''',
    'dev/rikumi/flymemod/R.java': '''package dev.rikumi.flymemod;
public class R { public static class string {
 public static final int google_service_tile=1,google_service_enabled=2,google_service_disabled=3,
 google_service_pending=4,google_service_not_installed=5,google_service_bridge_unavailable=6,
 google_service_write_failed=7,google_service_locked=8,google_service_enabling=9,google_service_disabling=10;
} public static class drawable { public static final int ic_google_service_tile=1; } }''',
}
with tempfile.TemporaryDirectory(prefix='flyme-google-tile-', dir='/private/tmp') as directory:
    output = Path(directory)
    sources = []
    for name, code in stubs.items():
        path = output / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(code)
        sources.append(str(path))
    for name in ['GoogleServiceTile', 'GoogleServiceTileHooks', 'GoogleServiceTileService']:
        sources.append(str(repo / 'app/src/main/java/dev/rikumi/flymemod' / f'{name}.java'))
    sources.append(str(Path(__file__).with_name('GoogleServiceTileTest.java')))
    jdk = Path('/opt/homebrew/opt/openjdk@21/bin')
    subprocess.run([str(jdk / 'javac'), '-d', directory, *sources], check=True)
    subprocess.run([str(jdk / 'java'), '-cp', directory, 'dev.rikumi.flymemod.GoogleServiceTileTest'], check=True)
