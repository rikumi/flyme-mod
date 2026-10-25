package dev.rikumi.flymemod;

import java.util.LinkedHashMap;
import java.util.List;

final class PlayedMediaHistory {
    private final LinkedHashMap<List<?>, Boolean> played = new LinkedHashMap<>(64, .75f, true);
    synchronized boolean allow(List<?> track, boolean playing, boolean enabled) {
        if (playing) {
            played.put(track, true);
            while (played.size() > 256) played.remove(played.keySet().iterator().next());
        }
        return !enabled || played.get(track) != null;
    }
}
