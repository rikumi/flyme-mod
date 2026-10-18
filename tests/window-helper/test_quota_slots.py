"""Execute production mixed-mode quota and native handle slot selection."""
from pathlib import Path
import subprocess,tempfile
base=Path("app/src/main/java/dev/rikumi/flymemod")
quota=(base/"SmallWindowLimitHooks.java").read_text()
placement=quota[quota.index("    static int nextPinTop("):quota.index("    private void collapseOtherExpanded(")]
quota=quota[quota.index("    static boolean floatingMode("):quota.rfind("\n}")]
slots=(base/"WeltWindowHandles.java").read_text()
slots=slots[slots.index("    static boolean isEdgeHidden("):slots.index("    private static final class Entry")]
code="""import java.util.*;import java.util.function.*;
public class QuotaSlotsTest {
static class Rect {int left,top,right,bottom;Rect(int l,int t,int r,int b){left=l;top=t;right=r;bottom=b;}}
record Task(int mode,boolean home) {}
static int checks;static void check(boolean b){checks++;if(!b)throw new AssertionError("check "+checks);}
"""+placement+quota+slots+"""
public static void main(String[] args){
 check(nextPinTop(500,300,100,1200,24)==524);
 check(nextPinTop(1000,300,100,1200,24)==100);
 check(nextPinTop(876,300,100,1200,24)==900);
 // Pin mode does not change on edge hiding: test the same native offscreen predicate.
 check(isEdgeHidden(-336,0,1200));check(isEdgeHidden(1200,1536,1200));
 check(!isEdgeHidden(-335,1,1200));check(!isEdgeHidden(1199,1535,1200));
 check(!isEdgeHidden(800,1136,1200));
 for(int limit=1;limit<=6;limit++){
  Object incoming=new Task(1,false);
  List<Object> tasks=new ArrayList<>();
  for(int mode:new int[]{12,11,12,1035,268,12})tasks.add(new Task(mode,false));
  tasks.add(new Task(1,true));tasks.add(new Task(12,false));
  for(int i=0;i<6;i++)check(retain(tasks,incoming,tasks.get(i),limit,
   t->((Task)t).mode(),t->((Task)t).home())==(i+2<=limit));
  check(!retain(tasks,incoming,tasks.get(7),limit,t->((Task)t).mode(),t->((Task)t).home()));
  check(retain(tasks,incoming,incoming,limit,t->((Task)t).mode(),t->((Task)t).home()));
  // Converting an existing window must not consume a second quota slot.
  incoming=tasks.get(0);check(retain(tasks,incoming,incoming,limit,t->((Task)t).mode(),t->((Task)t).home()));
 }
 for(boolean left:new boolean[]{false,true})for(int preferred:new int[]{0,289,2200}){
  List<Rect> occupied=new ArrayList<>();
  occupied.add(new Rect(left?-100:1201,289,left?0:1300,548));
  for(int i=0;i<5;i++){
   int y=findSlot(preferred,259,2670,24,left,occupied);check(y>=24&&y+259<=2670-24);
   for(Rect r:occupied)check(y>=r.bottom+24 || y+259+24<=r.top);
   occupied.add(new Rect(left?-100:1201,y,left?0:1300,y+259));
  }
 }
 List<Rect> right=List.of(new Rect(1201,100,1300,359));
 check(findSlot(100,259,2670,24,true,right)==100);
 System.out.println("PASS: "+checks+" mixed-window quota, home boundary and six-handle placement checks");
}}
"""
with tempfile.TemporaryDirectory(dir="/private/tmp") as tmp:
 p=Path(tmp)/"QuotaSlotsTest.java";p.write_text(code)
 jdk="/opt/homebrew/opt/openjdk@21/bin/"
 subprocess.run([jdk+"javac",str(p)],check=True)
 subprocess.run([jdk+"java","-cp",tmp,"QuotaSlotsTest"],check=True)
