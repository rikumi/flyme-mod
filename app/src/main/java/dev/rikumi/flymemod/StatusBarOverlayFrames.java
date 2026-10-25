package dev.rikumi.flymemod;

import java.util.Collection;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

final class StatusBarOverlayFrames {
    static final String KEY = "flyme_mod_statusbar_overlay_frames";
    record Frame(int display, int left, int top, int right, int bottom) {
        boolean valid() { return display >= 0 && left < right && top < bottom; }
        boolean covers(Frame bar) {
            return valid() && bar.valid() && display == bar.display && left < bar.right
                    && right > bar.left && top < bar.bottom && bottom > bar.top;
        }
        String encode() { return display + "," + left + "," + top + "," + right + "," + bottom; }
    }
    static boolean overlayType(int type) {
        return type == 2038 || type == 2002 || type == 2003 || type == 2010 || type == 2007;
    }
    static String encode(Collection<Frame> frames) {
        TreeSet<String> unique = new TreeSet<>();
        for (Frame frame : frames) if (frame != null && frame.valid()) unique.add(frame.encode());
        return String.join(";", unique);
    }
    static List<Frame> parse(String raw) {
        List<Frame> result = new ArrayList<>();
        if (raw == null || raw.length() > 65536) return result;
        for (String row : raw.split(";")) {
            String[] f = row.split(",");
            if (f.length != 5) continue;
            try {
                Frame frame = new Frame(Integer.parseInt(f[0]), Integer.parseInt(f[1]),
                        Integer.parseInt(f[2]), Integer.parseInt(f[3]), Integer.parseInt(f[4]));
                if (frame.valid()) result.add(frame);
            } catch (NumberFormatException ignored) { }
        }
        return result;
    }
    private StatusBarOverlayFrames() {}
}
