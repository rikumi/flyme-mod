"""Run the production session reconciliation without any SystemUI request."""
from pathlib import Path
import subprocess
import tempfile
root = Path(__file__).resolve().parents[2]
source = (root / 'app/src/main/java/dev/rikumi/flymemod/HeyMelodyNoiseBridge.java').read_text()
start = source.index('    private void reconcile()')
brace = source.index('{', start)
end, depth = brace + 1, 1
while depth:
    depth += (source[end] == '{') - (source[end] == '}')
    end += 1
reconcile = source[start:end]
transaction = source.split('installer.hook(ams.getName(), "onTransact", chain -> {',1)[1].split('}, int.class, Parcel.class, Parcel.class, int.class);',1)[0]
for operation in ('initialize(', 'bind(', 'release(', 'refresh(', 'scheduleReconcile('):
    # Ignore explanatory comments; only inspect executable lines.
    assert operation not in '\n'.join(line for line in transaction.splitlines() if not line.strip().startswith('//'))
assert '"systemReady"' in source and 'registerAudioDeviceCallback' in source
assert 'lastRequest' not in source and 'expire' not in source
java = '''
import java.util.*;
public class SessionTest {
    static final long POLL_INTERVAL=3000;
    static class Handler {
        boolean scheduled;
        void removeCallbacks(Runnable r){scheduled=false;}
        void postDelayed(Runnable r,long delay){check(delay==3000);scheduled=true;}
    }
    Handler worker=new Handler();
    Runnable poll=this::reconcile;
    Map<String,Integer> snapshots=new HashMap<>();
    List<String> outputs=new ArrayList<>();
    boolean setting=true, bound;
    int refreshes, releases;
    boolean enabled(){return setting;}
    List<String> connectedOutputs(){return new ArrayList<>(outputs);}
    void release(){bound=false;snapshots.clear();releases++;}
    void refresh(String address){refreshes++;bound=true;snapshots.put(address,2);}
    METHOD
    static void check(boolean condition){if(!condition)throw new AssertionError();}
    void run(){
        reconcile();check(!bound&&!worker.scheduled);
        outputs.add("A");reconcile();check(bound&&snapshots.get("A")==2&&worker.scheduled);
        int released=releases;
        // No UI exists. The system timer continues maintaining the session.
        for(int i=0;i<50;i++)poll.run();
        check(bound&&releases==released&&refreshes==51);
        outputs.clear();outputs.add("B");reconcile();
        check(!snapshots.containsKey("A")&&snapshots.get("B")==2);
        setting=false;reconcile();check(!bound&&snapshots.isEmpty()&&!worker.scheduled);
        setting=true;reconcile();check(bound&&worker.scheduled);
        outputs.clear();reconcile();check(!bound&&snapshots.isEmpty()&&!worker.scheduled);
        System.out.println("PASS: boot/connection without UI, idle persistence, device change, disable and disconnect");
    }
    public static void main(String[] args){new SessionTest().run();}
}
'''.replace('METHOD',reconcile)
with tempfile.TemporaryDirectory(dir='/private/tmp',prefix='melody-session-') as tmp:
    path=Path(tmp)/'SessionTest.java'
    path.write_text(java)
    jdk='/opt/homebrew/opt/openjdk@21/bin/'
    subprocess.run([jdk+'javac',str(path)],check=True)
    subprocess.run([jdk+'java','-cp',tmp,'SessionTest'],check=True)
