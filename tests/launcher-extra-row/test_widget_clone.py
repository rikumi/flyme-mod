"""Run production configuration-copy logic with minimal Android API substitutes."""
from pathlib import Path
import subprocess
import tempfile

jdk = Path('/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home/bin')
with tempfile.TemporaryDirectory() as folder:
    root = Path(folder)
    sources = {
        'android/os/Bundle.java': '''package android.os;
public class Bundle extends java.util.HashMap<String,Object> {
 public Bundle() {} public Bundle(Bundle source) { super(source); }
 public void putInt(String key,int value) { put(key,value); }
}''',
        'android/content/SharedPreferences.java': '''package android.content;
public interface SharedPreferences {
 boolean contains(String key); String getString(String key,String fallback); Editor edit();
 interface Editor { Editor putString(String key,String value); boolean commit(); }
}''',
        'dev/rikumi/flymemod/WidgetCloneTest.java': '''package dev.rikumi.flymemod;
import android.content.SharedPreferences;
import android.os.Bundle;
public class WidgetCloneTest {
 static class Prefs implements SharedPreferences, SharedPreferences.Editor {
  java.util.Map<String,String> data = new java.util.HashMap<>(); boolean fail;
  public boolean contains(String key) { return data.containsKey(key); }
  public String getString(String key,String fallback) { return data.getOrDefault(key,fallback); }
  public SharedPreferences.Editor edit() { return this; }
  public SharedPreferences.Editor putString(String key,String value) { if(!fail) data.put(key,value); return this; }
  public boolean commit() { return !fail; }
 }
 static void check(boolean ok) { if(!ok) throw new AssertionError(); }
 public static void main(String[] args) {
  Prefs p = new Prefs(); p.data.put("6", "{configuration:original}");
  check(LauncherWidgetClone.copyConfiguration(p,6,10));
  check(p.data.get("6").equals(p.data.get("10")));
  p.data.put("10","user edited");
  check(LauncherWidgetClone.copyConfiguration(p,6,10));
  check(p.data.get("10").equals("user edited"));
  check(p.data.get("6").equals("{configuration:original}"));
  check(LauncherWidgetClone.copyConfiguration(p,99,11)); check(!p.contains("11"));
  p.fail=true; check(!LauncherWidgetClone.copyConfiguration(p,6,12));
  Bundle original=new Bundle(); original.putInt("flymeWidgetOldId",6);
  original.putInt("flymeWidgetNewId",10); original.put("flymeWidgetIdChanged",true);
  original.put("appWidgetRestoreCompleted",true); original.putInt("appWidgetMinWidth",180);
  Bundle copy=LauncherWidgetClone.options(original,6,true);
  check(copy.get(LauncherWidgetClone.SOURCE).equals(6));
  check(copy.get("appWidgetMinWidth").equals(180));
  check(!copy.containsKey("flymeWidgetIdChanged") && !copy.containsKey("flymeWidgetOldId"));
  check(!copy.containsKey("flymeWidgetNewId") && !copy.containsKey("appWidgetRestoreCompleted"));
  check(original.containsKey("flymeWidgetIdChanged"));
  check(!LauncherWidgetClone.options(original,6,false).containsKey(LauncherWidgetClone.SOURCE));
  System.out.println("PASS: non-destructive config copy, preserved user edits, default configuration, failed commit, sanitized options");
 }
}'''}
    for name, source in sources.items():
        path = root/name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(source)
    production = Path('app/src/main/java/dev/rikumi/flymemod/LauncherWidgetClone.java')
    subprocess.run([str(jdk/'javac'), '-d', str(root), str(production), *map(str,root.rglob('*.java'))], check=True)
    subprocess.run([str(jdk/'java'), '-cp', str(root), 'dev.rikumi.flymemod.WidgetCloneTest'], check=True)
