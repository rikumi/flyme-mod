package dev.rikumi.flymemod;

import java.util.ArrayDeque;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Exercise real production cache with a provider that does not return until released. */
public final class SystemIconPackCacheTest {
    static int checks;
    static void check(boolean value,String message) {
        checks++;
        if(!value) throw new AssertionError(message);
    }
    public static void main(String[] args)throws Exception {
        ExecutorService worker=Executors.newSingleThreadExecutor();
        ExecutorService ui=Executors.newSingleThreadExecutor();
        CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1);
        AtomicInteger calls=new AtomicInteger(), changes=new AtomicInteger();
        AtomicReference<String> source=new AtomicReference<>("old-pack");
        AtomicLong clock=new AtomicLong();
        SystemIconPackCache<String,String> cache=new SystemIconPackCache<>(worker,clock::get,key -> {
            int call=calls.incrementAndGet(); String result=source.get();
            if(call==1) {
                entered.countDown();
                try { release.await(); } catch(InterruptedException e) { throw new IllegalStateException(e); }
            }
            return new SystemIconPackCache.Result<>(result,2,30000);
        },changes::incrementAndGet,16);
        try {
            check(ui.submit(()->cache.get("telegram/NoxIcon")).get(1,TimeUnit.SECONDS)==null,"cold UI returns native immediately");
            check(entered.await(1,TimeUnit.SECONDS),"provider entered on worker");
            for(int i=0;i<100;i++)
                check(ui.submit(()->cache.get("telegram/NoxIcon")).get(1,TimeUnit.SECONDS)==null,"blocked provider never owns UI lock");
            check(calls.get()==1,"duplicate lookup coalesced");
            ui.submit(cache::invalidate).get(1,TimeUnit.SECONDS);
            source.set("new-pack"); release.countDown();
            worker.submit(()->{}).get(1,TimeUnit.SECONDS);
            check(cache.get("telegram/NoxIcon")==null,"old in-flight pack cannot publish after invalidation");
            worker.submit(()->{}).get(1,TimeUnit.SECONDS);
            check("new-pack".equals(cache.get("telegram/NoxIcon")),"new generation is usable");
            check(changes.get()>=2,"invalidation and completed request notify clients");
        } finally { release.countDown(); worker.shutdownNow(); ui.shutdownNow(); }

        ArrayDeque<Runnable> queue=new ArrayDeque<>(); AtomicInteger negativeCalls=new AtomicInteger();
        SystemIconPackCache<String,String> negative=new SystemIconPackCache<>(queue::add,clock::get,k -> {
            negativeCalls.incrementAndGet(); return new SystemIconPackCache.Result<>(null,1,1000);
        },()->{},8);
        negative.get("missing"); queue.remove().run();
        for(int i=0;i<10;i++) negative.get("missing");
        check(queue.isEmpty() && negativeCalls.get()==1,"negative cache avoids IPC on every frame");
        clock.set(1001); negative.get("missing"); check(queue.size()==1,"unavailable provider can retry after cooldown");
        queue.remove().run();
        for(int i=0;i<1000;i++) negative.get("key"+i);
        check(queue.size()==64,"pending requests bounded even during a provider outage");
        negative.invalidate();
        while(!queue.isEmpty()) queue.remove().run();
        check(negativeCalls.get()==2,"queued stale generations never contact provider");
        negative.get("missing"); check(queue.size()==1,"stale queue releases keys for later retry"); queue.remove().run();

        AtomicReference<String> current=new AtomicReference<>("first");
        SystemIconPackCache<String,String> bounded=new SystemIconPackCache<>(queue::add,clock::get,k ->
                new SystemIconPackCache.Result<>(current.get()+k,4,10),()->{},8);
        for(String key:new String[]{"a","b","c"}) { bounded.get(key); queue.remove().run(); }
        check(bounded.get("a")==null,"bitmap byte budget evicts oldest request"); queue.remove().run();
        String old=bounded.get("a"); current.set("changed"); clock.addAndGet(11);
        check(old.equals(bounded.get("a")),"periodic revalidation returns cached value without waiting");
        queue.remove().run(); check("changeda".equals(bounded.get("a")),"periodic result replaces old value");
        SystemIconPackCache<String,String> broken=new SystemIconPackCache<>(queue::add,clock::get,k->{throw new IllegalStateException();},()->{},8);
        broken.get("a"); queue.remove().run(); check(broken.get("a")==null && queue.isEmpty(),"failure falls back and is throttled");
        clock.addAndGet(1001); broken.get("a"); check(queue.size()==1,"failure is retryable");queue.remove().run();
        System.out.println("PASS: "+checks+" asynchronous icon cache checks");
    }
}
