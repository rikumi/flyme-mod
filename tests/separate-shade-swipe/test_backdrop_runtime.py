"""Exercise the production backdrop against independent wallpaper and application blur owners."""
from pathlib import Path
import subprocess
import tempfile

sources = {
    'android/graphics/drawable/Drawable.java': '''package android.graphics.drawable;
public class Drawable { private int alpha=255; public int getAlpha(){return alpha;} public void setAlpha(int v){alpha=v;} public boolean setVisible(boolean v,boolean restart){return true;} }''',
    'com/android/internal/graphics/drawable/BackgroundBlurDrawable.java': '''package com.android.internal.graphics.drawable;
public class BackgroundBlurDrawable extends android.graphics.drawable.Drawable { public int radius,radiusWrites; public void setBlurRadius(int v){radius=v;radiusWrites++;} public void setColor(int v){} }''',
    'android/content/res/Resources.java': '''package android.content.res;
public class Resources { public int getIdentifier(String name,String type,String pkg){return 1;} }''',
    'android/view/View.java': '''package android.view;
public class View implements ViewParent {
 public static final int VISIBLE=0,GONE=8,IMPORTANT_FOR_ACCESSIBILITY_NO=2; private int visibility; private float alpha=1;
 public Object effect=new Object(); public View child; public android.graphics.drawable.Drawable background;
 public View(){} public View(android.content.Context context){}
 public ViewParent parent; public ViewParent getParent(){return parent;}
 public ViewRootImpl window=new ViewRootImpl(); public ViewRootImpl getViewRootImpl(){return window;}
 public android.content.Context getContext(){return new android.content.Context();}
 public void setImportantForAccessibility(int v){} public void setClickable(boolean v){} public void setFocusable(boolean v){}
 public int getWidth(){return 1080;} public int getHeight(){return 2400;} public void layout(int l,int t,int r,int b){}
 public void setBackground(android.graphics.drawable.Drawable value){background=value;}
 public float getAlpha(){return alpha;} public void setAlpha(float value){alpha=value;}
 public int getVisibility(){return visibility;} public void setVisibility(int v){visibility=v;}
 public android.graphics.drawable.Drawable getBackground(){return background;}
 public android.content.res.Resources getResources(){return new android.content.res.Resources();}
 public View findViewById(int id){return child;} public void setRenderEffect(Object v){effect=v;}
}''',
    'android/widget/FrameLayout.java': 'package android.widget; public class FrameLayout extends android.view.ViewGroup {}',
    'android/widget/ImageView.java': 'package android.widget; public class ImageView extends android.view.View {}',
    'android/view/SurfaceControl.java': '''package android.view;
public class SurfaceControl { public float alpha=1; public boolean valid=true; public boolean isValid(){return valid;}
 public static class Transaction implements AutoCloseable {
  public Transaction setAlpha(SurfaceControl target,float value){target.alpha=value;return this;}
  public void apply(){} public void close(){}
 }
}''',
    'kotlinx/coroutines/flow/StateFlow.java': 'package kotlinx.coroutines.flow; public interface StateFlow { Object getValue(); }',
    'com/android/systemui/Dependency.java': '''package com.android.systemui;
public class Dependency { public static Object get(Class<?> type){return com.flyme.systemui.wallpaper.LockscreenWallpaperManager.instance;} }''',
    'com/flyme/systemui/controlcenter/domain/interactor/ControlCenterInteractor.java': '''package com.flyme.systemui.controlcenter.domain.interactor;
public interface ControlCenterInteractor {void setBlurRadiusFactor(float v);void setBlurScaleFactor(float v);}''',
    'com/flyme/systemui/controlcenter/domain/interactor/ControlCenterBlurInteractor.java': '''package com.flyme.systemui.controlcenter.domain.interactor;
public class ControlCenterBlurInteractor {public static class BlurState {}}''',
    'com/flyme/systemui/controlcenter/ui/binder/MzRootContainerBlurBinder.java': '''package com.flyme.systemui.controlcenter.ui.binder;
public class MzRootContainerBlurBinder {
 public int applies; public kotlinx.coroutines.flow.StateFlow getBlurState(){return ()->new com.flyme.systemui.controlcenter.domain.interactor.ControlCenterBlurInteractor.BlurState();}
 public void applyBlurState(com.flyme.systemui.controlcenter.domain.interactor.ControlCenterBlurInteractor.BlurState value){applies++;}
}''',
    'com/android/systemui/shade/NotificationPanelViewController.java': '''package com.android.systemui.shade;
public class NotificationPanelViewController {public float mCurrentBlurRadius,mCurrentBlurScale; public com.android.systemui.statusbar.NotificationShadeDepthController mDepthController=new com.android.systemui.statusbar.NotificationShadeDepthController();}''',
    'com/flyme/systemui/controlcenter/phone/CenterController.java': '''package com.flyme.systemui.controlcenter.phone;
public class CenterController {
 public float mExpandedFraction=1; public static int sBLUR_RADIUS_MAX=300; public float mCurrentBlurRadius,mCurrentBlurScale;
 public com.flyme.systemui.controlcenter.domain.interactor.ControlCenterInteractor mControlCenterInteractor;
 public com.flyme.systemui.controlcenter.ui.binder.MzRootContainerBlurBinder mMzRootContainerBlurBinder=new com.flyme.systemui.controlcenter.ui.binder.MzRootContainerBlurBinder();
}''',
    'com/flyme/systemui/wallpaper/LockscreenWallpaperManager.java': '''package com.flyme.systemui.wallpaper;
public class LockscreenWallpaperManager {
 public static LockscreenWallpaperManager instance=new LockscreenWallpaperManager();
 public android.widget.FrameLayout keyguardBackgroundFrame=new android.widget.FrameLayout();
 public android.view.View wallpaperBlurBg=new android.view.View(), foregroundShadeView=new android.view.View();
 public android.widget.ImageView variedpage=new android.widget.ImageView();
 public android.view.SurfaceControl surfaceControl=new android.view.SurfaceControl();
 public kotlinx.coroutines.flow.StateFlow wallpaperSurfaceAlpha=()->1f;
 public void setKeyguardBackgroundFrameVisibility(boolean v){keyguardBackgroundFrame.setVisibility(v?0:8);}
 public int mirrorHeight=1;public float wallpaperY=-2400,wallpaperClip;
 public void setWallpapeMirrorViewHeight(int height){mirrorHeight=height;}
 public void setWallpaperBlurBgY(float y,float clip){wallpaperY=y;wallpaperClip=clip;}
 public void setWallpaperAlpha(float v){if(surfaceControl!=null)surfaceControl.alpha=v;}
}''',
    'dev/rikumi/flymemod/SignalHooks.java': '''package dev.rikumi.flymemod;
class SignalHooks {
 interface Chain {Object getThisObject();Object getArg(int index);Object proceed() throws Throwable;Object proceed(Object[] args) throws Throwable;}
 interface Hooker {Object invoke(Chain c) throws Throwable;}
 interface Installer {void hook(String cls,String method,Hooker hooker,Class<?>...args);}
}''',
    'dev/rikumi/flymemod/BackdropTest.java': '''package dev.rikumi.flymemod;
import android.view.*;
import com.flyme.systemui.controlcenter.phone.CenterController;
import com.android.systemui.shade.NotificationPanelViewController;
import com.flyme.systemui.wallpaper.LockscreenWallpaperManager;
public class BackdropTest {
 static int checks;
 static void check(boolean v){checks++;if(!v)throw new AssertionError("check "+checks);}
 static void near(float a,float b){check(Math.abs(a-b)<.005f);}
 public static class Blur implements com.flyme.systemui.controlcenter.domain.interactor.ControlCenterInteractor {
  float radius,scale;public void setBlurRadiusFactor(float v){radius=v;} public void setBlurScaleFactor(float v){scale=v;}
 }
 public static void main(String[]args)throws Throwable {
  var wallpaper=LockscreenWallpaperManager.instance;var center=new CenterController();var panel=new NotificationPanelViewController();
  var root=new ViewGroup();root.child=new View();var blur=new Blur();center.mControlCenterInteractor=blur;
  var drawable=new com.android.internal.graphics.drawable.BackgroundBlurDrawable();wallpaper.wallpaperBlurBg.background=drawable;
  var engine=new SeparateShadeSwipeBackdrop(BackdropTest.class.getClassLoader());
  var hooks=new java.util.HashMap<String,SignalHooks.Hooker>();engine.install((cls,name,hook,types)->hooks.put(name,hook));
  for(boolean sourceCenter:new boolean[]{false,true})for(boolean destinationCenter:new boolean[]{false,true}) {
   engine.begin(panel,center,root,sourceCenter);
   for(float p:new float[]{0,.1f,.4f,.8f,1,.6f,.2f,0}) {
    engine.apply(p);near(blur.radius,0);near(blur.scale,0);near(center.mCurrentBlurRadius,0);
    check(root.window.latest.radius==300);check(root.window.latest.radiusWrites==1);
    near(root.window.latest.getAlpha(),255);
    check(wallpaper.mirrorHeight==2400);near(wallpaper.wallpaperY,0);
    // Stacked composition must have zero weight for the unblurred application.
    near((1-wallpaper.surfaceControl.alpha)*(1-root.window.latest.getAlpha()/255f),0);
    near(panel.mCurrentBlurRadius,0);near(wallpaper.surfaceControl.alpha,1-p);
    check(wallpaper.keyguardBackgroundFrame.getVisibility()==View.VISIBLE);
    near(drawable.getAlpha(),Math.round(255*(1-p)));near(wallpaper.wallpaperBlurBg.getAlpha(),1);
   }
   engine.finish(destinationCenter,true);
   near(blur.radius,destinationCenter?1:0);near(blur.scale,destinationCenter?1:0);near(panel.mCurrentBlurRadius,0);
   check(wallpaper.keyguardBackgroundFrame.getVisibility()==(destinationCenter?View.GONE:View.VISIBLE));
   check(drawable.getAlpha()==255);near(wallpaper.foregroundShadeView.getAlpha(),1);check(root.child.effect==null);
  }
  // Full-opacity temporary region stays until the native endpoint is submitted.
  engine.begin(panel,center,root,false); engine.apply(1); engine.finish(true,true);
  check(root.children.size()==1); check(engine.hasPendingRoot(root));
  for(int applied:new int[]{0,150,300}) {
   hooks.get("onBlurApplied").invoke(new SignalHooks.Chain(){
    public Object getThisObject(){return panel.mDepthController;}public Object getArg(int i){return i==0?(Object)Integer.valueOf(applied):Float.valueOf(1);}
    public Object proceed(){return null;}public Object proceed(Object[]args){return null;}
   });
   check(root.children.size()==(applied<300?1:0));
  }
  check(!engine.hasPendingRoot(root));
  engine.setBlurRadiusOverride(()->700);engine.begin(panel,center,root,false);
  engine.setBlurRadiusOverride(()->900);for(float p:new float[]{0,.5f,1}) {engine.apply(p);check(root.window.latest.radius==700);check(root.window.latest.radiusWrites==1);}
  engine.finish(false,true);check(root.children.isEmpty());
  engine.setBlurRadiusOverride(()->0);engine.begin(panel,center,root,true);engine.apply(.5f);check(root.window.latest.radius==0);near(root.window.latest.getAlpha(),255);
  engine.finish(true,true);engine.onDetached(root);check(root.children.isEmpty());check(!engine.hasPendingRoot(root));
  engine.setBlurRadiusOverride(()->-1);
  // Replacement/native alpha updates must not accumulate our previous fade.
  engine.begin(panel,center,root,false);engine.apply(.5f);drawable.setAlpha(200);engine.apply(.25f);
  near(drawable.getAlpha(),150);wallpaper.foregroundShadeView.setAlpha(.4f);engine.apply(.5f);
  near(wallpaper.foregroundShadeView.getAlpha(),.2f);engine.finish(false,true);near(drawable.getAlpha(),200);
  near(wallpaper.foregroundShadeView.getAlpha(),.4f);near(blur.radius,0);
  // A late-created Surface joins the current pose instead of appearing at full alpha.
  wallpaper.surfaceControl=null;engine.begin(panel,center,root,true);engine.apply(.3f);
  wallpaper.surfaceControl=new SurfaceControl();engine.apply(.3f);near(wallpaper.surfaceControl.alpha,.7f);
  engine.apply(-.2f);near(blur.radius,0);engine.apply(1.2f);near(blur.radius,0);near(root.window.latest.getAlpha(),255);
  engine.finish(false,true);near(blur.radius,0);
  // A native hide received during an active drag is deferred and replayed on interruption.
  engine.begin(panel,center,root,false);engine.apply(.5f);
  hooks.get("setKeyguardBackgroundFrameVisibility").invoke(new SignalHooks.Chain(){
   public Object getThisObject(){return wallpaper;}public Object getArg(int index){return false;}
   public Object proceed(){wallpaper.setKeyguardBackgroundFrameVisibility(false);return null;}
   public Object proceed(Object[] values){wallpaper.setKeyguardBackgroundFrameVisibility((Boolean)values[0]);return null;}
  });
  check(wallpaper.keyguardBackgroundFrame.getVisibility()==View.VISIBLE);
  engine.finish(false,false);check(wallpaper.keyguardBackgroundFrame.getVisibility()==View.GONE);
  System.out.println("PASS: "+checks+" production backdrop progression, rollback and native writer checks");
 }
}''',
}

