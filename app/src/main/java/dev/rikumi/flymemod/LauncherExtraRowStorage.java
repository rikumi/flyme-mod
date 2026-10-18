package dev.rikumi.flymemod;

import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/** One-time, transactional snapshot. No live database file/WAL copying and no writes to the seed. */
final class LauncherExtraRowStorage {
    private LauncherExtraRowStorage() {}

    static void prepare(Context context, java.util.function.BiConsumer<String, Throwable> log) throws IOException {
        File data = context.getDataDir();
        File root = new File(data, LauncherExtraRowPolicy.DIRECTORY);
        try (RandomAccessFile lockFile = new RandomAccessFile(new File(data,
                LauncherExtraRowPolicy.DIRECTORY + ".lock"), "rw");
             FileLock ignored = lockFile.getChannel().lock()) {
            if (new File(root, "ready").isFile()) {
                try { repairPendingWidgets(context, data, root); }
                catch (RuntimeException error) { log.accept("Cannot repair pending widget configuration; retaining isolated layout", error); }
                return;
            }
            File staging = new File(data, LauncherExtraRowPolicy.DIRECTORY + ".initializing");
            removeTree(staging);
            mkdir(staging);
            ArrayList<Integer> allocated = new ArrayList<>();
            AppWidgetHost host = new AppWidgetHost(context, LauncherExtraRowPolicy.WIDGET_HOST);
            try {
                Map<Integer, WidgetCopy> widgets = new HashMap<>();
                File databases = new File(staging, "databases");
                mkdir(databases);
                File[] sources = new File(data, "databases").listFiles();
                if (sources != null) for (File source : sources) {
                    String name = source.getName();
                    if (!source.isFile() || name.endsWith("-wal") || name.endsWith("-shm")
                            || name.endsWith("-journal")) continue;
                    snapshot(source, new File(databases, name), context, host, widgets, allocated);
                }
                copyPreferences(new File(data, "shared_prefs"), new File(staging, "shared_prefs"));
                copyFiles(new File(data, "files"), new File(staging, "files"));
                if (!new File(staging, "ready").createNewFile()) throw new IOException("Cannot mark snapshot complete");
                if (!staging.renameTo(root)) throw new IOException("Cannot publish isolated launcher snapshot");
            } catch (IOException | RuntimeException error) {
                for (int id : allocated) host.deleteAppWidgetId(id);
                removeTree(staging);
                throw error;
            }
        }
    }

    private static void snapshot(File source, File target, Context context, AppWidgetHost host,
            Map<Integer, WidgetCopy> widgets, ArrayList<Integer> allocated) {
        // VACUUM INTO is available in this Android 16 launcher target. It takes a
        // consistent SQLite snapshot (including WAL, triggers, FTS and sequences)
        // without changing the attached source, unlike VACUUM without INTO.
        try (SQLiteDatabase connection = SQLiteDatabase.create(null)) {
            connection.execSQL("ATTACH DATABASE ? AS seed", new Object[]{source.getAbsolutePath()});
            connection.execSQL("VACUUM seed INTO ?", new Object[]{target.getAbsolutePath()});
        }
        try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(target, null)) {
            boolean favorites;
            try (Cursor cursor = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='favorites'", null)) {
                favorites = cursor.moveToFirst();
            }
            if (!favorites) return;
            db.beginTransaction();
            try {
                cloneWidgets(db, context, host, widgets, allocated);
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
        }
    }

