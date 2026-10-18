"""Exercise production foreground drawing with native updates, scope changes and stable frames."""
from pathlib import Path
import subprocess
import tempfile

root = Path('app/src/main/java/dev/rikumi/flymemod')
sources = {
 'android/content/Context.java': 'package android.content; public class Context {}',
 'android/content/res/Configuration.java': '''package android.content.res;public class Configuration {
 public static final int UI_MODE_NIGHT_MASK=48,UI_MODE_NIGHT_YES=32;public int uiMode=16;}''',
 'android/content/res/ColorStateList.java': '''package android.content.res;
public class ColorStateList { final int color;public ColorStateList(int c){color=c;}
 public static ColorStateList valueOf(int c){return new ColorStateList(c);}
 public int getDefaultColor(){return color;}public int getColorForState(int[] s,int fallback){return color;}}''',
 'android/content/res/Resources.java': '''package android.content.res;
public class Resources {
 public int reads;
 public final Configuration configuration=new Configuration();public Configuration getConfiguration(){return configuration;}
 static final String[] names={"qs_tile_label_color","qs_tile_secondlabel_color","qs_icon_inactive_color","qs_icon_unavailable_color","qs_media_header_artist","qs_meida_icon_inactive_color"};
 static final int[] day={0xff000000,0x993c3c43,0x96000000,0x59000000,0x73000000,0x99000000};
 static final int[] night={0xffffffff,0x96ffffff,0x96ffffff,0x4dffffff,0x73ffffff,0x99ffffff};
 public int getIdentifier(String n,String t,String p){reads++;for(int i=0;i<names.length;i++){if(n.equals(names[i]))return 1+i;if(n.equals(names[i]+"_for_night"))return 101+i;}return 0;}
 public int getColor(int id,Object theme){return id>=101?night[id-101]:day[id-1];}
}''',
 'android/graphics/Canvas.java':'package android.graphics;public class Canvas {}',
 'android/graphics/ColorFilter.java':'package android.graphics;public class ColorFilter {}',
 'android/graphics/PorterDuff.java':'package android.graphics;public class PorterDuff {public enum Mode {SRC_IN,SRC_ATOP}}',
 'android/graphics/PorterDuffColorFilter.java':'''package android.graphics;public class PorterDuffColorFilter extends ColorFilter {
 final int c;final PorterDuff.Mode m; public PorterDuffColorFilter(int c,PorterDuff.Mode m){this.c=c;this.m=m;}
 public int getColor(){return c;}public PorterDuff.Mode getMode(){return m;}}''',
 'android/view/View.java':'''package android.view;public class View {
 public static final int LAYER_TYPE_HARDWARE=2,LAYER_TYPE_NONE=0;
 public float alpha=1;public int pathColor,pathWrites;
 public void setLayerType(int type,Object paint){}public void setAlpha(float value){alpha=value;}
 public boolean circular,center=true;public int invalidates;public final android.content.res.Resources resources=new android.content.res.Resources();
 public android.content.Context getContext(){return new android.content.Context();}
 public android.content.res.Resources getResources(){return resources;}public int[] getDrawableState(){return new int[0];}
 public void invalidate(){invalidates++;}}''',
 'android/widget/TextView.java':'''package android.widget;public class TextView extends android.view.View {
 public android.content.res.ColorStateList colors=android.content.res.ColorStateList.valueOf(0xff000000);public int writes;
 public android.content.res.ColorStateList getTextColors(){return colors;}
 public void setTextColor(android.content.res.ColorStateList c){colors=c;writes++;}}''',
 'android/widget/ImageView.java':'''package android.widget;public class ImageView extends android.view.View {
 public android.content.res.ColorStateList tint;public android.graphics.ColorFilter filter;public int tintWrites,filterWrites;
 public android.content.res.ColorStateList getImageTintList(){return tint;}
 public void setImageTintList(android.content.res.ColorStateList c){tint=c;tintWrites++;}
 public android.graphics.ColorFilter getColorFilter(){return filter;}
 public void setColorFilter(android.graphics.ColorFilter f){filter=f;filterWrites++;}}''',
 'dev/rikumi/flymemod/SignalHooks.java':'''package dev.rikumi.flymemod;class SignalHooks {
 interface Chain {Object getThisObject();Object proceed();}interface Hooker{Object run(Chain c)throws Throwable;}
 interface Installer{void hook(String cls,String method,Hooker hook,Class<?>...args);}}''',
 'dev/rikumi/flymemod/ForegroundTest.java':'''package dev.rikumi.flymemod;
import android.widget.*;import android.view.*;import android.graphics.*;import android.content.res.*;import java.util.*;
public class ForegroundTest {
 static int checks;static void check(boolean v){checks++;if(!v)throw new AssertionError("check "+checks);}
 static Map<String,SignalHooks.Hooker> hooks=new HashMap<>();
 static void draw(View v)throws Throwable{hooks.get(v instanceof TextView?"android.widget.TextView":"android.widget.ImageView").run(new SignalHooks.Chain(){public Object getThisObject(){return v;}public Object proceed(){return null;}});}
 static class Color {static final int WHITE=0xffffffff;static int alpha(int color){return color>>>24;}}
 boolean whiteActiveEnabled,darkenEnabled;
 boolean hasCircularCardActivation(View view){return view.circular;}
 SURFACE
 Set<ImageView> volumeSliderIcons=new HashSet<>();Map<ImageView,Integer> sliderIconColors=new HashMap<>();
 public static class Owner extends View {public ImageView mIconView=new ImageView();}
 public static class Slider extends View {
  final Owner owner=new Owner();int progress;public int getProgress(){return progress;}public int getMax(){return 100;}
 }
 View sliderOwner(View slider){return ((Slider)slider).owner;}
 int activeTileIconColor(String spec){return 0xff123456;}
 void applyIconColor(ImageView icon,int color){icon.pathColor=color;icon.pathWrites++;}
 VOLUME
 public static void main(String[] args)throws Throwable {
  boolean[] enabled={false},activeTile={false};var palette=new ControlCenterForegroundHooks(c->enabled[0],v->v.center,v->activeTile[0]);
  palette.install((cls,method,hook,types)->hooks.put(cls,hook));
  TextView text=new TextView();var black=text.colors;draw(text);check(text.writes==0);
  enabled[0]=true;for(int i=0;i<1000;i++)draw(text);
  check(text.colors.getDefaultColor()==0xffffffff);check(text.writes==1);check(text.resources.reads==12);
  enabled[0]=false;draw(text);check(text.colors==black);check(text.writes==2);
  enabled[0]=true;text.colors=ColorStateList.valueOf(0x993c3c43);var secondary=text.colors;draw(text);check(text.colors.getDefaultColor()==0x96ffffff);
  text.center=false;draw(text);check(text.colors==secondary);
  text.center=true;text.colors=ColorStateList.valueOf(0xff206cff);draw(text);check(text.colors.getDefaultColor()==0xff206cff);
  text.colors=ColorStateList.valueOf(0xff000000);draw(text);text.colors=ColorStateList.valueOf(0x73000000);draw(text);check(text.colors.getDefaultColor()==0x73ffffff);
  enabled[0]=false;draw(text);check(text.colors.getDefaultColor()==0x73000000);
  enabled[0]=true;text.colors=ColorStateList.valueOf(0x99000000);draw(text);
  check(text.colors.getDefaultColor()==0x99ffffff);
  activeTile[0]=true;draw(text);check(text.colors.getDefaultColor()==0x99000000);
  int activeWrites=text.writes;for(int i=0;i<1000;i++)draw(text);check(text.writes==activeWrites);
  // All tile labels retain the actual theme palette, including inactive day labels.
  for(int nativeColor:new int[]{0xff000000,0x993c3c43,0xffffffff,0x96ffffff}) {
   text.colors=ColorStateList.valueOf(nativeColor);draw(text);
   check(text.colors.getDefaultColor()==nativeColor);
  }
  text.colors=ColorStateList.valueOf(0x99000000);
  activeTile[0]=false;draw(text);check(text.colors.getDefaultColor()==0x99ffffff);
  enabled[0]=false;draw(text);check(text.colors.getDefaultColor()==0x99000000);
  enabled[0]=true;ImageView icon=new ImageView();icon.tint=ColorStateList.valueOf(0x59000000);var unavailable=icon.tint;
  for(int i=0;i<1000;i++)draw(icon);check(icon.tint.getDefaultColor()==0x4dffffff);check(icon.tintWrites==1);
  enabled[0]=false;draw(icon);check(icon.tint==unavailable);
  enabled[0]=true;ImageView media=new ImageView();var nativeFilter=new PorterDuffColorFilter(0x99000000,PorterDuff.Mode.SRC_ATOP);media.filter=nativeFilter;
  for(int i=0;i<1000;i++)draw(media);check(media.filterWrites==1);check(((PorterDuffColorFilter)media.filter).getColor()==0x99ffffff);check(((PorterDuffColorFilter)media.filter).getMode()==PorterDuff.Mode.SRC_ATOP);
  enabled[0]=false;draw(media);check(media.filter==nativeFilter);
  enabled[0]=true;media.filter=new PorterDuffColorFilter(0xff206cff,PorterDuff.Mode.SRC_IN);var art=media.filter;draw(media);check(media.filter==art);
  ImageView album=new ImageView();draw(album);check(album.tint==null&&album.filter==null);
  palette.refresh();check(text.invalidates==1&&icon.invalidates==1&&media.invalidates==1);
  for(boolean white:new boolean[]{false,true})for(int level:new int[]{0,14,15})check(volumeSliderIconColor(white,false,level,100)==0xcc555555);
  check(volumeSliderIconColor(false,false,16,100)==0xffffffff);
  check(volumeSliderIconColor(true,false,16,100)==0xcc555555);
  check(volumeSliderIconColor(false,true,0,100)==0xffffffff);
  check(volumeSliderIconColor(true,true,0,100)==0xccdadada);
  // Exercise the real caller too: the dim switch must not masquerade as system night mode.
  ForegroundTest host=new ForegroundTest();Slider slider=new Slider();ImageView volume=slider.owner.mIconView;
  host.volumeSliderIcons.add(volume);
  for(boolean dim:new boolean[]{false,true})for(boolean white:new boolean[]{false,true}) {
   host.darkenEnabled=dim;host.whiteActiveEnabled=white;
   for(int level:new int[]{50,16,15,14,0,15,16,50,0}) {
    slider.progress=level;host.updateSliderIcon(slider,false);draw(volume);
    if(level<=15){check(volume.pathColor==0xff555555);check(Math.abs(volume.alpha-.8f)<.001f);}
   }
  }
  View tile=new View();
  for(boolean white:new boolean[]{false,true})for(boolean circle:new boolean[]{false,true})
   for(boolean disabled:new boolean[]{false,true})for(int state=0;state<3;state++) {
    host.whiteActiveEnabled=white;tile.circular=circle;
    check(host.hasActiveTileSurface(tile,state,disabled)==(white&&!circle&&!disabled&&state==2));
   }
  host.whiteActiveEnabled=true;
  int writes=volume.pathWrites;
  for(int i=0;i<1000;i++)host.updateSliderIcon(slider,false);
  check(volume.pathWrites==writes);
  volume.resources.configuration.uiMode=Configuration.UI_MODE_NIGHT_YES;
  host.updateSliderIcon(slider,false);check(volume.pathColor==0xffdadada);
  volume.resources.configuration.uiMode=16;
  host.updateSliderIcon(slider,false);check(volume.pathColor==0xff555555);
  System.out.println("PASS: "+checks+" palette/scope/native restoration/volume boundary checks; 1000 stable frames write each tint only once");
 }
}'''
}
text=(root/'XposedInit.java').read_text();start=text.index('    private static int volumeSliderIconColor(');end=text.index('\n    private void applyIconColor',start)
sources['dev/rikumi/flymemod/ForegroundTest.java']=sources['dev/rikumi/flymemod/ForegroundTest.java'].replace('VOLUME',text[start:end])
start=text.index('    private boolean hasActiveTileSurface(');end=text.index('    private boolean hasCircularCardActivation(',start)
sources['dev/rikumi/flymemod/ForegroundTest.java']=sources['dev/rikumi/flymemod/ForegroundTest.java'].replace('SURFACE',text[start:end])
sources['dev/rikumi/flymemod/ControlCenterForegroundHooks.java']=(root/'ControlCenterForegroundHooks.java').read_text()
with tempfile.TemporaryDirectory(dir='/private/tmp',prefix='flyme-foreground-') as tmp:
    target=Path(tmp)
    for name,code in sources.items():
        p=target/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(code)
    jdk=Path('/opt/homebrew/opt/openjdk@21/bin')
    subprocess.run([str(jdk/'javac'),'-d',tmp,*map(str,target.rglob('*.java'))],check=True)
    subprocess.run([str(jdk/'java'),'-cp',tmp,'dev.rikumi.flymemod.ForegroundTest'],check=True)
