package dev.rikumi.flymemod;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Filters Mine's final rows, including the presenter's offline fallback. */
final class StoreMineEntryHooks {
    private StoreMineEntryHooks() {}

    static void install(ClassLoader loader, SignalHooks.Installer installer, Supplier<boolean[]> options)
            throws ReflectiveOperationException {
        String prefix = "com.meizu.mstore.multtype.itemdata.mine.";
        Class<?> component = loader.loadClass("com.meizu.mstore.data.net.requestitem.ComponentDataItem");
        Class<?> componentBlock = loader.loadClass(prefix + "MineComponentBlock");
        Class<?> col4 = loader.loadClass(prefix + "MineRow1Col4ItemData");
        Class<?> col2 = loader.loadClass(prefix + "MineRow1Col2ItemData");
        Constructor<?> single = loader.loadClass(prefix + "MineRow1ItemData").getConstructor(component);
        Constructor<?> grid = col4.getConstructor();
        Constructor<?> pair = col2.getConstructor();
        Method components = componentBlock.getMethod("getComponentDataItems");
        Method setGrid = col4.getMethod("setComponentDataItemList", List.class);
        Method setPair = col2.getMethod("setComponentDataItemList", List.class);
        Field type = component.getField("type");
        Class<?> base = loader.loadClass("com.meizu.mstore.multtype.itemdata.base.BaseItemData");
        Field[] metadata = base.getFields();
        Field stat = base.getField("mItemDataStat");
        Field[] statFields = stat.getType().getFields();
        Method emptyRows = loader.loadClass("com.meizu.mstore.tools.AssembleTool")
                .getMethod("assembleFeedBlock", List.class);
        // The rows type is qb.d in 12.5.1 and oa.e in 11.1.5; resolve the actual signature.
        Class<?> mine = loader.loadClass("com.meizu.mstore.page.mine.a");
        Class<?> rowsType = emptyRows.getReturnType();
        mine.getDeclaredMethod("setData", rowsType);
        installer.hook(mine.getName(), "setData", chain -> {
            boolean[] selected = options.get();
            if (!selected[8] && !selected[9] && !selected[10]) return chain.proceed();
            List<?> rows = (List<?>) chain.getArg(0);
            if (rows == null) return chain.proceed();
            @SuppressWarnings("unchecked")
            List<Object> filtered = (List<Object>) emptyRows.invoke(null, java.util.Collections.emptyList());
            boolean changed = false;
            for (Object row : rows) {
                if (!componentBlock.isInstance(row)) {
                    filtered.add(row);
                    continue;
                }
                List<?> entries = (List<?>) components.invoke(row);
                List<Object> retained = new ArrayList<>();
                for (Object entry : entries) {
                    if (!hideType((String) type.get(entry), selected[8], selected[9], selected[10]))
                        retained.add(entry);
                }
                if (retained.size() == entries.size()) {
                    filtered.add(row);
                    continue;
                }
                changed = true;
                if (retained.isEmpty()) continue;
                Object replacement;
                if (col4.isInstance(row)) {
                    replacement = grid.newInstance();
                    setGrid.invoke(replacement, retained);
                } else if (col2.isInstance(row)) {
                    // The pair binder requires two entries. Use the native single-row layout.
                    replacement = retained.size() == 1 ? single.newInstance(retained.get(0)) : pair.newInstance();
                    if (retained.size() > 1) setPair.invoke(replacement, retained);
                } else {
                    throw new IllegalStateException("Unexpected multi-entry Mine row: " + row.getClass().getName());
                }
                // Keep the input/cache rows intact so disabling the switches restores them on reload.
                copyFields(metadata, row, replacement);
                copyFields(statFields, stat.get(row), stat.get(replacement));
                filtered.add(replacement);
            }
            return changed ? chain.proceed(new Object[]{filtered}) : chain.proceed();
        }, rowsType);
    }

    static boolean hideType(String type, boolean subscribeWish, boolean campaignQuickApp, boolean phoneClear) {
        if (type == null) return false;
        return switch (type) {
            case "SUBSCRIBE", "WISH" -> subscribeWish;
            case "CAMPAIGN", "BUS_ACTIVITIES", "QUICK_APP" -> campaignQuickApp;
            case "CLEAR", "手机瘦身" -> phoneClear;
            default -> false;
        };
    }

    private static void copyFields(Field[] fields, Object source, Object target) throws IllegalAccessException {
        for (Field field : fields) {
            if (!Modifier.isStatic(field.getModifiers()) && !Modifier.isFinal(field.getModifiers()))
                field.set(target, field.get(source));
        }
    }
}
