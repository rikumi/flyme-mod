package dev.rikumi.flymemod;

import android.content.Context;
import android.app.Activity;
import android.os.Bundle;
import android.database.ContentObserver;
import android.database.Cursor;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/** Store 12.5.1 / 11.1.5: filter the shared navigation data and complete feed blocks. */
final class StoreLayoutHooks {
    private volatile boolean[] options = new boolean[11];
    private volatile int applicationPosition = -1;
    private volatile int applicationPageId = -1;
    private ContentObserver observer;
    private Context observerContext;
    private boolean readErrorLogged;
    private final BiConsumer<String, Throwable> log;
    private static final String[] KEYS = {
            ModuleSettings.STORE_HIDE_FEATURED, ModuleSettings.STORE_HIDE_GAMES,
            ModuleSettings.STORE_HIDE_POPULAR, ModuleSettings.STORE_HIDE_COMMUNITY,
            ModuleSettings.STORE_HIDE_DAILY, ModuleSettings.STORE_HIDE_SEARCH_HOT,
            ModuleSettings.STORE_EMPTY_APPLICATION_PAGE,
            ModuleSettings.STORE_DETAIL_HIDE_SAME_MODEL, ModuleSettings.STORE_DETAIL_HIDE_TOPICS,
            ModuleSettings.STORE_HIDE_SEARCH_RECOMMENDATIONS, ModuleSettings.STORE_DETAIL_HIDE_REVIEWS
    };

    StoreLayoutHooks(BiConsumer<String, Throwable> log) { this.log = log; }

    void install(ClassLoader loader, SignalHooks.Installer installer) throws ReflectiveOperationException {
        installSettings(installer);
        Class<?> mainValue = Class.forName("com.meizu.mstore.data.net.requestitem.main.MainValue", false, loader);
        Class<?> navItem = Class.forName("com.meizu.mstore.data.net.requestitem.NavItem", false, loader);
        Field navigation = mainValue.getField("nav");
        Field pageType = navItem.getField("page_type");
        Field blocks = mainValue.getField("blocksArray");
        Field secondFloor = mainValue.getField("secondFloorItem");
        installer.hook("com.meizu.mstore.data.net.requestitem.base.ResultModel", "getValue", chain -> {
            Object value = chain.proceed();
            boolean[] selected = options;
            if (!mainValue.isInstance(value)) return value;
            List<?> tabs = (List<?>) navigation.get(value);
            if (tabs == null || tabs.isEmpty()) return value;
            List<Object> retained = new ArrayList<>();
            Object application = null;
            for (Object tab : tabs) {
                String type = (String) pageType.get(tab);
                if ("app".equals(type)) application = tab;
                if ((selected[0] && "home".equals(type)) || (selected[1] && "game".equals(type))) continue;
                retained.add(tab);
            }
            // Without an application tab, retain the server's navigation rather than an empty page.
            if (retained.isEmpty() || (selected[0] && application == null)) return value;
            if (selected[0]) {
                retained.remove(application);
                retained.add(0, application);
                // The index response's preload and second floor belong to the removed home page.
                blocks.set(value, null);
                secondFloor.set(value, null);
            }
            applicationPosition = application == null ? -1 : retained.indexOf(application);
            applicationPageId = application == null ? -1 : navItem.getField("page_id").getInt(application);
            if (selected[6] && applicationPosition == 0) {
                blocks.set(value, null);
                secondFloor.set(value, null);
            }
            navigation.set(value, retained);
            return value;
        });

        Class<?> blockItem = Class.forName("com.meizu.mstore.data.net.requestitem.base.BlockItem", false, loader);
        Field blockName = blockItem.getField("name");
        Class<?> assemble = Class.forName("com.meizu.mstore.tools.AssembleTool", false, loader);
        int installed = 0;
        for (Method method : assemble.getDeclaredMethods()) {
            if (!"assembleSingleBlockItem".equals(method.getName()) || method.getReturnType() != void.class
                    || method.getParameterCount() < 2 || method.getParameterTypes()[0] != blockItem) continue;
            installer.hook(assemble.getName(), method.getName(), chain -> {
                Object block = chain.getArg(0);
                if (block != null && hideBlock((String) blockName.get(block), options)) return null;
                return chain.proceed();
            }, method.getParameterTypes());
            installed++;
        }
        // Cached MainValue bypasses ResultModel#getValue. Capture the actual navigation
        // immediately before either cached or network data creates the first fragment.
        Field pageId = navItem.getField("page_id");
        installer.hook("com.meizu.flyme.appcenter.activitys.AppMainActivity", "setupViewPager", chain -> {
            applicationPosition = -1;
            applicationPageId = -1;
            List<?> tabs = (List<?>) chain.getArg(0);
            if (tabs != null) for (int i = 0; i < tabs.size(); i++) {
                Object tab = tabs.get(i);
                if ("app".equals(pageType.get(tab))) {
                    applicationPosition = i;
                    applicationPageId = pageId.getInt(tab);
                    break;
                }
            }
            return chain.proceed();
        }, List.class, Class.forName("com.alibaba.fastjson.JSONArray", false, loader), boolean.class);
        try { installDetail(loader, installer); }
        catch (ReflectiveOperationException e) { log.accept("Cannot resolve store detail recommendations", e); }
        if (installed == 0) throw new NoSuchMethodException("Store feed block assembly");
        try { installSearch(loader, installer); }
        catch (ReflectiveOperationException e) { log.accept("Cannot resolve store search controls", e); }
        try { installSearchLanding(loader, installer); }
        catch (ReflectiveOperationException e) { log.accept("Cannot resolve store search landing page", e); }
        try { installApplicationPage(loader, installer); }
        catch (ReflectiveOperationException e) { log.accept("Cannot resolve store application page", e); }
    }

