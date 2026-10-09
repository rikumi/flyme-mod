package dev.rikumi.flymemod;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Process;

/** Read-only settings bridge, accessible only to this app and supported system apps. */
public final class SettingsProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        int uid = Binder.getCallingUid();
        boolean allowed = uid == Process.myUid();
        for (String pkg : new String[]{"com.android.systemui", "com.meizu.flyme.launcher", "com.android.settings", "com.meizu.mstore", "com.meizu.flyme.weather", "com.android.packageinstaller"}) {
            try {
                allowed |= uid == getContext().getPackageManager().getPackageUid(pkg, 0);
            } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
                // An absent launcher must not prevent SystemUI reading settings.
            }
        }
        if (!allowed) {
            throw new SecurityException("Only supported system apps may read module settings");
        }
        if (!ModuleSettings.URI.equals(uri)) throw new IllegalArgumentException("Unknown URI");
        SharedPreferences prefs = ModuleSettings.preferences(getContext());
        java.util.List<String> columns = new java.util.ArrayList<>(java.util.Arrays.asList(new String[]{ModuleSettings.SCALE, ModuleSettings.LIGHT,
                ModuleSettings.DARKEN, ModuleSettings.WHITE_ACTIVE, ModuleSettings.NETWORK_DARK_SPINNER, ModuleSettings.HEADS_UP_WIDTH, ModuleSettings.NOTIFICATION_CORNERS,
                ModuleSettings.BLUR_ENABLED, ModuleSettings.BLUR_RADIUS, ModuleSettings.WALLPAPER_STARTUP_FIX,
                ModuleSettings.HIDE_GBOARD, ModuleSettings.RECENTS_SWIPE_UP_KILL, ModuleSettings.RECENTS_HIDE_NOT_RUNNING, ModuleSettings.FOLDER_PAGING, ModuleSettings.FOLDER_CENTER, ModuleSettings.FOLDER_RESTORE_COLOR, ModuleSettings.FOLDER_RADIUS_ENABLED, ModuleSettings.FOLDER_RADIUS,
                ModuleSettings.ORIGINAL_NOTIFICATION_ICONS, ModuleSettings.MONOCHROME_NOTIFICATION_ACTIONS,
                ModuleSettings.MERGE_DUAL_SIGNAL, ModuleSettings.SEPARATE_NETWORK_TYPE, ModuleSettings.HIDE_DISABLED_APPS, ModuleSettings.HIDE_LUNAR, ModuleSettings.BLOCK_STORE_SPLASH, ModuleSettings.BLOCK_WEATHER_RECOMMENDATIONS, ModuleSettings.SLIDER_ACTIVE_CORNERS, ModuleSettings.STORE_HIDE_FEATURED, ModuleSettings.STORE_HIDE_GAMES, ModuleSettings.STORE_HIDE_POPULAR, ModuleSettings.STORE_HIDE_COMMUNITY, ModuleSettings.STORE_HIDE_DAILY, ModuleSettings.BLOCK_WEATHER_ADS, ModuleSettings.WEATHER_DARK_BACKGROUND, ModuleSettings.STORE_HIDE_SEARCH_HOT, ModuleSettings.STORE_EMPTY_APPLICATION_PAGE, ModuleSettings.INSTALLER_SKIP_WARNINGS, ModuleSettings.STORE_DETAIL_HIDE_SAME_MODEL, ModuleSettings.STORE_DETAIL_HIDE_TOPICS, ModuleSettings.STORE_HIDE_SEARCH_RECOMMENDATIONS, ModuleSettings.STORE_DETAIL_HIDE_REVIEWS, ModuleSettings.COMBINED_PULL_ANIMATION, ModuleSettings.LIMIT_AOD_MOVEMENT, ModuleSettings.COMBINED_COLLAPSE_FIX, ModuleSettings.VOLUME_FIRST_FOUR, ModuleSettings.RESTORE_COLLAPSED_CARD_HEIGHT, ModuleSettings.QS_TRANSLATION_ORIGIN, ModuleSettings.SECONDARY_EXPANSION_FIX, ModuleSettings.COMBINED_EMPTY_SHADE_KEEP_OPEN}));
        for (String[] folder : ModuleSettings.MEDIA_FOLDERS) columns.add(folder[1]);
        columns.addAll(java.util.Arrays.asList(ModuleSettings.HIDE_STATUS_ICON_KEYS));
        columns.add(ModuleSettings.LOCK_CLOCK_FONT);
        columns.add(ModuleSettings.AOD_CLOCK_FONT);
        columns.add(ModuleSettings.STATUS_BAR_CLOCK_FONT);
        columns.add(ModuleSettings.CONTROL_CENTER_CLOCK_FONT);
        columns.add(ModuleSettings.LOCK_CLOCK_MONOSPACE);
        columns.add(ModuleSettings.AOD_CLOCK_MONOSPACE);
        columns.add(ModuleSettings.STATUS_BAR_CLOCK_MONOSPACE);
        columns.add(ModuleSettings.CONTROL_CENTER_CLOCK_MONOSPACE);
        columns.add(ModuleSettings.STATUS_BAR_CLOCK_WEIGHT);
        columns.add(ModuleSettings.CONTROL_CENTER_CLOCK_WEIGHT);
        columns.add(ModuleSettings.CONTROL_CENTER_CLOCK_DATE_UP);
        columns.add(ModuleSettings.CLOCK_FONT_WEIGHT);
        columns.add(ModuleSettings.LOCK_CLOCK_SPACING);
        columns.add(ModuleSettings.AOD_CLOCK_SPACING);
        columns.add(ModuleSettings.STATUS_BAR_CLOCK_SPACING);
        columns.add(ModuleSettings.CONTROL_CENTER_CLOCK_SPACING);
        columns.add(ModuleSettings.LOCK_CLOCK_SPACING_ENABLED);
        columns.add(ModuleSettings.AOD_CLOCK_SPACING_ENABLED);
        columns.add(ModuleSettings.STATUS_BAR_CLOCK_SPACING_ENABLED);
        columns.add(ModuleSettings.CONTROL_CENTER_CLOCK_SPACING_ENABLED);
        columns.add(ModuleSettings.HIDDEN_LAUNCHER_APPS);
        columns.add(ModuleSettings.CONTROL_CENTER_CLOCK_DATE_UP_DISTANCE);
        columns.add(ModuleSettings.SPLIT_NETWORK_CARD);
        columns.add(ModuleSettings.WHITE_ACTIVE_OPACITY);
        columns.add(ModuleSettings.CIRCLE_SMALL_TILES);
        columns.add(ModuleSettings.FOLD_IDLE_MEDIA);
        columns.add(ModuleSettings.NETWORK_SPLIT_STYLE);
        columns.add(ModuleSettings.OPTIMIZE_2X1_TEXT);
        columns.add(ModuleSettings.ANIMATED_MUTE_SLASH);
        columns.add(ModuleSettings.WIFI_LABEL);
        columns.add(ModuleSettings.LIGHT_OPACITY);
        columns.add(ModuleSettings.CONTROL_CENTER_BUTTONS_UP);
        columns.add(ModuleSettings.CONTROL_CENTER_BUTTONS_UP_DISTANCE);
        columns.add(ModuleSettings.NATIVE_NOTIFICATION_EXPANSION);
        columns.add(ModuleSettings.FOLDER_CLOSE_TARGET);
        columns.add(ModuleSettings.COLOROS_CONTOUR);
        MatrixCursor cursor = new MatrixCursor(columns.toArray(new String[0]));
        java.util.List<Object> values = new java.util.ArrayList<>(java.util.Arrays.asList(new Object[]{prefs.getBoolean(ModuleSettings.SCALE, false) ? 1 : 0,
                prefs.getBoolean(ModuleSettings.LIGHT, false) ? 1 : 0,
                prefs.getBoolean(ModuleSettings.DARKEN, false) ? 1 : 0,
                prefs.getBoolean(ModuleSettings.WHITE_ACTIVE, false) ? 1 : 0,
                prefs.getBoolean(ModuleSettings.NETWORK_DARK_SPINNER, false) ? 1 : 0,
                prefs.getBoolean(ModuleSettings.HEADS_UP_WIDTH, false) ? 1 : 0,
                prefs.getBoolean(ModuleSettings.NOTIFICATION_CORNERS, false) ? 1 : 0,
                prefs.getBoolean(ModuleSettings.BLUR_ENABLED, false) ? 1 : 0,
                prefs.getInt(ModuleSettings.BLUR_RADIUS, ModuleSettings.BLUR_DEFAULT),
                prefs.getBoolean(ModuleSettings.WALLPAPER_STARTUP_FIX, false) ? 1 : 0,
                prefs.getBoolean(ModuleSettings.HIDE_GBOARD, false) ? 1 : 0,
                prefs.getBoolean(ModuleSettings.RECENTS_SWIPE_UP_KILL, false) ? 1 : 0,
                prefs.getBoolean(ModuleSettings.RECENTS_HIDE_NOT_RUNNING, false) ? 1 : 0,
                prefs.getBoolean(ModuleSettings.FOLDER_PAGING, false) ? 1 : 0,
                prefs.getBoolean(ModuleSettings.FOLDER_CENTER, false) ? 1 : 0,
                prefs.getBoolean(ModuleSettings.FOLDER_RESTORE_COLOR, false) ? 1 : 0,
                prefs.getBoolean(ModuleSettings.FOLDER_RADIUS_ENABLED, false) ? 1 : 0,
                prefs.getInt(ModuleSettings.FOLDER_RADIUS, ModuleSettings.FOLDER_RADIUS_DEFAULT),
                prefs.getBoolean(ModuleSettings.ORIGINAL_NOTIFICATION_ICONS, false) ? 1 : 0,
                prefs.getBoolean(ModuleSettings.MONOCHROME_NOTIFICATION_ACTIONS, false) ? 1 : 0,
                prefs.getBoolean(ModuleSettings.MERGE_DUAL_SIGNAL, false) ? 1 : 0,
                prefs.getBoolean(ModuleSettings.SEPARATE_NETWORK_TYPE, false) ? 1 : 0,
                prefs.getBoolean(ModuleSettings.HIDE_DISABLED_APPS, false) ? 1 : 0,
                prefs.getBoolean(ModuleSettings.HIDE_LUNAR, false) ? 1 : 0,
                prefs.getBoolean(ModuleSettings.BLOCK_STORE_SPLASH, false) ? 1 : 0,
                prefs.getBoolean(ModuleSettings.BLOCK_WEATHER_RECOMMENDATIONS, false) ? 1 : 0,
                prefs.getBoolean(ModuleSettings.SLIDER_ACTIVE_CORNERS, false) ? 1 : 0,
                prefs.getBoolean(ModuleSettings.STORE_HIDE_FEATURED, false) ? 1 : 0,
                prefs.getBoolean(ModuleSettings.STORE_HIDE_GAMES, false) ? 1 : 0,
                prefs.getBoolean(ModuleSettings.STORE_HIDE_POPULAR, false) ? 1 : 0,
                prefs.getBoolean(ModuleSettings.STORE_HIDE_COMMUNITY, false) ? 1 : 0,
                prefs.getBoolean(ModuleSettings.STORE_HIDE_DAILY, false) ? 1 : 0,
                prefs.getBoolean(ModuleSettings.BLOCK_WEATHER_ADS, false) ? 1 : 0,
                prefs.getBoolean(ModuleSettings.WEATHER_DARK_BACKGROUND, false) ? 1 : 0,
                prefs.getBoolean(ModuleSettings.STORE_HIDE_SEARCH_HOT, false) ? 1 : 0,
                prefs.getBoolean(ModuleSettings.STORE_EMPTY_APPLICATION_PAGE, false) ? 1 : 0,
                prefs.getBoolean(ModuleSettings.INSTALLER_SKIP_WARNINGS, false) ? 1 : 0,
                prefs.getBoolean(ModuleSettings.STORE_DETAIL_HIDE_SAME_MODEL, false) ? 1 : 0,
                prefs.getBoolean(ModuleSettings.STORE_DETAIL_HIDE_TOPICS, false) ? 1 : 0,
                prefs.getBoolean(ModuleSettings.STORE_HIDE_SEARCH_RECOMMENDATIONS, false) ? 1 : 0,
                prefs.getBoolean(ModuleSettings.STORE_DETAIL_HIDE_REVIEWS, false) ? 1 : 0,
                prefs.getBoolean(ModuleSettings.COMBINED_PULL_ANIMATION, false) ? 1 : 0,
                prefs.getBoolean(ModuleSettings.LIMIT_AOD_MOVEMENT, false) ? 1 : 0,
                prefs.getBoolean(ModuleSettings.COMBINED_COLLAPSE_FIX, false) ? 1 : 0,
                prefs.getInt(ModuleSettings.VOLUME_FIRST_FOUR, 0),
                prefs.getBoolean(ModuleSettings.RESTORE_COLLAPSED_CARD_HEIGHT, false) ? 1 : 0,
                prefs.getBoolean(ModuleSettings.QS_TRANSLATION_ORIGIN, false) ? 1 : 0,
                prefs.getBoolean(ModuleSettings.SECONDARY_EXPANSION_FIX, false) ? 1 : 0,
                prefs.getBoolean(ModuleSettings.COMBINED_EMPTY_SHADE_KEEP_OPEN, false) ? 1 : 0}));
        for (String[] folder : ModuleSettings.MEDIA_FOLDERS) values.add(prefs.getBoolean(folder[1], false) ? 1 : 0);
        for (String key : ModuleSettings.HIDE_STATUS_ICON_KEYS) values.add(prefs.getBoolean(key, false) ? 1 : 0);
        values.add(prefs.getInt(ModuleSettings.LOCK_CLOCK_FONT, 0));
        values.add(prefs.getInt(ModuleSettings.AOD_CLOCK_FONT, 0));
        values.add(prefs.getInt(ModuleSettings.STATUS_BAR_CLOCK_FONT, 0));
        values.add(prefs.getInt(ModuleSettings.CONTROL_CENTER_CLOCK_FONT, 0));
        values.add(prefs.getBoolean(ModuleSettings.LOCK_CLOCK_MONOSPACE, false) ? 1 : 0);
        values.add(prefs.getBoolean(ModuleSettings.AOD_CLOCK_MONOSPACE, false) ? 1 : 0);
        values.add(prefs.getBoolean(ModuleSettings.STATUS_BAR_CLOCK_MONOSPACE, false) ? 1 : 0);
        values.add(prefs.getBoolean(ModuleSettings.CONTROL_CENTER_CLOCK_MONOSPACE, false) ? 1 : 0);
        values.add(prefs.getInt(ModuleSettings.STATUS_BAR_CLOCK_WEIGHT, 0));
        values.add(prefs.getInt(ModuleSettings.CONTROL_CENTER_CLOCK_WEIGHT, 0));
        values.add(prefs.getBoolean(ModuleSettings.CONTROL_CENTER_CLOCK_DATE_UP, false) ? 1 : 0);
        values.add(prefs.getInt(ModuleSettings.CLOCK_FONT_WEIGHT, 0));
        values.add(prefs.getInt(ModuleSettings.LOCK_CLOCK_SPACING, 0));
        values.add(prefs.getInt(ModuleSettings.AOD_CLOCK_SPACING, 0));
        values.add(prefs.getInt(ModuleSettings.STATUS_BAR_CLOCK_SPACING, 0));
        values.add(prefs.getInt(ModuleSettings.CONTROL_CENTER_CLOCK_SPACING, 0));
        values.add(prefs.getBoolean(ModuleSettings.LOCK_CLOCK_SPACING_ENABLED, false) ? 1 : 0);
        values.add(prefs.getBoolean(ModuleSettings.AOD_CLOCK_SPACING_ENABLED, false) ? 1 : 0);
        values.add(prefs.getBoolean(ModuleSettings.STATUS_BAR_CLOCK_SPACING_ENABLED, false) ? 1 : 0);
        values.add(prefs.getBoolean(ModuleSettings.CONTROL_CENTER_CLOCK_SPACING_ENABLED, false) ? 1 : 0);
        values.add(prefs.getBoolean(ModuleSettings.HIDDEN_LAUNCHER_APPS_ENABLED, true)
                ? String.join("\n", prefs.getStringSet(ModuleSettings.HIDDEN_LAUNCHER_APPS, java.util.Collections.emptySet())) : "");
        values.add(Math.max(0, Math.min(40, prefs.getInt(ModuleSettings.CONTROL_CENTER_CLOCK_DATE_UP_DISTANCE, 16))));
        values.add(ModuleSettings.networkSplitStyle(prefs) != 0 ? 1 : 0);
        // Retain the provider column for compatibility; radiant backgrounds use fixed 90% alpha.
        values.add(90);
        values.add(prefs.getBoolean(ModuleSettings.CIRCLE_SMALL_TILES, false) ? 1 : 0);
        values.add(prefs.getBoolean(ModuleSettings.FOLD_IDLE_MEDIA, false) ? 1 : 0);
        values.add(ModuleSettings.networkSplitStyle(prefs));
        values.add(prefs.getBoolean(ModuleSettings.OPTIMIZE_2X1_TEXT, false) ? 1 : 0);
        values.add(prefs.getBoolean(ModuleSettings.ANIMATED_MUTE_SLASH, false) ? 1 : 0);
        values.add(prefs.getBoolean(ModuleSettings.WIFI_LABEL, false) ? 1 : 0);
        values.add(Math.max(0, Math.min(ModuleSettings.LIGHT_OPACITY_MAX, prefs.getInt(ModuleSettings.LIGHT_OPACITY, ModuleSettings.LIGHT_OPACITY_DEFAULT))));
        values.add(prefs.getBoolean(ModuleSettings.CONTROL_CENTER_BUTTONS_UP, false) ? 1 : 0);
        values.add(Math.max(0, Math.min(40, prefs.getInt(ModuleSettings.CONTROL_CENTER_BUTTONS_UP_DISTANCE, 8))));
        values.add(prefs.getBoolean(ModuleSettings.NATIVE_NOTIFICATION_EXPANSION, false) ? 1 : 0);
        values.add(prefs.getBoolean(ModuleSettings.FOLDER_CLOSE_TARGET, false) ? 1 : 0);
        values.add(prefs.getBoolean(ModuleSettings.COLOROS_CONTOUR, false) ? 1 : 0);
        cursor.addRow(values);
        return cursor;
    }

    @Override public String getType(Uri uri) { return "vnd.android.cursor.item/vnd.flymemod.settings"; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
