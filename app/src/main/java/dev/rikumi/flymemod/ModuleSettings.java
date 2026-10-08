package dev.rikumi.flymemod;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

public final class ModuleSettings {
    public static final String QS_TRANSLATION_ORIGIN = "control_center_translation_origin_fix";
    public static final String SECONDARY_EXPANSION_FIX = "combined_secondary_expansion_fix";
    public static final String COMBINED_EMPTY_SHADE_KEEP_OPEN = "combined_empty_shade_keep_open";
    public static final String RESTORE_COLLAPSED_CARD_HEIGHT = "restore_combined_collapsed_card_height";
    public static final String[][] MEDIA_FOLDERS = {
            {"Alarms", "media_folder_block_alarms"},
            {"Audiobooks", "media_folder_block_audiobooks"},
            {"Movies", "media_folder_block_movies"},
            {"Notifications", "media_folder_block_notifications"},
            {"Podcasts", "media_folder_block_podcasts"},
            {"Recordings", "media_folder_block_recordings"},
            {"Ringtones", "media_folder_block_ringtones"},
    };
    public static final String FILE = "control_center";
    public static final String VOLUME_FIRST_FOUR = "volume_first_four_steps";
    public static final String LIMIT_AOD_MOVEMENT = "limit_aod_movement";
    public static final String LOCK_CLOCK_FONT = "lock_clock_font";
    public static final String AOD_CLOCK_FONT = "aod_clock_font";
    public static final String STATUS_BAR_CLOCK_FONT = "status_bar_clock_font";
    public static final String CONTROL_CENTER_CLOCK_FONT = "control_center_clock_font";
    public static final String LOCK_CLOCK_MONOSPACE = "lock_clock_monospace";
    public static final String AOD_CLOCK_MONOSPACE = "aod_clock_monospace";
    public static final String STATUS_BAR_CLOCK_MONOSPACE = "status_bar_clock_monospace";
    public static final String CONTROL_CENTER_CLOCK_MONOSPACE = "control_center_clock_monospace";
    public static final String STATUS_BAR_CLOCK_WEIGHT = "status_bar_clock_weight";
    public static final String CONTROL_CENTER_CLOCK_WEIGHT = "control_center_clock_weight";
    public static final String CONTROL_CENTER_CLOCK_DATE_UP = "control_center_clock_date_up";
    public static final String CONTROL_CENTER_CLOCK_DATE_UP_DISTANCE = "control_center_clock_date_up_distance";
    public static final String CONTROL_CENTER_BUTTONS_UP = "control_center_buttons_up";
    public static final String CONTROL_CENTER_BUTTONS_UP_DISTANCE = "control_center_buttons_up_distance";
    public static final String CLOCK_FONT_WEIGHT = "clock_font_weight";
    public static final String LOCK_CLOCK_SPACING = "lock_clock_spacing";
    public static final String AOD_CLOCK_SPACING = "aod_clock_spacing";
    public static final String STATUS_BAR_CLOCK_SPACING = "status_bar_clock_spacing";
    public static final String CONTROL_CENTER_CLOCK_SPACING = "control_center_clock_spacing";
    public static final String LOCK_CLOCK_SPACING_ENABLED = "lock_clock_spacing_enabled";
    public static final String AOD_CLOCK_SPACING_ENABLED = "aod_clock_spacing_enabled";
    public static final String STATUS_BAR_CLOCK_SPACING_ENABLED = "status_bar_clock_spacing_enabled";
    public static final String CONTROL_CENTER_CLOCK_SPACING_ENABLED = "control_center_clock_spacing_enabled";
    public static final String[] CLOCK_FONT_NAMES = {"系统默认", "Inter", "Manrope", "Rubik", "Lato"};
    public static final String COMBINED_COLLAPSE_FIX = "combined_control_center_collapse_fix";
    public static final String COMBINED_PULL_ANIMATION = "combined_control_center_pull_animation";
    public static final String SCALE = "operation_area_scale";
    public static final String COLOROS_CONTOUR = "coloros_control_center_contour";
    public static final String LIGHT = "light_tile_background";
    public static final String LIGHT_OPACITY = "light_tile_background_opacity";
    public static final int LIGHT_OPACITY_DEFAULT = 15;
    public static final int LIGHT_OPACITY_MAX = 30;
    public static final String NETWORK_DARK_SPINNER = "network_tile_dark_spinner_fix";
    public static final String DARKEN = "dark_mode_background_darken";
    public static final String WHITE_ACTIVE = "white_active_tiles";
    public static final String WHITE_ACTIVE_OPACITY = "white_active_opacity";
    public static final String HEADS_UP_WIDTH = "wider_heads_up_notifications";
    public static final String NOTIFICATION_CORNERS = "larger_notification_corners";
    public static final String ORIGINAL_NOTIFICATION_ICONS = "original_third_party_notification_icons";
    public static final String NATIVE_NOTIFICATION_EXPANSION = "native_notification_pull_expansion";
    public static final String MONOCHROME_NOTIFICATION_ACTIONS = "monochrome_notification_actions";
    public static final String MERGE_DUAL_SIGNAL = "merge_dual_sim_signal";
    public static final String SEPARATE_NETWORK_TYPE = "separate_network_type";
    public static final String BLUR_ENABLED = "qs_blur_radius_enabled";
    public static final String BLUR_RADIUS = "qs_blur_radius";
    public static final String WALLPAPER_STARTUP_FIX = "wallpaper_startup_dim_fix";
    public static final String HIDE_GBOARD = "hide_gboard_enabled";
    public static final String HIDDEN_LAUNCHER_APPS = "hidden_launcher_apps";
    public static final String HIDDEN_LAUNCHER_APPS_ENABLED = "hidden_launcher_apps_enabled";
    public static final String RECENTS_SWIPE_UP_KILL = "recents_swipe_up_kill_enabled";
    public static final String RECENTS_HIDE_NOT_RUNNING = "recents_hide_not_running_enabled";
    public static final String FOLDER_PAGING = "folder_horizontal_paging";
    public static final String FOLDER_RESTORE_COLOR = "folder_restore_background_color";
    public static final String FOLDER_RADIUS_ENABLED = "folder_custom_radius_enabled";
    public static final String FOLDER_RADIUS = "folder_corner_radius_dp";
    public static final int FOLDER_RADIUS_DEFAULT = 12;
    public static final int FOLDER_RADIUS_MAX = 32;
    public static final String FOLDER_CENTER = "folder_vertical_center";
    public static final String FOLDER_CLOSE_TARGET = "folder_restore_close_animation_target";
    public static final String HOME_SWIPE_DAMPING = "home_swipe_damping_reduced";
    public static final String[] HIDE_STATUS_ICON_KEYS = {
            "status_icon_hide_zen", "status_icon_hide_vpn", "status_icon_hide_location",
            "status_icon_hide_bluetooth", "status_icon_hide_cast", "status_icon_hide_hotspot",
            "status_icon_hide_screen_record", "status_icon_hide_camera", "status_icon_hide_microphone"
    };
    public static final String HIDE_LUNAR = "hide_control_center_lunar_date";
    public static final String WIFI_LABEL = "control_center_wifi_label";
    public static final String OPTIMIZE_2X1_TEXT = "optimize_2x1_text";
    public static final String ANIMATED_MUTE_SLASH = "animated_mute_slash";
    public static final String SOLID_2X1_CARDS = "solid_2x1_cards";
    public static final String NETWORK_SPLIT_STYLE = "network_split_style";
    public static final String SPLIT_NETWORK_CARD = "split_network_card";
    public static final String FOLD_IDLE_MEDIA = "fold_idle_media";
    public static final String CIRCLE_SMALL_TILES = "circle_small_tiles";
    public static final String HIDE_DISABLED_APPS = "hide_disabled_apps_enabled";
    public static final String BLOCK_STORE_SPLASH = "block_store_splash_ads";
    public static final String BLOCK_WEATHER_RECOMMENDATIONS = "block_weather_recommendations";
    public static final String SLIDER_ACTIVE_CORNERS = "slider_active_top_corners";
    public static final String STORE_HIDE_FEATURED = "store_hide_featured";
    public static final String STORE_HIDE_GAMES = "store_hide_games";
    public static final String STORE_HIDE_POPULAR = "store_hide_popular";
    public static final String STORE_HIDE_COMMUNITY = "store_hide_community";
    public static final String STORE_HIDE_DAILY = "store_hide_daily";
    public static final String STORE_HIDE_SEARCH_HOT = "store_hide_search_hot";
    public static final String STORE_HIDE_SEARCH_RECOMMENDATIONS = "store_hide_search_recommendations";
    public static final String STORE_DETAIL_HIDE_SAME_MODEL = "store_detail_hide_same_model";
    public static final String STORE_DETAIL_HIDE_REVIEWS = "store_detail_hide_reviews";
    public static final String STORE_DETAIL_HIDE_TOPICS = "store_detail_hide_topics";
    public static final String STORE_EMPTY_APPLICATION_PAGE = "store_empty_application_page";
    public static final String INSTALLER_SKIP_WARNINGS = "installer_skip_warnings";
    public static final String BLOCK_WEATHER_ADS = "block_weather_ads";
    public static final String WEATHER_DARK_BACKGROUND = "weather_dark_background";
    public static final int BLUR_DEFAULT = 15;
    public static final int BLUR_MAX = 30;
    public static final int BLUR_SCALE = 10;
    public static final Uri URI = Uri.parse("content://dev.rikumi.flymemod.settings/control_center");
    private static final String CLOCK_SPACING_FORMAT_VERSION = "clock_spacing_format_version";

