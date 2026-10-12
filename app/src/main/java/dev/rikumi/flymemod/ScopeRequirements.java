package dev.rikumi.flymemod;

import java.util.Set;

final class ScopeRequirements {
    static boolean hasMissing(Set<String> recommended, Set<String> installed, Set<String> selected) {
        for (String scope : recommended) {
            if ("system".equals(scope)) {
                if (!selected.contains("system") && !selected.contains("android")) return true;
            } else if (installed.contains(scope) && !selected.contains(scope)) {
                return true;
            }
        }
        return false;
    }
}
