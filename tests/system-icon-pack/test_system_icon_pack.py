"""Compile production matching/generation plus actual API/provider bodies, with host platform doubles."""
from pathlib import Path
import subprocess
import tempfile
root=Path(__file__).resolve().parents[2]
b=root/'app/src/main/java/dev/rikumi/flymemod'
hooks=(b/'SystemIconPackHooks.java').read_text()
start=hooks.index('            if(!active()) return chain.proceed();',hooks.index('"loadUnbadgedItemIcon"'))
end=hooks.index('        },PackageItemInfo.class,ApplicationInfo.class);',start)
load=hooks[start:end]
start=hooks.index('            if(!active()) return chain.proceed();',hooks.index('"loadIcon",chain'))
end=hooks.index('        },PackageManager.class);',start)
resolve=hooks[start:end]
store=(b/'SystemIconPackStore.java').read_text()
start=store.index('    Bundle lookup(');end=store.index('    ParcelFileDescriptor open(',start)
lookup=store[start:end]
client=(b/'SystemIconPackClient.java').read_text()
start=client.index('    private static final ThreadLocal<Integer>');end=client.index('    private static final LruCache',start)
native=client[start:end]
recent=(b/'SystemIconPackLauncherHooks.java').read_text()
start=recent.index('            if(!settings.getBoolean(ModuleSettings.SYSTEM_ICON_PACK,false) || SystemIconPackClient.isNative()) return chain.proceed();',recent.index('"getCacheEntry",chain'))
end=recent.index('        },task);',start)
recent_body=recent[start:end]
java=r'''
package dev.rikumi.flymemod;
import java.util.*;import java.lang.reflect.*;
public class SystemIconPackHostTest {
 static int checks,reads;static boolean enabled=true;
 static java.util.function.BiConsumer<String,Throwable> log=(message,error)->{throw new AssertionError(message,error);};
 static void check(boolean yes){checks++;if(!yes)throw new AssertionError("check "+checks);}
 static class Drawable {String source;boolean badged;Drawable(String s){source=s;}}
 static class PackageItemInfo {String packageName,name;public int showUserIcon=-10000;public boolean isArchived;}
 static class ApplicationInfo extends PackageItemInfo {int uid=10042;}
 static class ActivityInfo extends PackageItemInfo {ApplicationInfo applicationInfo;}
 static class ServiceInfo extends PackageItemInfo {}
 static class ResolveInfo {ActivityInfo activityInfo;String resolvePackageName="caller.resource.owner";}
 static class UserHandle {int uid;static UserHandle getUserHandleForUid(int uid){UserHandle u=new UserHandle();u.uid=uid;return u;}}
 static class Context {}
 static class PackageManager {Context mContext=new Context();Drawable getUserBadgedIcon(Drawable d,UserHandle u){d.badged=true;return d;}}
 static class Chain {Object target;Object[] args;int calls;Object original=new Drawable("native");Object getThisObject(){return target;}Object getArg(int i){return args[i];}Object proceed(){calls++;return original;}}
 static class SystemIconPackClient {
 NATIVE
 static Drawable found;static Drawable lookup(Context c,String pkg,String component,int user,int density){reads++;return found==null?null:new Drawable(found.source);}
 static Drawable decorateApi(Context c,String pkg,String component,int user,int density,Drawable original){Drawable d=lookup(c,pkg,component,user,density);return d==null?original:d;}
 static Drawable decorateApi(Context c,String pkg,String component,int user,int density,Drawable original,java.util.function.UnaryOperator<Drawable> badge){Drawable d=lookup(c,pkg,component,user,density);return d==null?original:badge.apply(d);}
 }
 static boolean active(){return enabled&&!SystemIconPackClient.isNative();}
 static Field context,userIcon,archived,key,description,user;
 static Method pkg,component,primary,bitmap,newIcon,label,title;
 static Constructor<?> replacement;
 static class ComponentName {String name;ComponentName(String n){name=n;}String flattenToString(){return name;}}
 static class TaskKey {public int userId;public String getPackageName(){return "org.example.app";}public ComponentName getComponent(){return new ComponentName("org.example.app/.Alias");}}
 static class Task {public TaskKey key=new TaskKey();public Description taskDescription=new Description();}
 static class Description {public int getPrimaryColor(){return 123;}}
 static class Entry {Drawable icon;String description,title;public Entry(Drawable d,String desc,String t){icon=d;description=desc;title=t;}public String getContentDescription(){return description;}public String getTitle(){return title;}}
 static class Pixels {Drawable icon;Pixels(Drawable d){icon=d;}public Drawable newIcon(Context c){Drawable result=new Drawable(icon.source);result.badged=true;return result;}}
 static class TaskCache {Context context=new Context();public Pixels getBitmapInfo(Drawable d,int user,int color,boolean instant){check(color==123);return new Pixels(d);}}
 static Object recent(Chain chain)throws Throwable {RECENT}

 static Object load(Chain chain)throws Throwable {LOAD}
 static Object resolve(Chain chain)throws Throwable {RESOLVE}
 static class Bundle {Map<String,Object> data=new HashMap<>();void putString(String k,String v){data.put(k,v);}void putInt(String k,int v){data.put(k,v);}String getString(String k){return (String)data.get(k);}}
 static class Settings {boolean getBoolean(String k,boolean d){return enabled;}}
 static class ModuleSettings {static String SYSTEM_ICON_PACK="enabled";}
 static Settings settings=new Settings();
 static final SystemIconPackIndex.Generation<Snapshot> generations=new SystemIconPackIndex.Generation<>();
 static class Snapshot {String id;Map<String,SystemIconPackIndex.App> apps;Map<String,String> files;int density=964;Snapshot(String i,Map<String,SystemIconPackIndex.App> a,Map<String,String> f){id=i;apps=a;files=f;}}
 LOOKUP
 static ApplicationInfo application(){ApplicationInfo a=new ApplicationInfo();a.packageName="org.example.app";return a;}
 static Chain item(PackageItemInfo i,ApplicationInfo a){Chain c=new Chain();c.target=new PackageManager();c.args=new Object[]{i,a};return c;}
 public static void main(String[] ignored)throws Throwable {
  SystemIconPackIndex idx=new SystemIconPackIndex();
  idx.add("ComponentInfo{org.example.app/.Other}","first");idx.add("org.example.app/.Alias","alias");idx.add("org.example.app/Main","main");
  idx.add("org.example.app/.Other","duplicate_must_not_win");idx.add("org.noentry","headless");
  List<String> entries=List.of("org.example.app/.Main","org.example.app/.Alias","org.example.app/.Missing");
  SystemIconPackIndex.App app=idx.resolve("org.example.app",entries);
  check(app.defaultIcon().equals("main"));check(app.forComponent("ComponentInfo{org.example.app/.Alias}").equals("alias"));
  check(app.forComponent("org.example.app/.Missing").equals("first"));check(app.forComponent(null).equals("main"));
  check(app.forComponent("org.example.app/.Internal").equals("main"));check(idx.resolve("org.noentry",List.of()).defaultIcon().equals("headless"));
  check(idx.resolve("org.negative",List.of("org.negative/.Main")).defaultIcon().isEmpty());
  check(idx.resolve("org.example.app",List.of()).defaultIcon().equals("first"));
  check(idx.resolve("org.example.app",List.of("evil.app/.Main")).entrances().isEmpty());
  check(SystemIconPackIndex.component("ComponentInfo{org.example.app/Main}").equals("org.example.app/org.example.app.Main"));
  try{app.entrances().put("x","y");throw new AssertionError();}catch(UnsupportedOperationException expected){}
  long old=generations.invalidate();Snapshot a=new Snapshot("old",Map.of("org.example.app",app,"org.negative",idx.resolve("org.negative",List.of())),Map.of("first","0.png","alias","1.png","main","2.png"));
  check(generations.publish(old,a));SystemIconPackHostTest host=new SystemIconPackHostTest();
  check(host.lookup("org.example.app","org.example.app/.Alias",0).getString("file").equals("old/1.png"));
  check(host.lookup("org.negative",null,0).getString("file")==null);check(host.lookup("org.example.app",null,10).getString("file")==null);
  // Pack switch invalidates immediately; stale background completion cannot publish.
  long newer=generations.invalidate();check(generations.get()==null);check(!generations.publish(old,a));check(host.lookup("org.example.app",null,0).getString("file")==null);
  Snapshot n=new Snapshot("new",a.apps,Map.of("main","new.png"));check(generations.publish(newer,n));check(host.lookup("org.example.app",null,0).getString("file").equals("new/new.png"));
  long broken=generations.invalidate();check(generations.publish(broken,new Snapshot("fallback",a.apps,Map.of("first","0.png"))));check(host.lookup("org.example.app","org.example.app/.Alias",0).getString("file").equals("fallback/0.png"));
  // Install/update/uninstall use the same atomic invalidation and materialize misses.
  for(int event=0;event<100;event++){long t=generations.invalidate();Map<String,SystemIconPackIndex.App> next=new HashMap<>(a.apps);next.put("org.installed",idx.resolve("org.installed",List.of("org.installed/.Main")));check(generations.publish(t,new Snapshot("event"+event,next,n.files)));check(host.lookup("org.installed",null,0).getString("file")==null);check(host.lookup("org.installed",null,0).getString("generation").equals("event"+event));}
  enabled=false;check(host.lookup("org.example.app",null,0).getString("file")==null);enabled=true;
  context=PackageManager.class.getDeclaredField("mContext");userIcon=PackageItemInfo.class.getField("showUserIcon");archived=PackageItemInfo.class.getField("isArchived");
  ApplicationInfo info=application();Chain c=item(info,info);SystemIconPackClient.found=new Drawable("pack");Drawable d=(Drawable)load(c);check(d.source.equals("pack")&&!d.badged&&c.calls==1);check(info.packageName.equals("org.example.app")&&info.uid==10042);
  ActivityInfo activity=new ActivityInfo();activity.applicationInfo=info;activity.packageName=info.packageName;activity.name="org.example.app.Alias";c=item(activity,info);check(((Drawable)load(c)).source.equals("pack"));check(activity.name.equals("org.example.app.Alias")&&activity.applicationInfo==info);
  ResolveInfo ri=new ResolveInfo();ri.activityInfo=activity;c=new Chain();c.target=ri;c.args=new Object[]{new PackageManager()};check(((Drawable)resolve(c)).badged);check(ri.resolvePackageName.equals("caller.resource.owner"));
  int before=reads;enabled=false;c=item(info,info);check(load(c)==c.original&&c.calls==1);check(reads==before);enabled=true;
  SystemIconPackClient.found=null;c=item(info,info);check(load(c)==c.original&&c.calls==1);SystemIconPackClient.found=new Drawable("pack");
  c=item(new ServiceInfo(),info);check(load(c)==c.original);info.showUserIcon=12;c=item(info,info);check(load(c)==c.original);info.showUserIcon=-10000;info.isArchived=true;c=item(info,info);check(load(c)==c.original);info.isArchived=false;
  // Raw native UI caches are not polluted, including nested scopes and failures.
  try(SystemIconPackClient.NativeScope outer=SystemIconPackClient.nativeScope()) {check(SystemIconPackClient.isNative());c=item(info,info);check(load(c)==c.original);try(SystemIconPackClient.NativeScope inner=SystemIconPackClient.nativeScope()){check(SystemIconPackClient.isNative());}check(SystemIconPackClient.isNative());}
  check(!SystemIconPackClient.isNative());try(SystemIconPackClient.NativeScope failing=SystemIconPackClient.nativeScope()){throw new IllegalStateException();}catch(IllegalStateException expected){}check(!SystemIconPackClient.isNative());
  Field pmContext=context;context=TaskCache.class.getDeclaredField("context");key=Task.class.getField("key");description=Task.class.getField("taskDescription");user=TaskKey.class.getField("userId");pkg=TaskKey.class.getMethod("getPackageName");component=TaskKey.class.getMethod("getComponent");primary=Description.class.getMethod("getPrimaryColor");bitmap=TaskCache.class.getMethod("getBitmapInfo",Drawable.class,int.class,int.class,boolean.class);newIcon=Pixels.class.getMethod("newIcon",Context.class);label=Entry.class.getMethod("getContentDescription");title=Entry.class.getMethod("getTitle");replacement=Entry.class.getConstructor(Drawable.class,String.class,String.class);
  Task originalTask=new Task();Entry entry=new Entry(new Drawable("native"),"description","title");c=new Chain();c.target=new TaskCache();c.args=new Object[]{originalTask};c.original=entry;
  Entry packed=(Entry)recent(c);check(packed!=entry&&packed.icon.source.equals("pack")&&packed.icon.badged);check(entry.icon.source.equals("native"));check(packed.description.equals(entry.description)&&packed.title.equals(entry.title));check(originalTask.key.userId==0);
  enabled=false;check(recent(c)==entry);enabled=true;SystemIconPackClient.found=null;check(recent(c)==entry);SystemIconPackClient.found=new Drawable("pack");check(!SystemIconPackClient.isNative());context=pmContext;
  System.out.println("PASS: "+checks+" actual matching/generation/provider/API checks: aliases, multi-entry, ordered fallback, negative cache, atomic changes, per-user isolation, native off, metadata and badging, raw cache restoration");
 }
}
'''
java=java.replace('NATIVE',native).replace('LOAD',load).replace('RESOLVE',resolve).replace('LOOKUP',lookup).replace('RECENT',recent_body)
with tempfile.TemporaryDirectory(prefix='flyme-system-icon-pack-',dir='/private/tmp') as temp:
 p=Path(temp);(p/'SystemIconPackHostTest.java').write_text(java)
 q=p/'android/os/Process.java';q.parent.mkdir(parents=True);q.write_text('package android.os;public class Process{public static int myUid(){return 10042;}}')
 subprocess.run(['/opt/homebrew/opt/openjdk@21/bin/javac','-d',str(p/'out'),str(b/'SystemIconPackIndex.java'),str(p/'SystemIconPackHostTest.java'),str(q)],check=True)
 subprocess.run(['/opt/homebrew/opt/openjdk@21/bin/java','-cp',str(p/'out'),'dev.rikumi.flymemod.SystemIconPackHostTest'],check=True)
