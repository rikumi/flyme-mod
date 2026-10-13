#!/usr/bin/env python3
"""Execute production cache code with a deterministic executor and minimal view lifecycle doubles.

These checks validate cache behavior and operation counts, not Android rendering or frame time.
"""
from pathlib import Path
import subprocess
import tempfile

repo = Path(__file__).resolve().parents[2]
jdk = Path('/opt/homebrew/opt/openjdk@21/bin')
stubs = {
    'android/os/Build.java': '''package android.os;
public class Build { public static class VERSION { public static int SDK_INT=36; } }''',
    'android/graphics/Path.java': '''package android.graphics;
import java.util.*;
public class Path {
 public int fill; int generation; final List<float[]> parts=new ArrayList<>();
 public Path(){} public Path(Path other){set(other);}
 public void set(Path other){fill=other.fill;parts.clear();for(var p:other.parts)parts.add(p.clone());generation++;}
 public int getFillType(){return fill;} public int getGenerationId(){return generation;}
 public PathIterator getPathIterator(){return new PathIterator(this);}
 public Path add(int verb,float... points){float[] p=new float[9];p[0]=verb;System.arraycopy(points,0,p,1,points.length);parts.add(p);generation++;return this;}
 public void change(int part,int coordinate,float value){parts.get(part)[coordinate+1]=value;generation++;}
}''',
    'android/graphics/PathIterator.java': '''package android.graphics;
public class PathIterator {
 public static final int VERB_MOVE=0,VERB_LINE=1,VERB_QUAD=2,VERB_CONIC=3,VERB_CUBIC=4,VERB_CLOSE=5,VERB_DONE=6;
 public static int iterations; final Path path; int index;
 PathIterator(Path path){this.path=path;}
 public int next(float[] points,int offset){iterations++;if(index==path.parts.size())return VERB_DONE;
  float[] p=path.parts.get(index++);System.arraycopy(p,1,points,offset,8);return (int)p[0];}
}''',
    'android/view/ViewParent.java': 'package android.view; public interface ViewParent {}',
    'android/view/ViewTreeObserver.java': '''package android.view;
import java.util.*;
public class ViewTreeObserver {
 public interface OnGlobalLayoutListener { void onGlobalLayout(); }
 public final List<OnGlobalLayoutListener> listeners=new ArrayList<>();
 public boolean isAlive(){return true;}
 public void addOnGlobalLayoutListener(OnGlobalLayoutListener l){listeners.add(l);}
 public void removeOnGlobalLayoutListener(OnGlobalLayoutListener l){listeners.remove(l);}
 public void layout(){for(var l:new ArrayList<>(listeners))l.onGlobalLayout();}
}''',
    'android/view/View.java': '''package android.view;
import java.util.*;
public class View implements ViewParent {
 public interface OnAttachStateChangeListener {
  void onViewAttachedToWindow(View v); void onViewDetachedFromWindow(View v);
 }
 ViewParent parent; boolean attached;
 final ViewTreeObserver observer=new ViewTreeObserver();
 final List<OnAttachStateChangeListener> listeners=new ArrayList<>();
 public ViewParent getParent(){return parent;}
 public View getRootView(){return parent instanceof View v?v.getRootView():this;}
 public boolean isAttachedToWindow(){return getRootView().attached;}
 public ViewTreeObserver getViewTreeObserver(){return getRootView().observer;}
 public void addOnAttachStateChangeListener(OnAttachStateChangeListener l){listeners.add(l);}
 public void attach(){attached=true; for(var l:listeners)l.onViewAttachedToWindow(this);}
 public void detach(){for(var l:listeners)l.onViewDetachedFromWindow(this); attached=false;}
}''',
    'android/view/ViewGroup.java': '''package android.view;
import java.util.*;
public class ViewGroup extends View {
 final List<View> children=new ArrayList<>(); public int scans;
 public void add(View v){children.add(v);v.parent=this;}
 public void remove(View v){children.remove(v);v.parent=null;}
 public int getChildCount(){return children.size();}
 public View getChildAt(int i){scans++;return children.get(i);}
}''',
}
with tempfile.TemporaryDirectory(prefix='flyme-performance-', dir='/private/tmp') as directory:
    output = Path(directory)
    sources = []
    for name, code in stubs.items():
        path = output / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(code)
        sources.append(str(path))
    for name in ['RunningPackagesSnapshot', 'LayoutScopedViewLookup', 'ContourMaskCache']:
        sources.append(str(repo / 'app/src/main/java/dev/rikumi/flymemod' / f'{name}.java'))
        sources.append(str(Path(__file__).parent / f'{name}Test.java'))
    subprocess.run([str(jdk / 'javac'), '-d', directory, *sources], check=True)
    for name in ['RunningPackagesSnapshot', 'LayoutScopedViewLookup', 'ContourMaskCache']:
        subprocess.run([str(jdk / 'java'), '-cp', directory, f'dev.rikumi.flymemod.{name}Test'], check=True)
