package dev.rikumi.flymemod;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;

final class SystemIconPackCache<K,V> {
    record Result<V>(V value,int bytes,long lifetime) {}
    interface Loader<K,V> { Result<V> load(K key); }
    private record Entry<V>(V value,int bytes,long expires) {}
    private final Map<K,Entry<V>> entries=new LinkedHashMap<>(16,.75f,true);
    private final Set<K> pending=new HashSet<>();
    private final Executor worker;
    private final LongSupplier clock;
    private final Loader<K,V> loader;
    private final Runnable changed;
    private final int maxBytes;
    private int bytes;
    private long generation;

    SystemIconPackCache(Executor worker,LongSupplier clock,Loader<K,V> loader,Runnable changed,int maxBytes) {
        this.worker=worker; this.clock=clock; this.loader=loader; this.changed=changed; this.maxBytes=maxBytes;
    }
    V get(K key) {
        V value;
        long ticket;
        synchronized(this) {
            Entry<V> entry=entries.get(key);
            value=entry==null?null:entry.value;
            if((entry!=null && entry.expires>clock.getAsLong()) || pending.contains(key) || pending.size()>=64)
                return value;
            pending.add(key);
            ticket=generation;
        }
        try { worker.execute(()->load(key,ticket)); }
        catch(RuntimeException unavailable) { synchronized(this) { pending.remove(key); } }
        return value;
    }
    void invalidate() {
        synchronized(this) { generation++; entries.clear(); bytes=0; }
        changed.run();
    }
    private void load(K key,long ticket) {
        boolean stale;
        synchronized(this) {
            stale=ticket!=generation;
            if(stale) pending.remove(key);
        }
        if(stale) { changed.run(); return; }
        Result<V> result;
        try { result=loader.load(key); }
        catch(RuntimeException | LinkageError unavailable) { result=new Result<>(null,1,1000); }
        boolean notify;
        synchronized(this) {
            pending.remove(key);

            if(ticket!=generation) { notify=true; }
            else {
                Entry<V> old=entries.remove(key);
                if(old!=null) bytes-=old.bytes;
                // An oversized image must not cause a decode attempt on every subsequent draw.
                if(result.bytes>maxBytes) result=new Result<>(null,1,30000);
                int weight=Math.max(1,result.bytes);
                if(weight<=maxBytes) {
                    entries.put(key,new Entry<>(result.value,weight,clock.getAsLong()+result.lifetime));
                    bytes+=weight;
                    while(bytes>maxBytes || entries.size()>512) {
                        K eldest=entries.keySet().iterator().next();
                        bytes-=entries.remove(eldest).bytes;
                    }
                }
                notify=!Objects.equals(old==null?null:old.value,result.value) && weight<=maxBytes;
            }
        }
        if(notify) changed.run();
    }
}
