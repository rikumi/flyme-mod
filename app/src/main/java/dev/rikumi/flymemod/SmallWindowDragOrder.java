package dev.rikumi.flymemod;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

final class SmallWindowDragOrder {
    private static final Map<Object, WeakReference<Object>> active = new WeakHashMap<>();

    static synchronized void begin(Object area, Object task) {
        active.remove(area);
        if (area != null && task != null) active.put(area, new WeakReference<>(task));
    }

    static synchronized Object end(Object area) {
        WeakReference<Object> task = active.remove(area);
        return task == null ? null : task.get();
    }

    static synchronized boolean isActive(Object task) {
        if (task == null) return false;
        for (WeakReference<Object> reference : active.values())
            if (reference.get() == task) return true;
        return false;
    }
}
