package dev.rikumi.flymemod;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewParent;
import android.widget.ImageView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Reuse the native Settings action in the classic shade's notification inbox slot. */
final class CombinedSettingsButtonHooks {
    private static final String HEADER = "com.flyme.systemui.statusbar.phone.StatusBarHeaderView";
    private static final String QS = "com.flyme.systemui.qs.MzQSImpl";
    private static final String BUTTON = "com.flyme.systemui.statusbar.notification.CornerAlphaOptimizedImageButton";
    private final Consumer<Context> settings;
    private final BooleanSupplier enabled;
    private final BiConsumer<String, Throwable> log;
    private final Class<?> headerType;
    private final Field button, classicOwner, settingListener, qsHeader;
    private final Method classicMode;
    private final int settingsIcon, settingsDescription;
    private final Map<View, Original> originals = new WeakHashMap<>();

    CombinedSettingsButtonHooks(ClassLoader loader, Consumer<Context> settings, BooleanSupplier enabled,
                               BiConsumer<String, Throwable> log) throws ReflectiveOperationException {
        this.settings = settings;
        this.enabled = enabled;
        this.log = log;
        headerType = loader.loadClass(HEADER);
        button = headerType.getField("mQSNotificationFilterBtn");
        classicOwner = headerType.getField("mIsBelongToClassicPanel");
        settingListener = headerType.getField("mSettingClickListener");
        classicMode = headerType.getDeclaredMethod("isClassicalPanel");
        Class<?> qs = loader.loadClass(QS);
        qsHeader = qs.getField("mHeader");
        qs.getDeclaredMethod("lambda$onComponentCreated$1", View.class);
        qs.getDeclaredMethod("lambda$onComponentCreated$3", Boolean.class);
        headerType.getDeclaredMethod("updateEditButtonPosition");
        headerType.getDeclaredMethod("onFinishInflate");
        loader.loadClass(BUTTON).getDeclaredMethod("updateResource");
        settingsIcon = loader.loadClass("com.android.systemui.res.R$drawable")
                .getField("qs_tiles_setting_icon").getInt(null);
        settingsDescription = loader.loadClass("com.android.systemui.res.R$string")
                .getField("accessibility_quick_settings_settings").getInt(null);
    }

    void install(SignalHooks.Installer installer, Consumer<Method> deoptimize)
            throws ReflectiveOperationException {
        installer.hook(HEADER, "onFinishInflate", chain -> {
            Object result = chain.proceed();
            apply((View) chain.getThisObject(), false);
            return result;
        });
        installer.hook(HEADER, "updateEditButtonPosition", chain -> {
            Object result = chain.proceed();
            apply((View) chain.getThisObject(), true);
            return result;
        });
        installer.hook(QS, "lambda$onComponentCreated$3", chain -> {
            Object result = chain.proceed();
            apply((View) qsHeader.get(chain.getThisObject()), true);
            return result;
        }, Boolean.class);
        installer.hook(BUTTON, "updateResource", chain -> {
            Object result = chain.proceed();
            View header = findHeader((View) chain.getThisObject());
            if (header != null) apply(header, true);
            return result;
        });
        installer.hook(QS, "lambda$onComponentCreated$1", chain -> {
            View header = (View) qsHeader.get(chain.getThisObject());
            settings.accept(header.getContext());
            if (!shouldReplace(header)) return chain.proceed();
            ((View.OnClickListener) settingListener.get(header)).onClick((View) chain.getArg(0));
            return null;
        }, View.class);
        deoptimize.accept(headerType.getDeclaredMethod("updateResources"));
    }

    private boolean shouldReplace(View header) throws ReflectiveOperationException {
        return enabled.getAsBoolean() && classicOwner.getBoolean(header)
                && Boolean.TRUE.equals(classicMode.invoke(header));
    }

    private View findHeader(View view) {
        for (ViewParent parent = view.getParent(); parent instanceof View ancestor; parent = ancestor.getParent()) {
            if (headerType.isInstance(ancestor)) return ancestor;
        }
        return null;
    }

    private void apply(View header, boolean nativeImageWritten) throws ReflectiveOperationException {
        if (header == null) return;
        ImageView image = (ImageView) button.get(header);
        if (image == null) return;
        settings.accept(header.getContext());
        Original original = originals.get(header);
        if (original == null) {
            original = new Original(image);
            originals.put(header, original);
        }
        if (nativeImageWritten) original.drawable = image.getDrawable();
        boolean replace = shouldReplace(header);
        if (replace) {
            if (nativeImageWritten || !original.replaced) image.setImageResource(settingsIcon);
            image.setContentDescription(header.getResources().getString(settingsDescription));
        } else if (original.replaced) {
            if (!nativeImageWritten) image.setImageDrawable(original.drawable);
            image.setContentDescription(original.description);
        }
        original.replaced = replace;
    }

    void refresh() {
        for (View header : new ArrayList<>(originals.keySet())) {
            try { apply(header, false); }
            catch (ReflectiveOperationException | RuntimeException error) {
                log.accept("Cannot refresh combined shade Settings button", error);
            }
        }
    }

    private static final class Original {
        Drawable drawable;
        final CharSequence description;
        boolean replaced;
        Original(ImageView image) {
            drawable = image.getDrawable();
            description = image.getContentDescription();
        }
    }
}
