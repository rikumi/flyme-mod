package dev.rikumi.flymemod;

import android.view.View;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

final class MergedShadeTranslation {
    private final Field[] views;
    private final Field notifications;
    private final Method notificationTranslation;
    private final float[] factors;

    MergedShadeTranslation(Class<?> type) throws ReflectiveOperationException {
        views = new Field[]{field(type, "mQSStatusBar"), field(type, "mHeader"), field(type, "mMzQSContainer")};
        factors = (float[]) field(type, "FIBONACCI_SEQUENCE").get(null);
        notifications = field(type, "mSharedNotificationContainerInteractor");
        notificationTranslation = notifications.getType().getMethod("setTranslationY", float.class);
    }

    void apply(Object owner, float translation) throws ReflectiveOperationException {
        int group = 0;
        for (Field field : views) {
            View view = (View) field.get(owner);
            if (view == null) continue;
            float target = translation * factors[Math.min(group++, factors.length - 1)];
            if (view.getTranslationY() != target) view.setTranslationY(target);
        }
        Object interactor = notifications.get(owner);
        if (interactor != null) notificationTranslation.invoke(interactor, translation * .6f);
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
