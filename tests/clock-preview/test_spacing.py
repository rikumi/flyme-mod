from pathlib import Path
import subprocess
import tempfile
ROOT=Path(__file__).resolve().parents[2]
JDK=Path('/opt/homebrew/opt/openjdk@21/bin')
S={
'android/content/Context.java':'package android.content; public class Context {}',
'android/content/SharedPreferences.java':'''package android.content; public interface SharedPreferences {boolean getBoolean(String k,boolean d);int getInt(String k,int d);void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l);interface OnSharedPreferenceChangeListener {void onSharedPreferenceChanged(SharedPreferences p,String k);}}''',
'android/graphics/Typeface.java':'''package android.graphics; public class Typeface {public static final int NORMAL=0;public static Typeface create(Typeface t,int s){return t;}}''',
'android/os/Looper.java':'package android.os; public class Looper {public static Looper getMainLooper(){return new Looper();}}',
'android/os/Handler.java':'package android.os; public class Handler {public Handler(Looper l){} public void post(Runnable r){r.run();}}',
'android/view/View.java':'''package android.view; public class View {public static final int GONE=8;public View parent;public int visibility,layouts;public ViewGroup.MarginLayoutParams params=new ViewGroup.MarginLayoutParams();public View getParent(){return parent;}public int getVisibility(){return visibility;}public ViewGroup.LayoutParams getLayoutParams(){return params;}public void setLayoutParams(ViewGroup.LayoutParams p){params=(ViewGroup.MarginLayoutParams)p;}public android.content.Context getContext(){return new android.content.Context();}public void requestLayout(){layouts++;}public void invalidate(){}public void measure(int a,int b){}}''',
'android/view/ViewGroup.java':'''package android.view;public class ViewGroup extends View {public java.util.List<View> children=new java.util.ArrayList<>();public int getChildCount(){return children.size();}public View getChildAt(int i){return children.get(i);}public void add(View v){children.add(v);v.parent=this;}public static class LayoutParams{} public static class MarginLayoutParams extends LayoutParams {int start=2;public int getMarginStart(){return start;}public void setMarginStart(int n){start=n;}}}''',
'android/widget/LinearLayout.java':'''package android.widget;public class LinearLayout extends android.view.ViewGroup {public static final int HORIZONTAL=0;public int orientation;public int getOrientation(){return orientation;}public void onMeasure(int a,int b){}}''',
'android/widget/TextView.java':'''package android.widget;public class TextView extends android.view.View {public float spacing=.03f,size=100;public android.graphics.Typeface face=new android.graphics.Typeface();public float getTextSize(){return size;}public float getLetterSpacing(){return spacing;}public void setLetterSpacing(float n){spacing=n;}public android.graphics.Typeface getTypeface(){return face;}public void setTypeface(android.graphics.Typeface t){face=t;}public void setTypeface(android.graphics.Typeface t,int s){face=t;}public boolean setFontVariationSettings(String s){return true;}public void onMeasure(int a,int b){}}''',
'com/flyme/systemuieditor/widget/view/clockview/EditorClockTextView.java':'''package com.flyme.systemuieditor.widget.view.clockview;public class EditorClockTextView extends android.widget.LinearLayout{}''',
'com/flyme/systemui/plugins/clocks/core/ui/view/PerspectiveDigitClockView.java':'''package com.flyme.systemui.plugins.clocks.core.ui.view;public class PerspectiveDigitClockView extends android.widget.TextView {public void onMeasure(int a,int b){}}''',
'dev/rikumi/flymemod/ModuleSettings.java':'''package dev.rikumi.flymemod;class ModuleSettings {static final String LOCK_CLOCK_FONT="font",CLOCK_FONT_WEIGHT="weight",LOCK_CLOCK_SPACING="spacing",LOCK_CLOCK_SPACING_ENABLED="enabled";}''',
'dev/rikumi/flymemod/ClockTypefaceLoader.java':'''package dev.rikumi.flymemod;class ClockTypefaceLoader {android.graphics.Typeface resolve(android.content.Context c,int f,int w,android.graphics.Typeface t){return null;}}''',
'dev/rikumi/flymemod/SignalHooks.java':'''package dev.rikumi.flymemod;class SignalHooks {interface Installer {void hook(String n,String m,Hook h,Class<?>...p);}interface Hook {Object run(Chain c)throws Throwable;}static class Chain {Object owner;Object[] args;Chain(Object o,Object... a){owner=o;args=a;}Object getThisObject(){return owner;}Object getArg(int i){return args[i];}Object proceed(){return null;}Object proceed(Object[] a){args=a;return null;}}}''',
'dev/rikumi/flymemod/Test.java':'''package dev.rikumi.flymemod;
import android.widget.*;import android.view.*;import android.content.*;import java.util.*;
import com.flyme.systemuieditor.widget.view.clockview.EditorClockTextView;
import com.flyme.systemui.plugins.clocks.core.ui.view.PerspectiveDigitClockView;
public class Test {
 static int checks;static void eq(float a,float b){checks++;if(Math.abs(a-b)>.00001)throw new AssertionError(a+" != "+b);}
 static class Prefs implements SharedPreferences {boolean enabled=true;int spacing=-4;OnSharedPreferenceChangeListener listener;public boolean getBoolean(String k,boolean d){return enabled;}public int getInt(String k,int d){return k.equals("spacing")?spacing:d;}public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l){listener=l;}}
 public static void main(String[] args)throws Throwable {
  Prefs p=new Prefs();Map<String,SignalHooks.Hook> h=new HashMap<>();
  new ClockPreviewFontHooks(Test.class.getClassLoader(),p,(m,e)->{throw new AssertionError(m,e);}).install((n,m,f,t)->h.put(n+"#"+m+t.length,f),m->{});
  EditorClockTextView editor=new EditorClockTextView();LinearLayout root=new LinearLayout(),hours=new LinearLayout(),minutes=new LinearLayout();
  TextView h1=new TextView(),h2=new TextView(),colon=new TextView(),m1=new TextView(),m2=new TextView();editor.add(root);root.add(hours);root.add(colon);root.add(minutes);hours.add(h1);hours.add(h2);minutes.add(m1);minutes.add(m2);
  SignalHooks.Hook measure=h.get("android.widget.LinearLayout#onMeasure2");
  measure.run(new SignalHooks.Chain(editor,0,0));eq(h1.params.getMarginStart(),2);eq(h2.params.getMarginStart(),-2);eq(colon.params.getMarginStart(),-2);eq(minutes.params.getMarginStart(),-2);eq(m2.params.getMarginStart(),-2);
  measure.run(new SignalHooks.Chain(editor,0,0));eq(h2.params.getMarginStart(),-2);
  root.orientation=1;colon.visibility=View.GONE;measure.run(new SignalHooks.Chain(editor,0,0));eq(minutes.params.getMarginStart(),2);eq(h2.params.getMarginStart(),-2);
  p.spacing=10;h2.size=50;measure.run(new SignalHooks.Chain(editor,0,0));eq(h2.params.getMarginStart(),7);
  p.enabled=false;measure.run(new SignalHooks.Chain(editor,0,0));eq(h2.params.getMarginStart(),2);eq(colon.params.getMarginStart(),2);eq(m2.params.getMarginStart(),2);
  PerspectiveDigitClockView perspective=new PerspectiveDigitClockView();SignalHooks.Hook textMeasure=h.get("android.widget.TextView#onMeasure2");p.enabled=true;p.spacing=-4;textMeasure.run(new SignalHooks.Chain(perspective,0,0));eq(perspective.spacing,-.04f);p.enabled=false;textMeasure.run(new SignalHooks.Chain(perspective,0,0));eq(perspective.spacing,.03f);
  TextView other=new TextView();p.enabled=true;textMeasure.run(new SignalHooks.Chain(other,0,0));eq(other.spacing,.03f);
  p.listener.onSharedPreferenceChanged(p,"spacing");eq(h2.layouts,1);eq(perspective.layouts,1);
  System.out.println("PASS: "+checks+" preview spacing checks");
 }
}'''
}
with tempfile.TemporaryDirectory(dir='/private/tmp',prefix='clock-preview-') as d:
 p=Path(d)
 for n,s in S.items():
  f=p/n;f.parent.mkdir(parents=True,exist_ok=True);f.write_text(s)
 subprocess.run([str(JDK/'javac'),'-d',d,*map(str,p.rglob('*.java')),str(ROOT/'app/src/main/java/dev/rikumi/flymemod/ClockPreviewFontHooks.java')],check=True)
 subprocess.run([str(JDK/'java'),'-cp',d,'dev.rikumi.flymemod.Test'],check=True)
