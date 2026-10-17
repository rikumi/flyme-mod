"""Check production gesture routing with recorded Android touch recipients."""
from pathlib import Path
import subprocess
import tempfile
root=Path(__file__).resolve().parents[2]
stubs={
'android/graphics/Matrix.java': 'package android.graphics; public class Matrix { public float dx,dy; }',
'android/graphics/Rect.java': 'package android.graphics; public class Rect {}',
'android/view/MotionEvent.java': '''package android.view;
public class MotionEvent {
 public static final int ACTION_DOWN=0,ACTION_UP=1,ACTION_MOVE=2,ACTION_CANCEL=3;
 int action; float x,y;
 public MotionEvent(int a,float x,float y){action=a;this.x=x;this.y=y;}
 public static MotionEvent obtain(MotionEvent e){return new MotionEvent(e.action,e.x,e.y);}
 public int getActionMasked(){return action;} public void setAction(int a){action=a;}
 public float getX(){return x;} public float getY(){return y;} public int getPointerCount(){return 1;}
 public void transform(android.graphics.Matrix m){x+=m.dx;y+=m.dy;} public void recycle(){}
}''',
'android/view/View.java': '''package android.view;
public class View {
 public java.util.List<MotionEvent> events=new java.util.ArrayList<>(); public int clicks;
 public float x,y;
 public boolean dispatchTouchEvent(MotionEvent e){events.add(MotionEvent.obtain(e));return true;}
 public Object getContext(){return null;}
 public void transformMatrixToGlobal(android.graphics.Matrix m){m.dx+=x;m.dy+=y;}
 public void transformMatrixToLocal(android.graphics.Matrix m){m.dx-=x;m.dy-=y;}
}''',
'android/view/ViewGroup.java': '''package android.view; public class ViewGroup extends View {
 public boolean disallow; public void requestDisallowInterceptTouchEvent(boolean v){disallow=v;}
}''',
'android/view/ViewConfiguration.java': '''package android.view; public class ViewConfiguration {
 public static ViewConfiguration get(Object c){return new ViewConfiguration();} public int getScaledTouchSlop(){return 8;}
}''',
'android/view/TouchDelegate.java': '''package android.view; public class TouchDelegate {
 View view; boolean down;
 public TouchDelegate(android.graphics.Rect r,View v){view=v;}
 public boolean onTouchEvent(MotionEvent e){view.dispatchTouchEvent(e);
  if(e.getActionMasked()==0)down=true;
  if(e.getActionMasked()==3)down=false;
  if(e.getActionMasked()==1&&down){view.clicks++;down=false;}return true;
 }
}'''}
test='''package dev.rikumi.flymemod;
import android.view.*; import android.graphics.*;
public class TouchTest {
 static void check(boolean ok){if(!ok)throw new AssertionError();}
 static MotionEvent e(int a,float y){return new MotionEvent(a,12,y);}
 public static void main(String[] args){
  ViewGroup parent=new ViewGroup();parent.y=100;
  View slider=new View();slider.y=80;View icon=new View();
  EarphoneNoiseTouchGesture tap=new EarphoneNoiseTouchGesture(parent,icon,slider,new Rect(),e(0,30));
  tap.dispatch(e(0,30),true);check(parent.disallow);
  tap.dispatch(e(1,31),true);check(icon.clicks==1&&!parent.disallow);
  check(slider.events.get(0).getActionMasked()==0&&slider.events.get(0).getY()==50);
  for(int direction:new int[]{-1,1}){
   icon.events.clear();slider.events.clear();
   EarphoneNoiseTouchGesture drag=new EarphoneNoiseTouchGesture(parent,icon,slider,new Rect(),e(0,30));
   drag.dispatch(e(0,30),true);drag.dispatch(e(2,30+direction*20),true);
   check(parent.disallow);drag.dispatch(e(1,30+direction*40),true);
   check(icon.clicks==1&&!parent.disallow);
   check(icon.events.size()==2&&icon.events.get(1).getActionMasked()==3);
   check(slider.events.size()==3&&slider.events.get(1).getActionMasked()==2&&slider.events.get(2).getActionMasked()==1);
  }
  slider.events.clear();
  EarphoneNoiseTouchGesture cancel=new EarphoneNoiseTouchGesture(parent,icon,slider,new Rect(),e(0,30));
  cancel.dispatch(e(0,30),true);cancel.dispatch(e(2,31),false);cancel.dispatch(e(1,31),true);
  check(icon.clicks==1&&!parent.disallow&&slider.events.size()==2&&slider.events.get(1).getActionMasked()==3);
  System.out.println("PASS: tap, upward/downward drag, coordinates, shade interception and disabled cancellation");
 }
}'''
with tempfile.TemporaryDirectory(dir='/private/tmp',prefix='noise-touch-') as tmp:
 files=[]
 for name,text in stubs.items():
  p=Path(tmp)/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text);files.append(str(p))
 p=Path(tmp)/'TouchTest.java';p.write_text(test);files.append(str(p))
 files.append(str(root/'app/src/main/java/dev/rikumi/flymemod/EarphoneNoiseTouchGesture.java'))
 jdk='/opt/homebrew/opt/openjdk@21/bin/'
 subprocess.run([jdk+'javac','-d',tmp,*files],check=True)
 subprocess.run([jdk+'java','-cp',tmp,'dev.rikumi.flymemod.TouchTest'],check=True)
