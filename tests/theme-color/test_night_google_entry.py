"""Exercise production cached-list reconciliation using the ROM model and comparator signatures."""
from pathlib import Path
import subprocess,tempfile
ROOT=Path(__file__).resolve().parents[2]
files={
'android/provider/Settings.java':'package android.provider;public class Settings{public static class Global{public static String black="";public static String getString(Object resolver,String key){return black;}}}',
'android/content/Context.java':'package android.content;public class Context{public Object getContentResolver(){return null;}public android.content.pm.PackageManager pm=new android.content.pm.PackageManager();public android.content.pm.PackageManager getPackageManager(){return pm;}}',
'android/content/pm/ApplicationInfo.java':'package android.content.pm;public class ApplicationInfo{public String label="Google Play services";public CharSequence loadLabel(PackageManager pm){return label;}}',
'android/content/pm/PackageManager.java':'package android.content.pm;public class PackageManager{public boolean installed=true;public ApplicationInfo info=new ApplicationInfo();public int queries;public static class NameNotFoundException extends Exception{}public ApplicationInfo getApplicationInfo(String p,int f)throws NameNotFoundException{queries++;if(!installed)throw new NameNotFoundException();return info;}}',
'dev/rikumi/flymemod/GoogleEntryTest.java':'''package dev.rikumi.flymemod;import java.util.*;public class GoogleEntryTest{
 public static class Model{String pkg;String title;boolean night;public String getPackageName(){return pkg;}public void setPackageName(String s){pkg=s;}public void setTitle(CharSequence s){title=s.toString();}public void setNightModeEnabled(boolean s){night=s;}}
 public static class Manager{boolean selected;int reads;public boolean j(String p){reads++;return selected;}private class a implements Comparator<Model>{private a(){}public int compare(Model l,Model r){return l.title.compareTo(r.title);}}}
 static int checks;static void check(boolean b){checks++;if(!b)throw new AssertionError("check "+checks);}
 public static void main(String[] args)throws Exception{var feature=new NightModeSpecialEntries(Manager.class,Model.class);var ctx=new android.content.Context();var mgr=new Manager();List<Model> list=new ArrayList<>();Model z=new Model();z.pkg="other";z.title="ZZZ";list.add(z);check(feature.ensure(ctx,mgr,list));check(list.size()==2);check(list.get(0).pkg.equals("com.google.android.gms"));check(!list.get(0).night);check(mgr.reads==1);check(!feature.ensure(ctx,mgr,list));check(ctx.pm.queries==1);check(list.get(1)==z);
 list.clear();mgr.selected=true;check(feature.ensure(ctx,mgr,list));check(list.get(0).night);list.clear();ctx.pm.installed=false;check(!feature.ensure(ctx,mgr,list));check(list.isEmpty());ctx.pm.installed=true;ctx.pm.info.label="";check(!feature.ensure(ctx,mgr,list));check(list.isEmpty());System.out.println("PASS: "+checks+" cached GMS entry checks: native label/order/selection, no duplicate, absent/empty label");}}
'''}
with tempfile.TemporaryDirectory(prefix='flyme-night-gms-',dir='/private/tmp') as d:
 paths=[]
 for n,s in files.items():
  p=Path(d)/n;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s);paths.append(str(p))
 subprocess.run(['/opt/homebrew/opt/openjdk@21/bin/javac','-d',d,*paths,str(ROOT/'app/src/main/java/dev/rikumi/flymemod/NightModeSpecialEntries.java')],check=True)
 subprocess.run(['/opt/homebrew/opt/openjdk@21/bin/java','-cp',d,'dev.rikumi.flymemod.GoogleEntryTest'],check=True)
