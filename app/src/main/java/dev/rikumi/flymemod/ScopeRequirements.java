package dev.rikumi.flymemod;

import java.util.Set;

final class ScopeRequirements {
    static boolean hasMissing(Set<String> recommended, Set<String> installed, Set<String> selected) {
        return !missing(recommended, installed, selected).isEmpty();
    }

    static Set<String> missing(Set<String> recommended, Set<String> installed, Set<String> selected) {
        Set<String> missing = new java.util.LinkedHashSet<>();
        for (String scope : recommended) {
            if ("system".equals(scope)) {
                if (!selected.contains("system") && !selected.contains("android")) missing.add(scope);
            } else if (installed.contains(scope) && !selected.contains(scope)) {
                missing.add(scope);
            }
        }
        return missing;
    }
}
