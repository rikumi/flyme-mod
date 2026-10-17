"""Compile production property guards and exercise native writes between callbacks."""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
jdk = Path('/opt/homebrew/opt/openjdk@21/bin')
with tempfile.TemporaryDirectory(prefix='flyme-shade-properties-', dir='/private/tmp') as temp:
    temp = Path(temp)
    view = temp / 'android/view/View.java'
    view.parent.mkdir(parents=True)
    view.write_text('''package android.view;
public class View {
 public float alpha=1, x=1, y=1; public int writes;
 public float getAlpha(){return alpha;} public void setAlpha(float v){alpha=v;writes++;}
 public float getScaleX(){return x;} public void setScaleX(float v){x=v;writes++;}
 public float getScaleY(){return y;} public void setScaleY(float v){y=v;writes++;}
}''')
    test = temp / 'ShadePropertiesTest.java'
    test.write_text('''package dev.rikumi.flymemod;
import android.view.View;
public class ShadePropertiesTest {
 public static void main(String[] args) {
  View v=new View();
  ShadeViewProperties.unitScale(null);
  for(int i=0;i<1000;i++) {
   float target=i/999f;
   ShadeViewProperties.alpha(v,target);
   int before=v.writes;
   ShadeViewProperties.alpha(v,target);
   ShadeViewProperties.unitScale(v);
   if(v.writes!=before) throw new AssertionError("unchanged properties written");
   // Native callbacks write a different value without changing our target.
   v.alpha=target+.1f; v.x=.8f;
   ShadeViewProperties.alpha(v,target);
   ShadeViewProperties.unitScale(v);
   if(v.alpha!=target || v.x!=1 || v.y!=1 || v.writes!=before+2)
    throw new AssertionError("late native write not corrected independently");
  }
  System.out.println("PASS: 1000 shade frames, duplicate suppression and same-frame native overwrite recovery");
 }
}''')
    subprocess.run([str(jdk / 'javac'), '-d', str(temp), str(view), str(test),
                    str(root / 'app/src/main/java/dev/rikumi/flymemod/ShadeViewProperties.java')], check=True)
    subprocess.run([str(jdk / 'java'), '-cp', str(temp), 'dev.rikumi.flymemod.ShadePropertiesTest'], check=True)
