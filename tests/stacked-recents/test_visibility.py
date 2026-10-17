"""Differential test of the production visibility method against the pre-optimization path."""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
folder = Path(__file__).parent
source = root / 'app/src/main/java/dev/rikumi/flymemod'
hooks = (source / 'StackedRecentsHooks.java').read_text()
method = hooks[hooks.index('    private boolean visible('):hooks.index('    private State state(')]
baseline = (folder / 'fixtures/visible-before.java.txt').read_text().replace('boolean visible(', 'boolean baseline(')
# These checks keep unrelated base View calls outside weak-map lookups.
for name, cast, guard in [('drawChild', 'ViewGroup parent', 'recentsType.isInstance(parent)'),
                          ('setTranslationZ', 'View task', 'taskType.isInstance(task)')]:
    part = hooks[hooks.index('"'+name+'", chain -> {'):]
    assert part.index(guard) < part.index('cards.get(')
java = '''package dev.rikumi.flymemod;
import java.util.*;
import java.lang.reflect.*;
public class VisibilityTest {
 static class View {
  View parent; int width=700,height=900,left=0,top=0,scrollX,scrollY;
  float tx,ty,sx=1,sy=1,px=350,py=450;
  View getParent(){return parent;} int getWidth(){return width;} int getHeight(){return height;}
  int getLeft(){return left;} int getTop(){return top;}
  int getScrollX(){return scrollX;} int getScrollY(){return scrollY;}
  float getTranslationX(){return tx;} float getTranslationY(){return ty;}
  float getScaleX(){return sx;} float getScaleY(){return sy;}
  float getPivotX(){return px;} float getPivotY(){return py;}
 }
 static class ViewGroup extends View {
  int calls; int indexOfChild(View v){return 0;}
  public int scrollForPage(int index){calls++;return 100;}
 }
 static class EntryPose {float x,y,scaleX=.8f,scaleY=.9f,targetScaleX=.7f,targetScaleY=.6f,targetCenter,targetAlpha;}
 record DismissPose(float start,float end){}
 static class State {
  boolean horizontal,entryStarted,entryFinished; float step=700,entryProgress,handoffProgress,
   entryTargetScroll,overScroll,dismissProgress,density=3,full=1;
  Map<View,EntryPose> entryPoses=new HashMap<>(); Map<View,DismissPose> dismissPoses=new HashMap<>();
 }
 static class Card {
  float translationX,translationY,scaleX=1,scaleY=1; int scroll=100; boolean hidden;
  StackedRecentsGeometry.Projection projection=new StackedRecentsGeometry.Projection();
 }
 Map<View,State> states=new HashMap<>(); Map<View,Card> cards=new HashMap<>();
 Method scrollForPage;
 VisibilityTest() throws Exception {scrollForPage=ViewGroup.class.getMethod("scrollForPage",int.class);}
 float stackStrength(View v,State s){return s.full;}
''' + method + baseline + '''
 public static void main(String[] args) throws Exception {
  VisibilityTest test=new VisibilityTest(); Random r=new Random(17);
  ViewGroup parent=new ViewGroup(); parent.width=1080;parent.height=2400;
  View child=new View(); child.parent=parent;
  State s=new State();test.states.put(parent,s); Card c=new Card();
  for(int i=0;i<100000;i++) {
   test.cards.clear(); if((i&1)==0)test.cards.put(child,c);
   s.horizontal=r.nextBoolean();s.entryStarted=r.nextBoolean();s.entryFinished=r.nextBoolean();
   s.entryProgress=r.nextFloat();s.handoffProgress=(i%3==0)?1:r.nextFloat();s.full=(i%4==0)?1:r.nextFloat();
   s.overScroll=r.nextFloat()*200-100;s.entryTargetScroll=r.nextInt(3000);
   parent.scrollX=r.nextInt(6000)-3000;parent.scrollY=r.nextInt(6000)-3000;
   child.px=r.nextInt(700);child.py=r.nextInt(900);child.tx=r.nextFloat()*100;child.ty=r.nextFloat()*100;
   c.translationX=child.tx;c.translationY=child.ty;c.hidden=r.nextBoolean();
   s.entryPoses.clear();s.dismissPoses.clear();
   if(i%3==0){EntryPose p=new EntryPose();p.targetCenter=r.nextInt(4000)-1000;p.targetAlpha=r.nextBoolean()?1:0;s.entryPoses.put(child,p);}
   if(i%5==0){s.dismissPoses.put(child,new DismissPose(-4,3));s.dismissProgress=r.nextFloat();}
   float buffer=r.nextFloat()*500;
   if(test.baseline(parent,child,buffer)!=test.visible(parent,child,buffer))throw new AssertionError("visibility changed: "+i);
  }
  test.cards.clear();s.entryFinished=false;s.entryStarted=true;EntryPose p=new EntryPose();p.targetAlpha=1;s.entryPoses.put(child,p);
  parent.calls=0;test.visible(parent,child,0);
  if(parent.calls!=0)throw new AssertionError("entry queried native page scroll");
  System.out.println("PASS: 100000 visibility comparisons; entry bypasses native page lookup; base hook guards verified");
 }
}
'''
jdk = Path('/opt/homebrew/opt/openjdk@21/bin')
with tempfile.TemporaryDirectory(prefix='flyme-visibility-', dir='/private/tmp') as temp:
    test = Path(temp) / 'VisibilityTest.java'
    test.write_text(java)
    subprocess.run([str(jdk/'javac'), '-d',temp,str(test),str(source/'StackedRecentsGeometry.java')],check=True)
    subprocess.run([str(jdk/'java'),'-cp',temp,'dev.rikumi.flymemod.VisibilityTest'],check=True)
