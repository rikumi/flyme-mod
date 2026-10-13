"""Exercise one visible host at the actual notification/control-center vertical endpoints."""
from pathlib import Path
import subprocess
import tempfile
sources={
 'android/content/res/Resources.java': '''package android.content.res;public class Resources {public int getIdentifier(String n,String t,String p){return 1;}}''',
 'android/view/View.java': '''package android.view;public class View {
 public static final int VISIBLE=0,INVISIBLE=4;public float x,y,alpha=1;public int visibility,width=20,height=20,screenX,screenY;public View child;
 public float getTranslationX(){return x;}public float getTranslationY(){return y;}
 public void setTranslationX(float v){x=v;}public void setTranslationY(float v){y=v;}
 public float getAlpha(){return alpha;}public void setAlpha(float v){alpha=v;}
 public int getVisibility(){return visibility;}public void setVisibility(int v){visibility=v;}
 public int getWidth(){return width;}public int getHeight(){return height;}
 public android.content.res.Resources getResources(){return new android.content.res.Resources();}
 public View findViewById(int id){return child;}public void getLocationOnScreen(int[] p){p[0]=screenX;p[1]=screenY;}
 }''',
 'dev/rikumi/flymemod/StatusBarTest.java':'''package dev.rikumi.flymemod;import android.view.View;
 public class StatusBarTest {static int checks;static void check(boolean v){checks++;if(!v)throw new AssertionError("check "+checks);}
 static void near(float a,float b){check(Math.abs(a-b)<.001f);}
 public static void main(String[]args) {
 for(float direction:new float[]{1,-1}) {
  View center=new View(),notification=new View();center.child=new View();notification.child=new View();
  center.x=7;center.y=5;center.child.screenX=900;notification.child.screenX=950;
  center.child.screenY=220;notification.child.screenY=40;
  var pose=new SeparateShadeStatusBar(center,notification);
  for(int i=0;i<=10;i++) {float p=direction>0?i/10f:1-i/10f;
   // Native alpha/visibility updates cannot bring back the horizontal copy.
   notification.setAlpha(1);notification.setVisibility(View.VISIBLE);pose.apply(p);
   near(center.x,7);near(center.y,5-180*(1-p));
   check(notification.getAlpha()==0&&notification.getVisibility()==View.INVISIBLE);
   check(center.getAlpha()==1&&center.getVisibility()==View.VISIBLE);
  }
  pose.apply(-.5f);near(center.y,-175);pose.apply(1.5f);near(center.y,5);
  pose.restore();near(center.y,5);check(notification.alpha==1&&notification.visibility==View.VISIBLE);
 }
 View c=new View(),n=new View();var pose=new SeparateShadeStatusBar(c,n);pose.apply(.5f);c.y=42;pose.restore();near(c.y,42);
 System.out.println("PASS: "+checks+" vertical endpoint, single visible host, native rewrite and restoration checks");
 }}''',
}
with tempfile.TemporaryDirectory(dir='/private/tmp') as directory:
 root=Path(directory)
 for name,content in sources.items():
  path=root/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(content)
 (root/'dev/rikumi/flymemod/SeparateShadeStatusBar.java').write_text(Path('app/src/main/java/dev/rikumi/flymemod/SeparateShadeStatusBar.java').read_text())
 jdk=Path('/opt/homebrew/opt/openjdk@21/bin')
 subprocess.run([str(jdk/'javac'),'-d',str(root/'classes'),*map(str,root.rglob('*.java'))],check=True)
 subprocess.run([str(jdk/'java'),'-cp',str(root/'classes'),'dev.rikumi.flymemod.StatusBarTest'],check=True)
