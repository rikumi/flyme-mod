"""Reproduce the native ViewPager2 child name in the production recursive hit test."""
from pathlib import Path
import subprocess
import tempfile
sources = {
 'android/graphics/Rect.java': '''package android.graphics; public class Rect {public int l,t,r,b;public boolean contains(int x,int y){return x>=l&&x<r&&y>=t&&y<b;}}''',
 'android/view/ViewParent.java': 'package android.view; public interface ViewParent {ViewParent getParent();}',
 'android/view/View.java': '''package android.view; public class View implements ViewParent {
 public static final int VISIBLE=0,GONE=8; public int visibility=VISIBLE,left=0,top=0,right=100,bottom=100;public ViewParent parent;
 public ViewParent getParent(){return parent;}public int getVisibility(){return visibility;}
 public boolean getGlobalVisibleRect(android.graphics.Rect r){r.l=left;r.t=top;r.r=right;r.b=bottom;return right>left&&bottom>top;}
 }''',
 'android/view/ViewGroup.java': '''package android.view; public class ViewGroup extends View {
 public final java.util.List<View> children=new java.util.ArrayList<>();
 public void add(View v){children.add(v);v.parent=this;}public int getChildCount(){return children.size();} public View getChildAt(int i){return children.get(i);}
 }''',
 'android/widget/SeekBar.java':'package android.widget;public class SeekBar extends android.view.View {}',
 'androidx/recyclerview/widget/RecyclerView.java':'package androidx.recyclerview.widget;public class RecyclerView extends android.view.ViewGroup {}',
 'androidx/viewpager2/widget/ViewPager2.java':'''package androidx.viewpager2.widget;public class ViewPager2 extends android.view.ViewGroup {
 public static class RecyclerViewImpl extends androidx.recyclerview.widget.RecyclerView {}
 }''',
 'dev/rikumi/flymemod/TouchTargetsTest.java':'''package dev.rikumi.flymemod;import android.view.*;import androidx.viewpager2.widget.ViewPager2;
 public class TouchTargetsTest {
 static int checks;static void check(boolean v){checks++;if(!v)throw new AssertionError("check "+checks);}
 static class MediaScrollView extends View {} static class BrightnessSlider extends View {} static class ExpandableNotificationRow extends View {}
 public static void main(String[]args) {
  var root=new ViewGroup();var pager=new ViewPager2();var recycler=new ViewPager2.RecyclerViewImpl();var page=new ViewGroup();var tile=new View();
  root.add(pager);pager.add(recycler);recycler.add(page);page.add(tile);
  check(!SeparateShadeSwipeTouchTargets.reserved(root,50,50,pager));
  check(SeparateShadeSwipeTouchTargets.reserved(root,50,50,null));
  var nested=new ViewPager2();page.add(nested);check(SeparateShadeSwipeTouchTargets.reserved(root,50,50,pager));
  nested.visibility=View.GONE;check(!SeparateShadeSwipeTouchTargets.reserved(root,50,50,pager));
  for(View target:new View[]{new android.widget.SeekBar(),new BrightnessSlider(),new MediaScrollView(),new ExpandableNotificationRow()}) {
   root.add(target);check(SeparateShadeSwipeTouchTargets.reserved(root,50,50,pager));
   target.left=60;check(!SeparateShadeSwipeTouchTargets.reserved(root,50,50,pager));target.visibility=View.GONE;
  }
  check(!SeparateShadeSwipeTouchTargets.reserved(root,150,50,pager));
  System.out.println("PASS: "+checks+" actual pager child, independent pager and reserved-control hit checks");
 }
 }''',
}
with tempfile.TemporaryDirectory(dir='/private/tmp') as directory:
 root=Path(directory)
 for name,content in sources.items():
  target=root/name;target.parent.mkdir(parents=True,exist_ok=True);target.write_text(content)
 (root/'dev/rikumi/flymemod/SeparateShadeSwipeTouchTargets.java').write_text(Path('app/src/main/java/dev/rikumi/flymemod/SeparateShadeSwipeTouchTargets.java').read_text())
 jdk=Path('/opt/homebrew/opt/openjdk@21/bin')
 subprocess.run([str(jdk/'javac'),'-d',str(root/'classes'),*map(str,root.rglob('*.java'))],check=True)
 subprocess.run([str(jdk/'java'),'-cp',str(root/'classes'),'dev.rikumi.flymemod.TouchTargetsTest'],check=True)
