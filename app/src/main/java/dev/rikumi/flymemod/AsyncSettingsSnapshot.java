package dev.rikumi.flymemod;

import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** No provider work or snapshot application while holding the scheduling lock. */
final class AsyncSettingsSnapshot<T extends AutoCloseable> {
    private final Executor worker, owner;
    private final Supplier<T> read;
    private final Consumer<T> apply;
    private final Consumer<Throwable> error;
    private final LongSupplier clock;
    private boolean pending, loaded;
    private long generation, retryAt;

    AsyncSettingsSnapshot(Executor worker, Executor owner, Supplier<T> read, Consumer<T> apply,
                          Consumer<Throwable> error, LongSupplier clock) {
        this.worker=worker; this.owner=owner; this.read=read; this.apply=apply;
        this.error=error; this.clock=clock;
    }
    void invalidate() {
        synchronized(this) { generation++; loaded=false; retryAt=0; }
        request();
    }
    void request() {
        long ticket;
        synchronized(this) {
            if(pending || loaded || clock.getAsLong()<retryAt) return;
            pending=true; ticket=generation;
        }
        worker.execute(()-> {
            T snapshot=null;
            Throwable failure=null;
            try { snapshot=read.get(); }
            catch(RuntimeException e) { failure=e; }
            T result=snapshot;
            Throwable problem=failure;
            owner.execute(()->complete(ticket,result,problem));
        });
    }
    private void complete(long ticket,T snapshot,Throwable failure) {
        boolean stale;
        synchronized(this) { stale=ticket!=generation; }
        boolean success=false;
        try {
            if(!stale && snapshot!=null) { apply.accept(snapshot); success=true; }
            if(!stale && failure!=null) error.accept(failure);
        } finally {
            if(snapshot!=null) try { snapshot.close(); } catch(Exception ignored) { }
            synchronized(this) {
                pending=false;
                loaded=success && ticket==generation;
                if(!loaded && ticket==generation) retryAt=clock.getAsLong()+1000;
                stale=ticket!=generation;
            }
            if(stale) request();
        }
    }
}