sources.update({
 'android/content/Context.java': 'package android.content; public class Context {}',
 'android/view/ViewParent.java': 'package android.view; public interface ViewParent {ViewParent getParent();}',
 'android/view/ViewGroup.java': """package android.view; public class ViewGroup extends View {
 public final java.util.List<View> children=new java.util.ArrayList<>();
 public static class LayoutParams {public static final int MATCH_PARENT=-1;public LayoutParams(int w,int h){}}
 public void addView(View v,int i,LayoutParams p){children.add(i,v);v.parent=this;}
 public void removeView(View v){children.remove(v);v.parent=null;}
 }""",
 'android/view/ViewRootImpl.java': """package android.view; public class ViewRootImpl {
 public com.android.internal.graphics.drawable.BackgroundBlurDrawable latest;
 public com.android.internal.graphics.drawable.BackgroundBlurDrawable createBackgroundBlurDrawable(){return latest=new com.android.internal.graphics.drawable.BackgroundBlurDrawable();}
 }""",
 'com/android/systemui/statusbar/NotificationShadeDepthController.java': """package com.android.systemui.statusbar;
 public class NotificationShadeDepthController {public com.android.systemui.statusbar.BlurUtils blurUtils=new com.android.systemui.statusbar.BlurUtils();}
 """,
 'com/android/systemui/statusbar/BlurUtils.java': """package com.android.systemui.statusbar;
 public class BlurUtils {public float getMaxBlurRadius(){return 300;} public void applyBlurMZ(android.view.ViewRootImpl root,int radius,boolean opaque,float scale) {if(radius!=0)throw new AssertionError();}}
 """,
})

with tempfile.TemporaryDirectory(dir='/private/tmp') as directory:
    root = Path(directory)
    for name, content in sources.items():
        target = root / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content)
    (root / 'dev/rikumi/flymemod/SeparateShadeSwipeBackdrop.java').write_text(
        Path('app/src/main/java/dev/rikumi/flymemod/SeparateShadeSwipeBackdrop.java').read_text())
    (root / 'dev/rikumi/flymemod/SeparateShadeFixedBlur.java').write_text(Path('app/src/main/java/dev/rikumi/flymemod/SeparateShadeFixedBlur.java').read_text())
    jdk = Path('/opt/homebrew/opt/openjdk@21/bin')
    subprocess.run([str(jdk / 'javac'), '-d', str(root / 'classes'),
                    *map(str, root.rglob('*.java'))], check=True)
    subprocess.run([str(jdk / 'java'), '-cp', str(root / 'classes'),
                    'dev.rikumi.flymemod.BackdropTest'], check=True)
