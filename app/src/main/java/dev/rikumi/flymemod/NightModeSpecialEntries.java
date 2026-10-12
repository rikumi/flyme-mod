package dev.rikumi.flymemod;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;

/** Reconcile the explicitly requested package with SDKStage's cached native AppInfo list. */
final class NightModeSpecialEntries {
    private static final String GOOGLE = "com.google.android.gms";
    private final Constructor<?> create, order;
    private final Method packageName, setPackage, setTitle, setNight, readNight;

    NightModeSpecialEntries(Class<?> manager, Class<?> appInfo) throws ReflectiveOperationException {
        create = appInfo.getDeclaredConstructor();
        packageName = appInfo.getDeclaredMethod("getPackageName");
        setPackage = appInfo.getDeclaredMethod("setPackageName", String.class);
        setTitle = appInfo.getDeclaredMethod("setTitle", CharSequence.class);
        setNight = appInfo.getDeclaredMethod("setNightModeEnabled", boolean.class);
        readNight = manager.getDeclaredMethod("j", String.class);
        order = Class.forName(manager.getName() + "$a", false, manager.getClassLoader()).getDeclaredConstructor(manager);
        order.setAccessible(true);
    }

    boolean ensure(Context context, Object manager, List<?> entries) throws ReflectiveOperationException {
        Set<String> explicit = new LinkedHashSet<>();
        explicit.add(GOOGLE);
        boolean changed = false;
        for (String pkg : explicit) changed |= ensurePackage(context, manager, entries, pkg);
        if (changed) {
            @SuppressWarnings("unchecked") List<Object> mutable = (List<Object>) entries;
            @SuppressWarnings("unchecked") Comparator<Object> comparator = (Comparator<Object>) order.newInstance(manager);
            mutable.sort(comparator);
        }
        return changed;
    }

    @SuppressWarnings("unchecked")
    private boolean ensurePackage(Context context, Object manager, List<?> entries, String pkg) throws ReflectiveOperationException {
        for (Object item : entries) if (pkg.equals(packageName.invoke(item))) return false;
        ApplicationInfo info;
        try { info = context.getPackageManager().getApplicationInfo(pkg, 0); }
        catch (PackageManager.NameNotFoundException absent) { return false; }
        CharSequence title = info.loadLabel(context.getPackageManager());
        if (title == null || title.length() == 0) return false;
        Object entry = create.newInstance();
        setPackage.invoke(entry, pkg);
        setTitle.invoke(entry, title.toString());
        setNight.invoke(entry, readNight.invoke(manager, pkg));
        ((List<Object>) entries).add(entry);
        return true;
    }
}
