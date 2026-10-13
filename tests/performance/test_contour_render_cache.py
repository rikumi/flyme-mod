"""Exercise production contour hardware-layer reuse across shade alpha/position changes."""
from pathlib import Path
import subprocess
import tempfile
sources={
 'android/graphics/Rect.java':'''package android.graphics;public class Rect {public int left,top,right,bottom;public Rect(){}public Rect(int l,int t,int r,int b){set(l,t,r,b);}public void set(int l,int t,int r,int b){left=l;top=t;right=r;bottom=b;}public void set(Rect r){set(r.left,r.top,r.right,r.bottom);}public void setEmpty(){set(0,0,0,0);}public int width(){return right-left;}public int height(){return bottom-top;}public boolean equals(Object o){return o instanceof Rect r&&left==r.left&&top==r.top&&right==r.right&&bottom==r.bottom;}}''',
 'android/graphics/Paint.java':'package android.graphics;public class Paint {public int alpha;public void setAlpha(int a){alpha=a;}}',
 'android/graphics/Canvas.java':'''package android.graphics;public class Canvas {public static int records;public RenderNode last;public void translate(float x,float y){}public void drawRect(Rect r,Paint p){if(p.alpha!=255)throw new AssertionError();records++;}public void drawRenderNode(RenderNode n){last=n;}}''',
 'android/graphics/RenderNode.java':'''package android.graphics;public class RenderNode {public float alpha;boolean recorded;public boolean layer;public RenderNode(String n){}public boolean hasDisplayList(){return recorded;}public void setPosition(int l,int t,int r,int b){}public void setUseCompositingLayer(boolean v,Paint p){layer=v;}public Canvas beginRecording(int w,int h){return new Canvas();}public void endRecording(){recorded=true;}public void setAlpha(float a){alpha=a;}public void discardDisplayList(){recorded=false;}}''',
 'dev/rikumi/flymemod/CacheTest.java':'''package dev.rikumi.flymemod;import android.graphics.*;public class CacheTest {static int checks;static void check(boolean b){checks++;if(!b)throw new AssertionError("check "+checks);}public static void main(String[]args){var cache=new ContourRenderCache();var canvas=new Canvas();var rect=new Rect(3,4,303,304);var paint=new Paint();for(int a=0;a<=255;a++){cache.draw(canvas,rect,paint,1,a);check(Canvas.records==1);check(canvas.last.layer);check(Math.abs(canvas.last.alpha-a/255f)<.001f);}cache.draw(canvas,rect,paint,2,255);check(Canvas.records==2);rect.right++;cache.draw(canvas,rect,paint,2,255);check(Canvas.records==3);cache.clear();check(!canvas.last.layer);cache.draw(canvas,rect,paint,2,255);check(Canvas.records==4);System.out.println("PASS: "+checks+" static layer reuse, alpha composition, style/size invalidation and detach checks");}}'''
}
with tempfile.TemporaryDirectory(dir='/private/tmp') as directory:
 root=Path(directory)
 for name,content in sources.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content)
 (root/'dev/rikumi/flymemod/ContourRenderCache.java').write_text(Path('app/src/main/java/dev/rikumi/flymemod/ContourRenderCache.java').read_text())
 jdk=Path('/opt/homebrew/opt/openjdk@21/bin')
 subprocess.run([str(jdk/'javac'),'-d',str(root/'classes'),*map(str,root.rglob('*.java'))],check=True)
 subprocess.run([str(jdk/'java'),'-cp',str(root/'classes'),'dev.rikumi.flymemod.CacheTest'],check=True)
