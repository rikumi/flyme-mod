"""Exercise production bind/release against Android's retained dispatcher semantics."""
from pathlib import Path
import subprocess
import tempfile
root = Path(__file__).resolve().parents[2]
source = (root / 'app/src/main/java/dev/rikumi/flymemod/HeyMelodyNoiseBridge.java').read_text()
def method(name):
    start = source.index('    private void ' + name + '(')
    brace = source.index('{', start)
    end, depth = brace + 1, 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]
executor = source[source.index('    private final java.util.concurrent.Executor connectionExecutor'):]
executor = executor[:executor.index(';') + 1]
java = '''
import java.util.*;
import java.util.concurrent.Executor;
public class BindingTest {
    static final String PACKAGE="app", SERVICE="service";
    static class Intent { Intent(String action) {} Intent setComponent(ComponentName c) { return this; } }
    record ComponentName(String pkg, String name) {}
    static class Worker { void post(Runnable r) { r.run(); } }
    static class Context {
        static final int BIND_AUTO_CREATE=1;
        Executor retained, first;
        boolean success, fail;
        int binds, unbinds;
        PackageManager getPackageManager() { return new PackageManager(); }
        boolean bindService(Intent i, int flags, Executor executor, Object connection) {
            binds++;
            if (retained != null && retained != executor) throw new AssertionError("differing executor");
            if (first == null) first=executor;
            if (first != executor) throw new AssertionError("unstable executor");
            retained=executor;
            if (fail) throw new SecurityException("denied");
            return success;
        }
        void unbindService(Object connection) {
            unbinds++;
            if (retained == null) throw new IllegalArgumentException("not registered");
            retained=null;
        }
    }
    static class PackageManager { int getPackageUid(String pkg, int flags) { check(pkg.equals(PACKAGE)); return 10285; } }
    public class Optimizer {
        int thaws;
        public void unfreezeTemporarily(int uid) { check(uid==10285); thaws++; context.success=true; }
    }
    Optimizer cachedAppOptimizer = new Optimizer();
    java.lang.reflect.Method unfreezeUid;
    boolean enabled=true, audio=true, trusted=true;
    boolean enabled() { return enabled; }
    boolean audioOutput(String address) { return audio; }
    boolean trusted() { return trusted; }
    Context context=new Context(); Worker worker=new Worker();
    Object connection=new Object(), remote;
    boolean bound, bindingRegistered;
    Map<String,Integer> snapshots=new HashMap<>();
    void rejection(String s) {}
    EXECUTOR
    METHODS
    static void check(boolean condition) { if (!condition) throw new AssertionError(); }
    void run() throws Exception {
        unfreezeUid=Optimizer.class.getMethod("unfreezeTemporarily", int.class);
        for (int n=0;n<3;n++) {
            bind(); check(!bound && !bindingRegistered && context.retained==null);
        }
        context.fail=true;
        try { bind(); throw new AssertionError(); } catch (SecurityException expected) {}
        check(!bindingRegistered && context.retained==null);
        context.fail=false; context.success=true;
        bind(); check(bound && bindingRegistered);
        int attempts=context.binds; bind(); check(context.binds==attempts);
        snapshots.put("device",2); remote=new Object();
        release(); check(!bound && !bindingRegistered && remote==null && snapshots.isEmpty());
        int unbinds=context.unbinds; release(); check(context.unbinds==unbinds);
        bind(); check(bound); release();
        context.success=false;
        enabled=false; bindForOutput("earphone"); check(cachedAppOptimizer.thaws==0);
        enabled=true; audio=false; bindForOutput("earphone"); check(cachedAppOptimizer.thaws==0);
        audio=true; trusted=false; bindForOutput("earphone"); check(cachedAppOptimizer.thaws==0);
        trusted=true; bindForOutput("earphone"); check(bound && cachedAppOptimizer.thaws==1);
        bindForOutput("earphone"); check(cachedAppOptimizer.thaws==1);
        release();
        System.out.println("PASS: failed bind, exception, retry, repeated bind and release lifecycle");
    }
    public static void main(String[] args) throws Exception { new BindingTest().run(); }
}
'''.replace('EXECUTOR', executor).replace('METHODS', method('bind')+'\n'+method('release')+'\n'+method('bindForOutput'))
with tempfile.TemporaryDirectory(dir='/private/tmp', prefix='melody-binding-') as tmp:
    path = Path(tmp) / 'BindingTest.java'
    path.write_text(java)
    jdk = '/opt/homebrew/opt/openjdk@21/bin/'
    subprocess.run([jdk+'javac', str(path)], check=True)
    subprocess.run([jdk+'java', '-cp', tmp, 'BindingTest'], check=True)
