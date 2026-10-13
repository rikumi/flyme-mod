package dev.rikumi.flymemod;

import java.util.ArrayDeque;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

public final class RunningPackagesSnapshotTest {
    static final class Queue implements Executor {
        final ArrayDeque<Runnable> tasks=new ArrayDeque<>();
        public void execute(Runnable task) { tasks.add(task); }
        void run() { tasks.remove().run(); }
    }
    static void check(boolean condition,String message) {
        if(!condition)throw new AssertionError(message);
    }
    public static void main(String[] args) {
        Queue worker=new Queue(), owner=new Queue();
        long[] time={0}; int[] updates={0}, queries={0};
        RunningPackagesSnapshot cache=new RunningPackagesSnapshot(worker,owner,()->time[0],()->updates[0]++);
        Supplier<Set<String>> empty=()->{ queries[0]++; return Set.of(); };
        check(cache.isRunning("music",empty),"unknown must retain media");
        for(int i=0;i<1000;i++)cache.isRunning("music",empty);
        check(queries[0]==0 && worker.tasks.size()==1,"UI reads must coalesce without doing IPC");
        worker.run();
        check(cache.isRunning("music",empty),"worker must not publish before owner callback");
        owner.run();
        check(!cache.isRunning("music",empty) && updates[0]==1,"fresh absent package can compact");
        time[0]=499; cache.isRunning("music",empty);
        check(worker.tasks.isEmpty(),"fresh snapshot must not poll");
        time[0]=500; cache.isRunning("music",empty); worker.run(); owner.run();
        check(updates[0]==1,"same process set must not request another layout");
        time[0]=1000;
        cache.isRunning("music",()->Set.of("music")); worker.run(); owner.run();
        check(cache.isRunning("music",empty) && updates[0]==2,"new process must restore media");
        cache.invalidate(); cache.isRunning("music",empty); worker.run();
        cache.invalidate(); owner.run();
        check(cache.isRunning("music",empty),"invalidated in-flight result must not replace current state");
        check(worker.tasks.size()==1,"discarded result must permit a new query");
        worker.run(); owner.run();
        check(!cache.isRunning("music",empty),"retry must publish fresh state");
        time[0]+=500;
        cache.isRunning("music",()->{throw new SecurityException("unavailable");}); worker.run(); owner.run();
        check(cache.isRunning("music",empty),"failure must preserve expanded media conservatively");
        check(worker.tasks.isEmpty(),"failure must be rate limited too");
        System.out.println("PASS: background IPC, coalescing, expiry, publication, invalidation, failure fallback");
    }
}
