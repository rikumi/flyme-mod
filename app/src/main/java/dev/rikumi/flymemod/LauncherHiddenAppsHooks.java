package dev.rikumi.flymemod;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

final class LauncherHiddenAppsHooks {
    private static final String POPUP = "com.android.launcher3.popup.PopupContainerWithArrow";
    private static final String CONTAINER = "com.meizu.flyme.view.SystemShortcutContainer";
    private static final String PINCH = "com.meizu.flyme.launcher.touch.WorkspacePinchController";
    private final Consumer<Context> settings;
    private final BooleanSupplier menuEnabled, spreadEnabled;
    private final BiConsumer<String, Throwable> log;
    private final ThreadLocal<View> menuIcon = new ThreadLocal<>();
    private final Set<Object> opened = Collections.newSetFromMap(new WeakHashMap<>());

    LauncherHiddenAppsHooks(Consumer<Context> settings, BooleanSupplier menuEnabled,
            BooleanSupplier spreadEnabled, BiConsumer<String, Throwable> log) {
        this.settings = settings; this.menuEnabled = menuEnabled; this.spreadEnabled = spreadEnabled; this.log = log;
    }

    void install(ClassLoader loader, SignalHooks.Installer installer, Consumer<Method> deoptimize)
            throws ReflectiveOperationException {
        Class<?> launcher = loader.loadClass("com.android.launcher3.Launcher");
        launcher.getDeclaredMethod("onResume");
        installer.hook(launcher.getName(), "onResume", chain -> {
            Object result = chain.proceed();
            try { LauncherFolderMetrics.publishAsync((Context) chain.getThisObject(), loader, log); }
            catch (ReflectiveOperationException | RuntimeException error) { log.accept("Cannot read launcher folder layout", error); }
            return result;
        });
        Class<?> bubble = loader.loadClass("com.android.launcher3.BubbleTextView");
        Class<?> item = loader.loadClass("com.android.launcher3.model.data.ItemInfo");
        Method component = item.getMethod("getTargetComponent");
        Field itemType = item.getField("itemType"), user = item.getField("user");
        Method getLauncher = launcher.getMethod("getLauncher", Context.class);
        Method close = loader.loadClass("com.android.launcher3.AbstractFloatingView").getMethod("closeAllOpenViews",
                loader.loadClass("com.android.launcher3.views.ActivityContext"));
        Method populate = loader.loadClass(POPUP).getDeclaredMethod("populateAndShowRowsFlyme", launcher, bubble, int.class);
        Method shortcuts = loader.loadClass(CONTAINER).getDeclaredMethod("setShortcuts", LayoutInflater.class, List.class, boolean.class);
        installer.hook(POPUP, populate.getName(), chain -> {
            View icon = (View) chain.getArg(1);
            settings.accept(icon.getContext());
            Object info = icon.getTag();
            if (!menuEnabled.getAsBoolean() || info == null || !item.isInstance(info) || itemType.getInt(info) != 0
                    || component.invoke(info) == null || !android.os.Process.myUserHandle().equals(user.get(info))) return chain.proceed();
            View previous = menuIcon.get();
            menuIcon.set(icon);
            try { return chain.proceed(); }
            finally { if (previous == null) menuIcon.remove(); else menuIcon.set(previous); }
        }, launcher, bubble, int.class);
        installer.hook(CONTAINER, shortcuts.getName(), chain -> {
            Object result = chain.proceed();
            View icon = menuIcon.get();
            if (icon == null) return result;
            try {
                ViewGroup container = (ViewGroup) chain.getThisObject();
                Field normal = field(container.getClass(), "mNormalLayout");
                ViewGroup rows = (ViewGroup) normal.get(container);
                Context context = icon.getContext();
                int layout = context.getResources().getIdentifier("item_system_shortcut", "layout", context.getPackageName());
                if (layout == 0) throw new IllegalStateException("Missing Flyme item_system_shortcut");
                View row = ((LayoutInflater) chain.getArg(0)).inflate(layout, rows, false);
                TextView label = (TextView) row.getClass().getMethod("getTxtView").invoke(row);
                ImageView image = (ImageView) row.getClass().getMethod("getIcon").invoke(row);
                label.setText("隐藏");
                image.setBackground(LauncherIconEditor.flymeDrawable(context, "mc_ic_password_invisible").mutate());
                image.setBackgroundTintList(ColorStateList.valueOf(label.getCurrentTextColor()));
                row.setOnClickListener(v -> {
                    try {
                        settings.accept(context);
                        if (!menuEnabled.getAsBoolean()) return;
                        ComponentName target = (ComponentName) component.invoke(icon.getTag());
                        if (target == null) return;
                        close.invoke(null, getLauncher.invoke(null, context));
                        Bundle saved = context.getContentResolver().call(ModuleSettings.URI,
                                "hide_launcher_application", target.getPackageName(), null);
                        if (saved == null || !saved.getBoolean("success")) throw new IllegalStateException("保存隐藏应用失败");
                        Toast.makeText(context, "已隐藏", Toast.LENGTH_SHORT).show();
                    } catch (ReflectiveOperationException | RuntimeException error) {
                        log.accept("Cannot hide launcher application", error);
                        Toast.makeText(context, "隐藏失败：" + error.getMessage(), Toast.LENGTH_LONG).show();
                    }
                });
                rows.addView(row);
                @SuppressWarnings("unchecked") List<View> views = (List<View>) field(container.getClass(), "mShortcutViews").get(container);
                views.add(row);
            } catch (ReflectiveOperationException | RuntimeException error) { log.accept("Cannot add Flyme hide shortcut", error); }
            return result;
        }, LayoutInflater.class, List.class, boolean.class);
        deoptimize.accept(populate); deoptimize.accept(shortcuts);

        Class<?> pinch = loader.loadClass(PINCH), canvas = loader.loadClass(PINCH + "$MultiTouchObjectCanvasClass");
        Class<?> scaleClass = loader.loadClass("com.meizu.flyme.launcher.uioverrides.MultiTouchController$PositionAndScale");
        Class<?> pointClass = loader.loadClass("com.meizu.flyme.launcher.uioverrides.MultiTouchController$PointInfo");
        Field outer = field(canvas, "this$0"), contextField = field(pinch, "mContext");
        Method scale = scaleClass.getMethod("getScale"), fingers = pointClass.getMethod("getNumTouchPoints");
        Class<?> stateClass = loader.loadClass("com.android.launcher3.LauncherState");
        Object normalState = stateClass.getField("NORMAL").get(null);
        Class<?> flymeLauncher = loader.loadClass("com.meizu.flyme.launcher.MzLauncher");
        Method isNormal = flymeLauncher.getMethod("isInState", loader.loadClass("com.android.launcher3.statemanager.BaseState")),
                folder = flymeLauncher.getMethod("isFolderOpened"),
                animating = flymeLauncher.getMethod("isAnimatingLauncherContent");
        Method intercept = pinch.getDeclaredMethod("onControllerInterceptTouchEvent", MotionEvent.class);
        installer.hook(PINCH, intercept.getName(), chain -> {
            if (((MotionEvent) chain.getArg(0)).getActionMasked() == MotionEvent.ACTION_DOWN) opened.remove(chain.getThisObject());
            return chain.proceed();
        }, MotionEvent.class);
        Method change = canvas.getDeclaredMethod("setPositionAndScale", Object.class, scaleClass, pointClass);
        installer.hook(canvas.getName(), change.getName(), chain -> {
            Object controller = outer.get(chain.getThisObject());
            Context context = (Context) contextField.get(controller);
            settings.accept(context);
            if (!spreadEnabled.getAsBoolean() || controller.getClass() != pinch || !flymeLauncher.isInstance(context)
                    || !(Boolean) isNormal.invoke(context, normalState) || (Boolean) folder.invoke(context)
                    || (Boolean) animating.invoke(context) || (int) fingers.invoke(chain.getArg(2)) != 2) return chain.proceed();
            if (opened.contains(controller)) return true;
            // Match Flyme's existing logarithmic zoom threshold for a spread gesture.
            if (Math.round(Math.log((Float) scale.invoke(chain.getArg(1))) / Math.log(1.25)) <= 0) return chain.proceed();
            try {
                try { LauncherFolderMetrics.publish(context, loader); }
                catch (ReflectiveOperationException | RuntimeException error) { log.accept("Cannot read launcher folder layout", error); }
                context.startActivity(new Intent().setClassName("dev.rikumi.flymemod", "dev.rikumi.flymemod.MainActivity")
                        .putExtra(ModuleSettings.SETTINGS_PAGE_EXTRA, "hidden_apps")
                        .putExtra(ModuleSettings.LAUNCHER_ENTRY_EXTRA, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP
                                | Intent.FLAG_ACTIVITY_NO_ANIMATION),
                        android.app.ActivityOptions.makeCustomAnimation(context, 0, 0).toBundle());
                opened.add(controller);
                return true;
            } catch (RuntimeException error) { log.accept("Cannot open hidden applications from spread", error); return chain.proceed(); }
        }, Object.class, scaleClass, pointClass);
        deoptimize.accept(intercept); deoptimize.accept(change);
        log.accept("Flyme launcher hide shortcut and spread gesture hooks registered", null);
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field value = type.getDeclaredField(name); value.setAccessible(true); return value;
    }
}
