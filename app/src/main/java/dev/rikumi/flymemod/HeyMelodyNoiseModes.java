package dev.rikumi.flymemod;

import java.util.List;

final class HeyMelodyNoiseModes {
    record Entry(int index, int type, boolean available, List<Entry> children) {}
    private final int[] modes = new int[32];
    private final boolean[] assigned = new boolean[32];
    private int anc = -1, transparency = -1;
    private boolean valid = true;

    HeyMelodyNoiseModes(List<Entry> entries) {
        for (Entry entry : entries) {
            int mode = canonical(entry.type());
            assign(entry, mode, 0);
            if (entry.type() == 2 && entry.available()) transparency = entry.index();
        }
        for (int preferred : new int[]{5, 4, 3, 10}) {
            for (Entry entry : entries) if (entry.type() == preferred && entry.available()) { anc = entry.index(); break; }
            if (anc >= 0) break;
        }
    }
    private void assign(Entry entry, int mode, int depth) {
        int index = entry.index();
        if (depth > 4 || index < 0 || index >= modes.length) { valid = false; return; }
        if (assigned[index] && modes[index] != mode) valid = false;
        assigned[index] = true;
        modes[index] = mode;
        for (Entry child : entry.children()) assign(child, mode, depth + 1);
    }
    private static int canonical(int type) {
        return switch (type) { case 1 -> 1; case 2 -> 3; case 3, 4, 5, 10 -> 2; default -> 0; };
    }
    boolean supported() { return valid && anc >= 0 && transparency >= 0; }
    int mode(int index) { return supported() && index >= 0 && index < modes.length ? modes[index] : 0; }
    int target(int mode) { return !supported() ? -1 : mode == 2 ? anc : mode == 3 ? transparency : -1; }
}
