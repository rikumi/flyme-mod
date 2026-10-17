"""Exercise actual Ticker hooks against native-style presentation fixtures."""
from pathlib import Path
import subprocess,tempfile
exec(Path('tests/statusbar-overlay/test_lifecycle.py').read_text().split('with tempfile.TemporaryDirectory()')[0])
files['android/content/ContentResolver.java']='''package android.content; import android.database.ContentObserver;import java.util.*;public class ContentResolver{public final List<ContentObserver> observers=new ArrayList<>();public final Map<ContentObserver,Object> uris=new HashMap<>();public void registerContentObserver(Object uri,boolean b,ContentObserver o){observers.add(o);uris.put(o,uri);}public void unregisterContentObserver(ContentObserver o){observers.remove(o);uris.remove(o);}}'''
files['android/provider/Settings.java']=files['android/provider/Settings.java'].replace('o.onChange(false);','if(k.equals(r.uris.get(o)))o.onChange(false);')
files['android/view/View.java']=files['android/view/View.java'].replace('public Context getContext()', '''public float alpha=1;public float getAlpha(){return alpha;}public void setAlpha(float a){alpha=a;}
public WindowInsets insets=new WindowInsets();public WindowInsets getRootWindowInsets(){return insets;}
public void requestLayout(){}public void getLocationInWindow(int[] a){getLocationOnScreen(a);}
public static class MeasureSpec{public static final int EXACTLY=0x40000000;public static int getSize(int i){return i&0x3fffffff;}public static int makeMeasureSpec(int s,int m){return s|m;}}
public Context getContext()''')
files['android/graphics/Rect.java']=files['android/graphics/Rect.java'].replace('public int left,top,right,bottom;', 'public boolean isEmpty(){return left>=right||top>=bottom;}public int left,top,right,bottom;')
files['android/view/WindowInsets.java']='''package android.view;public class WindowInsets{public DisplayCutout getDisplayCutout(){return new DisplayCutout();}}'''
files['android/view/DisplayCutout.java']='''package android.view;public class DisplayCutout{public android.graphics.Rect getBoundingRectTop(){return new android.graphics.Rect(400,0,480,24);}}'''
files['android/app/INotificationManager.java']='package android.app;import dev.rikumi.flymemod.SignalHooks;public class INotificationManager{public static class Stub{public static class Proxy{public boolean allow=true;public boolean getNotificationHeadsUpAllowForPackage(String pkg,int uid){return (Boolean)SignalHooks.dispatch(this,getClass().getName(),"getNotificationHeadsUpAllowForPackage",new Object[]{pkg,uid},args->allow);}}}}'
files['android/app/Notification.java']='package android.app;public class Notification{public int flags=0,priority=0;public CharSequence tickerText="hello";}'
files['android/service/notification/StatusBarNotification.java']='package android.service.notification;public class StatusBarNotification{public String getOrigPackageName(){return "test.app";}public int getOrigPkgUid(){return 10001;}android.app.Notification n=new android.app.Notification();public android.app.Notification getNotification(){return n;}}'
files['dev/rikumi/flymemod/SignalHooks.java']=files['dev/rikumi/flymemod/SignalHooks.java'].replace('public Object getArg(int n)', 'public java.util.List<Object> getArgs(){return java.util.Arrays.asList(args);}public Object getArg(int n)')
files['com/flyme/systemui/statusbar/ticker/MarqueeTicker.java']='''package com.flyme.systemui.statusbar.ticker;
import android.view.View;import dev.rikumi.flymemod.SignalHooks;public class MarqueeTicker{
public View mTextSwitcher;public boolean center,curved;public int entries;
public void setDisplayCutout(boolean a,int b,int c,int d,boolean e,boolean f,int g){SignalHooks.dispatch(this,MarqueeTicker.class.getName(),"setDisplayCutout",new Object[]{a,b,c,d,e,f,g},args->{center=(Boolean)args[0];curved=(Boolean)args[5];return null;});}
public void addEntry(android.service.notification.StatusBarNotification n){SignalHooks.dispatch(this,MarqueeTicker.class.getName(),"addEntry",new Object[]{n},args->{entries++;return null;});}}
'''
files['com/flyme/systemui/statusbar/ticker/FlymeMarqueeTicker.java']='''package com.flyme.systemui.statusbar.ticker;import android.view.View;public class FlymeMarqueeTicker extends MarqueeTicker{public void setTickerView(View v){}public void tickerStarting(){}public void tickerDone(){}public void tickerHalting(){}public void destroy(){}}'''
files['com/android/systemui/statusbar/notification/collection/NotificationEntry.java']='''package com.android.systemui.statusbar.notification.collection;import dev.rikumi.flymemod.SignalHooks;public class NotificationEntry{public int importance=2;public final android.service.notification.StatusBarNotification sbn=new android.service.notification.StatusBarNotification();public android.service.notification.StatusBarNotification getSbn(){return sbn;}public int getImportance(){return (Integer)SignalHooks.dispatch(this,getClass().getName(),"getImportance",new Object[]{},args->importance);}}'''
files['com/android/systemui/statusbar/notification/interruption/NotificationInterruptStateProviderImpl.java']='''package com.android.systemui.statusbar.notification.interruption;import com.android.systemui.statusbar.notification.collection.NotificationEntry;import dev.rikumi.flymemod.SignalHooks;public class NotificationInterruptStateProviderImpl{public final java.util.List<String> mDisableHeadsUpUnclearableList=java.util.List.of("test.app");public final android.app.INotificationManager.Stub.Proxy manager=new android.app.INotificationManager.Stub.Proxy();public boolean checkHeadsUp(NotificationEntry e,boolean log){return shouldHeadsUpWhenAwake(e,log);}public boolean shouldHeadsUp(NotificationEntry e){return checkHeadsUp(e,true);}public boolean allowed=true;public boolean shouldHeadsUpWhenAwake(NotificationEntry e,boolean log){return (Boolean)SignalHooks.dispatch(this,getClass().getName(),"shouldHeadsUpWhenAwake",new Object[]{e,log},args->allowed&&e.getImportance()>=4&&manager.getNotificationHeadsUpAllowForPackage(e.getSbn().getOrigPackageName(),e.getSbn().getOrigPkgUid())&&!((e.getSbn().getNotification().flags&(2|32|64))!=0&&mDisableHeadsUpUnclearableList.contains(e.getSbn().getOrigPackageName())&&e.getSbn().getNotification().priority<1));}}'''
files['com/flyme/systemui/statusbar/ticker/NotificationTickController.java']='package com.flyme.systemui.statusbar.ticker;public class NotificationTickController{public void inflateTickerView(com.android.systemui.statusbar.phone.PhoneStatusBarView v){}public void updateUIForCutoutChanged(android.graphics.Rect r,int i,boolean a,boolean b){}}'
files['android/content/pm/PackageManager.java']=files['android/content/pm/PackageManager.java'].replace('public class PackageManager{','public class PackageManager{public static class NameNotFoundException extends Exception{} public CharSequence getApplicationLabel(ApplicationInfo info){return "测试应用";}').replace('getApplicationInfo(String p,int f){','getApplicationInfo(String p,int f)throws NameNotFoundException{')
files['android/os/Bundle.java']='package android.os;public class Bundle extends java.util.HashMap<String,Object>{public CharSequence getCharSequence(String k){return (CharSequence)get(k);}public CharSequence[] getCharSequenceArray(String k){return (CharSequence[])get(k);}}'
files['android/text/TextUtils.java']='package android.text;public class TextUtils{public static String join(CharSequence d,Object[] values){return java.util.Arrays.stream(values).map(Object::toString).collect(java.util.stream.Collectors.joining(d));}}'
files['android/app/Notification.java']=files['android/app/Notification.java'].replace('public int flags', 'public static final String EXTRA_TITLE="title",EXTRA_TEXT="text",EXTRA_BIG_TEXT="big",EXTRA_TEXT_LINES="lines";public android.os.Bundle extras=new android.os.Bundle();public int flags')
files['android/service/notification/StatusBarNotification.java']=files['android/service/notification/StatusBarNotification.java'].replace('public String getOrigPackageName()', 'public String getPackageName(){return "test.app";}public StatusBarNotification clone(){StatusBarNotification c=new StatusBarNotification();c.n.tickerText=n.tickerText;c.n.extras.putAll(n.extras);return c;}public String getOrigPackageName()')
files['com/flyme/systemui/statusbar/ticker/NotificationTickController.java']=files['com/flyme/systemui/statusbar/ticker/NotificationTickController.java'].replace('public void inflateTickerView', 'public FlymeMarqueeTicker ticker;public void inflateTickerView')
files['com/flyme/systemui/statusbar/ticker/NotificationTickController$notifCollectionListener$1.java']='package com.flyme.systemui.statusbar.ticker;import com.android.systemui.statusbar.notification.collection.NotificationEntry;public class NotificationTickController$notifCollectionListener$1{public NotificationTickController this$0;public int nativeCalls;public void onEntryAdded(NotificationEntry e){}public void onEntryUpdated(NotificationEntry e){}}'
files['com/flyme/systemui/statusbar/ticker/MarqueeTicker.java']=files['com/flyme/systemui/statusbar/ticker/MarqueeTicker.java'].replace('public View mTextSwitcher;', 'public android.service.notification.StatusBarNotification last;public View mTextSwitcher;').replace('entries++;return null;', 'entries++;last=(android.service.notification.StatusBarNotification)args[0];return null;')
files['dev/rikumi/flymemod/TickerTest.java']='''package dev.rikumi.flymemod;
import android.content.*;import android.view.*;import android.provider.Settings;
import com.flyme.systemui.statusbar.ticker.*;import com.android.systemui.statusbar.phone.*;
import com.android.systemui.statusbar.notification.collection.*;import com.android.systemui.statusbar.notification.interruption.*;
public class TickerTest{
static int count;static void check(boolean b){count++;if(!b)throw new AssertionError("check "+count);}
public static void main(String[] args)throws Exception{
Context context=new Context();int[] mode={1,0};boolean[] left={false};
TickerLyricHooks hooks=new TickerLyricHooks(c->{},()->mode[0]==1,()->mode[1],()->left[0]);hooks.install(TickerTest.class.getClassLoader(),SignalHooks.INSTALLER,m->{});
PhoneStatusBarView bar=new PhoneStatusBarView(context);View view=new View(context);view.parent=bar;
FlymeMarqueeTicker ticker=new FlymeMarqueeTicker();ticker.mTextSwitcher=new View(context);
SignalHooks.dispatch(ticker,ticker.getClass().getName(),"setTickerView",new Object[]{view},a->null);
Settings.Global.putString(context.getContentResolver(),StatusBarOverlayFrames.KEY,"0,0,0,100,20");
SignalHooks.dispatch(ticker,ticker.getClass().getName(),"tickerStarting",new Object[]{},a->null);
check(ticker.mTextSwitcher.getAlpha()==0);
mode[0]=0;hooks.refresh();check(ticker.mTextSwitcher.getAlpha()==1);
mode[0]=1;hooks.refresh();check(ticker.mTextSwitcher.getAlpha()==0);
// Native exit sets GONE but continues rendering its animation.
SignalHooks.dispatch(ticker,ticker.getClass().getName(),"tickerDone",new Object[]{},a->{view.setVisibility(8);return null;});
check(ticker.mTextSwitcher.getAlpha()==0);
Settings.Global.putString(context.getContentResolver(),StatusBarOverlayFrames.KEY,"");
mode[0]=0;hooks.refresh();check(ticker.mTextSwitcher.getAlpha()==0);
SignalHooks.dispatch(ticker,ticker.getClass().getName(),"tickerStarting",new Object[]{},a->{view.setVisibility(0);return null;});
check(ticker.mTextSwitcher.getAlpha()==1);
for(String end:new String[]{"tickerHalting","destroy"}){
mode[0]=1;Settings.Global.putString(context.getContentResolver(),StatusBarOverlayFrames.KEY,"0,0,0,100,20");
check(ticker.mTextSwitcher.getAlpha()==0);
SignalHooks.dispatch(ticker,ticker.getClass().getName(),end,new Object[]{},a->null);
mode[0]=0;hooks.refresh();check(ticker.mTextSwitcher.getAlpha()==0);
SignalHooks.dispatch(ticker,ticker.getClass().getName(),"tickerStarting",new Object[]{},a->null);
check(ticker.mTextSwitcher.getAlpha()==1);
}
NotificationEntry entry=new NotificationEntry();NotificationInterruptStateProviderImpl provider=new NotificationInterruptStateProviderImpl();
provider.manager.allow=false;
mode[1]=0;entry.importance=4;check(!provider.shouldHeadsUpWhenAwake(entry,false));
mode[1]=2;entry.importance=2;check(provider.shouldHeadsUpWhenAwake(entry,false));
check(!provider.manager.getNotificationHeadsUpAllowForPackage("test.app",10001));
check(!provider.manager.getNotificationHeadsUpAllowForPackage("other.app",10002));check(entry.getImportance()==2);
provider.allowed=false;check(!provider.shouldHeadsUpWhenAwake(entry,false));provider.allowed=true;provider.manager.allow=true;
for(int flag:new int[]{2,32,64}){
entry.getSbn().getNotification().flags=flag;entry.importance=4;
mode[1]=0;check(!provider.shouldHeadsUpWhenAwake(entry,false));
mode[1]=2;check(provider.shouldHeadsUpWhenAwake(entry,false));
check(provider.mDisableHeadsUpUnclearableList.contains("test.app"));
provider.allowed=false;check(!provider.shouldHeadsUpWhenAwake(entry,false));provider.allowed=true;
}
entry.getSbn().getNotification().flags=0;
mode[0]=0;mode[1]=1;entry.importance=4;check(!provider.shouldHeadsUpWhenAwake(entry,false));
mode[1]=0;check(provider.shouldHeadsUpWhenAwake(entry,false));
mode[1]=2;entry.importance=2;check(provider.shouldHeadsUpWhenAwake(entry,false));ticker.addEntry(entry.getSbn());check(ticker.entries==0);
mode[1]=0;ticker.addEntry(entry.getSbn());check(ticker.entries==1);
ticker.setDisplayCutout(true,400,480,1080,true,true,0);check(ticker.center);
left[0]=true;hooks.refresh();check(!ticker.center&&!ticker.curved);
int[] width={0};SignalHooks.dispatch(view,"android.widget.LinearLayout","onMeasure",new Object[]{View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),24},a->{width[0]=View.MeasureSpec.getSize((Integer)a[0]);return null;});check(width[0]==400);
left[0]=false;hooks.refresh();check(ticker.center&&ticker.curved);
NotificationTickController controller=new NotificationTickController();controller.ticker=ticker;
NotificationTickController$notifCollectionListener$1 listener=new NotificationTickController$notifCollectionListener$1();listener.this$0=controller;
mode[1]=1;entry.getSbn().getNotification().tickerText=null;
entry.getSbn().getNotification().extras.put("title","标题");entry.getSbn().getNotification().extras.put("text","正文");
check(!provider.shouldHeadsUpWhenAwake(entry,false));
for(String event:new String[]{"onEntryAdded","onEntryUpdated"}){
int before=ticker.entries;
SignalHooks.dispatch(listener,listener.getClass().getName(),event,new Object[]{entry},a->{listener.nativeCalls++;return null;});
check(ticker.entries==before+1);check(listener.nativeCalls==0);
check("标题：正文".contentEquals(ticker.last.getNotification().tickerText));
check(entry.getSbn().getNotification().tickerText==null);
}
entry.getSbn().getNotification().extras.clear();
SignalHooks.dispatch(listener,listener.getClass().getName(),"onEntryAdded",new Object[]{entry},a->null);
check("测试应用".contentEquals(ticker.last.getNotification().tickerText));
mode[1]=0;SignalHooks.dispatch(listener,listener.getClass().getName(),"onEntryAdded",new Object[]{entry},a->{listener.nativeCalls++;return null;});check(listener.nativeCalls==1);
System.out.println("PASS: "+count+" ticker exit-animation suppression, native alert restrictions, display modes and cutout checks");}}
'''
with tempfile.TemporaryDirectory(dir='/private/tmp',prefix='ticker-') as tmp:
 root=Path(tmp)
 for name,text in files.items():
  path=root/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(text)
 for name in ['StatusBarOverlayFrames.java','StatusBarOverlayServiceHooks.java','StatusBarLyricHooks.java','TickerLyricHooks.java']:
  (root/'dev/rikumi/flymemod'/name).write_text((base/name).read_text())
 jdk='/opt/homebrew/opt/openjdk@21/bin/'
 subprocess.run([jdk+'javac','-d',str(root/'classes'),*[str(p) for p in root.rglob('*.java')]],check=True)
 subprocess.run([jdk+'java','-cp',str(root/'classes'),'dev.rikumi.flymemod.TickerTest'],check=True)
