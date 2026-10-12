"""Exercise the actual background hook and window gate with host-side Android mocks."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
source = (ROOT / 'app/src/main/java/dev/rikumi/flymemod/ThemeColorHooks.java').read_text()
start = source.index('            ThemeIconFilter dialogColors = iconFilters;', source.index('"drawBackground"'))
end = source.index('\n        }, Canvas.class);', start)
body = source[start:end]
start = source.index('    private static boolean normalActivityBackground(')
end = source.index('    private int color(', start)
gate = source[start:source.index('    private static boolean isColor(', start)] + source[source.index('    private static boolean night(', start):end]
for old, new in [('android.view.WindowManager.LayoutParams','LayoutParams'), ('android.content.ContextWrapper','ContextWrapper'), ('android.content.Context','Context'), ('android.app.Activity','Activity'), ('android.view.Window','Window'), ('android.R.attr.windowIsTranslucent','1')]:
    gate = gate.replace(old, new)
body = body.replace('android.graphics.drawable.VectorDrawable', 'VectorDrawable').replace('android.graphics.drawable.AdaptiveIconDrawable', 'AdaptiveIconDrawable')
body = body.replace('com.android.internal.policy.DecorView', 'dev.rikumi.flymemod.BatteryBackgroundTest$DecorView')
java = r'''
package dev.rikumi.flymemod;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
public class BatteryBackgroundTest {
 static String packageName="com.meizu.battery";
 static int legacyBackgroundFamily=0;
 static boolean blackBackground=true;
 static ThemeIconFilter iconFilters=null;
 static ThemeBackgroundFilter backgroundFilter=new ThemeBackgroundFilter(DarkBackgroundPolicy.applicationShades(packageName,0));
 static ThemeBackgroundFilter windowBackgroundFilter=new ThemeBackgroundFilter(DarkBackgroundPolicy.STORE_GLOBAL_SHADES);
 static ThemeBackgroundFilter storeBackgroundFilter;
 static ThemeBackgroundFilter appManagementBackgroundFilter=new ThemeBackgroundFilter(AppManagementBackgroundPolicy.SHADES);
 static String appActivity="", appFragment;
 static boolean appManagementSurface(View v){return AppManagementBackgroundPolicy.page(packageName,appActivity,appFragment);}
 static Object sidebarBlack=new Object();
 static int themeFamily=1;
 static Map<View,Boolean> storePageBackgrounds=new HashMap<>();
 static ThreadLocal<Paint> backgroundPaint=ThreadLocal.withInitial(Paint::new);
 static class AnrDialogHooks{static View root;static boolean nativeSurface(View v){return v.getRootView()==root;}}
 static Field decorWindow;
 static class Configuration {static final int UI_MODE_NIGHT_MASK=48,UI_MODE_NIGHT_YES=32;int uiMode=32;}
 static class Resources {Configuration config=new Configuration();Configuration getConfiguration(){return config;}}
 static class TypedArray {boolean translucent;boolean getBoolean(int n,boolean fallback){return translucent;}void recycle(){}}
 static class Context {}
 static class ContextWrapper extends Context {Context base;ContextWrapper(Context b){base=b;}Context getBaseContext(){return base;}}
 static class Activity extends Context {Window window=new Window(this);boolean translucent;Window getWindow(){return window;}TypedArray obtainStyledAttributes(int[] ids){TypedArray a=new TypedArray();a.translucent=translucent;return a;}}
 static class Window {Context context;boolean floating;Window(Context c){context=c;}Context getContext(){return context;}boolean isFloating(){return floating;}}
 static class LayoutParams {static final int TYPE_BASE_APPLICATION=1;int type=1;}
 static class View {
  View parent;Context context;Resources resources=new Resources();Drawable bg=new Drawable();LayoutParams params=new LayoutParams();
  View getParent(){return parent;}View getRootView(){return parent==null?this:parent.getRootView();}
  Object getLayoutParams(){return params;}Context getContext(){return context;}Resources getResources(){return resources;}
  Drawable getBackground(){return bg;}int getScrollX(){return 0;}int getScrollY(){return 0;}int getWidth(){return 100;}int getHeight(){return 100;}
 }
 static class DecorView extends View {Window mWindow;}
 static class PopupContainer extends View {}
 static class DialogContainer extends View {}
 static class BottomSheetContainer extends View {}
 static class Drawable {}
 static class BitmapDrawable extends Drawable {}
 static class VectorDrawable extends Drawable {}
 static class AdaptiveIconDrawable extends Drawable {}
 static class Paint {Object filter;void setColorFilter(Object f){filter=f;}}
 static class ThemeBackgroundFilter {Object filter;ThemeBackgroundFilter(int[] shades){filter=shades;}}
 static class ThemeIconFilter {Object colorParts,preserveRedParts;}
 static class ThemeTabPolicy {static boolean storeGlobalBackground(String pkg,String id){return false;}}
 static boolean dialogButton(View v){return false;}static boolean storeSidebar(View v){return false;}static String viewId(View v){return "";}
 static class Canvas {int depth,saves,restores;Object filter;int saveLayer(float l,float t,float r,float b,Paint p){filter=p.filter;saves++;return ++depth;}void restoreToCount(int n){restores++;depth--;}}
 static class Chain {View view;Canvas canvas=new Canvas();boolean fail;int calls;Chain(View v){view=v;}Object getThisObject(){return view;}Object getArg(int n){return canvas;}Object proceed(){calls++;if(fail)throw new IllegalStateException();return canvas.depth>0;}}
 static Object draw(Chain chain){View view=(View)chain.getThisObject();BODY}
 GATE
 static int checks;
 static void check(boolean value){checks++;if(!value)throw new AssertionError("check "+checks);}
 static boolean shade(int[] values,int color){for(int value:values)if(value==color)return true;return false;}
 static View page(){Activity activity=new Activity();DecorView decor=new DecorView();decor.context=new ContextWrapper(new Context());decor.mWindow=activity.window;View card=new View();card.parent=decor;card.context=new ContextWrapper(activity);return card;}
 static void nativeDraw(View view){Chain c=new Chain(view);check(Boolean.FALSE.equals(draw(c)));check(c.canvas.saves==0&&c.calls==1);}
 public static void main(String[] args) throws Exception {
  decorWindow=DecorView.class.getDeclaredField("mWindow");
  check(DarkBackgroundTargets.contains("com.meizu.battery"));
  for(int rgb:new int[]{0x0b0b0c,0x232323,0x17171a,0x202020,0x1c1c1e,0x2d2d2d})check(shade(DarkBackgroundPolicy.applicationShades(packageName,0),rgb));
  for(int rgb:new int[]{0x767680,0x747480,0x88888c,0x79797e,0xffffff,0x35c44f,0xe42d22})check(!shade(DarkBackgroundPolicy.applicationShades(packageName,0),rgb));
  check(DarkBackgroundPolicy.applicationShades("com.android.settings",0)==DarkBackgroundPolicy.SHADES);
  check(DarkBackgroundPolicy.applicationShades("com.meizu.mstore",1)==DarkBackgroundPolicy.LEGACY_BRIGHT_SHADES);
  View v=page();Chain c=new Chain(v);check(Boolean.TRUE.equals(draw(c)));check(c.canvas.filter==DarkBackgroundPolicy.BATTERY_SURFACE_SHADES);check(c.canvas.depth==0&&c.canvas.restores==1);
  c=new Chain(v.getRootView());check(Boolean.TRUE.equals(draw(c)));check(c.canvas.filter==DarkBackgroundPolicy.STORE_GLOBAL_SHADES);
  AnrDialogHooks.root=v.getRootView();nativeDraw(v);AnrDialogHooks.root=null;
  blackBackground=false;nativeDraw(v);blackBackground=true;
  v.resources.config.uiMode=16;nativeDraw(v);v.resources.config.uiMode=32;
  for(int type:new int[]{2,1000,1002,2005}){v.getRootView().params.type=type;nativeDraw(v);}v.getRootView().params.type=1;
  for(View overlay:new View[]{new PopupContainer(),new DialogContainer(),new BottomSheetContainer()}){overlay.parent=v.getRootView();overlay.context=v.context;View child=new View();child.parent=overlay;child.context=v.context;nativeDraw(child);}
  Activity owner=(Activity)((DecorView)v.getRootView()).mWindow.context;owner.window.floating=true;nativeDraw(v);owner.window.floating=false;
  owner.translucent=true;nativeDraw(v);owner.translucent=false;
  v.bg=new BitmapDrawable();nativeDraw(v);v.bg=new Drawable();
  c=new Chain(v);c.fail=true;try{draw(c);throw new AssertionError();}catch(IllegalStateException expected){}check(c.canvas.depth==0&&c.canvas.restores==1);
  check(!shade(DarkBackgroundPolicy.applicationShades("com.android.settings",0),0x1c1c1e));
  packageName="com.meizu.safe";appActivity="com.meizu.safe.ui.app.AppManagementActivity";
  c=new Chain(v);check(Boolean.TRUE.equals(draw(c)));check(c.canvas.filter==AppManagementBackgroundPolicy.SHADES);
  blackBackground=false;nativeDraw(v);blackBackground=true;
  v.resources.config.uiMode=16;nativeDraw(v);v.resources.config.uiMode=32;
  DialogContainer popup=new DialogContainer();popup.parent=v.getRootView();popup.context=v.context;nativeDraw(popup);
  packageName="com.android.settings";appActivity="com.android.settings.applications.InstalledAppDetailsTop";
  c=new Chain(v);draw(c);check(c.canvas.filter==AppManagementBackgroundPolicy.SHADES);
  appActivity="com.android.settings.SubSettings";appFragment="com.meizu.settings.applications.InstalledAppDetailsPermissionsDetails";
  c=new Chain(v);draw(c);check(c.canvas.filter==AppManagementBackgroundPolicy.SHADES);
  appFragment="com.meizu.settings.MzDisplaySettings";c=new Chain(v);draw(c);check(c.canvas.filter==DarkBackgroundPolicy.BATTERY_SURFACE_SHADES);
  for(String pkg:new String[]{"com.android.dialer","com.android.mms"}) {
   packageName=pkg;appActivity="";appFragment="";
   backgroundFilter=new ThemeBackgroundFilter(DarkBackgroundPolicy.applicationShades(pkg,0));
   check(DarkBackgroundTargets.contains(pkg));
   for(int rgb:new int[]{0x0b0b0c,0x232323,0x17171a,0x202020,0x1c1c1e,0x2d2d2d})check(shade((int[])backgroundFilter.filter,rgb));
   for(int rgb:new int[]{0x767680,0xffffff,0x35c44f,0xe42d22})check(!shade((int[])backgroundFilter.filter,rgb));
   c=new Chain(v);check(Boolean.TRUE.equals(draw(c)));check(c.canvas.filter==DarkBackgroundPolicy.PHONE_MESSAGE_SURFACE_SHADES);
   blackBackground=false;nativeDraw(v);blackBackground=true;
   v.resources.config.uiMode=16;nativeDraw(v);v.resources.config.uiMode=32;
   nativeDraw(popup);v.bg=new BitmapDrawable();nativeDraw(v);v.bg=new Drawable();
  }
  System.out.println("PASS: "+checks+" actual battery/phone/message background scope checks; night/off, card/window, popups/dialogs/overlays, alpha shades and exception restoration");
 }
}
'''.replace('BODY',body).replace('GATE',gate)
assert 'DarkBackgroundPolicy.applicationShades(packageName, legacyBackgroundFamily)' in source
assert 'com.meizu.battery' in (ROOT / 'app/src/main/resources/META-INF/xposed/scope.list').read_text().splitlines()
with tempfile.TemporaryDirectory(prefix='flyme-battery-test-', dir='/private/tmp') as directory:
    path=Path(directory)/'BatteryBackgroundTest.java'
    path.write_text(java)
    subprocess.run(['/opt/homebrew/opt/openjdk@21/bin/javac','-d',directory,str(path),*[str(ROOT/'app/src/main/java/dev/rikumi/flymemod'/f'{name}.java') for name in ['DarkBackgroundPolicy','DarkBackgroundTargets','AppManagementBackgroundPolicy']]],check=True)
    subprocess.run(['/opt/homebrew/opt/openjdk@21/bin/java','-cp',directory,'dev.rikumi.flymemod.BatteryBackgroundTest'],check=True)
