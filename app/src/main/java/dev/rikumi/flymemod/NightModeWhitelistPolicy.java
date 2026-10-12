package dev.rikumi.flymemod;

import java.util.LinkedHashSet;

final class NightModeWhitelistPolicy {
    static final String KEY = "flyme_night_mode_force_dark_white_list";
    static boolean contains(String stored, String pkg) {
        if (stored == null || pkg == null) return false;
        for (String item : stored.split(",")) if (pkg.equals(item.trim())) return true;
        return false;
    }
    static String update(String stored, String pkg, boolean enabled) {
        LinkedHashSet<String> packages = new LinkedHashSet<>();
        if (stored != null) for (String item : stored.split(",")) {
            String name = item.trim();
            if (!name.isEmpty()) packages.add(name);
        }
        if (enabled) packages.add(pkg); else packages.remove(pkg);
        return String.join(",", packages);
    }
}
