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
# Exercise the notification call site, not only the cache in isolation.
material = Path('app/src/main/java/dev/rikumi/flymemod/ColorOsMaterialHooks.java').read_text()
def block(marker):
 start = material.index('{', material.index(marker))
 end, depth = start + 1, 1
 while depth:
  depth += (material[end] == '{') - (material[end] == '}')
  end += 1
 return material[start:end]
corner_marker = 'if (configuredCorners[0] != radii[0]'
corner_start = material.index(corner_marker)
corner_condition = material[corner_start:material.index('{', corner_start)]
cache_marker = 'if (mediaGeometry == null && canvas.isHardwareAccelerated())'
assert cache_marker in material
harness = r"""
 static class Notification {
  final ContourRenderCache renderCache = new ContourRenderCache();
  final float[] configuredCorners = {Float.NaN,Float.NaN,Float.NaN,Float.NaN};
  long shaderGeneration; int alpha = 255;
  Object mediaGeometry;
  Shader shader = new Shader();
  static class Shader {void setFloatUniform(String name,float... values) {}}
  void corners(float[] radii) { CORNERS }
  void draw(Canvas canvas,Rect bounds,Paint paint) { DISPATCH }
  void detach() DETACH
 }
 static void notifications() {
  var canvas = new Canvas(); var bounds = new Rect(0,0,900,220); var paint = new Paint();
  var cards = new Notification[24];
  for(int i=0;i<cards.length;i++) cards[i]=new Notification();
  int before=Canvas.records;
  for(int frame=0;frame<1000;frame++) for(var card:cards) {
   card.corners(new float[]{30,30,30,30,15,15,15,15});
   card.alpha=frame%256; card.draw(canvas,bounds,paint);
  }
  check(Canvas.records==before+24);
  // Group roundness changes only the lower corners; size/radius stay the same.
  cards[0].corners(new float[]{30,30,30,30,0,0,0,0}); cards[0].draw(canvas,bounds,paint);
  check(Canvas.records==before+25);
  // Side swipe changes local width; vertical scrolling does not.
  bounds.right=740; cards[0].draw(canvas,bounds,paint); check(Canvas.records==before+26);
  cards[0].detach(); cards[0].draw(canvas,bounds,paint); check(Canvas.records==before+27);
 }
""".replace('CORNERS',corner_condition+block(corner_marker)).replace('DISPATCH',cache_marker+block(cache_marker)).replace('DETACH',block('@Override public void onViewDetachedFromWindow(View view) { renderCache.clear(); }'))
name='dev/rikumi/flymemod/CacheTest.java'
sources[name]=sources[name].replace('static int checks;',harness+'static int checks;').replace('System.out.println(', 'notifications();System.out.println(')
sources['android/graphics/Canvas.java']=sources['android/graphics/Canvas.java'].replace('public static int records;', 'public static int records;public boolean isHardwareAccelerated(){return true;}')

with tempfile.TemporaryDirectory(dir='/private/tmp') as directory:
 root=Path(directory)
 for name,content in sources.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content)
 (root/'dev/rikumi/flymemod/ContourRenderCache.java').write_text(Path('app/src/main/java/dev/rikumi/flymemod/ContourRenderCache.java').read_text())
 jdk=Path('/opt/homebrew/opt/openjdk@21/bin')
 subprocess.run([str(jdk/'javac'),'-d',str(root/'classes'),*map(str,root.rglob('*.java'))],check=True)
 subprocess.run([str(jdk/'java'),'-cp',str(root/'classes'),'dev.rikumi.flymemod.CacheTest'],check=True)