    private void installSettings(SignalHooks.Installer installer) {
        installer.hook("com.meizu.flyme.appcenter.AppCenterApplication", "onCreate", chain -> {
            Context context = (Context) chain.getThisObject();
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

    private void installSearch(ClassLoader loader, SignalHooks.Installer installer)
            throws ReflectiveOperationException {
        Class<?> hintController;
        boolean current;
        try {
            hintController = Class.forName("ge.e", false, loader);
            hintController.getDeclaredMethod("c", boolean.class);
            current = true;
        } catch (ReflectiveOperationException e) {
            hintController = Class.forName("nc.d", false, loader);
            current = false;
        }
        Field hintActivity = hintController.getDeclaredField("k");
        hintActivity.setAccessible(true);
        installer.hook(hintController.getName(), current ? "c" : "b", chain -> options[5]
                ? ((Context) hintActivity.get(chain.getThisObject())).getString(android.R.string.search_go)
                : chain.proceed(), boolean.class);
        installer.hook(hintController.getName(), current ? "f" : "d", chain -> options[5] ? null : chain.proceed());
        installer.hook("com.meizu.flyme.appcenter.activitys.AppMainActivity", "setHotHintStr",
                chain -> options[5] ? null : chain.proceed(), List.class);
        Method empty = Class.forName("io.reactivex.Observable", false, loader).getMethod("empty");
        // Retrofit creates a dynamic proxy, so stop the API call before its service method executes.
        installer.hook("retrofit2.Retrofit$1", "invoke", chain -> {
            Method request = (Method) chain.getArg(1);
            if (options[5] && request.getName().equals("getSearchHotStr")
                    && request.getDeclaringClass().getName().equals("com.meizu.mstore.data.net.api.MainApi")) {
                return empty.invoke(null);
            }
            return chain.proceed();
        }, Object.class, Method.class, Object[].class);

    }

    private void installSearchLanding(ClassLoader loader, SignalHooks.Installer installer)
            throws ReflectiveOperationException {
        Class<?> fragment = Class.forName("com.meizu.mstore.page.search.SearchFragment", false, loader);
        Class<?> presenter = Class.forName("com.meizu.mstore.page.search.w", false, loader);
        Class<?> rows = Class.forName("qb.d", false, loader);
        Class<?> history = Class.forName("com.meizu.mstore.multtype.itemdata.SearchHistoryItemData", false, loader);
        Field controller = fragment.getDeclaredField("g");
        Field keywords = presenter.getDeclaredField("U");
        Field page = presenter.getDeclaredField("S");
        Field cache = presenter.getDeclaredField("k");
        controller.setAccessible(true);
        keywords.setAccessible(true);
        page.setAccessible(true);
        cache.setAccessible(true);
        Method cancel = presenter.getMethod("G");
        Method name = fragment.getMethod("setPageName", String.class);
        Method setData = fragment.getMethod("setData", rows);
        // R8 removed qb.d's declared constructor. Use the app's verified factory.
        Method emptyRows = Class.forName("com.meizu.mstore.tools.AssembleTool", false, loader)
                .getMethod("assembleFeedBlock", List.class);
        var historyConstructor = history.getConstructor(List.class);
        // The empty-query landing page has its own entry point. Do not intercept
        // the search-result or query-suggestion pipelines which also call setData.
        installer.hook(fragment.getName(), "s", chain -> {
            if (!options[9]) return chain.proceed();
            Object view = chain.getThisObject();
            Object state = controller.get(view);
            cancel.invoke(state);
            List<?> words = (List<?>) keywords.get(state);
            Object data = emptyRows.invoke(null, new ArrayList<>());
            @SuppressWarnings("unchecked") List<Object> items = (List<Object>) data;
            if (words != null && !words.isEmpty()) items.add(historyConstructor.newInstance(words));
            name.invoke(view, "search");
            page.setInt(state, 1);
            cache.set(state, data);
            setData.invoke(view, data);
            return null;
        });
    }

    private void installApplicationPage(ClassLoader loader, SignalHooks.Installer installer)
            throws ReflectiveOperationException {
        Class<?> pageConfig;
        boolean current;
        try {
            pageConfig = Class.forName("jd.b", false, loader);
            if (pageConfig.getMethod("a").getReturnType() != Bundle.class) throw new NoSuchMethodException("Store page arguments");
            current = true;
        } catch (ReflectiveOperationException e) {
            pageConfig = Class.forName("zb.c", false, loader);
            current = false;
        }
        Field arguments = findFieldByType(pageConfig, Bundle.class);
        Field pageInfo = null;
        for (Field candidate : pageConfig.getDeclaredFields()) {
            try {
                candidate.getType().getDeclaredField(current ? "page_id" : "b");
                pageInfo = candidate;
                break;
            } catch (NoSuchFieldException ignored) {
                // Locate the page metadata by its page-id field; obfuscation prefixes
                // on the containing jd.b fields vary between JADX and runtime names.
            }
        }
        if (pageInfo == null) throw new NoSuchFieldException("Store page metadata field");
        Field resolvedPageInfo = pageInfo;
        Field pageId = resolvedPageInfo.getType().getDeclaredField(current ? "page_id" : "b");
        arguments.setAccessible(true);
        resolvedPageInfo.setAccessible(true);
        pageId.setAccessible(true);
        Method getArguments = pageConfig.getMethod("a");
        Class<?> fragment = Class.forName("androidx.fragment.app.Fragment", false, loader);
        Method setArguments = fragment.getMethod("setArguments", Bundle.class);
        Class<?> appMainActivity = Class.forName("com.meizu.flyme.appcenter.activitys.AppMainActivity", false, loader);
        Class<?> actionBar = Class.forName("flyme.support.v7.app.ActionBar", false, loader);
        HeaderBinding headerBinding = resolveHeaderBinding(loader, appMainActivity, actionBar);
        installer.hook(current ? "com.meizu.cloud.app.utils.g" : "com.bumptech.glide.f", current ? "c" : "a", chain -> {
            Context context = (Context) chain.getArg(0);
            if (!options[6] || applicationPosition < 0
                    || !appMainActivity.isInstance(context)) {
                return chain.proceed();
            }
            Object config = chain.getArg(1);
            Bundle bundle = (Bundle) arguments.get(config);
            if (bundle.getInt("position", -1) != applicationPosition
                    || pageId.getInt(resolvedPageInfo.get(config)) != applicationPageId) return chain.proceed();
            // Keep the activity's toolbar and navigation, without creating any feed or nested tabs.
            // Returning a plain Fragment skips the app page's menu callbacks. Rebind the
            // Activity-owned search/download header after the tab transaction, especially when
            // returning from Mine where its ActionBar custom view was replaced.
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    Activity activity = (Activity) context;
                    if (!options[6] || activity.isFinishing() || activity.isDestroyed()) return;
                    if (headerBinding != null) headerBinding.restore(activity);
                } catch (ReflectiveOperationException | RuntimeException error) {
                    log.accept("Cannot restore store search/download header", error);
                }
            });
            Object blank = fragment.getConstructor().newInstance();
            setArguments.invoke(blank, getArguments.invoke(config));
            return blank;
        }, Context.class, pageConfig);
    }

