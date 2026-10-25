package dev.rikumi.flymemod;

import android.content.Context;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

public final class ScopeRestartPlan {
    private static boolean safe(String name) { return name != null && !name.equals("system_server") && !name.equals("system") && name.matches("[A-Za-z0-9_.:]+"); }
    private static void add(Set<String> names, String name) { if (safe(name)) names.add(name); }
    private static void components(Set<String> names, ComponentInfo[] components) {
        if (components != null) for (ComponentInfo component : components) add(names, component.processName);
    }

    public static String command(Context context) throws Exception {
        Set<String> processes = new LinkedHashSet<>();
        InputStream input = ScopeRestartPlan.class.getClassLoader().getResourceAsStream("META-INF/xposed/scope.list");
        if (input == null) throw new IllegalStateException("Missing module scope declaration");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null;) {
                String pkg = line.trim();
                if (pkg.equals("system")) continue;
                if (!pkg.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")) continue;
                try {
                    PackageInfo info = context.getPackageManager().getPackageInfo(pkg,
                            PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES
                                    | PackageManager.GET_RECEIVERS | PackageManager.GET_PROVIDERS);
                    add(processes, pkg);
                    if (info.applicationInfo != null) add(processes, info.applicationInfo.processName);
                    components(processes, info.activities); components(processes, info.services);
                    components(processes, info.receivers); components(processes, info.providers);
                } catch (PackageManager.NameNotFoundException absent) { /* Not installed in this user. */ }
            }
        }
        return shell(processes);
    }

    static String shell(Set<String> processes) {
        for (String name : processes) if (!safe(name)) throw new IllegalArgumentException("Invalid process name");
        // Snapshot once, then validate each PID's current name before signalling to avoid PID reuse.
        return "processes=$(ps -A -o PID,NAME) || exit 1; "
                + "targets=$(printf '%s\\n' \"$processes\" | awk -v names='" + String.join(" ", processes)
                + "' 'BEGIN { n=split(names,a,\" \") } NR>1 { for(i=1;i<=n;i++) if($2==a[i] || index($2,a[i] \":\")==1) { print $1, $2; break } }') || exit 1; "
                + "failed=0; while read -r pid name; do "
                + "case \"$pid\" in ''|*[!0-9]*) continue;; esac; [ \"$pid\" -gt 1 ] || continue; "
                + "current=$(tr '\\000' '\\n' < /proc/$pid/cmdline 2>/dev/null | head -n 1); "
                + "[ \"$current\" = \"$name\" ] || continue; "
                + "kill -TERM \"$pid\" 2>/dev/null || { kill -0 \"$pid\" 2>/dev/null && failed=1; }; "
                + "done <<SCOPE_PROCESSES\n$targets\nSCOPE_PROCESSES\n"
                + "exit $failed";
    }
}
