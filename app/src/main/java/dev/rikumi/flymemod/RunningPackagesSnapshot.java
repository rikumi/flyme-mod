package dev.rikumi.flymemod;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

final class RunningPackagesSnapshot {
    private final Executor worker, owner;
    private final LongSupplier clock;
    private final Runnable changed;
    private Set<String> packages;
    private long checkedAt = Long.MIN_VALUE;
    private int generation;
    private boolean loading;

    RunningPackagesSnapshot(Executor worker, Executor owner, LongSupplier clock, Runnable changed) {
        this.worker=worker; this.owner=owner; this.clock=clock; this.changed=changed;
    }

    void invalidate() { generation++; checkedAt=Long.MIN_VALUE; }

    boolean isRunning(String name, Supplier<Set<String>> query) {
        long now=clock.getAsLong();
        if(!loading && (checkedAt==Long.MIN_VALUE || now-checkedAt>=500)) {
            loading=true;
            int request=generation;
            worker.execute(() -> {
                Set<String> result;
                try { result=query.get(); }
                catch(RuntimeException unavailable) { result=null; }
                Set<String> snapshot=result;
                owner.execute(() -> {
                    loading=false;
                    if(request!=generation) {
                        // A refresh arrived during the IPC. Let the owner request a fresh sample.
                        changed.run();
                        return;
                    }
                    checkedAt=clock.getAsLong();
                    boolean different=!Objects.equals(packages,snapshot);
                    packages=snapshot;
                    if(different)changed.run();
                });
            });
        }
        return packages==null || packages.contains(name);
    }
}
