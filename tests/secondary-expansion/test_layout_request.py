"""Exercise production alignment with View.layout's clearing of in-layout requests."""
from pathlib import Path
import subprocess, tempfile
source=Path('app/src/main/java/dev/rikumi/flymemod/SecondaryExpansionHooks.java').read_text()
start=source.index('    private void alignMini(')
end=source.index('    private static void restoreGeometry(',start)
methods=source[start:end]
code='''import java.util.*;
class LayoutRequestTest {
 static class View {
  int width=1200,left=85,right=221,top=82,bottom,childWidth=894,childTop=82,requests;
  boolean requested; Queue<Runnable> queue=new ArrayDeque<>();
  int getWidth(){return width;} int getPaddingLeft(){return left;}
  int getPaddingRight(){return right;} int getPaddingTop(){return top;}
  View getRootView(){return this;}
  void setPadding(int l,int t,int r,int b){left=l;top=t;right=r;bottom=b;requestLayout();}
  void requestLayout(){requested=true;requests++;}
  void post(Runnable r){queue.add(r);}
  void finishLayout(){requested=false;} // View.layout clears FORCE_LAYOUT after onLayout.
  void traverse(){if(requested){childWidth=width-left-right;childTop=top;requested=false;}}
 }
 static class ViewGroup extends View {}
 static class Panels {
  View mini=new View(),full=new View(),wrapper=new View(),sliders=new View();
  int bottom; boolean active=true,geometryDirty,layoutPosted;
 }
 static class Field {
  int value;Object object;Field(int v){value=v;}Field(Object o){object=o;}
  Object get(Object o){return object;}int getInt(Object o){return value;}
 }
 static class Method {Object object;Method(Object o){object=o;} Object invoke(Object o,int i){return object;}}
 ViewGroup first=new ViewGroup();
 Field tileLayout=new Field(first),cellWidth=new Field(228),gap=new Field(39),alignOffset=new Field(0);
 Method pageAt=new Method(first);
 static int coordinate(View v,View root,boolean vertical){return 0;}
 static void restoreGeometry(Panels s){}
 static void alignSpacers(Panels s,View v,int width){}
'''+methods+'''
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args)throws Exception {
  LayoutRequestTest h=new LayoutRequestTest();Panels s=new Panels();h.first.top=26;
  h.alignMini(s);check(s.mini.left==85&&s.mini.right==86&&s.mini.top==26);
  s.mini.finishLayout();s.mini.traverse();check(s.mini.childWidth==894);
  // A second alignment before delivery must not enqueue another traversal.
  h.first.top=27;h.alignMini(s);s.mini.finishLayout();check(s.mini.queue.size()==1);
  s.mini.queue.remove().run();s.mini.traverse();
  check(s.mini.childWidth==1029&&s.mini.childTop==27&&!s.layoutPosted);
  h.alignMini(s);check(s.mini.queue.isEmpty());
  // Rotation / return changes geometry again and must schedule a new traversal.
  s.mini.width=1800;h.alignMini(s);s.mini.finishLayout();
  check(s.mini.queue.size()==1);s.mini.queue.remove().run();s.mini.traverse();
  check(s.mini.childWidth==1029&&s.mini.right==686);
  h.first.width=0;h.alignMini(s);check(s.geometryDirty&&s.mini.queue.isEmpty());
  System.out.println("PASS: stale layout recovery, coalescing, stable layout, width change, unready grid");
 }
}
'''
with tempfile.TemporaryDirectory(dir='/private/tmp') as tmp:
 p=Path(tmp)/'LayoutRequestTest.java';p.write_text(code)
 jdk='/opt/homebrew/opt/openjdk@21/bin/'
 subprocess.run([jdk+'javac',str(p)],check=True)
 subprocess.run([jdk+'java','-cp',tmp,'LayoutRequestTest'],check=True)