    private ModuleSettings() {}

    public static int networkSplitStyle(SharedPreferences preferences) {
        return preferences.getBoolean(SPLIT_NETWORK_CARD, false)
                ? (preferences.getBoolean(SOLID_2X1_CARDS, false) ? 2 : 1) : 0;
    }

    public static SharedPreferences preferences(Context context) {
        SharedPreferences preferences = context.createDeviceProtectedStorageContext()
                .getSharedPreferences(FILE, Context.MODE_PRIVATE);
        if (!preferences.contains(SOLID_2X1_CARDS)) {
            int oldStyle = Math.max(0, Math.min(2, preferences.getInt(NETWORK_SPLIT_STYLE,
                    preferences.getBoolean(SPLIT_NETWORK_CARD, false) ? 2 : 0)));
            preferences.edit().putBoolean(SPLIT_NETWORK_CARD, oldStyle != 0)
                    .putBoolean(SOLID_2X1_CARDS, oldStyle == 2).commit();
        }
        if (!preferences.contains("hidden_launcher_apps_migrated")) {
            java.util.Set<String> hidden = new java.util.HashSet<>(
                    preferences.getStringSet(HIDDEN_LAUNCHER_APPS, java.util.Collections.emptySet()));
            if (preferences.getBoolean(HIDE_GBOARD, false)) hidden.add("com.google.android.inputmethod.latin");
            preferences.edit().putStringSet(HIDDEN_LAUNCHER_APPS, hidden)
                    .putBoolean(HIDDEN_LAUNCHER_APPS_ENABLED, true)
                    .putBoolean("hidden_launcher_apps_migrated", true).commit();
        }
        int formatVersion = preferences.getInt(CLOCK_SPACING_FORMAT_VERSION, 0);
        if (formatVersion < 2) {
            SharedPreferences.Editor editor = preferences.edit();
            for (String key : new String[]{LOCK_CLOCK_SPACING, AOD_CLOCK_SPACING,
                    STATUS_BAR_CLOCK_SPACING, CONTROL_CENTER_CLOCK_SPACING}) {
                int oldTenths = preferences.getInt(key, 0);
                editor.putInt(key, Math.max(-10, Math.min(10, oldTenths * 10)));
            }
            editor.putInt(CLOCK_SPACING_FORMAT_VERSION, 2).commit();
            formatVersion = 2;
        }
        if (formatVersion < 3) {
            SharedPreferences.Editor editor = preferences.edit();
            if (preferences.getInt(LOCK_CLOCK_FONT, 0) == 0
                    && preferences.getInt(AOD_CLOCK_FONT, 0) != 0) {
                editor.putInt(LOCK_CLOCK_FONT, preferences.getInt(AOD_CLOCK_FONT, 0));
            }
            if (!preferences.getBoolean(LOCK_CLOCK_MONOSPACE, false)
                    && preferences.getBoolean(AOD_CLOCK_MONOSPACE, false)) {
                editor.putBoolean(LOCK_CLOCK_MONOSPACE, true);
            }
            if (!preferences.getBoolean(LOCK_CLOCK_SPACING_ENABLED, false)
                    && preferences.getBoolean(AOD_CLOCK_SPACING_ENABLED, false)) {
                editor.putBoolean(LOCK_CLOCK_SPACING_ENABLED, true);
                editor.putInt(LOCK_CLOCK_SPACING, preferences.getInt(AOD_CLOCK_SPACING, 0));
            }
            editor.putInt(CLOCK_SPACING_FORMAT_VERSION, 3).commit();
        }
        return preferences;
    }
}
