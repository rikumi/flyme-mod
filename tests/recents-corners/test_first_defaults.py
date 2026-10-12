"""Compile the actual first-default initializer and verify only absent switches change."""
from pathlib import Path
import tempfile
import subprocess
root=Path(__file__).resolve().parents[2]
java='''package dev.rikumi.flymemod;
import android.content.SharedPreferences;import java.util.*;
public class DesktopFeatureDefaultsTest {
 static int checks;
 static void check(boolean result){checks++;if(!result)throw new AssertionError("check "+checks);}
 static class Prefs implements SharedPreferences {
  Map<String,Object> values=new HashMap<>();int commits;
  public boolean contains(String key){return values.containsKey(key);}
  public Editor edit(){return new Editor(){Map<String,Object> pending=new HashMap<>();
   public Editor putBoolean(String key,boolean value){pending.put(key,value);return this;}
   public boolean commit(){values.putAll(pending);commits++;return true;}};}
 }
 public static void main(String[] args){
  String radius=ModuleSettings.RECENTS_CORNER_ENABLED,icons=ModuleSettings.SYSTEM_ICON_PACK;
  Prefs first=new Prefs();DesktopFeatureDefaults.initialize(first);
  check(Boolean.TRUE.equals(first.values.get(radius)));check(Boolean.TRUE.equals(first.values.get(icons)));
  check(first.commits==1);DesktopFeatureDefaults.initialize(first);check(first.commits==1);
  for(String key:new String[]{ModuleSettings.FIX_ICON_PACK_PREVIEW,ModuleSettings.BACK_GESTURE_REMOVE_SMART,
      ModuleSettings.BACK_GESTURE_DARK_BACKGROUND,ModuleSettings.GESTURE_BAR_WIDTH_ENABLED,
      ModuleSettings.FIX_ZYGOTE_RESTART_UNLOCK,ModuleSettings.REMOVE_FINGERPRINT_ANIMATION}) {
   check(Boolean.TRUE.equals(first.values.get(key)));
   for(boolean saved:new boolean[]{false,true}) {
    Prefs p=new Prefs();p.values.put(key,saved);DesktopFeatureDefaults.initialize(p);
    check(p.values.get(key).equals(saved));int commits=p.commits;
    DesktopFeatureDefaults.initialize(p);check(p.commits==commits);
   }
  }
  for(int r=-1;r<=1;r++)for(int i=-1;i<=1;i++)for(int dp=10;dp<=90;dp++) {
   Prefs p=new Prefs();if(r>=0)p.values.put(radius,r==1);if(i>=0)p.values.put(icons,i==1);
   p.values.put("recents_corner_radius_dp",dp);p.values.put("pack","existing.pack");p.values.put("unrelated",false);
   DesktopFeatureDefaults.initialize(p);
   check(p.values.get(radius).equals(r==-1||r==1));check(p.values.get(icons).equals(i==-1||i==1));
   check(p.values.get("recents_corner_radius_dp").equals(dp));check(p.values.get("pack").equals("existing.pack"));check(p.values.get("unrelated").equals(false));
   int commits=p.commits;DesktopFeatureDefaults.initialize(p);check(commits==p.commits);
  }
  first.values.put(radius,false);first.values.put(icons,false);DesktopFeatureDefaults.initialize(first);
  check(first.values.get(radius).equals(false));check(first.values.get(icons).equals(false));
  System.out.println("PASS: "+checks+" actual first-default checks: absent keys on, explicit false/true, custom dp/pack retained, idempotent initialization");
 }
}'''
with tempfile.TemporaryDirectory(prefix='flyme-first-defaults-',dir='/private/tmp') as temp:
 p=Path(temp);(p/'DesktopFeatureDefaultsTest.java').write_text(java)
 q=p/'android/content/SharedPreferences.java';q.parent.mkdir(parents=True);q.write_text('package android.content;public interface SharedPreferences{boolean contains(String key);Editor edit();interface Editor{Editor putBoolean(String key,boolean value);boolean commit();}}')
 m=p/'ModuleSettings.java';m.write_text('package dev.rikumi.flymemod;public class ModuleSettings{public static final String RECENTS_CORNER_ENABLED="radius",SYSTEM_ICON_PACK="icons",FIX_ICON_PACK_PREVIEW="preview",BACK_GESTURE_REMOVE_SMART="smart",BACK_GESTURE_DARK_BACKGROUND="dark",GESTURE_BAR_WIDTH_ENABLED="bar",FIX_ZYGOTE_RESTART_UNLOCK="restart",REMOVE_FINGERPRINT_ANIMATION="fingerprint";}')
 subprocess.run(['/opt/homebrew/opt/openjdk@21/bin/javac','-d',str(p/'classes'),str(root/'app/src/main/java/dev/rikumi/flymemod/DesktopFeatureDefaults.java'),str(q),str(m),str(p/'DesktopFeatureDefaultsTest.java')],check=True)
 subprocess.run(['/opt/homebrew/opt/openjdk@21/bin/java','-cp',str(p/'classes'),'dev.rikumi.flymemod.DesktopFeatureDefaultsTest'],check=True)
