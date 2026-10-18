package dev.rikumi.flymemod;

import io.reactivex.Observable;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** DetailPresenter must receive onNext even when recommendation rows are hidden. */
public final class StoreDetailPageTest {
    public static final class ResultModel { public int code; public Object value; }
    public static final class RecommendValue { public List<Object> blocks; public String[] contents; }
    private static final ClassLoader STORE = new ClassLoader(StoreDetailPageTest.class.getClassLoader()) {
        @Override public Class<?> loadClass(String name) throws ClassNotFoundException {
            return switch (name) {
                case "com.meizu.mstore.data.net.requestitem.base.ResultModel" -> ResultModel.class;
                case "com.meizu.mstore.data.net.requestitem.detail.RecommendValue" -> RecommendValue.class;
                default -> super.loadClass(name);
            };
        }
    };
    @SuppressWarnings("unchecked")
    static Observable<ResultModel> response() throws Exception {
        return (Observable<ResultModel>) StoreLayoutHooks.emptyDetailRecommendations(STORE);
    }
    public static void main(String[] args) throws Exception {
        AtomicInteger updates = new AtomicInteger();
        Observable.just("details").flatMap(detail -> Observable.empty())
                .doOnNext(value -> updates.incrementAndGet()).test().assertNoValues().assertComplete();
        if (updates.get() != 0) throw new AssertionError();
        ResultModel first = response().blockingSingle();
        RecommendValue value = (RecommendValue) first.value;
        if (first.code != 200 || value.blocks == null || !value.blocks.isEmpty()) throw new AssertionError();
        // Same flatMap -> response unwrap -> recommendation mapper -> UI consumer order.
        Observable.just("details").flatMap(detail -> response())
                .map(result -> ((RecommendValue) result.value).blocks)
                .doOnNext(rows -> updates.incrementAndGet()).test()
                .assertValue(List.of()).assertComplete().assertNoErrors();
        if (updates.get() != 1) throw new AssertionError("Install/pager callback skipped");
        value.blocks.add("cached item");
        RecommendValue next = (RecommendValue) response().blockingSingle().value;
        if (value == next || !next.blocks.isEmpty()) throw new AssertionError("Shared response state");
        System.out.println("PASS: detail completion, empty recommendations, install/pager callback, fresh response");
    }
}
