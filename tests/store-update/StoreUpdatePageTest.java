package dev.rikumi.flymemod;

import io.reactivex.Observable;
import io.reactivex.schedulers.Schedulers;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Runs against the compiled production response factory and real RxJava 2. */
public final class StoreUpdatePageTest {
    public static final class ResultModel {
        public int code;
        public Object value;
    }

    public static final class ValueBlock {
        public List<Object> blocks;
        public boolean more;
        public boolean networkError;
    }

    private static final ClassLoader STORE = new ClassLoader(StoreUpdatePageTest.class.getClassLoader()) {
        @Override public Class<?> loadClass(String name) throws ClassNotFoundException {
            return switch (name) {
                case "com.meizu.mstore.data.net.requestitem.base.ResultModel" -> ResultModel.class;
                case "com.meizu.mstore.data.net.requestitem.base.ValueBlock" -> ValueBlock.class;
                default -> super.loadClass(name);
            };
        }
    };

    @SuppressWarnings("unchecked")
    private static Observable<ResultModel> recommendations() throws ReflectiveOperationException {
        return (Observable<ResultModel>) StoreLayoutHooks.emptyUpdateRecommendations(STORE);
    }

    public static void main(String[] args) throws Exception {
        List<String> updates = List.of("available update");
        Observable.zip(Observable.just(updates), Observable.empty(), (apps, ignored) -> apps)
                .test().assertNoValues().assertComplete().assertNoErrors();

        ResultModel response = recommendations().blockingSingle();
        ValueBlock value = (ValueBlock) response.value;
        if (response.code != 200 || value.blocks == null || !value.blocks.isEmpty()
                || value.more || value.networkError) throw new AssertionError("Invalid empty success response");

        // Match the store's response unwrap, block mapper, and update-list zip.
        Observable<List<Object>> rows = recommendations().map(result -> {
            if (result.code != 200 || result.value == null) throw new IllegalStateException("Rejected response");
            return ((ValueBlock) result.value).blocks;
        });
        for (List<String> installed : List.of(updates, List.<String>of())) {
            Observable.zip(Observable.just(installed).subscribeOn(Schedulers.single()), rows,
                    (apps, recommended) -> {
                        List<Object> combined = new ArrayList<>(apps);
                        combined.addAll(recommended);
                        return combined;
                    }).test().awaitDone(5, TimeUnit.SECONDS)
                    .assertValue(new ArrayList<>(installed)).assertComplete().assertNoErrors();
        }

        value.blocks.add("old cached row");
        ValueBlock next = (ValueBlock) recommendations().blockingSingle().value;
        if (next == value || !next.blocks.isEmpty()) throw new AssertionError("Response leaked across refreshes");
        System.out.println("PASS: empty-stream regression, populated/empty updates, response schema, refresh isolation");
    }
}
