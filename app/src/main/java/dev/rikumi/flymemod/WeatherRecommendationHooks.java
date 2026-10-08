package dev.rikumi.flymemod;

import android.content.Context;
import android.app.Application;
import android.database.ContentObserver;
import android.database.Cursor;
import android.os.Handler;
import android.os.Looper;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/** Weather 54.3.60 / 52.0.13: independent news and advertisement controls. */
final class WeatherRecommendationHooks {
    private volatile boolean enabled;
    private volatile boolean blockAdvertisements;
    private boolean current;
    private Class<?> nativeAdModel;
    private ContentObserver observer;
    private Context observerContext;
    private boolean readErrorLogged;
    private final BiConsumer<String, Throwable> log;

    WeatherRecommendationHooks(BiConsumer<String, Throwable> log) { this.log = log; }

    void install(ClassLoader loader, SignalHooks.Installer installer) throws ReflectiveOperationException {
        installSettings(installer);
        Class<?> feature;
        Class<?> config;
        try {
            feature = Class.forName("com.hy.weather.mz.feature.WeatherFeature", false, loader);
            config = Class.forName("com.hy.weather.mz.feature.WeatherFeatureManager", false, loader);
            current = true;
        } catch (ClassNotFoundException e) {
            feature = Class.forName("g4.b", false, loader);
            config = Class.forName("g4.c", false, loader);
        }
        Object newsLite = feature.getField("NEWS_LITE").get(null);
        Object newsSdk = feature.getField("NEWS_SDK").get(null);
        Object forecastNews = feature.getField("HOME_FORECAST").get(null);
        Object advertisements = feature.getField("AD").get(null);
        Object detailAds = optionalFeature(feature, "DETAIL_AD");
        Object detailNews = optionalFeature(feature, "DETAIL_NEWS");
        Object splash = feature.getField("SHOW_SPLASH").get(null);
        Method gate = null;
        for (Method method : config.getDeclaredMethods()) {
            if (method.getReturnType() == boolean.class && method.getParameterCount() == 1
                    && method.getParameterTypes()[0] == feature) {
                if (gate != null) throw new NoSuchMethodException("Ambiguous weather feature gate");
                gate = method;
            }
        }
        if (gate == null) throw new NoSuchMethodException("Weather news feature gate");
        installer.hook(config.getName(), gate.getName(), chain -> {
            Object requested = chain.getArg(0);
            if (enabled && (requested == newsLite || requested == newsSdk || requested == forecastNews || (detailNews != null && requested == detailNews))) return false;
            if (blockAdvertisements && (requested == advertisements || requested == splash || (detailAds != null && requested == detailAds))) return false;
            return chain.proceed();
        }, feature);
        try { installAdvertisements(loader, installer); }
        catch (ReflectiveOperationException e) { log.accept("Cannot resolve weather advertisement hooks", e); }
        try { installContent(loader, installer); }
        catch (ReflectiveOperationException e) { log.accept("Cannot resolve weather content hooks", e); }
    }

