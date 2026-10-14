package dev.rikumi.flymemod;

import android.content.SharedPreferences;

/** First defaults only: absent switches stay disabled; explicit choices and numeric values are retained. */
final class DesktopFeatureDefaults {
    static synchronized void initialize(SharedPreferences preferences) {
        SharedPreferences.Editor editor=null;
        for(String key:new String[]{ModuleSettings.RECENTS_CORNER_ENABLED,ModuleSettings.SYSTEM_ICON_PACK,
                ModuleSettings.FIX_ICON_PACK_PREVIEW,ModuleSettings.BACK_GESTURE_REMOVE_SMART,
                ModuleSettings.BACK_GESTURE_DARK_BACKGROUND,ModuleSettings.GESTURE_BAR_WIDTH_ENABLED,
                ModuleSettings.FIX_ZYGOTE_RESTART_UNLOCK,ModuleSettings.REMOVE_FINGERPRINT_ANIMATION}) {
            if(preferences.contains(key)) continue;
            if(editor==null) editor=preferences.edit();
            editor.putBoolean(key,false);
        }
        if(editor!=null) editor.commit();
    }
}
