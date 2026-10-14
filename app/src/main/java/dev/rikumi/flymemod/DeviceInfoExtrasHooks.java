package dev.rikumi.flymemod;

import android.content.Context;
import android.content.SharedPreferences;
import android.system.Os;
import android.system.StructUtsname;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.function.BiConsumer;

/** Extra native preference rows for the verified Flyme Settings device-info pages. */
final class DeviceInfoExtrasHooks {
    private final SharedPreferences preferences;
    private final BiConsumer<String, Throwable> log;

    DeviceInfoExtrasHooks(SharedPreferences preferences, BiConsumer<String, Throwable> log) {
        this.preferences = preferences;
        this.log = log;
    }

    void install(ClassLoader loader, ThemeColorHooks.Installer installer) {
        installPage(loader, installer, "FlymeAndroidVersionFragment", "android_version",
                ModuleSettings.ANDROID_VERSION_KERNEL, "内核版本", true);
        installPage(loader, installer, "FlymeStatus", "battery_level",
                ModuleSettings.STATUS_BATTERY_CAPACITY, "电池容量", false);
    }

    private void installPage(ClassLoader loader, ThemeColorHooks.Installer installer,
            String pageName, String anchorKey, String setting, String title, boolean kernel) {
        try {
            Class<?> page = loader.loadClass("com.meizu.settings.deviceinfo." + pageName);
            Class<?> preference = loader.loadClass("androidx.preference.Preference");
            Class<?> group = loader.loadClass("androidx.preference.PreferenceGroup");
            Method find = page.getMethod("findPreference", CharSequence.class);
            Method contextMethod = page.getMethod("getContext");
            Field parentField = preference.getField("mParentGroup");
            Method add = group.getMethod("addPreference", preference);
            Method remove = group.getMethod("removePreference", preference);
            Method resume = page.getMethod("onResume");
            Method summary = preference.getMethod("setSummary", CharSequence.class);
            Method kernelFormat = kernel ? loader.loadClass("com.android.settingslib.DeviceInfoUtils")
                    .getMethod("formatKernelVersion", Context.class, StructUtsname.class) : null;
            Class<?> power = kernel ? null : loader.loadClass("com.android.internal.os.PowerProfile");
            String rowKey = "flymemod_" + setting;
            installer.hook(resume, chain -> {
                Object result = chain.proceed();
                if (!page.isInstance(chain.getThisObject())) return result;
                try {
                    Object owner = chain.getThisObject();
                    Object row = find.invoke(owner, rowKey);
                    boolean enabled = preferences.getBoolean(setting, false);
                    if (!enabled) {
                        if (row != null) remove.invoke(parentField.get(row), row);
                        return result;
                    }
                    Context context = (Context) contextMethod.invoke(owner);
                    if (context == null) return result;
                    CharSequence value;
                    if (kernel) value = (CharSequence) kernelFormat.invoke(null, context, Os.uname());
                    else {
                        Object profile = power.getConstructor(Context.class).newInstance(context);
                        double capacity = ((Number) power.getMethod("getBatteryCapacity").invoke(profile)).doubleValue();
                        value = Double.isFinite(capacity) && capacity > 0
                                ? Math.round(capacity) + " mAh" : "无法获取";
                    }
                    if (row == null) {
                        Object anchor = find.invoke(owner, anchorKey);
                        if (anchor == null) return result;
                        Object parent = parentField.get(anchor);
                        if (parent == null) return result;
                        row = preference.getConstructor(Context.class).newInstance(context);
                        preference.getField("mKey").set(row, rowKey);
                        preference.getMethod("setTitle", CharSequence.class).invoke(row, title);
                        preference.getField("mPersistent").setBoolean(row, false);
                        preference.getMethod("setSelectable", boolean.class).invoke(row, false);
                        // Match native read-only rows: avoid the enabled/nonselectable gray-title branch.
                        preference.getMethod("setEnabled", boolean.class).invoke(row, false);
                        preference.getMethod("setShouldDisableView", boolean.class).invoke(row, false);
                        // Reserve a gap after every native row; equal orders otherwise sort by title.
                        Method setOrder = preference.getMethod("setOrder", int.class);
                        int count = ((Number) group.getMethod("getPreferenceCount").invoke(parent)).intValue();
                        java.util.List<Object> siblings = new java.util.ArrayList<>();
                        for (int i = 0; i < count; i++)
                            siblings.add(group.getMethod("getPreference", int.class).invoke(parent, i));
                        for (int i = 0; i < siblings.size(); i++) setOrder.invoke(siblings.get(i), i * 2);
                        setOrder.invoke(row, siblings.indexOf(anchor) * 2 + 1);
                        summary.invoke(row, value);
                        add.invoke(parent, row);
                    } else summary.invoke(row, value);
                } catch (ReflectiveOperationException | RuntimeException error) {
                    log.accept("Cannot update Flyme device information: " + pageName, error);
                }
                return result;
            });
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log.accept("Cannot resolve Flyme device information: " + pageName, error);
        }
    }
}
