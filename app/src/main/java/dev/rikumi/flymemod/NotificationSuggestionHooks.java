package dev.rikumi.flymemod;

import android.content.Context;
import android.view.View;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Filters assistant ranking suggestions before native smart-reply state selection. */
final class NotificationSuggestionHooks {
    private static final String ENTRY =
            "com.android.systemui.statusbar.notification.collection.NotificationEntry";
    private final Method getRow;
    private final Consumer<Context> settings;
    private final BooleanSupplier enabled;

    NotificationSuggestionHooks(ClassLoader loader, Consumer<Context> settings,
                                BooleanSupplier enabled) throws ReflectiveOperationException {
        this.settings = settings;
        this.enabled = enabled;
        Class<?> entry = loader.loadClass(ENTRY);
        getRow = entry.getMethod("getRow");
        entry.getDeclaredMethod("getSmartActions");
        entry.getDeclaredMethod("getSmartReplies");
    }

    void install(SignalHooks.Installer installer) {
        for (String method : new String[]{"getSmartActions", "getSmartReplies"}) {
            installer.hook(ENTRY, method, chain -> {
                View row = (View) getRow.invoke(chain.getThisObject());
                if (row != null) settings.accept(row.getContext());
                // Leave Notification.actions and RemoteInput choices provided by the app intact.
                // Native selection also computes phishing-action suppression from these lists.
                return enabled.getAsBoolean() ? Collections.emptyList() : chain.proceed();
            });
        }
    }
}
