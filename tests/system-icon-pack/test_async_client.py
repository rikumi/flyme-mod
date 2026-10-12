"""Run the production Android client against a blocking provider and drawable/looper doubles."""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
production = root / 'app/src/main/java/dev/rikumi/flymemod'
sources = {
'android/content/SharedPreferences.java': '''package android.content; public interface SharedPreferences {
 boolean getBoolean(String k,boolean d); void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l);
 void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l);
 interface OnSharedPreferenceChangeListener {void onSharedPreferenceChanged(SharedPreferences p,String k);}}''',
'android/content/Context.java': '''package android.content; public class Context {
 public ContentResolver resolver=new ContentResolver(); public Context getApplicationContext(){return this;}
 public ContentResolver getContentResolver(){return resolver;} public android.content.res.Resources getResources(){return new android.content.res.Resources();}
 public android.content.pm.PackageManager getPackageManager(){return new android.content.pm.PackageManager();}}''',
'android/content/ContentResolver.java': '''package android.content;
import java.util.concurrent.*; import android.os.Bundle;
public class ContentResolver {
 public final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
 public volatile String generation="old"; public volatile int calls,opens; public volatile Throwable violation;
 public final java.util.List<String> selections=new java.util.concurrent.CopyOnWriteArrayList<>();
 void worker(){if(!Thread.currentThread().getName().equals("FlymeIconPack") && !Thread.currentThread().getName().equals("FlymeIconSelection")){violation=new AssertionError("IPC on caller thread");throw new AssertionError(violation);}}
 public void registerContentObserver(android.net.Uri u,boolean b,android.database.ContentObserver o){worker();}
 public Bundle call(android.net.Uri u,String method,String pkg,Bundle args){worker();String g=generation;int n=++calls;
 if(n==1){entered.countDown();try{release.await();}catch(InterruptedException e){throw new RuntimeException(e);}}
 if(method.equals("selection"))selections.add(pkg);
 Bundle b=new Bundle();b.putString("file",g+"/icon.png");b.putInt("density",480);return b;}
 public java.io.InputStream openInputStream(android.net.Uri u){worker();opens++;return new java.io.ByteArrayInputStream(u.value.getBytes());}}
''',
'android/content/pm/ApplicationInfo.java': 'package android.content.pm;public class ApplicationInfo{public String packageName;public int uid;}',
'android/content/pm/PackageManager.java': '''package android.content.pm;import android.graphics.drawable.*;public class PackageManager{
 public Drawable getApplicationIcon(ApplicationInfo a){return new Drawable("native");}
 public Drawable getUserBadgedIcon(Drawable d,android.os.UserHandle u){d.badges++;return d;}}''',
'android/content/res/Resources.java':'package android.content.res;public class Resources{}',
'android/content/res/ColorStateList.java':'package android.content.res;public record ColorStateList(int color){public static ColorStateList valueOf(int c){return new ColorStateList(c);}}',
'android/database/ContentObserver.java':'package android.database;public class ContentObserver{public ContentObserver(android.os.Handler h){}public void onChange(boolean self){}}',
'android/net/Uri.java':'''package android.net;public class Uri{public String value;public Uri(String s){value=s;}public Uri buildUpon(){return new Uri(value);}public Uri appendEncodedPath(String p){value+="/"+p;return this;}public Uri build(){return this;}}''',
'android/os/Bundle.java':'''package android.os;public class Bundle{java.util.Map<String,Object> m=new java.util.HashMap<>();public void putString(String k,String v){m.put(k,v);}public void putInt(String k,int v){m.put(k,v);}public String getString(String k){return (String)m.get(k);}public int getInt(String k){return (Integer)m.get(k);}public boolean containsKey(String k){return m.containsKey(k);}}''',
'android/os/Handler.java':'''package android.os;public class Handler{static final java.util.Queue<Runnable> q=new java.util.concurrent.ConcurrentLinkedQueue<>();public Handler(Looper l){}public void removeCallbacks(Runnable r){q.removeIf(x->x==r);}public boolean postDelayed(Runnable r,long delay){q.add(r);return true;}public static void drain(){for(int i=0;i<100;i++){Runnable r=q.poll();if(r==null)return;r.run();}}}''',
'android/os/Looper.java':'package android.os;public class Looper{public static Looper getMainLooper(){return new Looper();}}',
'android/os/Process.java':'package android.os;public class Process{public static final int THREAD_PRIORITY_BACKGROUND=10;public static void setThreadPriority(int p){}}',
'android/os/SystemClock.java':'package android.os;public class SystemClock{public static long uptimeMillis(){return System.nanoTime()/1000000;}}',
'android/os/UserHandle.java':'package android.os;public class UserHandle{public static UserHandle getUserHandleForUid(int u){return new UserHandle();}}',
'android/graphics/Bitmap.java':'package android.graphics;public class Bitmap{public String name;public Bitmap(String s){name=s;}public enum Config{ARGB_8888}public static Bitmap createBitmap(int w,int h,Config c){return new Bitmap("render");}public int getAllocationByteCount(){return 16;}public void setDensity(int d){}}',
'android/graphics/Rect.java':'package android.graphics;public class Rect{public Rect(){}public Rect(Rect r){}}',
'android/graphics/Canvas.java':'package android.graphics;public class Canvas{public Bitmap bitmap;public Canvas(Bitmap b){bitmap=b;}}',
'android/graphics/drawable/AdaptiveIconDrawable.java':'''package android.graphics.drawable;public class AdaptiveIconDrawable extends Drawable implements Drawable.Callback{Drawable foreground;public AdaptiveIconDrawable(Drawable bg,Drawable fg){super("adaptive");foreground=fg;fg.setCallback(this);}public void invalidateDrawable(Drawable d){invalidateSelf();}public void scheduleDrawable(Drawable d,Runnable r,long w){}public void unscheduleDrawable(Drawable d,Runnable r){}}''',
'android/graphics/BitmapFactory.java':'''package android.graphics;public class BitmapFactory{public static Bitmap decodeStream(java.io.InputStream s)throws java.io.IOException{return new Bitmap(new String(s.readAllBytes()));}}''',
'android/graphics/ColorFilter.java':'package android.graphics;public class ColorFilter{}',
'android/graphics/PorterDuff.java':'package android.graphics;public class PorterDuff{public enum Mode{SRC_IN}}',
'android/graphics/drawable/Drawable.java':'''package android.graphics.drawable;
import android.graphics.*;import android.content.res.*;
public class Drawable{public String name;public int badges,alpha=255;public ColorFilter filter;public ColorStateList tint;public PorterDuff.Mode mode;public Object callback;
 public interface Callback{void invalidateDrawable(Drawable d);void scheduleDrawable(Drawable d,Runnable r,long w);void unscheduleDrawable(Drawable d,Runnable r);}
 public Drawable(String n){name=n;} public Drawable mutate(){return this;} public void invalidateSelf(){if(callback instanceof Callback c)c.invalidateDrawable(this);}
 public void setCallback(Callback c){callback=c;}public void scheduleSelf(Runnable r,long w){}public void unscheduleSelf(Runnable r){}
 public int getIntrinsicWidth(){return 48;}public int getIntrinsicHeight(){return 48;}public Rect getBounds(){return new Rect();}public void setBounds(Rect r){}public void setBounds(int l,int t,int r,int b){}public void draw(Canvas c){c.bitmap.name=name;}
 public void setAlpha(int a){alpha=a;}public void setColorFilter(ColorFilter f){filter=f;}public void setTint(int c){setTintList(ColorStateList.valueOf(c));}public void setTintList(ColorStateList c){tint=c;}public void setTintMode(PorterDuff.Mode m){mode=m;}
 public ConstantState getConstantState(){return new ConstantState(name,badges);} public static class ConstantState{String n;int b;ConstantState(String n,int b){this.n=n;this.b=b;}public Drawable newDrawable(Resources r){Drawable d=new Drawable(n);d.badges=b;return d;}}}''',
'android/graphics/drawable/BitmapDrawable.java':'''package android.graphics.drawable;public class BitmapDrawable extends Drawable{android.graphics.Bitmap b;public BitmapDrawable(android.content.res.Resources r,android.graphics.Bitmap b){super(b.name);this.b=b;}public final android.graphics.Bitmap getBitmap(){return b;}public void setBitmap(android.graphics.Bitmap next){b=next;name=next.name;}public void setTargetDensity(int d){}}''',
'android/graphics/drawable/DrawableWrapper.java':'''package android.graphics.drawable;import android.graphics.*;import android.content.res.*;public class DrawableWrapper extends Drawable implements Drawable.Callback{public void invalidateDrawable(Drawable d){invalidateSelf();}public void scheduleDrawable(Drawable d,Runnable r,long w){}public void unscheduleDrawable(Drawable d,Runnable r){}Drawable d;public DrawableWrapper(Drawable d){super("wrapper");setDrawable(d);}public void setDrawable(Drawable next){d=next;d.callback=this;}public Drawable getDrawable(){return d;}@Override public void draw(Canvas c){d.draw(c);}
 @Override public void setAlpha(int a){d.setAlpha(a);}@Override public void setColorFilter(ColorFilter f){d.setColorFilter(f);}@Override public void setTintList(ColorStateList c){d.setTintList(c);}@Override public void setTintMode(PorterDuff.Mode m){d.setTintMode(m);}}''',
'android/util/LruCache.java':'''package android.util;public class LruCache<K,V>{java.util.Map<K,V> m=new java.util.HashMap<>();public LruCache(int s){}protected int sizeOf(K k,V v){return 1;}public V get(K k){return m.get(k);}public void put(K k,V v){m.put(k,v);}}''',
'dev/rikumi/flymemod/ModuleSettings.java':'''package dev.rikumi.flymemod;class ModuleSettings{static final String SYSTEM_ICON_PACK="pack";static android.content.SharedPreferences preferences(android.content.Context c){return AsyncClientTest.prefs;}}''',
'dev/rikumi/flymemod/SystemIconPackStore.java':'''package dev.rikumi.flymemod;class SystemIconPackStore{static final android.net.Uri URI=new android.net.Uri("content://icons");}''',
'dev/rikumi/flymemod/AsyncClientTest.java':r'''
package dev.rikumi.flymemod;
import android.content.*;import android.graphics.*;import android.graphics.drawable.*;import android.os.Handler;
import java.util.concurrent.*;
public class AsyncClientTest {
 static class Prefs implements SharedPreferences {
  boolean enabled=true;OnSharedPreferenceChangeListener listener;
  public boolean getBoolean(String k,boolean d){return enabled;}
  public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l){listener=l;}
  public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l){listener=null;}
  void set(boolean b){enabled=b;listener.onSharedPreferenceChanged(this,"pack");}
 }
 static Prefs prefs=new Prefs();static int checks;
 static void check(boolean b,String msg){checks++;if(!b)throw new AssertionError(msg);}
 public static void main(String[] args)throws Exception {
  Context ctx=new Context();SystemIconPackClient.configure(prefs);
  Drawable nativeIcon=new Drawable("native");nativeIcon.badges=1;Object owner=new Object();nativeIcon.callback=owner;
  ExecutorService ui=Executors.newSingleThreadExecutor();DrawableWrapper icon;
  try{icon=(DrawableWrapper)ui.submit(()->SystemIconPackClient.decorate(ctx,"org.telegram.messenger","org.telegram.messenger/.NoxIcon",0,0,nativeIcon,d->{d.badges++;return d;})).get(1,TimeUnit.SECONDS);}
  finally{ui.shutdownNow();}
  try {
   check(ctx.resolver.entered.await(1,TimeUnit.SECONDS),"background provider started");
   check(icon.getDrawable().name.equals("native"),"native placeholder while provider blocked");
   check(icon.getDrawable().badges==1,"native fallback not double badged");
   check(nativeIcon.callback==owner,"native cached drawable callback preserved");
   icon.setAlpha(80);ColorFilter filter=new ColorFilter();icon.setColorFilter(filter);icon.setTint(0x123456);
   prefs.set(false);Handler.drain();ctx.resolver.generation="new";ctx.resolver.release.countDown();
   long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
   while(ctx.resolver.opens<1 && System.nanoTime()<end)Thread.sleep(2);
   Handler.drain();check(icon.getDrawable().name.equals("native"),"disabled during blocked request keeps native");
   prefs.set(true);
   end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
   while(!icon.getDrawable().name.contains("new/icon.png") && System.nanoTime()<end){Handler.drain();Thread.sleep(2);}
   check(icon.getDrawable().name.contains("new/icon.png"),"visible wrapper updates after async completion");
   check(icon.getDrawable().badges==1,"pack icon badged exactly once");
   check(icon.getDrawable().alpha==80 && icon.getDrawable().filter==filter && icon.getDrawable().tint.color()==0x123456,"paint state retained across replacement");
   int calls=ctx.resolver.calls;check(SystemIconPackClient.lookup(ctx,"org.telegram.messenger","org.telegram.messenger/.NoxIcon",0,0)!=null,"hot result available");
   check(ctx.resolver.calls==calls,"hot path performs no IPC");
   prefs.set(false);Handler.drain();check(icon.getDrawable().name.equals("native") && icon.getDrawable().badges==1,"toggle off restores existing visible native icon");
   check(ctx.resolver.violation==null,"registration, provider IPC and file IO never run on calling thread");
   Context selection=new Context();ActualPublisher publisher=new ActualPublisher();
   ExecutorService selectionUi=Executors.newSingleThreadExecutor();
   try {
    selectionUi.submit(()->publisher.select(selection,"a")).get(1,TimeUnit.SECONDS);
    check(selection.resolver.entered.await(1,TimeUnit.SECONDS),"publication reached blocked provider");
    selectionUi.submit(()->{publisher.select(selection,"b");publisher.select(selection,"c");}).get(1,TimeUnit.SECONDS);
    selection.resolver.release.countDown();end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
    while(selection.resolver.selections.size()<2 && System.nanoTime()<end)Thread.sleep(2);
    check(selection.resolver.selections.equals(java.util.List.of("a","c")),"rapid selection coalesces and final selection wins without UI waiting");
   } finally {selection.resolver.release.countDown();selectionUi.shutdownNow();}
   BitmapDrawable nativeBitmap=new BitmapDrawable(ctx.getResources(),new Bitmap("bitmap-native"));
   Drawable api=SystemIconPackClient.decorateApi(ctx,"org.telegram.messenger",null,0,0,nativeBitmap);
   check(api instanceof BitmapDrawable,"public bitmap icon retains BitmapDrawable type");
   check(((BitmapDrawable)api).getBitmap().name.equals("bitmap-native"),"getBitmap returns native image while disabled");
   prefs.set(true);end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
   while(!((BitmapDrawable)api).getBitmap().name.contains("new/icon.png") && System.nanoTime()<end){Handler.drain();Thread.sleep(2);}
   check(((BitmapDrawable)api).getBitmap().name.contains("new/icon.png"),"getBitmap follows async pack completion");
   prefs.set(false);Handler.drain();check(((BitmapDrawable)api).getBitmap().name.equals("bitmap-native"),"bitmap facade restores native on disable");
   AdaptiveIconDrawable adaptive=new AdaptiveIconDrawable(null,new Drawable("foreground"));
   Drawable apiAdaptive=SystemIconPackClient.decorateApi(ctx,"org.telegram.messenger",null,0,0,adaptive);
   check(apiAdaptive instanceof AdaptiveIconDrawable,"public adaptive icon retains AdaptiveIconDrawable type");
   check(SystemIconPackClient.decorateApi(ctx,"org.telegram.messenger",null,0,0,nativeIcon)==nativeIcon,"unknown concrete type retains native object");
   System.out.println("PASS: "+checks+" production async client and live drawable checks");
  } finally {ctx.resolver.release.countDown();}
 }
}'''
}
launcher=(production/'SystemIconPackLauncherHooks.java').read_text()
fields=launcher[launcher.index('    private String published;'):launcher.index('    private ContentObserver observer;')]
publish=launcher[launcher.index('    private void publish('):launcher.index('    private void refreshCards()')]
sources['dev/rikumi/flymemod/ActualPublisher.java']='''package dev.rikumi.flymemod;
import android.content.Context;
class ActualPublisher {
 final java.util.function.BiConsumer<String,Throwable> log=(m,t)->{throw new AssertionError(m,t);};
 void select(Context c,String p){publish(c,p);}
'''+fields+publish+'}'
with tempfile.TemporaryDirectory(prefix='flyme-icon-client-',dir='/private/tmp') as temp:
    p=Path(temp)
    files=[]
    for name,source in sources.items():
        f=p/name;f.parent.mkdir(parents=True,exist_ok=True);f.write_text(source);files.append(str(f))
    files += [str(production/name) for name in ('SystemIconPackCache.java','SystemIconPackClient.java','SystemIconPackApiDrawable.java')]
    subprocess.run(['/opt/homebrew/opt/openjdk@21/bin/javac','-d',str(p/'out'),*files],check=True)
    subprocess.run(['/opt/homebrew/opt/openjdk@21/bin/java','-cp',str(p/'out'),'dev.rikumi.flymemod.AsyncClientTest'],check=True,timeout=15)