    private static Field findFieldByType(Class<?> owner, Class<?> targetType) throws NoSuchFieldException {
        for (Field field : owner.getDeclaredFields()) {
            if (targetType.isAssignableFrom(field.getType())) return field;
        }
        throw new NoSuchFieldException(owner.getName() + " field of type " + targetType.getName());
    }

    private HeaderBinding resolveHeaderBinding(ClassLoader loader, Class<?> activity, Class<?> actionBar) {
        for (String className : new String[] {"ge.e", "nc.d"}) {
            try {
                Class<?> headerType = Class.forName(className, false, loader);
                Field activityHeader = findFieldByType(activity, headerType);
                activityHeader.setAccessible(true);
                Field root = headerType.getDeclaredField(className.equals("ge.e") ? "f9767d" : "f15922d");
                root.setAccessible(true);
                Method getActionBar = activity.getMethod("getSupportActionBar");
                Method bind = null;
                try { bind = headerType.getMethod("e", actionBar); }
                catch (NoSuchMethodException ignored) { /* Older build binds the same view inline. */ }
                Method showCustom = actionBar.getMethod("setDisplayShowCustomEnabled", boolean.class);
                Method setCustom = actionBar.getMethod("setCustomView", View.class);
                return new HeaderBinding(activityHeader, root, getActionBar, bind, showCustom, setCustom);
            } catch (ReflectiveOperationException | LinkageError ignored) {
                // Try the other supported Store build.
            }
        }
        log.accept("Cannot resolve store search/download header", null);
        return null;
    }

