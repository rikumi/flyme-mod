"""Run the actual width hook with shared-entry rows in distinct presentation containers."""
from pathlib import Path
import tempfile, subprocess
files={
'com/android/systemui/statusbar/notification/row/ExpandableView.java':'package com.android.systemui.statusbar.notification.row;public class ExpandableView extends android.view.View{public void onMeasure(int w,int h){}}',
'com/android/systemui/statusbar/notification/stack/MediaContainerView.java':'package com.android.systemui.statusbar.notification.stack;public class MediaContainerView extends com.android.systemui.statusbar.notification.row.ExpandableView{}',

'android/content/Context.java':'package android.content;public class Context{}',
'android/view/View.java':'''package android.view;import android.content.Context;public class View{public Object parent;public boolean force=true;public void forceLayout(){force=true;}public int measured=1200,requests,originalMargin=27;public Resources resources=new Resources();public Object getParent(){return parent;}public int getMeasuredWidth(){return measured;}public Context getContext(){return new Context();}public Resources getResources(){return resources;}public void requestLayout(){requests++;}public static class Resources{public Metrics metrics=new Metrics();public Metrics getDisplayMetrics(){return metrics;}}public static class Metrics{public float density=2.625f;public int widthPixels=2400;}public static class MeasureSpec{public static final int EXACTLY=0x40000000;public static int makeMeasureSpec(int s,int m){return s|m;}public static int getSize(int s){return s&0x3fffffff;}}}''',
'kotlinx/coroutines/flow/StateFlow.java':'package kotlinx.coroutines.flow;public interface StateFlow{Object getValue();}',
'com/android/systemui/shade/domain/interactor/ShadeInteractor.java':'package com.android.systemui.shade.domain.interactor;public class ShadeInteractor{public boolean combined;public kotlinx.coroutines.flow.StateFlow isClassicsMode(){return ()->combined;}}',
'android/widget/LinearLayout.java':'package android.widget;public class LinearLayout extends android.view.View{protected void onMeasure(int a,int b){}}',
'com/android/systemui/statusbar/notification/stack/NotificationStackScrollLayout.java':'package com.android.systemui.statusbar.notification.stack;public class NotificationStackScrollLayout extends android.view.View{public boolean mIsExpanded,filter;public int mStatusBarState,mSidePaddings;public com.android.systemui.shade.domain.interactor.ShadeInteractor mShadeInteractor=new com.android.systemui.shade.domain.interactor.ShadeInteractor();public boolean onKeyguard(){return mStatusBarState==1;}public boolean isFilterStackLayout(){return filter;}}',
'com/android/systemui/statusbar/notification/row/ExpandableNotificationRow.java':'''package com.android.systemui.statusbar.notification.row;public class ExpandableNotificationRow extends android.view.View{public Object entry;public boolean pinned,away,mShowingPublic;public boolean isPinned(){return pinned;}public boolean isHeadsUpAnimatingAway(){return away;}public void setPinnedStatus(int status){}public void setHeadsUp(boolean b){}public void setHeadsUpAnimatingAway(boolean b){}public void setOnKeyguard(boolean b){}public void setHideSensitive(boolean a,boolean b,long c,long d){}}''',
'com/flyme/notification/view/LandscapeHeadsUpNotificationView.java':'package com.flyme.notification.view;public class LandscapeHeadsUpNotificationView extends android.widget.LinearLayout{}',
'dev/rikumi/flymemod/SignalHooks.java':'''package dev.rikumi.flymemod;import java.util.*;public class SignalHooks{public interface Installer{void hook(String c,String m,Hook h,Class<?>...p);}public interface Hook{Object call(Chain c)throws Throwable;}public record Chain(Object object,Object[] args){public Object getThisObject(){return object;}public Object getArg(int i){return args[i];}public Object proceed(){if(object instanceof com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout s){if(method.equals("setIsExpanded"))s.mIsExpanded=(Boolean)args[0];if(method.equals("setStatusBarState"))s.mStatusBarState=(Integer)args[0];}if(object instanceof com.android.systemui.statusbar.notification.row.ExpandableNotificationRow r){if(method.equals("setPinnedStatus"))r.pinned=(Integer)args[0]!=0;if(method.equals("setHeadsUpAnimatingAway"))r.away=(Boolean)args[0];if(method.equals("setHideSensitive"))r.mShowingPublic=(Boolean)args[0];}return args;}public Object proceed(Object[] a){return a;}}public static String method;public static final Map<String,Hook> hooks=new HashMap<>();public static final Installer INSTALLER=(c,m,h,p)->hooks.put(c+"#"+m,h);}''',
'dev/rikumi/flymemod/WidthIsolationTest.java':'''package dev.rikumi.flymemod;
import android.view.View;import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout;import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;import java.util.*;
public class WidthIsolationTest{static boolean enabled=true;static int checks,nativeWidth=900;static void check(boolean b){checks++;if(!b)throw new AssertionError("check "+checks);}static Object[] run(Object o,String c,String m,Object...a)throws Throwable{SignalHooks.method=m;return (Object[])SignalHooks.hooks.get(c+"#"+m).call(new SignalHooks.Chain(o,a));}static int measure(ExpandableNotificationRow row)throws Throwable{Object[] result=run(row,row.getClass().getName(),"onMeasure",View.MeasureSpec.makeMeasureSpec(nativeWidth,View.MeasureSpec.EXACTLY),123);check(result[1].equals(123));row.measured=View.MeasureSpec.getSize((Integer)result[0]);row.force=false;return row.measured;}
static int cachedMeasure(ExpandableNotificationRow r)throws Throwable{return r.force?measure(r):r.measured;}
public static void main(String[] a)throws Throwable{var hook=new HeadsUpWidthHooks(WidthIsolationTest.class.getClassLoader(),c->{},()->enabled);hook.install(SignalHooks.INSTALLER);var floating=new NotificationStackScrollLayout();var shade=new NotificationStackScrollLayout();shade.mIsExpanded=true;var row=new ExpandableNotificationRow();var duplicate=new ExpandableNotificationRow();row.entry=duplicate.entry=new Object();row.parent=floating;duplicate.parent=shade;row.pinned=duplicate.pinned=true;
check(measure(row)==1158);check(measure(duplicate)==900);check(row.originalMargin==27&&duplicate.originalMargin==27);check((floating.measured-row.measured)/2==21);
for(int state=0;state<3;state++)for(boolean expanded:new boolean[]{false,true})for(boolean pinned:new boolean[]{false,true})for(boolean away:new boolean[]{false,true}){floating.mStatusBarState=state;floating.mIsExpanded=expanded;row.pinned=pinned;row.away=away;boolean expect=state==1||(state==0&&!expanded&&(pinned||away));check(measure(row)==(expect?1158:900));check(measure(duplicate)==900);}
floating.mStatusBarState=0;floating.mIsExpanded=false;row.pinned=true;row.away=false;check(measure(row)==1158);floating.mIsExpanded=false;run(floating,floating.getClass().getName(),"setIsExpanded",true);check(floating.requests>0 && row.force);check(cachedMeasure(row)==900);floating.mStatusBarState=0;run(floating,floating.getClass().getName(),"setStatusBarState",1);check(cachedMeasure(row)==1158);floating.mStatusBarState=1;floating.mIsExpanded=false;run(floating,floating.getClass().getName(),"setStatusBarState",2);check(cachedMeasure(row)==900);int oldRequests=floating.requests;for(int i=0;i<20;i++)run(floating,floating.getClass().getName(),"setStatusBarState",2);check(floating.requests==oldRequests);
row.parent=shade;check(measure(row)==900);row.parent=new android.widget.LinearLayout();check(measure(row)==900);row.parent=floating;floating.mStatusBarState=1;for(int i=0;i<20;i++)check(measure(row)==1158);
for(String name:new String[]{"setPinnedStatus","setHeadsUp","setHeadsUpAnimatingAway","setOnKeyguard","setHideSensitive"}){int before=floating.requests;Object[] values=name.equals("setPinnedStatus")?new Object[]{1}:new Object[]{false};run(row,row.getClass().getName(),name,values);check(floating.requests==before);check(measure(row)==1158);}
int privacyBefore=floating.requests;run(row,row.getClass().getName(),"setHideSensitive",true);check(floating.requests>privacyBefore && row.force);check(cachedMeasure(row)==1158);
floating.mStatusBarState=0;floating.mIsExpanded=false;row.pinned=false;check(measure(row)==900);run(row,row.getClass().getName(),"setPinnedStatus",1);check(row.force);check(cachedMeasure(row)==1158);run(row,row.getClass().getName(),"setPinnedStatus",0);check(cachedMeasure(row)==900);row.pinned=true;

floating.mStatusBarState=1;floating.measured=1080;row.resources.metrics.density=3;check(measure(row)==1032);row.parent=null;check(measure(row)==900);row.parent=floating;
enabled=false;hook.refresh();check(measure(row)==900);enabled=true;floating.mStatusBarState=1;check(measure(row)==1032);
var landscape=new com.flyme.notification.view.LandscapeHeadsUpNotificationView();landscape.resources.metrics.density=3;Object[] result=run(landscape,"android.widget.LinearLayout","onMeasure",386,40);check(View.MeasureSpec.getSize((Integer)result[0])==2352);enabled=false;result=run(landscape,"android.widget.LinearLayout","onMeasure",386,40);check(result[0].equals(386));var regular=new android.widget.LinearLayout();enabled=true;result=run(regular,"android.widget.LinearLayout","onMeasure",386,40);check(result[0].equals(386));check(HeadsUpWidthHooks.contentWidth(10,3)==0);
// Exercise the actual merged applyWidth/active bodies together with the full row hook.
var combined=new CombinedNotificationWidthHooks();var host=new NotificationStackScrollLayout();host.mShadeInteractor.combined=true;host.mIsExpanded=true;var reused=new ExpandableNotificationRow();reused.entry=row.entry;reused.pinned=true;reused.away=true;reused.mShowingPublic=true;reused.parent=host;
for(int state=0;state<3;state++)for(boolean classics:new boolean[]{false,true})for(boolean expanded:new boolean[]{false,true}){
host.mStatusBarState=state;host.mShadeInteractor.combined=classics;host.mIsExpanded=expanded;host.mSidePaddings=150;
boolean merged=state!=1&&classics&&expanded;check(combined.applyWidth(host,1200)==merged);check(host.mSidePaddings==(merged?42:150));nativeWidth=1200-2*host.mSidePaddings;
boolean floatingCard=state==1||(state==0&&!expanded);check(measure(reused)==(floatingCard?1158:nativeWidth));check(reused.originalMargin==27);
}
// Configured merged mode still permits genuine collapsed HUN and keyguard cards.
host.mShadeInteractor.combined=true;host.mIsExpanded=false;host.mStatusBarState=0;nativeWidth=900;check(measure(reused)==1158);
run(host,host.getClass().getName(),"setIsExpanded",true);host.mSidePaddings=150;check(combined.applyWidth(host,1200));nativeWidth=1200-2*host.mSidePaddings;check(cachedMeasure(reused)==1116);
run(host,host.getClass().getName(),"setStatusBarState",1);host.mSidePaddings=150;check(!combined.applyWidth(host,1200));nativeWidth=900;check(cachedMeasure(reused)==1158);
run(host,host.getClass().getName(),"setStatusBarState",2);check(combined.applyWidth(host,1200));nativeWidth=1116;check(cachedMeasure(reused)==1116);
run(host,host.getClass().getName(),"setIsExpanded",false);host.mSidePaddings=150;check(!combined.applyWidth(host,1200));nativeWidth=900;check(cachedMeasure(reused)==900);
// Reparent the SAME row/entry to ordinary shade, then merged and keyguard again.
reused.parent=shade;shade.mStatusBarState=2;check(measure(reused)==900);reused.parent=host;host.mIsExpanded=true;host.mStatusBarState=0;check(combined.applyWidth(host,1200));nativeWidth=1116;check(measure(reused)==1116);
combined.enabled=false;host.mSidePaddings=150;check(!combined.applyWidth(host,1200));nativeWidth=900;check(measure(reused)==900);
enabled=false;combined.enabled=true;check(combined.applyWidth(host,1200));nativeWidth=1116;check(measure(reused)==1116);enabled=true;
host.filter=true;host.mSidePaddings=150;check(!combined.applyWidth(host,1200));nativeWidth=900;check(measure(reused)==900);host.filter=false;
for(int i=0;i<20;i++){host.mSidePaddings=150;check(combined.applyWidth(host,1200));nativeWidth=1116;check(measure(reused)==1116);}check(host.mSidePaddings==42);
host.resources.metrics.density=3;check(combined.applyWidth(host,1080));check(host.mSidePaddings==48);nativeWidth=984;check(measure(reused)==984);host.mStatusBarState=1;host.measured=1080;reused.resources.metrics.density=3;check(measure(reused)==1032);
check(CombinedNotificationWidthHooks.contentWidth(10,3)==0);nativeWidth=900;
// Media uses the inherited measure method, sharing the same state-transition invalidation.
var media=new com.android.systemui.statusbar.notification.stack.MediaContainerView();
media.parent=host;host.measured=1200;media.resources.metrics.density=2.625f;
String mediaOwner="com.android.systemui.statusbar.notification.row.ExpandableView";
for(boolean active:new boolean[]{true,false})for(int state=0;state<3;state++)for(boolean classics:new boolean[]{false,true}){
 enabled=active;host.mStatusBarState=state;host.mShadeInteractor.combined=classics;
 Object[] spec=run(media,mediaOwner,"onMeasure",View.MeasureSpec.makeMeasureSpec(900,View.MeasureSpec.EXACTLY),123);
 check(View.MeasureSpec.getSize((Integer)spec[0])==(active&&state==1?1158:900));check(spec[1].equals(123));
}
enabled=true;host.mStatusBarState=1;run(media,mediaOwner,"onMeasure",900,123);media.force=false;
run(host,host.getClass().getName(),"setStatusBarState",0);check(media.force);
media.force=false;enabled=false;hook.refresh();check(media.force);
media.parent=new android.widget.LinearLayout();enabled=true;
check(run(media,mediaOwner,"onMeasure",900,123)[0].equals(900));
var unrelated=new com.android.systemui.statusbar.notification.row.ExpandableView();unrelated.parent=host;
check(run(unrelated,mediaOwner,"onMeasure",900,123)[0].equals(900));
System.out.println("PASS: "+checks+" production width/container isolation/transition/group/restore checks");}}
'''}
with tempfile.TemporaryDirectory(dir='/private/tmp') as tmp:
 root=Path(tmp)
 for name,contents in files.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(contents)
 # Compile exact production width methods, omitting unrelated separator graphics.
 combined=Path('app/src/main/java/dev/rikumi/flymemod/CombinedNotificationWidthHooks.java').read_text()
 body=combined[combined.index('    boolean applyWidth('):combined.index('    private static boolean content(')]
 wrapper="""package dev.rikumi.flymemod;import android.view.View;import java.lang.reflect.*;import java.util.function.BooleanSupplier;
 final class CombinedNotificationWidthHooks{boolean enabled=true;Field expanded,shade,side;Method classics,value,onKeyguard,filter;
 CombinedNotificationWidthHooks()throws Exception{Class<?> stack=Class.forName("com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout");expanded=stack.getField("mIsExpanded");shade=stack.getField("mShadeInteractor");side=stack.getField("mSidePaddings");classics=shade.getType().getMethod("isClassicsMode");value=Class.forName("kotlinx.coroutines.flow.StateFlow").getMethod("getValue");onKeyguard=stack.getMethod("onKeyguard");filter=stack.getMethod("isFilterStackLayout");}
 """+body.replace('enabled.getAsBoolean()', 'enabled')+'}'
 (root/'dev/rikumi/flymemod/CombinedNotificationWidthHooks.java').write_text(wrapper)
 p=root/'dev/rikumi/flymemod/HeadsUpWidthHooks.java';p.write_text(Path('app/src/main/java/dev/rikumi/flymemod/HeadsUpWidthHooks.java').read_text())
 jdk=Path('/opt/homebrew/opt/openjdk@21/bin')
 subprocess.run([str(jdk/'javac'),'-d',str(root/'classes'),*[str(p) for p in root.rglob('*.java')]],check=True)
 subprocess.run([str(jdk/'java'),'-cp',str(root/'classes'),'dev.rikumi.flymemod.WidthIsolationTest'],check=True)