    private static void cloneWidgets(SQLiteDatabase db, Context context, AppWidgetHost host,
            Map<Integer, WidgetCopy> widgets, ArrayList<Integer> allocated) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        try (Cursor cursor = db.rawQuery("SELECT DISTINCT appWidgetId FROM favorites WHERE itemType=4 AND appWidgetId>=0", null)) {
            while (cursor.moveToNext()) {
                int oldId = cursor.getInt(0);
                WidgetCopy copy = widgets.get(oldId);
                if (copy == null) {
                    int id = host.allocateAppWidgetId();
                    allocated.add(id);
                    AppWidgetProviderInfo provider = manager.getAppWidgetInfo(oldId);
                    String component = provider == null ? "" : provider.provider.flattenToString();
                    boolean bound = provider != null && manager.bindAppWidgetIdIfAllowed(id,
                            provider.getProfile(), provider.provider, LauncherWidgetClone.options(
                                    manager.getAppWidgetOptions(oldId), oldId, LauncherWidgetClone.isQuotes(component)));
                    // Use the native restore/configuration UI if a provider needs user configuration.
                    int restored = !bound ? 1 | 16 | 8
                            : provider.configure != null && !LauncherWidgetClone.isStock(component)
                                    && !LauncherWidgetClone.isQuotes(component) ? 4 : 0;
                    copy = new WidgetCopy(id, restored);
                    widgets.put(oldId, copy);
                }
                ContentValues values = new ContentValues();
                values.put("appWidgetId", copy.id);
                values.put("restored", copy.restored);
                db.update("favorites", values, "itemType=4 AND appWidgetId=?", new String[]{Integer.toString(oldId)});
            }
        }
    }

    /** Upgrade only unresolved widget configuration; never reseed or change layout coordinates. */
    private static void repairPendingWidgets(Context context, File data, File root) {
        File[] files = new File(root, "databases").listFiles();
        if (files == null) return;
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        for (File file : files) {
            if (!file.isFile() || file.getName().endsWith("-wal") || file.getName().endsWith("-shm")
                    || file.getName().endsWith("-journal")) continue;
            File seed = new File(new File(data, "databases"), file.getName());
            if (!seed.isFile()) continue;
            try (SQLiteDatabase db = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READWRITE);
                 SQLiteDatabase original = SQLiteDatabase.openDatabase(seed.getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
                try (Cursor table = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='favorites'", null)) {
                    if (!table.moveToFirst()) continue;
                }
                try (Cursor rows = db.rawQuery("SELECT _id,appWidgetId,appWidgetProvider,restored FROM favorites WHERE itemType=4 AND (restored & 4)!=0", null)) {
                    while (rows.moveToNext()) {
                        int id = rows.getInt(1);
                        String component = rows.getString(2);
                        if (!LauncherWidgetClone.isStock(component) && !LauncherWidgetClone.isQuotes(component)) continue;
                        AppWidgetProviderInfo target = manager.getAppWidgetInfo(id);
                        if (target == null || !component.equals(target.provider.flattenToString())) continue;
                        Bundle options = manager.getAppWidgetOptions(id);
                        if (LauncherWidgetClone.isQuotes(component) && !options.getBoolean(LauncherWidgetClone.COMPLETE)) {
                            try (Cursor source = original.rawQuery("SELECT appWidgetId FROM favorites WHERE _id=? AND itemType=4 AND appWidgetProvider=?",
                                    new String[]{rows.getString(0), component})) {
                                if (!source.moveToFirst()) continue;
                                int oldId = source.getInt(0);
                                AppWidgetProviderInfo from = manager.getAppWidgetInfo(oldId);
                                if (oldId == id || from == null || !from.provider.equals(target.provider)
                                        || !from.getProfile().equals(target.getProfile())) continue;
                                if (options.getInt(LauncherWidgetClone.SOURCE, -1) != oldId) {
                                    manager.updateAppWidgetOptions(id, LauncherWidgetClone.options(options, oldId, true));
                                }
                            }
                        }
                        ContentValues values = new ContentValues();
                        values.put("restored", rows.getInt(3) & ~4);
                        db.update("favorites", values, "_id=? AND appWidgetId=?", new String[]{rows.getString(0), Integer.toString(id)});
                    }
                }
            }
        }
    }

    private static void copyPreferences(File source, File target) throws IOException {
        mkdir(target);
        File[] files = source.listFiles();
        if (files == null) return;
        for (File file : files) if (file.isFile() && file.getName().endsWith(".xml")) {
            File backup = new File(file.getPath() + ".bak");
            Files.copy((backup.isFile() ? backup : file).toPath(), new File(target, file.getName()).toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void copyFiles(File source, File target) throws IOException {
        mkdir(target);
        File[] children = source.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (Files.isSymbolicLink(child.toPath())) continue;
            File destination = new File(target, child.getName());
            if (child.isDirectory()) copyFiles(child, destination);
            else if (child.isFile()) Files.copy(child.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static File path(Context context, String area, String name) {
        File directory = new File(new File(context.getDataDir(), LauncherExtraRowPolicy.DIRECTORY), area);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Cannot create isolated launcher " + area);
        return new File(directory, new File(name).getName());
    }

    private static void mkdir(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create " + directory);
    }
    private static void removeTree(File file) throws IOException {
        if (!file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) removeTree(child);
        if (!file.delete()) throw new IOException("Cannot remove incomplete snapshot " + file);
    }
    private record WidgetCopy(int id, int restored) {}
}
