package dev.rikumi.flymemod;

/** Verified Flyme 12.1 home, native application lists and details; no sibling activities. */
final class AppManagementBackgroundPolicy {
    // surface_default and surface_container_lowest, including ROM v888 variants.
    static final int[] SHADES = {0x0b0b0c, 0x232323, 0x1c1c1e, 0x2d2d2d};

    static boolean page(String pkg, String activity, String fragment) {
        if ("com.meizu.safe".equals(pkg))
            return "com.meizu.safe.ui.app.AppManagementActivity".equals(activity);
        if (!"com.android.settings".equals(pkg)) return false;
        if ("com.meizu.settings.MzSettingsActivity$AllAppsActivity".equals(activity)
                || "com.meizu.settings.MzSettingsActivity$DefaultAppsActivity".equals(activity)
                || "com.android.settings.applications.InstalledAppDetailsTop".equals(activity)) return true;
        if (!"com.android.settings.SubSettings".equals(activity)) return false;
        return "com.meizu.settings.applications.ManageApplications".equals(fragment)
                || "com.meizu.settings.applications.InstalledAppDetails".equals(fragment)
                || "com.meizu.settings.applications.InstalledAppDetailsPermissionsDetails".equals(fragment);
    }
}
