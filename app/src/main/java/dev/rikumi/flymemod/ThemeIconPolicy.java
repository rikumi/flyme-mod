package dev.rikumi.flymemod;

import java.util.Locale;
import java.util.regex.Pattern;

/** Only named, built-in interface icons participate; user images are not resource icons. */
final class ThemeIconPolicy {
    static final int NONE = 0, COLOR_PARTS = 1, WHITE_CUTOUT = 2, PRESERVE_RED = 3, INACTIVE_TAB = 4;
    private static final Pattern DESTRUCTIVE = Pattern.compile("(?:^|_)(?:delete|del|remove|uninstall|trash|clear|cancel|danger|warning|error)(?:_|$)");

    static int mode(String packageName, String name) {
        if ("com.meizu.media.camera".equals(packageName) || ThemeColorTargets.colorExcluded(packageName)) return NONE;
        if ("com.android.packageinstaller".equals(packageName)
                && ("update_logo".equals(name) || "update_logo_polestar".equals(name))) return WHITE_CUTOUT;
        if ("com.meizu.notepaper".equals(packageName)
                && name.startsWith("bottom_tab_") && name.endsWith("_select")) return COLOR_PARTS;
        boolean destructive = DESTRUCTIVE.matcher(name.toLowerCase(Locale.ROOT)).find();
        if (destructive && "com.meizu.flyme.launcher".equals(packageName)) return NONE;
        if ("com.meizu.mstore".equals(packageName)
                && (name.startsWith("mz_ac_tab_") || name.startsWith("start_ac_tab_")))
            return name.endsWith("_focus") ? WHITE_CUTOUT : INACTIVE_TAB;
        if ("com.meizu.flyme.launcher".equals(packageName) &&
                (name.contains("badge") || name.contains("red_point") || name.contains("notification_dot")
                        || name.contains("notification_count"))) return NONE;
        if (name.contains("launcher") || name.contains("avatar") || name.contains("thumbnail")
                || name.contains("preview") || name.contains("banner") || name.contains("illustration")
                || name.contains("placeholder") || name.contains("empty") || name.contains("guide")) return NONE;
        return name.startsWith("mz_function_list_ic_") || name.startsWith("ic_")
                || name.startsWith("icon_") || name.startsWith("mz_ic_") || name.startsWith("mc_ic_")
                || name.startsWith("mz_titlebar_ic_") || name.startsWith("ai_loading_icon")
                || name.startsWith("aicy_") || name.contains("_icon_")
                ? destructive && ThemeColorTargets.themeFamily(packageName) != 3 ? PRESERVE_RED : COLOR_PARTS : NONE;
    }
}
