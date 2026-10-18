"""Execute the production media swipe corner hook against native-provider lifecycle fixtures."""
from pathlib import Path
import subprocess, tempfile
sources = {
'android/content/Context.java': 'package android.content; public class Context {}',
'android/graphics/Rect.java': 'package android.graphics;public class Rect{public int left,top,right,bottom;public void set(Rect r){left=r.left;top=r.top;right=r.right;bottom=r.bottom;}}',
'android/graphics/Outline.java': '''package android.graphics;public class Outline{public Rect rect;public float radius;public void setEmpty(){rect=null;}public boolean getRect(Rect out){if(rect==null)return false;out.set(rect);return true;}public void setRoundRect(int l,int t,int r,int b,float f){Rect v=new Rect();v.left=l;v.top=t;v.right=r;v.bottom=b;setRoundRect(v,f);}public void setRoundRect(Rect r,float f){rect=new Rect();rect.set(r);radius=f;}}''',
'android/view/ViewOutlineProvider.java': 'package android.view;public abstract class ViewOutlineProvider{public abstract void getOutline(View v,android.graphics.Outline o);}',
'android/view/View.java': '''package android.view;import java.util.*;public class View{public int invalidations;public boolean clip;public ViewOutlineProvider provider;public List<OnAttachStateChangeListener> listeners=new ArrayList<>();public android.content.Context getContext(){return new android.content.Context();}public ViewOutlineProvider getOutlineProvider(){return provider;}public void setOutlineProvider(ViewOutlineProvider p){provider=p;}public void invalidateOutline(){invalidations++;}public void addOnAttachStateChangeListener(OnAttachStateChangeListener l){listeners.add(l);}public interface OnAttachStateChangeListener{void onViewAttachedToWindow(View v);void onViewDetachedFromWindow(View v);}public void attach(){for(var l:listeners)l.onViewAttachedToWindow(this);}public void detach(){for(var l:listeners)l.onViewDetachedFromWindow(this);}}''',
'com/android/systemui/media/controls/ui/view/MediaCarouselScrollHandler.java': '''package com.android.systemui.media.controls.ui.view;import android.view.*;public class MediaCarouselScrollHandler{public final View scrollView=new View();public int carouselWidth=800,carouselHeight=300,cornerRadius=14;public float contentTranslation;public int scrollIntoCurrentMedia;public final ViewOutlineProvider nativeProvider=new ViewOutlineProvider(){public void getOutline(View v,android.graphics.Outline o){o.setRoundRect(0,0,carouselWidth,carouselHeight,cornerRadius);}};public MediaCarouselScrollHandler(){scrollView.setOutlineProvider(nativeProvider);}public void updateClipToOutline(){scrollView.clip=contentTranslation!=0||scrollIntoCurrentMedia!=0;}public void setContentTranslation(float t){contentTranslation=t;updateClipToOutline();}public void onMediaScrollingChanged(int index,int x){scrollIntoCurrentMedia=x;updateClipToOutline();}public void setCarouselBounds(int w,int h){carouselWidth=w;carouselHeight=h;scrollView.invalidateOutline();}public void onSettingsButtonUpdated(View v){cornerRadius=18;scrollView.invalidateOutline();}}''',
'dev/rikumi/flymemod/SignalHooks.java': '''package dev.rikumi.flymemod;import java.util.*;public class SignalHooks{public interface Installer{void hook(String c,String m,Hook h,Class<?>...p);}public interface Hook{Object call(Chain c)throws Throwable;}public record Chain(Object owner,Runnable nativeCall){public Object getThisObject(){return owner;}public Object proceed(){nativeCall.run();return "native";}}public static Map<String,Hook> hooks=new HashMap<>();public static Installer installer=(c,m,h,p)->hooks.put(m,h);}''',
'dev/rikumi/flymemod/SwipeTest.java': '''package dev.rikumi.flymemod;import android.view.*;import android.graphics.*;import com.android.systemui.media.controls.ui.view.MediaCarouselScrollHandler;
public class SwipeTest{static int checks;static boolean contour=true,corners=true;static float radius=42.5f;static void check(boolean b){checks++;if(!b)throw new AssertionError("check "+checks);}static Outline outline(View v){Outline o=new Outline();v.getOutlineProvider().getOutline(v,o);return o;}static void run(MediaCarouselScrollHandler h,String method,Runnable nativeCall)throws Throwable{check(SignalHooks.hooks.get(method).call(new SignalHooks.Chain(h,nativeCall)).equals("native"));}
public static void main(String[] args)throws Throwable{var hooks=new MediaSwipeCornerHooks(SwipeTest.class.getClassLoader(),c->{},v->contour&&corners?radius:Double.NaN);hooks.install(SignalHooks.installer);check(SignalHooks.hooks.size()==5);var h=new MediaCarouselScrollHandler();View v=h.scrollView;run(h,"updateClipToOutline",h::updateClipToOutline);check(!v.clip);check(outline(v).radius==42.5f);check(outline(v).rect.right==800);check(h.cornerRadius==14);
for(float translation:new float[]{-120,120,0}){h.contentTranslation=translation;run(h,"updateClipToOutline",h::updateClipToOutline);check(v.clip==(translation!=0));check(outline(v).radius==radius);check(h.cornerRadius==14);}
run(h,"setContentTranslation",()->h.setContentTranslation(-50));check(v.clip);check(outline(v).radius==radius);run(h,"setContentTranslation",()->h.setContentTranslation(0));check(!v.clip);run(h,"onMediaScrollingChanged",()->h.onMediaScrollingChanged(1,3));check(v.clip);check(outline(v).radius==radius);h.scrollIntoCurrentMedia=0;
for(int[] bounds:new int[][]{{620,250},{280,120},{1200,480}}){run(h,"setCarouselBounds",()->h.setCarouselBounds(bounds[0],bounds[1]));check(outline(v).rect.right==bounds[0]);check(outline(v).rect.bottom==bounds[1]);check(outline(v).radius==radius);}
run(h,"onSettingsButtonUpdated",()->h.onSettingsButtonUpdated(v));check(h.cornerRadius==18);check(outline(v).radius==42.5f);
int invalidations=v.invalidations;for(int i=0;i<10;i++)hooks.refresh();check(v.invalidations==invalidations);radius=55.25f;hooks.refresh();check(outline(v).radius==55.25f);check(v.invalidations==invalidations+1);
contour=false;hooks.refresh();check(v.getOutlineProvider()==h.nativeProvider);check(outline(v).radius==18);check(v.clip);contour=true;hooks.refresh();check(outline(v).radius==55.25f);
corners=false;hooks.refresh();check(v.getOutlineProvider()==h.nativeProvider);corners=true;hooks.refresh();check(outline(v).radius==55.25f);
v.detach();check(v.getOutlineProvider()==h.nativeProvider);v.attach();check(outline(v).radius==55.25f);check(v.listeners.size()==1);
// A new native provider is adopted rather than overwritten with stale dimensions.
ViewOutlineProvider replacement=new ViewOutlineProvider(){public void getOutline(View view,Outline out){out.setRoundRect(5,7,530,207,26);}};v.setOutlineProvider(replacement);run(h,"updateClipToOutline",h::updateClipToOutline);Outline o=outline(v);check(o.rect.left==5&&o.rect.top==7&&o.rect.right==530&&o.rect.bottom==207);check(o.radius==55.25f);contour=false;hooks.refresh();check(v.getOutlineProvider()==replacement);check(outline(v).radius==26);
// Separate carousel and invalid settings cannot contaminate another view.
var second=new MediaCarouselScrollHandler();run(second,"updateClipToOutline",second::updateClipToOutline);check(second.scrollView.getOutlineProvider()==second.nativeProvider);contour=true;radius=Float.NaN;hooks.refresh();check(v.getOutlineProvider()==replacement);radius=-1;hooks.refresh();check(v.getOutlineProvider()==replacement);radius=0;hooks.refresh();check(outline(v).radius==0);check(outline(second.scrollView).radius==0);
contour=false;hooks.refresh();check(v.getOutlineProvider()==replacement);check(second.scrollView.getOutlineProvider()==second.nativeProvider);check(h.cornerRadius==18);check(h.carouselWidth==1200&&h.carouselHeight==480);
// Two independently registered hook states can observe the same carousel.
contour=true;radius=37;
var duplicate=new MediaSwipeCornerHooks(SwipeTest.class.getClassLoader(),c->{},view->contour&&corners?radius:Double.NaN);
duplicate.install(SignalHooks.installer);
run(h,"updateClipToOutline",h::updateClipToOutline);
for(int i=0;i<20;i++){
hooks.refresh();duplicate.refresh();check(outline(v).radius==37);
v.detach();check(v.getOutlineProvider()==replacement);
v.attach();check(outline(v).radius==37);
}
contour=false;hooks.refresh();duplicate.refresh();check(v.getOutlineProvider()==replacement);
// Captured wrappers remain usable after detach; restoration never nulls their delegate.
contour=true;hooks.refresh();ViewOutlineProvider captured=v.getOutlineProvider();
v.detach();Outline detached=new Outline();captured.getOutline(v,detached);check(detached.rect.right==530);
// External providers that delegate back to the current view cannot overflow the stack.
ViewOutlineProvider reentrant=new ViewOutlineProvider(){public void getOutline(View view,Outline out){view.getOutlineProvider().getOutline(view,out);}};
v.setOutlineProvider(reentrant);hooks.refresh();check(outline(v).rect==null);
// A failing native provider must not leave the recursion guard permanently active.
boolean[] fail={true};ViewOutlineProvider flaky=new ViewOutlineProvider(){public void getOutline(View view,Outline out){if(fail[0])throw new IllegalStateException("native");out.setRoundRect(1,2,90,40,8);}};
v.setOutlineProvider(flaky);hooks.refresh();boolean threw=false;try{outline(v);}catch(IllegalStateException expected){threw=true;}check(threw);
fail[0]=false;check(outline(v).rect.right==90);check(outline(v).radius==37);
contour=false;hooks.refresh();duplicate.refresh();check(v.getOutlineProvider()==flaky);
System.out.println("PASS: "+checks+" production media swipe corner/provider/bounds/lifecycle checks");}}
'''
}
material=Path('app/src/main/java/dev/rikumi/flymemod/ColorOsMaterialHooks.java').read_text()
bridge=Path('app/src/main/java/dev/rikumi/flymemod/XposedInit.java').read_text()
assert 'notificationEnabled.getAsBoolean() ? notificationRadius.applyAsDouble(view) : Double.NaN' in material
assert material.count('mediaSwipeCorners.install(installer);') == 1 and 'mediaSwipeCorners.refresh();' in material
assert 'view -> notificationCustomCornersEnabled && activeNotificationAppearanceHooks != null' in bridge
assert '? activeNotificationAppearanceHooks.radius(view) : Double.NaN' in bridge
with tempfile.TemporaryDirectory(dir='/private/tmp') as tmp:
 root=Path(tmp)
 sources['dev/rikumi/flymemod/MediaSwipeCornerHooks.java']=Path('app/src/main/java/dev/rikumi/flymemod/MediaSwipeCornerHooks.java').read_text()
 for name,text in sources.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
 jdk=Path('/opt/homebrew/opt/openjdk@21/bin')
 subprocess.run([str(jdk/'javac'),'-d',str(root/'classes'),*[str(p) for p in root.rglob('*.java')]],check=True)
 subprocess.run([str(jdk/'java'),'-cp',str(root/'classes'),'dev.rikumi.flymemod.SwipeTest'],check=True)
