from pathlib import Path
import subprocess,tempfile
s=Path('app/src/main/java/dev/rikumi/flymemod/AddPanelHooks.java').read_text()
batch=s[s.index('    private final class Batch'):s.rfind('\n}')]
code='''import java.util.*;import java.lang.reflect.*;import java.util.function.*;
class BatchTest {
 static class SystemClock {static long now;static long uptimeMillis(){return now;}}
 static class View {boolean shown=true,attached=true;int y;View parent;Queue<Runnable> queue=new ArrayDeque<>();
 boolean isShown(){return shown;}boolean isAttachedToWindow(){return attached;}Object getParent(){return parent;}
 int getScrollY(){return y;}void postOnAnimation(Runnable r){queue.add(r);}void removeCallbacks(Runnable r){queue.remove(r);}void requestLayout(){} }
 static class ViewGroup extends View {Map<Object,View> tiles=new HashMap<>();List<View> children=new ArrayList<>();int layouts;
 void removeView(View v){children.remove(v);}void addView(View v){children.add(v);}public void layoutTiles(){layouts++;}public void header(){} }
 static class ScrollView extends ViewGroup {}
 static class Record {public String spec;Record(String s){spec=s;}}
 static class Pair {Object a,b;Pair(String s){a=new Record(s);b=new View();}public Object component1(){return a;}public Object component2(){return b;}}
 Map<ViewGroup,Batch>batches=new WeakHashMap<>();Set<View>touching=new HashSet<>();
 Field mapField,specField;Method first,second,layout,header;BiConsumer<String,Throwable>log=(m,e)->{throw new AssertionError(e);};
 BatchTest()throws Exception{mapField=ViewGroup.class.getDeclaredField("tiles");specField=Record.class.getField("spec");first=Pair.class.getMethod("component1");second=Pair.class.getMethod("component2");layout=ViewGroup.class.getMethod("layoutTiles");header=ViewGroup.class.getMethod("header");}
 void cancel(ViewGroup v){Batch b=batches.remove(v);if(b!=null)v.removeCallbacks(b);}
'''+batch+'''
 static void check(boolean b){if(!b)throw new AssertionError();}
 static void frame(View v){SystemClock.now+=120;v.queue.remove().run();}
 public static void main(String[]args)throws Exception{
 BatchTest h=new BatchTest();ViewGroup v=new ViewGroup();ScrollView scroll=new ScrollView();v.parent=scroll;
 List<Pair> pairs=new ArrayList<>();for(int i=0;i<9;i++)pairs.add(new Pair("tile"+i));
 Batch b=h.new Batch(v,List.of(),pairs);h.batches.put(v,b);v.postOnAnimation(b);
 frame(v);check(v.children.isEmpty());h.touching.add(scroll);frame(v);check(v.children.isEmpty());h.touching.remove(scroll);
 scroll.y=90;frame(v);check(v.children.isEmpty());
 while(h.batches.containsKey(v)){int before=v.children.size();frame(v);check(v.children.size()-before<=2);}
 check(v.children.size()==9&&v.tiles.size()==9);
 Batch remove=h.new Batch(v,List.of("tile0","tile1","tile2"),List.of());h.batches.put(v,remove);v.postOnAnimation(remove);
 frame(v);frame(v);check(v.children.size()>=7);h.cancel(v);check(v.queue.isEmpty());
 Batch hidden=h.new Batch(v,List.of(),pairs);h.batches.put(v,hidden);v.postOnAnimation(hidden);v.shown=false;frame(v);check(!h.batches.containsKey(v)&&v.queue.isEmpty());
 System.out.println("PASS: touch/fling pause, bounded batches, completion, cancellation and hidden panel");
 }
}
'''
with tempfile.TemporaryDirectory(dir='/private/tmp') as t:
 p=Path(t)/'BatchTest.java';p.write_text(code);jdk='/opt/homebrew/opt/openjdk@21/bin/'
 subprocess.run([jdk+'javac',str(p)],check=True);subprocess.run([jdk+'java','-cp',t,'BatchTest'],check=True)