    private static final class HeaderBinding {
        private final Field activityHeader, root;
        private final Method getActionBar, bind, showCustom, setCustom;
        HeaderBinding(Field activityHeader, Field root, Method getActionBar, Method bind,
                      Method showCustom, Method setCustom) {
            this.activityHeader = activityHeader;
            this.root = root;
            this.getActionBar = getActionBar;
            this.bind = bind;
            this.showCustom = showCustom;
            this.setCustom = setCustom;
        }
        void restore(Activity activity) throws ReflectiveOperationException {
            Object header = activityHeader.get(activity);
            Object bar = getActionBar.invoke(activity);
            if (header == null || bar == null) return;
            if (bind != null) {
                bind.invoke(header, bar);
            } else {
                showCustom.invoke(bar, true);
                setCustom.invoke(bar, root.get(header));
            }
        }
    }

    private void installDetail(ClassLoader loader, SignalHooks.Installer installer)
            throws ReflectiveOperationException {
        Class<?> value = Class.forName("com.meizu.mstore.data.net.requestitem.detail.RecommendValue", false, loader);
        Class<?> block = Class.forName(value.getName() + "$BlocksBean", false, loader);
        Field blocks = value.getField("blocks");
        Field title = block.getField("title");
        Field type = block.getField("type");
        // Filter before the recommendation mapper creates titles, rows and dividers,
        // including its second pass and recommendation-id collection.
        installer.hook("com.meizu.mstore.tools.AssembleTool", "assembleAppDetailRecommend", chain -> {
            filterDetail(chain.getArg(0), blocks, title, type);
            return chain.proceed();
        }, value, boolean.class);
    }

    private void filterDetail(Object value, Field blocks, Field title, Field type) throws IllegalAccessException {
        boolean[] selected = options;
        if (value == null || (!selected[7] && !selected[8] && !selected[10])) return;
        List<?> source = (List<?>) blocks.get(value);
        if (source == null) return;
        List<Object> retained = new ArrayList<>();
        for (Object block : source) {
            String heading = block == null ? null : (String) title.get(block);
            boolean sameModel = heading != null && heading.contains("同机型")
                    && (heading.contains("喜爱") || heading.contains("喜欢"));
            boolean topics = block != null && "special".equals(type.get(block));
            boolean reviews = block != null && "h5_ext".equals(type.get(block));
            if (!(selected[7] && sameModel) && !(selected[8] && topics)
                    && !(selected[10] && reviews)) retained.add(block);
        }
        blocks.set(value, retained);
    }

    private static boolean hideBlock(String name, boolean[] selected) {
        if (name == null) return false;
        return switch (name.trim()) {
            case "大家都在用" -> selected[2];
            case "魅友安利" -> selected[3];
            case "每日推荐" -> selected[4];
            default -> false;
        };
    }

    private void read(Context context) {
        try (Cursor cursor = context.getContentResolver().query(ModuleSettings.URI, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                boolean[] updated = new boolean[KEYS.length];
                for (int i = 0; i < KEYS.length; i++) {
                    int column = cursor.getColumnIndex(KEYS[i]);
                    updated[i] = column >= 0 && cursor.getInt(column) != 0;
                }
                options = updated;
            }
        } catch (RuntimeException e) {
            if (!readErrorLogged) {
                readErrorLogged = true;
                log.accept("Cannot read store layout preferences", e);
            }
        }
    }
}
