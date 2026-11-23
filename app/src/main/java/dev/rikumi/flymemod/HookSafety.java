package dev.rikumi.flymemod;

import java.util.Set;

/**
 * Processes the module must never be injected into. Injecting here can break the user's only
 * recovery lever (LSPosed manager / root manager), which makes an unlucky scope choice
 * unrecoverable without a reboot into safe mode or uninstalling the module.
 */
final class HookSafety {
    private static final Set<String> PROTECTED = Set.of(
            "dev.rikumi.flymemod",
            // LSPosed's parasitic manager runs as shell and is labelled "LSPosed" on Flyme.
            "com.android.shell",
            "org.lsposed.manager",
            "de.robv.android.xposed.installer",
            "me.weishu.kernelsu",
            "com.topjohnwu.magisk"
    );

    static boolean isProtected(String packageName) {
        return packageName != null && PROTECTED.contains(packageName);
    }

    private HookSafety() {}
}
