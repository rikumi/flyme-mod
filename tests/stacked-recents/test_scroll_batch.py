"""Exercise the actual endpoint batching block, including a failing native page snap."""
from pathlib import Path
import subprocess,tempfile
root=Path(__file__).resolve().parents[2]
s=(root/'app/src/main/java/dev/rikumi/flymemod/StackedRecentsHooks.java').read_text()
a=s.index('                state.batchingEntryScroll = true;')
b=s.index('                state.entryPoses.clear();',a)
block=s[a:b]
assert 'if (state != null && state.batchingEntryScroll && enabled.getAsBoolean()) return null;' in s
assert 'if (state.entryProgress != 1f || state.entryFinished) dispatchScroll.invoke(current);' in s
assert 'finally { dispatchScroll.invoke(current); }' in s
assert 'if (state.blurProgress == progress && state.handoffProgress == progress) return;' in s
java='''import java.util.function.BiConsumer;
public class BatchTest {
 static class State {boolean batchingEntryScroll; int entryPage=1;}
 static State state=new State(); static int notifications;
 static boolean fail;
 static void dispatch(){if(!state.batchingEntryScroll)notifications++;}
 static class Snap {
  void invoke(Object owner,int page)throws ReflectiveOperationException {
   dispatch();dispatch();
   if(fail)throw new ReflectiveOperationException("native snap failed");
  }
 }
 static void test(boolean failure) {
  fail=failure;notifications=0;Object current=new Object();Snap snapImmediately=new Snap();
  BiConsumer<String,Throwable> log=(message,error)->{};
'''+block+'''
  if(state.batchingEntryScroll || notifications!=0)throw new AssertionError("intermediate callback leaked");
  dispatch();
  if(notifications!=1)throw new AssertionError("final pose was not published");
  dispatch();
  if(notifications!=2)throw new AssertionError("later native scroll suppressed");
 }
 public static void main(String[]args){test(false);test(true);System.out.println("PASS: native snap batching, exception restoration, final flush and later native scroll");}
}'''
jdk=Path('/opt/homebrew/opt/openjdk@21/bin')
with tempfile.TemporaryDirectory(dir='/private/tmp',prefix='flyme-scroll-batch-') as d:
 p=Path(d)/'BatchTest.java';p.write_text(java)
 subprocess.run([str(jdk/'javac'),'-d',d,str(p)],check=True)
 subprocess.run([str(jdk/'java'),'-cp',d,'BatchTest'],check=True)
