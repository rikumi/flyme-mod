package dev.rikumi.flymemod;

/** IDs verified in gallery tab bindings and the store's fixed/shifting navigation items. */
final class ThemeTabPolicy {
    static boolean hasIconViewRules(String pkg) {
        return "com.meizu.media.gallery".equals(pkg) || "com.meizu.mstore".equals(pkg)
                || "com.android.packageinstaller".equals(pkg);
    }
    static boolean galleryIcon(String pkg, String id) {
        return "com.meizu.media.gallery".equals(pkg) &&
                ("photo1".equals(id) || "album1".equals(id) || "time1".equals(id));
    }
    static boolean galleryText(String pkg, String id) {
        return "com.meizu.media.gallery".equals(pkg) &&
                ("tab_name".equals(id) || "tab2_name".equals(id) || "tab3_name".equals(id));
    }
    static boolean storeIcon(String pkg, String id) {
        return "com.meizu.mstore".equals(pkg) &&
                ("fixed_bottom_navigation_icon".equals(id) || "shifting_bottom_navigation_icon".equals(id));
    }
    static boolean installerUpgradeIcon(String pkg, String id) {
        return "com.android.packageinstaller".equals(pkg) && "update_logo".equals(id);
    }
    static boolean storeGlobalBackground(String pkg, String id) {
        return "com.meizu.mstore".equals(pkg) &&
                ("bottom_navigation_bar_container".equals(id) || "bottom_navigation_bar_item_container".equals(id)
                        || "bottom_navigation_bar_overLay".equals(id));
    }
    static boolean primaryAttribute(String name) {
        return "colorPrimary".equals(name) || "colorAccent".equals(name) || "colorControlActivated".equals(name);
    }
}