    private void installSettings(SignalHooks.Installer installer) {
        // Read before providers/SDKs can initialize or restore cached page data.
        installer.hook("android.app.Application", "attach", chain -> {
            Object result = chain.proceed();
            Application application = (Application) chain.getThisObject();
            if ("com.hy.weather.mz.WeatherApplication".equals(application.getClass().getName())) read(application);
            return result;
        }, Context.class);
        installer.hook("com.hy.weather.mz.WeatherApplication", "onCreate", chain -> {
            Context context = (Context) chain.getThisObject();
            // Load before the application's news SDK and home-page initialization.
            read(context);
            if (observer == null) {
                observerContext = context.getApplicationContext();
                observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
                    @Override public void onChange(boolean selfChange) { read(context); }
                };
                observerContext.getContentResolver().registerContentObserver(ModuleSettings.URI, false, observer);
            }
            return chain.proceed();
        });
    }

    private void installAdvertisements(ClassLoader loader, SignalHooks.Installer installer)
            throws ReflectiveOperationException {
        // Native splash, detail-page banners and WebView-requested ad slots all use this callback.
        Class<?> callback = Class.forName(current ? "z1.b" : "d3.b", false, loader);
        Method noAd = callback.getMethod("onNoAd", long.class);
        installer.hook("com.hy.weather.mz.advertise.AdHelper", "m", chain -> {
            if (!blockAdvertisements) return chain.proceed();
            Object listener = chain.getArg(2);
            if (listener != null) noAd.invoke(listener, 1012L);
            return null;
        }, Context.class, int.class, callback);

        Class<?> allAdConfig = Class.forName("com.hy.weather.mz.advertise.bean.AllAdConfig", false, loader);
        Method just = Class.forName(current ? "rx.d" : "rx.e", false, loader).getMethod(current ? "l" : "j", Object.class);
        installer.hook("com.hy.weather.mz.advertise.AdHelper", "j", chain -> blockAdvertisements
                ? just.invoke(null, allAdConfig.getConstructor().newInstance()) : chain.proceed());
        // The legacy home banner bypasses AdHelper and calls AdView directly.
        installer.hook("com.meizu.advertise.api.AdView", "load", chain -> blockAdvertisements
                ? chain.getThisObject() : chain.proceed(), String.class, Map.class);
        if (!current) installer.hook("com.hy.weather.mz.hf.BaseHfActivity$c", "a", chain -> {
            if (!blockAdvertisements) return chain.proceed();
            collapse((View) chain.getArg(1));
            return null;
        }, String.class, FrameLayout.class);
        Class<?> value = Class.forName("com.hy.weather.mz.modules.home.page.view.main.bean.WeatherModelBean$ValueData", false, loader);
        installer.hook(current ? "com.hy.weather.mz.modules.home.j" : "s4.i", "O", chain -> blockAdvertisements ? null : chain.proceed(), value);
        installer.hook(current ? "com.hy.weather.mz.modules.home.j" : "s4.i", "onCreateView", chain -> {
            View root = (View) chain.proceed();
            if (blockAdvertisements && root != null) {
                int id = root.getResources().getIdentifier("toolbar_protect_img", "id", "com.meizu.flyme.weather");
                if (id != 0) collapse(root.findViewById(id));
            }
            return root;
        }, LayoutInflater.class, ViewGroup.class, Bundle.class);

        // Cover configuration loaded from either the network or the application's existing cache.
        for (String getter : new String[]{"getMzAd", "getCpAd", "getHomeAd"}) {
            installer.hook("com.hy.weather.mz.advertise.bean.AllAdConfig", getter,
                    chain -> blockAdvertisements ? new ArrayList<>() : chain.proceed());
        }
        installer.hook("com.hy.weather.mz.modules.home.page.view.main.bean.WeatherModelBean$ValueData",
                "getAdBeanList", chain -> blockAdvertisements ? new ArrayList<>() : chain.proceed());

        // Leave news articles available while removing ad insertion from both bundled news engines.
        installer.hook("com.meizu.flyme.media.news.lite.NewsFullManager", "R",
                chain -> blockAdvertisements ? new ArrayList<>() : chain.proceed(), int.class);
        installer.hook("com.meizu.flyme.media.news.common.ad.e$d", "getAds",
                chain -> blockAdvertisements ? new ArrayList<>() : chain.proceed());
    }

    private void installContent(ClassLoader loader, SignalHooks.Installer installer) throws ReflectiveOperationException {
        if (current) nativeAdModel = Class.forName("h3.b", false, loader);
        installer.hook(current ? "multitype.f" : "multitype.h", current ? "k" : "l", chain -> {
            if (!enabled && !blockAdvertisements) return chain.proceed();
            List<?> source = (List<?>) chain.getArg(0);
            if (source == null) return chain.proceed();
            List<Object> retained = new ArrayList<>();
            for (Object item : source) {
                if (item == null || !(hideItem(item.getClass().getName()) || (blockAdvertisements && nativeAdModel != null && nativeAdModel.isInstance(item)))) retained.add(item);
            }
            // Remove entire rows before RecyclerView can create holders, decorations or ad views.
            return chain.proceed(new Object[]{retained});
        }, List.class);
        installer.hook("com.meizu.flyme.media.news.lite.NewsFlowView", "h",
                chain -> enabled ? null : chain.proceed());
        installer.hook("com.meizu.flyme.media.news.lite.NewsFlowView", "i",
                chain -> enabled ? null : chain.proceed(), Class.forName(current ? "r8.a" : "eb.a", false, loader));
        installer.hook("com.meizu.flyme.media.news.sdk.base.NewsBaseLifecycleView", "h", chain -> {
            int event = (Integer) chain.getArg(0);
            return enabled && event >= 0 && event <= 2 ? null : chain.proceed();
        }, int.class);
    }

    private static Object optionalFeature(Class<?> feature, String name) throws IllegalAccessException {
        try { return feature.getField(name).get(null); }
        catch (NoSuchFieldException e) { return null; }
    }

    private boolean hideItem(String name) {
        if (blockAdvertisements && (name.equals("w4.b$b") || name.equals("x4.a") || name.equals("y3.a"))) return true;
        return enabled && switch (name) {
            case "com.hy.weather.mz.modules.warn.detail.bean.CategoryForNewsFlow",
                    "com.hy.weather.mz.modules.index.bean.NewsFlowBean",
                    "com.hy.weather.mz.modules.home.page.view.newsSdk.bean.NewsFlowBean",
                    "com.meizu.flyme.media.news.sdk.NewsSdkInfoFlowView", "b5.a", "o5.a$a", "c4.a", "s4.a$a" -> true;
            default -> false;
        };
    }

    private static void collapse(View view) {
        if (view == null) return;
        view.setVisibility(View.GONE);
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params != null) {
            params.height = 0;
            if (params instanceof ViewGroup.MarginLayoutParams margins) {
                margins.topMargin = 0;
                margins.bottomMargin = 0;
            }
            view.setLayoutParams(params);
        }
    }

    private void read(Context context) {
        try (Cursor cursor = context.getContentResolver().query(ModuleSettings.URI, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(ModuleSettings.BLOCK_WEATHER_RECOMMENDATIONS);
                enabled = column >= 0 && cursor.getInt(column) != 0;
                int advertisements = cursor.getColumnIndex(ModuleSettings.BLOCK_WEATHER_ADS);
                blockAdvertisements = advertisements >= 0 && cursor.getInt(advertisements) != 0;
            }
        } catch (RuntimeException e) {
            if (!readErrorLogged) {
                readErrorLogged = true;
                log.accept("Cannot read weather recommendation preferences", e);
            }
        }
    }
}
