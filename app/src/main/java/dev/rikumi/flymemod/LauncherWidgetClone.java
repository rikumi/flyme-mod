package dev.rikumi.flymemod;

import android.os.Bundle;
import android.content.SharedPreferences;

final class LauncherWidgetClone {
    static final String SOURCE = "flymemod_clone_widget_from";
    static final String COMPLETE = "flymemod_clone_widget_complete";
    static boolean copyConfiguration(SharedPreferences prefs, int oldId, int id) {
        String target = Integer.toString(id);
        if (prefs.contains(target)) return true;
        // QuotesHelper.L() renders the provider's default when no per-ID entry exists.
        if (!prefs.contains(Integer.toString(oldId))) return true;
        String configuration = prefs.getString(Integer.toString(oldId), "");
        return configuration != null && !configuration.isEmpty()
                && prefs.edit().putString(target, configuration).commit();
    }
    static boolean isQuotes(String component) {
        return "com.meizu.assistant/com.meizu.assistant.feature.quotes.Quotes2x2WidgetProvider".equals(component)
                || "com.meizu.assistant/com.meizu.assistant.feature.quotes.Quotes4x2WidgetProvider".equals(component);
    }
    static boolean isStock(String component) {
        return "com.meizu.assistant/com.meizu.assistant.feature.stock.StockCard4x2WidgetProvider".equals(component);
    }
    static Bundle options(Bundle original, int source, boolean quotes) {
        Bundle result = new Bundle(original);
        // Do not replay an old drag/move request: the native provider deletes its source configuration.
        for (String key : new String[]{"flymeWidgetIdChanged", "flymeWidgetIdChangedComplete",
                "flymeWidgetOldId", "flymeWidgetNewId", "appWidgetRestoreCompleted", SOURCE, COMPLETE}) result.remove(key);
        if (quotes) result.putInt(SOURCE, source);
        return result;
    }
}
