package dev.rikumi.flymemod;
import java.util.Set;
public final class ScopeRequirementsTest {
    static void check(boolean condition) { if (!condition) throw new AssertionError(); }
    public static void main(String[] args) {
        var required = Set.of("system", "com.android.systemui", "com.meizu.assistant", "absent");
        var installed = Set.of("com.android.systemui", "com.meizu.assistant");
        check(ScopeRequirements.missing(required, installed, Set.of("android", "com.android.systemui"))
                .equals(Set.of("com.meizu.assistant")));
        check(!ScopeRequirements.hasMissing(required, installed, Set.of("system", "com.android.systemui", "com.meizu.assistant")));
        check(ScopeRequirements.missing(required, installed, Set.of()).equals(Set.of("system", "com.android.systemui", "com.meizu.assistant")));
        check(!ScopeRequirements.hasMissing(Set.of("absent"), installed, Set.of()));
        System.out.println("PASS: new installed scope, framework aliases, full/empty selection, absent packages");
    }
}
