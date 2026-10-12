"""Execute the production scheduler callbacks with deterministic host queues."""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
source = (root / "app/src/main/java/dev/rikumi/flymemod/ChargingInfoHooks.java").read_text()


def method(signature):
    start = source.index("{", source.index(signature))
    depth, end = 1, start + 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return signature + source[start:end]


java = r'''
package dev.rikumi.flymemod;
import java.util.*;
import java.lang.ref.WeakReference;
public class PollingTest {
    static class Queue {
        List<Runnable> work=new ArrayList<>(); long delay;
        void execute(Runnable r){work.add(r);} void post(Runnable r){work.add(r);}
        void postDelayed(Runnable r,long ms){delay=ms;work.add(r);}
        void removeCallbacks(Runnable r){work.removeIf(item->item==r);}
        void run(){work.remove(0).run();}
    }
    static class Bundle {
        static boolean legacy;
        String getString(String key){return key.equals("sample_micro") ? legacy ? null : "1 0 5000000 3000000 1 0" : "1 0 5000 3000 1 0";}
        String getString(String key,String fallback){return fallback;}
    }
    static class Resolver { int reads; boolean fail; Bundle call(Object uri,String m,Object a,Object b){reads++;if(fail)throw new IllegalStateException();return new Bundle();}}
    static class Context {Resolver resolver=new Resolver();Resolver getContentResolver(){return resolver;}}
    static class ModuleSettings {static Object URI=new Object();}
    static class SystemClock {static long now=100;static long elapsedRealtime(){return now;}}
    static class State {int renders;void render(){renders++;}}
    Queue main=new Queue(),worker=new Queue(); Context context=new Context();boolean needed;
    Map<Object,WeakReference<State>> states=new HashMap<>();
    ChargingInfo.Sample sample;long sampledAt;int generation;boolean polling,inFlight;
    String lastReadStatus;java.util.function.Consumer<String> trace=message->{};
    final Runnable tick=this::read;
    Context activeContext(){return needed?context:null;}
    void refresh(){reconcile();}
    RECONCILE
    READ
    static int checks;
    static void check(boolean c){checks++;if(!c)throw new AssertionError("check "+checks);}
    void run() {
        State state=new State();states.put(new Object(),new WeakReference<>(state));
        reconcile();check(main.work.isEmpty() && !polling);
        needed=true;reconcile();check(main.work.size()==1 && polling);
        main.run();check(inFlight && worker.work.size()==1 && context.resolver.reads==0);
        for(int i=0;i<100;i++)reconcile();check(main.work.isEmpty() && worker.work.size()==1);
        worker.run();check(context.resolver.reads==1 && sample==null);
        main.run();check(sample.format(1).equals("15W") && sampledAt==100);
        check(main.work.size()==1 && main.delay==5000 && !inFlight);
        Bundle.legacy=true;main.run();worker.run();main.run();check(sample.format(1).equals("15W"));Bundle.legacy=false;
        // A disconnected/detached/disabled last owner cancels all queued sampling.
        needed=false;reconcile();check(main.work.isEmpty() && sample==null && !polling);
        needed=true;reconcile();main.run();check(inFlight);
        needed=false;reconcile();needed=true;reconcile();check(main.work.isEmpty());
        worker.run();main.run();check(sample==null && main.work.size()==1 && main.delay==0);
        main.run();worker.run();main.run();check(sample!=null && main.delay==5000);
        // Failure replaces an earlier value with no data, not an old cached number.
        context.resolver.fail=true;main.run();worker.run();main.run();check(sample==null);
        main.run();needed=false;reconcile();worker.run();main.run();
        check(sample==null && main.work.isEmpty() && !inFlight && !polling);
        check(state.renders==6);
        System.out.println("PASS: "+checks+" production polling/cancellation/stale-result checks");
    }
    public static void main(String[] args){new PollingTest().run();}
}
'''.replace("RECONCILE", method("private void reconcile()" )).replace("READ", method("private void read()"))

with tempfile.TemporaryDirectory(prefix="flyme-charging-test-", dir="/private/tmp") as directory:
    path = Path(directory)
    (path / "PollingTest.java").write_text(java)
    production = root / "app/src/main/java/dev/rikumi/flymemod/ChargingInfo.java"
    formatter_test = root / "tests/charging-info/ChargingInfoTest.java"
    jdk = Path("/opt/homebrew/opt/openjdk@21/bin")
    subprocess.run([str(jdk/"javac"), "-d", directory, str(production), str(formatter_test), str(path/"PollingTest.java")], check=True)
    for test in ["ChargingInfoTest", "PollingTest"]:
        subprocess.run([str(jdk/"java"), "-cp", directory, "dev.rikumi.flymemod."+test], check=True)
