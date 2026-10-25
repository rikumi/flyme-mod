package dev.rikumi.flymemod;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

final class BackdropSnapshotCache {
    private final Map<Object, Object> snapshots = new WeakHashMap<>();

    void install(ClassLoader loader, Set<Long> ownedIds, SignalHooks.Installer installer,
            Consumer<Method> deoptimize, BiConsumer<String, Throwable> log)
            throws ReflectiveOperationException {
        Class<?> drawable = loader.loadClass("com.android.internal.graphics.drawable.FlymeEffectDrawable");
        Class<?> aggregator = loader.loadClass(drawable.getName() + "$Aggregator");
        Class<?> region = loader.loadClass(drawable.getName() + "$FlymeEffectRegion");
        Field effectId = field(drawable, "mFlymeEffectId");
        Field changed = field(aggregator, "mHasUiUpdates");
        Field drawables = field(aggregator, "mDrawables");
        Field current = field(aggregator, "mTmpFlymeEffectRegionsForFrame");
        Constructor<?> constructor = region.getDeclaredConstructor(drawable);
        constructor.setAccessible(true);
        Method updated = aggregator.getDeclaredMethod("onFlymeEffectDrawableUpdated", drawable);
        Method snapshot = aggregator.getDeclaredMethod("getFlymeEffectRegionsCopyForRT");
        installer.hook(aggregator.getName(), updated.getName(), chain -> {
            Object effect = chain.getArg(0);
            if (ownedIds.contains(effectId.getLong(effect))) {
                synchronized (snapshots) { snapshots.remove(effect); }
            }
            return chain.proceed();
        }, drawable);
        installer.hook(aggregator.getName(), snapshot.getName(), chain -> {
            Object owner = chain.getThisObject();
            if (!changed.getBoolean(owner)) return chain.proceed();
            try {
                Collection<?> active = (Collection<?>) drawables.get(owner);
                boolean hasOwned = false;
                for (Object effect : active) hasOwned |= ownedIds.contains(effectId.getLong(effect));
                if (!hasOwned) return chain.proceed();
                Object result = Array.newInstance(region, active.size());
                int index = 0;
                synchronized (snapshots) {
                    for (Object effect : active) {
                        Object copy;
                        if (ownedIds.contains(effectId.getLong(effect))) {
                            copy = snapshots.get(effect);
                            if (copy == null) {
                                copy = constructor.newInstance(effect);
                                snapshots.put(effect, copy);
                            }
                        } else copy = constructor.newInstance(effect);
                        Array.set(result, index++, copy);
                    }
                }

                current.set(owner, result);
                changed.setBoolean(owner, false);
                return result;
            } catch (ReflectiveOperationException | RuntimeException error) {
                synchronized (snapshots) { snapshots.clear(); }
                log.accept("Cannot reuse backdrop uniform snapshots", error);
                return chain.proceed();
            }
        });
        // Uniform/alpha setters can inline the invalidation callback on optimized ROMs.
        for (Method method : drawable.getDeclaredMethods()) deoptimize.accept(method);
        deoptimize.accept(updated);
        deoptimize.accept(snapshot);
        // The pre-draw lambda calls the snapshot accessor and may have it inlined.
        for (Method method : aggregator.getDeclaredMethods()) {
            if (method.getName().startsWith("lambda$registerPreDrawListener$")) deoptimize.accept(method);
        }
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field result = type.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }
}
