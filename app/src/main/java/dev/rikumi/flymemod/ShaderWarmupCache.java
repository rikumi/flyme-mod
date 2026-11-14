package dev.rikumi.flymemod;

import java.util.Map;
import java.util.TreeMap;
import java.util.Objects;

/** A compositor-compiled program can be reused by windows that were hidden during warmup. */
final class ShaderWarmupCache<T> {
    private final TreeMap<Integer, T> programs = new TreeMap<>();
    private Object settings;
    private boolean initialized;
    private long generation;

    synchronized boolean updateSettings(Object settings) {
        if (initialized && Objects.equals(this.settings, settings)) return false;
        initialized = true;
        this.settings = settings;
        generation++;
        programs.clear();
        return true;
    }

    synchronized long generation() { return generation; }

    synchronized boolean current(long ticket) { return ticket == generation; }

    synchronized boolean publish(int capacity, T program, ShaderWarmupGate proof, long ticket) {
        if (!proof.ready() || !current(ticket)) return false;
        programs.put(capacity, program);
        return true;
    }

    synchronized T find(int capacity) {
        Map.Entry<Integer, T> entry = programs.ceilingEntry(capacity);
        return entry == null ? null : entry.getValue();
    }
}
