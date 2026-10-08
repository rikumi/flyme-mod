package com.rikumi.flymemod;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.PowerManager;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.MetricAffectingSpan;
import android.text.style.ReplacementSpan;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/** Flyme SystemUI 16260625 digit TextViews; bitmap/vector clocks stay native. */
final class ClockFontHooks {
    private static final String LOCK = "com.flyme.keyguard.clock.FlymeDigitalClockLockScreen";
    private static final String AOD = "com.flyme.aod.view.ClockView";
    private static final String PERSPECTIVE = "com.flyme.systemui.plugins.clocks.core.ui.view.PerspectiveDigitClockView";
    private final Consumer<Context> settings;
    private final IntSupplier lockFont, aodFont, weight;
    private final IntSupplier statusBarFont, controlCenterFont;
    private final IntSupplier statusBarWeight, controlCenterWeight;
    private final java.util.function.BooleanSupplier lockMonospace, aodMonospace, statusBarMonospace, controlCenterMonospace;
    private final IntSupplier lockSpacing, aodSpacing;
    private final IntSupplier statusBarSpacing;
    private final java.util.function.BooleanSupplier lockSpacingEnabled, aodSpacingEnabled;
    private final java.util.function.BooleanSupplier statusBarSpacingEnabled;
    private final java.util.function.BooleanSupplier controlCenterDateUpEnabled;
    private final IntSupplier controlCenterDateUpDistance;
    private final java.util.function.BooleanSupplier controlCenterButtonsUpEnabled;
    private final IntSupplier controlCenterButtonsUpDistance;
    private final Field[] headerButtons;
    private final java.util.function.BooleanSupplier hideLunar;
    private final BiConsumer<String, Throwable> log;
    private final Class<?> lockClass, aodClass, perspectiveClass, systemClockClass;
    private final Class<?> bottomClockClass;
    private final Field lockDigits, aodDigits;
    private final Field qsClock;
    private final Field qsBatteryPercent;
    private final Field qsControllerClock;
    private final Class<?> statusBarHeaderClass;
    private final Field headerClock;
    private final Field headerAmPm;
    private final Field headerDate;
    private final Field headerClockGroup;
    private final Field headerDateGroup;
    private final Field statusBarClock;
    private final Field statusBarBatteryPercent;
    private final Field keyguardBatteryPercent;
    private final Map<TextView, Typeface> originals = new WeakHashMap<>();
    private final Map<TextView, Boolean> customFontApplied = new WeakHashMap<>();
    private final Map<TextView, Float> originalSpacing = new WeakHashMap<>();
    private final Map<TextView, Float> originalHeaderTranslationY = new WeakHashMap<>();
    private final Map<TextView, Integer> originalMarginStart = new WeakHashMap<>();
    private final Map<View, Float> originalGroupTranslationY = new WeakHashMap<>();
    private final Map<View, Boolean> headerObservers = new WeakHashMap<>();
    private final Map<TextView, String> originalFontFeatures = new WeakHashMap<>();
    private final Map<TextView, String> knownClockKinds = new WeakHashMap<>();
    private final Map<TextView, String> spacingKinds = new WeakHashMap<>();
    private final Map<TextView, Boolean> spacingViews = new WeakHashMap<>();
    private final Map<TextView, Boolean> controlCenterHeaderClocks = new WeakHashMap<>();
    private final Map<TextView, Boolean> originalHeaderFontPadding = new WeakHashMap<>();
    private final CardIconOverflow headerClockOverflow = new CardIconOverflow();
    private final Map<TextView, Boolean> splitDigitViews = new WeakHashMap<>();
    private final Map<Integer, Typeface> fonts = new HashMap<>();
    private final ThreadLocal<Boolean> applying = ThreadLocal.withInitial(() -> false);
    private Context moduleContext;
    private boolean failed;

    ClockFontHooks(ClassLoader loader, Consumer<Context> settings, IntSupplier lockFont,
                   IntSupplier aodFont, IntSupplier statusBarFont, IntSupplier controlCenterFont,
                   IntSupplier statusBarWeight, IntSupplier controlCenterWeight,
                   java.util.function.BooleanSupplier lockMonospace,
                   java.util.function.BooleanSupplier aodMonospace,
                   java.util.function.BooleanSupplier statusBarMonospace,
                   java.util.function.BooleanSupplier controlCenterMonospace,
                   IntSupplier weight, IntSupplier lockSpacing,
                   IntSupplier aodSpacing, IntSupplier statusBarSpacing,
                   java.util.function.BooleanSupplier lockSpacingEnabled,
                   java.util.function.BooleanSupplier aodSpacingEnabled,
                   java.util.function.BooleanSupplier statusBarSpacingEnabled,
                   java.util.function.BooleanSupplier controlCenterDateUpEnabled,
                   IntSupplier controlCenterDateUpDistance,
                   java.util.function.BooleanSupplier controlCenterButtonsUpEnabled,
                   IntSupplier controlCenterButtonsUpDistance, java.util.function.BooleanSupplier hideLunar,
                   BiConsumer<String, Throwable> log)
            throws ReflectiveOperationException {
        this.settings = settings; this.lockFont = lockFont; this.aodFont = aodFont;
        this.statusBarFont = statusBarFont; this.controlCenterFont = controlCenterFont;
        this.statusBarWeight = statusBarWeight; this.controlCenterWeight = controlCenterWeight;
        this.lockMonospace = lockMonospace; this.aodMonospace = aodMonospace;
        this.statusBarMonospace = statusBarMonospace; this.controlCenterMonospace = controlCenterMonospace;
        this.weight = weight; this.lockSpacing = lockSpacing; this.aodSpacing = aodSpacing;
        this.statusBarSpacing = statusBarSpacing; this.log = log;
        this.lockSpacingEnabled = lockSpacingEnabled; this.aodSpacingEnabled = aodSpacingEnabled;
        this.statusBarSpacingEnabled = statusBarSpacingEnabled;
        this.controlCenterDateUpEnabled = controlCenterDateUpEnabled;
        this.controlCenterDateUpDistance = controlCenterDateUpDistance;
        this.controlCenterButtonsUpEnabled = controlCenterButtonsUpEnabled;
        this.controlCenterButtonsUpDistance = controlCenterButtonsUpDistance;
        this.hideLunar = hideLunar;
        lockClass = loader.loadClass(LOCK); aodClass = loader.loadClass(AOD);
        perspectiveClass = loader.loadClass(PERSPECTIVE);
        systemClockClass = loader.loadClass("com.android.systemui.statusbar.policy.Clock");
        bottomClockClass = loader.loadClass("com.flyme.systemui.statusbar.phone.MzTextClockAlignBottom");
        Class<?> qsStatusBar = loader.loadClass("com.flyme.systemui.controlcenter.qs.QSStatusBar");
        qsClock = qsStatusBar.getField("mClock");
        qsBatteryPercent = qsStatusBar.getField("mBatteryPercent");
        Class<?> qsStatusBarController = loader.loadClass("com.flyme.systemui.controlcenter.qs.QSStatusBarController");
        qsControllerClock = qsStatusBarController.getField("mClockView");
        Class<?> statusBarHeader = loader.loadClass("com.flyme.systemui.statusbar.phone.StatusBarHeaderView");
        statusBarHeaderClass = statusBarHeader;
        headerClock = statusBarHeader.getField("mTime");
        headerAmPm = statusBarHeader.getField("mAmPm");
        headerDate = statusBarHeader.getField("mDateExpanded");
        headerClockGroup = statusBarHeader.getField("mClockView");
        headerDateGroup = statusBarHeader.getField("mDateGroup");
        headerButtons = new Field[]{statusBarHeader.getField("mQsTilesSettingButton"),
                statusBarHeader.getField("mQSTilesEditButton"), statusBarHeader.getField("mQSNotificationFilterBtn")};
        Class<?> statusBarController = loader.loadClass("com.android.systemui.statusbar.phone.PhoneStatusBarViewController");
        statusBarBatteryPercent = statusBarController.getField("batteryPercent");
        statusBarClock = statusBarController.getField("clock");
        Class<?> keyguardStatusBar = loader.loadClass("com.android.systemui.statusbar.phone.KeyguardStatusBarView");
        keyguardBatteryPercent = keyguardStatusBar.getField("mBatteryTextView");
        lockDigits = lockClass.getField("mCacheTextViewArray");
        aodDigits = aodClass.getField("mCacheTextViewArray");
        if (!TextView[].class.equals(lockDigits.getType()) || !TextView[].class.equals(aodDigits.getType())
                || !TextView.class.isAssignableFrom(perspectiveClass)) {
            throw new NoSuchFieldException("Unexpected Flyme clock digit types");
        }
    }

    void install(SignalHooks.Installer installer) {
        installer.hook("com.android.systemui.statusbar.phone.PhoneStatusBarViewController", "onViewAttached", chain -> {
            Object result = chain.proceed();
            try {
                Object battery = statusBarBatteryPercent.get(chain.getThisObject());
                if (battery instanceof TextView view) applyStatusBarFont(view);
                Object clock = statusBarClock.get(chain.getThisObject());
                if (clock instanceof TextView view) {
                    knownClockKinds.put(view, "clock");
                    applyClockTypeface(view);
                    applyClockSpacing(view, false);
                }
            } catch (IllegalAccessException | RuntimeException error) {
                log.accept("Cannot apply status bar font and spacing", error);
            }
            return result;
        });
        installer.hook("com.flyme.systemui.controlcenter.qs.QSStatusBar", "onFinishInflate", chain -> {
            Object result = chain.proceed();
            applyControlCenterClock(chain.getThisObject());
            return result;
        });
        installer.hook("com.flyme.systemui.statusbar.phone.StatusBarHeaderView", "onFinishInflate", chain -> {
            Object result = chain.proceed();
            applyControlCenterHeaderClock(chain.getThisObject());
            return result;
        });
        installer.hook("com.flyme.systemui.statusbar.phone.StatusBarHeaderView", "refreshFont", chain -> {
            Object result = chain.proceed();
            applyControlCenterHeaderClock(chain.getThisObject());
            return result;
        });
        installer.hook("com.flyme.systemui.statusbar.phone.StatusBarHeaderView", "updateDateLocation", chain -> {
            Object result = chain.proceed();
            applyControlCenterHeaderClock(chain.getThisObject());
            return result;
        });
        installer.hook("android.widget.RelativeLayout", "onLayout", chain -> {
            Object result = chain.proceed();
            Object owner = chain.getThisObject();
            if (statusBarHeaderClass.isInstance(owner)) applyControlCenterHeaderBaseline(owner);
            return result;
        }, boolean.class, int.class, int.class, int.class, int.class);
        installer.hook("com.android.systemui.statusbar.phone.KeyguardStatusBarView", "onFinishInflate", chain -> {
            Object result = chain.proceed();
            try {
                Object battery = keyguardBatteryPercent.get(chain.getThisObject());
                if (battery instanceof TextView view) applyStatusBarFont(view);
            } catch (IllegalAccessException | RuntimeException error) {
                log.accept("Cannot apply status bar font to keyguard battery percentage", error);
            }
            return result;
        });
        installer.hook("com.flyme.systemui.controlcenter.qs.QSStatusBarController", "onViewAttached", chain -> {
            Object result = chain.proceed();
            applyControlCenterControllerClock(chain.getThisObject());
            return result;
        });
        // Both Flyme clock implementations obtain cached digits before adding them to
        // their layouts, so apply spacing at the factory boundary for each clock type.
        for (String clockClass : new String[]{LOCK, AOD}) {
            boolean aod = AOD.equals(clockClass);
            installer.hook(clockClass, "getTimeView", chain -> {
                View result = (View) chain.proceed();
                if (result instanceof TextView digit) {
                    splitDigitViews.put(digit, aod);
                    applySpacing(digit, aod, false);
                }
                return result;
            }, char.class, int.class);
            installer.hook(clockClass, "setClockImageList", chain -> {
                Object result = chain.proceed();
                // Flyme reuses cached digit views and rebuilds the hour/minute lists
                // on every time tick. Reapply after that update, when the final
                // TextViews are already stored in mCacheTextViewArray.
                applyCachedSpacing(chain.getThisObject(), aod);
                return result;
            }, CharSequence.class);
            installer.hook(clockClass, "addImageViewToClock", chain -> {
                Object result = chain.proceed();
                // The native method attaches cached digits after setClockImageList;
                // apply again here so the final, attached views keep the selected spacing.
                applyCachedSpacing(chain.getThisObject(), aod);
                return result;
            });
        }
        installer.hook("android.widget.TextView", "setTypeface", chain -> {
            TextView view = (TextView) chain.getThisObject();
            if (applying.get() || !isClockDigit(view)) return chain.proceed();
            Typeface nativeFont = (Typeface) chain.getArg(0);
            // Keep the system's last requested face so selecting Default restores it.
            originals.put(view, nativeFont);
            Typeface replacement = resolve(view, nativeFont);
            customFontApplied.put(view, replacement != null);
            if (replacement == null) {
                Object result = chain.proceed();
                applyColonCenter(view);
                return result;
            }
            applying.set(true);
            try {
                Object result = chain.proceed(new Object[]{replacement});
                applyColonCenter(view);
                return result;
            } finally { applying.remove(); }
        }, Typeface.class);
        installer.hook("android.widget.TextView", "setTypeface", chain -> {
            TextView view = (TextView) chain.getThisObject();
            if (applying.get() || !isClockDigit(view)) return chain.proceed();
            Typeface nativeFont = (Typeface) chain.getArg(0);
            int style = (Integer) chain.getArg(1);
            Typeface styled = Typeface.create(nativeFont, style);
            originals.put(view, styled);
            Typeface replacement = resolve(view, styled);
            customFontApplied.put(view, replacement != null);
            if (replacement == null) {
                Object result = chain.proceed();
                applyColonCenter(view);
                return result;
            }
            applying.set(true);
            try {
                // The replacement already carries the desired weight; prevent synthetic bold.
                Object result = chain.proceed(new Object[]{replacement, Typeface.NORMAL});
                applyColonCenter(view);
                return result;
            } finally { applying.remove(); }
        }, Typeface.class, int.class);
        installer.hook("android.widget.TextView", "onMeasure", chain -> {
            TextView view = (TextView) chain.getThisObject();
            if (!applying.get() && isClockDigit(view)) {
                Typeface original = originals.containsKey(view) ? originals.get(view) : view.getTypeface();
                Typeface replacement = resolve(view, original);
                customFontApplied.put(view, replacement != null);
                if (replacement != null) originals.putIfAbsent(view, original);
                Typeface target = replacement != null ? replacement : original;
                if (view.getTypeface() != target) {
                    applying.set(true);
                    try { view.setTypeface(target); }
                    finally { applying.remove(); }
                }
                if (replacement == null) originals.remove(view);
                if (isSpacingTarget(view)) applySpacing(view, isAodDigit(view), true);
                applyMonospace(view, false);
                applyColonCenter(view);
            }
            if (controlCenterHeaderClocks.containsKey(view)) {
                boolean padded = Boolean.TRUE.equals(customFontApplied.get(view))
                        || controlCenterMonospace.getAsBoolean();
                if (padded) {
                    originalHeaderFontPadding.putIfAbsent(view, view.getIncludeFontPadding());
                    if (!view.getIncludeFontPadding()) view.setIncludeFontPadding(true);
                } else {
                    Boolean original = originalHeaderFontPadding.remove(view);
                    if (original != null && view.getIncludeFontPadding() != original) {
                        view.setIncludeFontPadding(original);
                    }
                }
            }
            if (controlCenterHeaderClocks.containsKey(view)
                    && Boolean.TRUE.equals(customFontApplied.get(view))) {
                // MzTextClockAlignBottom centers a custom-painted string inside its canvas.
                // Measure that current string rather than retaining TextClock's reserved width.
                CharSequence text = view.getText();
                int desired = (int) Math.ceil(android.text.Layout.getDesiredWidth(
                        text == null ? "" : text, view.getPaint()))
                        + view.getCompoundPaddingLeft() + view.getCompoundPaddingRight();
                int widthSpec = (Integer) chain.getArg(0);
                if (View.MeasureSpec.getMode(widthSpec) != View.MeasureSpec.UNSPECIFIED) {
                    desired = Math.min(desired, View.MeasureSpec.getSize(widthSpec));
                }
                return chain.proceed(new Object[]{View.MeasureSpec.makeMeasureSpec(
                        Math.max(0, desired), View.MeasureSpec.EXACTLY), chain.getArg(1)});
            }
            return chain.proceed();
        }, int.class, int.class);
        installer.hook("com.flyme.systemui.statusbar.phone.MzTextClockAlignBottom", "onDraw", chain -> {
            TextView view = (TextView) chain.getThisObject();
            if (!controlCenterHeaderClocks.containsKey(view)
                    || (!Boolean.TRUE.equals(customFontApplied.get(view))
                    && !controlCenterMonospace.getAsBoolean())) return chain.proceed();
            android.text.Layout layout = view.getLayout();
            if (layout == null || layout.getLineCount() == 0) return chain.proceed();
            // Native drawing copies only typeface/size into an independent Paint and converts
            // the text to String. Draw the measured layout so 'tnum' and colon spans survive.
            view.getPaint().setColor(view.getCurrentTextColor());
            view.getPaint().drawableState = view.getDrawableState();
            float left = layout.getLineLeft(0);
            float textWidth = layout.getLineRight(0) - left;
            Canvas canvas = (Canvas) chain.getArg(0);
            int saved = canvas.save();
            try {
                canvas.translate((view.getWidth() - textWidth) / 2f - left,
                        view.getHeight() - 3f - layout.getLineBaseline(0));
                layout.draw(canvas);
            } finally { canvas.restoreToCount(saved); }
            return null;
        }, Canvas.class);
        installer.hook("android.widget.TextView", "setLetterSpacing", chain -> {
            TextView view = (TextView) chain.getThisObject();
            if (applying.get() || !isSpacingTarget(view)) return chain.proceed();
            if (splitDigitViews.containsKey(view)) return chain.proceed();
            float nativeSpacing = (Float) chain.getArg(0);
            originalSpacing.put(view, nativeSpacing);
            if (controlCenterHeaderClocks.containsKey(view)) {
                Object result = chain.proceed(new Object[]{nativeSpacing});
                applySpacing(view, false, false);
                return result;
            }
            Float desired = selectedSpacing(view, isAodDigit(view));
            return chain.proceed(new Object[]{desired == null ? nativeSpacing : desired});
        }, float.class);
        installer.hook("android.widget.TextView", "setText", chain -> {
            Object result = chain.proceed();
            TextView view = (TextView) chain.getThisObject();
            if (!applying.get() && isClockDigit(view)) {
                if (isSpacingTarget(view)) applySpacing(view, isAodDigit(view), false);
                applyColonCenter(view);
            }
            return result;
        }, CharSequence.class);
        installer.hook("android.widget.TextView", "setText", chain -> {
            Object result = chain.proceed();
            TextView view = (TextView) chain.getThisObject();
            if (!applying.get() && isClockDigit(view)) {
                if (isSpacingTarget(view)) applySpacing(view, isAodDigit(view), false);
                applyColonCenter(view);
            }
            return result;
        }, CharSequence.class, TextView.BufferType.class);
        installer.hook("android.widget.TextView", "setFontFeatureSettings", chain -> {
            TextView view = (TextView) chain.getThisObject();
            if (applying.get() || !isClockDigit(view)) return chain.proceed();
            String nativeFeatures = (String) chain.getArg(0);
            originalFontFeatures.put(view, nativeFeatures);
            return chain.proceed(new Object[]{fontFeatures(view, nativeFeatures)});
        }, String.class);
    }

    private void applySpacing(TextView view, boolean aod, boolean rememberOriginal) {
        spacingViews.put(view, aod);
        Float desiredSpacing = selectedSpacing(view, aod);
        if (controlCenterHeaderClocks.containsKey(view)) {
            originalSpacing.putIfAbsent(view, view.getLetterSpacing());
            float nativeSpacing = originalSpacing.get(view);
            if (Float.compare(view.getLetterSpacing(), nativeSpacing) != 0) {
                applying.set(true);
                try { view.setLetterSpacing(nativeSpacing); }
                finally { applying.remove(); }
            }
            applyCharacterSpacing(view, desiredSpacing);
            return;
        }
        if (splitDigitViews.containsKey(view)) {
            ViewParent parent = view.getParent();
            if (!(parent instanceof LinearLayout row) || row.getOrientation() != LinearLayout.HORIZONTAL) return;
            int digitIndex = 0;
            for (int i = 0; i < row.getChildCount(); i++) {
                View child = row.getChildAt(i);
                if (!(child instanceof TextView digit) || !splitDigitViews.containsKey(digit)) continue;
                ViewGroup.LayoutParams raw = digit.getLayoutParams();
                if (!(raw instanceof ViewGroup.MarginLayoutParams params)) continue;
                Integer original = originalMarginStart.get(digit);
                if (original == null) {
                    original = params.getMarginStart();
                    originalMarginStart.put(digit, original);
                }
                int extra = digitIndex == 0 || desiredSpacing == null ? 0
                        : Math.round(desiredSpacing * digit.getTextSize());
                int targetMargin = original + extra;
                if (params.getMarginStart() != targetMargin) {
                    params.setMarginStart(targetMargin);
                    digit.setLayoutParams(params);
                }
                digitIndex++;
            }
            return;
        }
        if (desiredSpacing != null) {
            originalSpacing.putIfAbsent(view, view.getLetterSpacing());
            if (Float.compare(view.getLetterSpacing(), desiredSpacing) != 0) {
                applying.set(true);
                try { view.setLetterSpacing(desiredSpacing); }
                finally { applying.remove(); }
            }
        } else {
            Float previousSpacing = originalSpacing.remove(view);
            if (previousSpacing != null && Float.compare(view.getLetterSpacing(), previousSpacing) != 0) {
                applying.set(true);
                try { view.setLetterSpacing(previousSpacing); }
                finally { applying.remove(); }
            }
        }
    }

    void refreshSpacing() {
        for (Map.Entry<TextView, Boolean> entry : new HashMap<>(spacingViews).entrySet()) {
            TextView view = entry.getKey();
            if (view != null) applySpacing(view, Boolean.TRUE.equals(entry.getValue()), false);
        }
    }

    void refresh() {
        for (Map.Entry<TextView, Typeface> entry : new HashMap<>(originals).entrySet()) {
            TextView view = entry.getKey();
            if (view == null) continue;
            Typeface original = entry.getValue();
            Typeface replacement = resolve(view, original);
            customFontApplied.put(view, replacement != null);
            Typeface target = replacement == null ? original : replacement;
            if (view.getTypeface() != target) {
                applying.set(true);
                try { view.setTypeface(target); }
                finally { applying.remove(); }
            }
            applyColonCenter(view);
            if (replacement == null) originals.remove(view);
        }
        for (TextView view : new java.util.ArrayList<>(originalFontFeatures.keySet())) {
            if (view != null) applyMonospace(view, false);
        }
        for (TextView view : new java.util.ArrayList<>(originalHeaderTranslationY.keySet())) {
            if (view != null) {
                Object header = findStatusBarHeader(view);
                if (header != null) applyControlCenterHeaderBaseline(header);
            }
        }
        refreshSpacing();
    }

    private void applyMonospace(TextView view, boolean rememberOriginal) {
        if (rememberOriginal || !originalFontFeatures.containsKey(view)) {
            originalFontFeatures.put(view, view.getFontFeatureSettings());
        }
        String target = fontFeatures(view, originalFontFeatures.get(view));
        if (!java.util.Objects.equals(view.getFontFeatureSettings(), target)) {
            applying.set(true);
            try { view.setFontFeatureSettings(target); }
            finally { applying.remove(); }
        }
    }

    private void applyClockTypeface(TextView view) {
        Typeface original = view.getTypeface();
        originals.putIfAbsent(view, original);
        Typeface replacement = resolve(view, originals.get(view));
        customFontApplied.put(view, replacement != null);
        if (replacement != null && view.getTypeface() != replacement) {
            applying.set(true);
            try { view.setTypeface(replacement); }
            finally { applying.remove(); }
        }
        applyMonospace(view, false);
        applyColonCenter(view);
    }

    void applyStatusBarFont(TextView view) {
        knownClockKinds.put(view, "status_extra");
        applyClockTypeface(view);
        applyClockSpacing(view, false);
    }

    private void applyClockSpacing(TextView view, boolean rememberOriginal) {
        spacingViews.put(view, false);
        applySpacing(view, false, rememberOriginal);
    }

    private void applyControlCenterClock(Object owner) {
        try {
            Object battery = qsBatteryPercent.get(owner);
            if (battery instanceof TextView view) applyStatusBarFont(view);
            Object clock = qsClock.get(owner);
            if (clock instanceof TextView view) {
                knownClockKinds.put(view, "mz_clock");
                spacingKinds.put(view, "clock");
                applyClockTypeface(view);
                applyClockSpacing(view, false);
            }
        } catch (IllegalAccessException | RuntimeException error) {
            log.accept("Cannot apply font and spacing to control center clock", error);
        }
    }

    private void applyControlCenterControllerClock(Object owner) {
        try {
            Object clock = qsControllerClock.get(owner);
            if (clock instanceof TextView view) {
                knownClockKinds.put(view, "mz_clock");
                spacingKinds.put(view, "clock");
                applyClockTypeface(view);
                applyClockSpacing(view, false);
            }
        } catch (IllegalAccessException | RuntimeException error) {
            log.accept("Cannot apply font and spacing to attached control center clock", error);
        }
    }

    private void applyControlCenterHeaderClock(Object owner) {
        try {
            if (owner instanceof View header && !headerObservers.containsKey(header)) {
                headerObservers.put(header, true);
                // TextClock ticks can relayout only its child LinearLayout. Correct after
                // that layout and any native animation writes, immediately before drawing.
                android.view.ViewTreeObserver.OnPreDrawListener observer = () -> {
                    applyControlCenterHeaderBaseline(owner);
                    return true;
                };
                header.getViewTreeObserver().addOnPreDrawListener(observer);
                header.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                    @Override public void onViewAttachedToWindow(View view) {
                        view.getViewTreeObserver().removeOnPreDrawListener(observer);
                        view.getViewTreeObserver().addOnPreDrawListener(observer);
                    }
                    @Override public void onViewDetachedFromWindow(View view) {
                        if (view.getViewTreeObserver().isAlive()) {
                            view.getViewTreeObserver().removeOnPreDrawListener(observer);
                        }
                    }
                });
            }
            Object clock = headerClock.get(owner);
            if (clock instanceof TextView view) {
                controlCenterHeaderClocks.put(view, true);
                originalHeaderTranslationY.putIfAbsent(view, view.getTranslationY());
                knownClockKinds.put(view, "mz_clock");
                applyClockTypeface(view);
                applyClockSpacing(view, false);
            }
            Object amPm = headerAmPm.get(owner);
            if (amPm instanceof TextView view) {
                controlCenterHeaderClocks.put(view, true);
                originalHeaderTranslationY.putIfAbsent(view, view.getTranslationY());
                knownClockKinds.put(view, "mz_clock");
                applyClockTypeface(view);
                applyClockSpacing(view, false);
            }
            scheduleControlCenterHeaderBaseline(owner);
        } catch (IllegalAccessException | RuntimeException error) {
            log.accept("Cannot apply font and spacing to control center header clock", error);
        }
    }

    private void scheduleControlCenterHeaderBaseline(Object owner) {
        if (owner instanceof View header) header.post(() -> applyControlCenterHeaderBaseline(owner));
    }

    private void applyControlCenterHeaderBaseline(Object owner) {
        try {
            if (!(owner instanceof View header)) return;
            settings.accept(header.getContext());
            float buttonOffset = controlCenterButtonsUpEnabled.getAsBoolean()
                    ? -controlCenterButtonsUpDistance.getAsInt() * header.getResources().getDisplayMetrics().density : 0f;
            for (Field field : headerButtons) {
                Object button = field.get(owner);
                if (button instanceof View view) applyGroupOffset(view, buttonOffset);
            }
            applyControlCenterDateOffset(header);
            TextView clock = (TextView) headerClock.get(owner);
            TextView amPm = (TextView) headerAmPm.get(owner);
            TextView date = (TextView) headerDate.get(owner);
            // SplitClockView is translated to the date baseline. Its horizontal parent
            // still has the pre-translation bounds, which otherwise cuts off the clock top.
            if (clock != null) headerClockOverflow.apply(clock, header,
                    Boolean.TRUE.equals(customFontApplied.get(clock)) || controlCenterMonospace.getAsBoolean()
                            || controlCenterDateUpEnabled.getAsBoolean() || hideLunar.getAsBoolean());
            if (clock == null || date == null || !date.isShown() || date.getHeight() == 0
                    || !clock.isLaidOut() || date.getBaseline() < 0 || clock.getBaseline() < 0) return;
            settings.accept(header.getContext());
            Float nativeClockTranslation = originalHeaderTranslationY.get(clock);
            if (nativeClockTranslation == null) {
                nativeClockTranslation = clock.getTranslationY();
                originalHeaderTranslationY.put(clock, nativeClockTranslation);
            }
            Float nativeAmPmTranslation = amPm == null ? null : originalHeaderTranslationY.get(amPm);
            if (amPm != null && nativeAmPmTranslation == null) {
                nativeAmPmTranslation = amPm.getTranslationY();
                originalHeaderTranslationY.put(amPm, nativeAmPmTranslation);
            }
            if (controlCenterFont.getAsInt() == 0 && !hideLunar.getAsBoolean()) {
                if (Float.compare(clock.getTranslationY(), nativeClockTranslation) != 0) {
                    clock.setTranslationY(nativeClockTranslation);
                }
                if (amPm != null && nativeAmPmTranslation != null
                        && Float.compare(amPm.getTranslationY(), nativeAmPmTranslation) != 0) {
                    amPm.setTranslationY(nativeAmPmTranslation);
                }
                View group = (View) headerClockGroup.get(owner);
                if (group != null) applyGroupOffset(group, dateUpOffset(header));
                return;
            }
            // Move the complete SplitClockView, keeping the glyphs inside its clipping
            // bounds. Its TextClock draws at height - 3px, not TextView.getBaseline().
            if (Float.compare(clock.getTranslationY(), nativeClockTranslation) != 0) {
                clock.setTranslationY(nativeClockTranslation);
            }
            if (amPm != null && nativeAmPmTranslation != null
                    && Float.compare(amPm.getTranslationY(), nativeAmPmTranslation) != 0) {
                amPm.setTranslationY(nativeAmPmTranslation);
            }
            View clockGroup = (View) headerClockGroup.get(owner);
            if (clockGroup == null) return;
            originalGroupTranslationY.putIfAbsent(clockGroup, clockGroup.getTranslationY());
            float clockBaseline = baselineInHeader(clock, header);
            float dateBaseline = baselineInHeader(date, header);
            float offset = dateBaseline - clockBaseline;
            if (Math.abs(offset) > 0.01f) {
                clockGroup.setTranslationY(clockGroup.getTranslationY() + offset);
            }
        } catch (IllegalAccessException | RuntimeException error) {
            log.accept("Cannot align control center clock baseline with date", error);
        }
    }

    private void applyControlCenterDateOffset(View header) throws IllegalAccessException {
        settings.accept(header.getContext());
        Object value = headerDateGroup.get(header);
        if (value instanceof View group) applyGroupOffset(group, dateUpOffset(header));
    }

    private float dateUpOffset(View header) {
        return controlCenterDateUpEnabled.getAsBoolean()
                ? -controlCenterDateUpDistance.getAsInt() * header.getResources().getDisplayMetrics().density : 0f;
    }

    private void applyGroupOffset(View group, float offset) {
        originalGroupTranslationY.putIfAbsent(group, group.getTranslationY());
        float target = originalGroupTranslationY.get(group) + offset;
        if (Math.abs(group.getTranslationY() - target) > 0.01f) group.setTranslationY(target);
    }

    private float baselineInHeader(TextView view, View header) {
        float result = bottomClockClass.isInstance(view) ? view.getHeight() - 3f : view.getBaseline();
        View current = view;
        while (current != header) {
            result += current.getY();
            if (!(current.getParent() instanceof View parent)) break;
            result -= parent.getScrollY();
            current = parent;
        }
        return result;
    }

    private Object findStatusBarHeader(TextView view) {
        ViewParent parent = view.getParent();
        for (int depth = 0; parent instanceof View current && depth < 16; depth++, parent = current.getParent()) {
            if (statusBarHeaderClass.isInstance(current)) return current;
        }
        return null;
    }

    private void applyCharacterSpacing(TextView view, Float spacingEm) {
        CharSequence current = view.getText();
        if (current == null || current.length() < 2) return;
        ClockTrackingSpan[] existing = current instanceof Spanned spanned
                ? spanned.getSpans(0, current.length(), ClockTrackingSpan.class) : new ClockTrackingSpan[0];
        int expectedSpans = 0;
        if (spacingEm != null && spacingEm != 0f) {
            int codePoints = Character.codePointCount(current, 0, current.length());
            expectedSpans = Math.max(0, codePoints - 1);
        }
        float expectedExtra = spacingEm == null ? 0f : spacingEm * view.getTextSize();
        if (existing.length == expectedSpans) {
            boolean matches = true;
            for (ClockTrackingSpan span : existing) {
                if (Math.abs(span.extraWidth - expectedExtra) > 0.01f) { matches = false; break; }
            }
            if (matches) return;
        }
        SpannableString text = new SpannableString(current);
        existing = text.getSpans(0, text.length(), ClockTrackingSpan.class);
        boolean changed = existing.length > 0;
        for (ClockTrackingSpan span : existing) text.removeSpan(span);
        if (spacingEm != null && spacingEm != 0f) {
            float extra = expectedExtra;
            int index = 0;
            while (index < text.length()) {
                int next = Character.offsetByCodePoints(text, index, 1);
                if (next < text.length()) {
                    text.setSpan(new ClockTrackingSpan(extra), index, next, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    changed = true;
                }
                index = next;
            }
        }
        if (changed) {
            applying.set(true);
            try { view.setText(text); }
            finally { applying.remove(); }
        }
    }

    private void applyColonCenter(TextView view) {
        CharSequence current = view.getText();
        if (current == null) return;
        ColonCenterSpan[] existing = current instanceof Spanned spanned
                ? spanned.getSpans(0, current.length(), ColonCenterSpan.class) : new ColonCenterSpan[0];
        boolean enabled = Boolean.TRUE.equals(customFontApplied.get(view));
        Rect digitBounds = new Rect();
        Rect colonBounds = new Rect();
        Paint paint = view.getPaint();
        paint.getTextBounds("8", 0, 1, digitBounds);
        paint.getTextBounds(":", 0, 1, colonBounds);
        float shift = ((digitBounds.top + digitBounds.bottom) - (colonBounds.top + colonBounds.bottom)) / 2f;
        int colonCount = 0;
        for (int i = 0; i < current.length(); i++) if (current.charAt(i) == ':') colonCount++;
        if ((!enabled && existing.length == 0) || (enabled && existing.length == colonCount
                && java.util.Arrays.stream(existing).allMatch(span -> Math.abs(span.verticalOffset - shift) < 0.25f))) return;
        SpannableString text = new SpannableString(current);
        existing = text.getSpans(0, text.length(), ColonCenterSpan.class);
        for (ColonCenterSpan span : existing) text.removeSpan(span);
        if (enabled) {
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == ':') text.setSpan(new ColonCenterSpan(shift), i, i + 1,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        applying.set(true);
        try { view.setText(text); }
        finally { applying.remove(); }
    }

    private static final class ColonCenterSpan extends MetricAffectingSpan {
        private final float verticalOffset;
        ColonCenterSpan(float verticalOffset) { this.verticalOffset = verticalOffset; }

        @Override public void updateMeasureState(TextPaint paint) {
            // Keep native glyph metrics and advance so the colon remains part of the
            // clock's original shaping and layout path.
        }

        @Override public void updateDrawState(TextPaint paint) {
            paint.baselineShift += Math.round(verticalOffset);
        }
    }

    private static final class ClockTrackingSpan extends ReplacementSpan {
        private final float extraWidth;

        ClockTrackingSpan(float extraWidth) { this.extraWidth = extraWidth; }

        @Override public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt metrics) {
            return Math.max(0, Math.round(paint.measureText(text, start, end) + extraWidth));
        }

        @Override public void draw(Canvas canvas, CharSequence text, int start, int end, float x,
                                   int top, int y, int bottom, Paint paint) {
            canvas.drawText(text, start, end, x, y, paint);
        }
    }

    private String fontFeatures(TextView view, String nativeFeatures) {
        boolean enabled;
        String clockId = clockKind(view);
        if ("mz_clock".equals(clockId)) enabled = controlCenterMonospace.getAsBoolean();
        else if ("clock".equals(clockId) || "status_extra".equals(clockId)) enabled = statusBarMonospace.getAsBoolean();
        else {
            ViewParent parent = view.getParent();
            boolean aod = false;
            for (int depth = 0; parent instanceof View && depth < 16; depth++, parent = parent.getParent()) {
                if (aodClass.isInstance(parent)) { aod = true; break; }
                if (lockClass.isInstance(parent)) break;
            }
            enabled = aod ? aodMonospace.getAsBoolean() : lockMonospace.getAsBoolean();
        }
        String features = nativeFeatures == null ? "" : nativeFeatures.trim();
        features = features.replaceAll("(?i)(?:,\\s*)?'tnum'", "").trim();
        if (enabled) return features.isEmpty() ? "'tnum'" : features + ", 'tnum'";
        return features.isEmpty() ? null : features;
    }

    private void applyCachedSpacing(Object clock, boolean aod) {
        try {
            TextView[] digits = (TextView[]) (aod ? aodDigits : lockDigits).get(clock);
            if (digits == null) return;
            for (TextView digit : digits) if (digit != null) {
                splitDigitViews.put(digit, aod);
                applySpacing(digit, aod, false);
            }
        } catch (IllegalAccessException | RuntimeException error) {
            log.accept("Cannot apply spacing to cached clock digits", error);
        }
    }

    private Float selectedSpacing(TextView view, boolean aod) {
        try {
            Context context = view.getContext();
            settings.accept(context);
            String kind = spacingKinds.getOrDefault(view, clockKind(view));
            if ("mz_clock".equals(kind)) return null;
            boolean enabled = "clock".equals(kind) || "status_extra".equals(kind) ? statusBarSpacingEnabled.getAsBoolean()
                    : aod ? aodSpacingEnabled.getAsBoolean() : lockSpacingEnabled.getAsBoolean();
            if (!enabled) return null;
            int step = "clock".equals(kind) || "status_extra".equals(kind) ? statusBarSpacing.getAsInt()
                    : aod ? aodSpacing.getAsInt() : lockSpacing.getAsInt();
            return Math.max(-10, Math.min(10, step)) * 0.01f;
        } catch (RuntimeException | LinkageError error) {
            fail(error);
            return null;
        }
    }

    private boolean isAodDigit(TextView view) {
        ViewParent parent = view.getParent();
        for (int depth = 0; parent instanceof View && depth < 16; depth++, parent = parent.getParent()) {
            if (aodClass.isInstance(parent)) return true;
            if (lockClass.isInstance(parent)) return false;
        }
        return false;
    }

    private boolean isSpacingTarget(TextView view) {
        if (splitDigitViews.containsKey(view)) return true;
        if ("status_extra".equals(knownClockKinds.get(view))) return true;
        if (!isClockDigit(view)) return false;
        if (systemClockClass.isInstance(view)) {
            String kind = clockKind(view);
            return "clock".equals(kind) || "mz_clock".equals(kind);
        }
        if (perspectiveClass.isInstance(view)) return true;
        ViewParent parent = view.getParent();
        for (int depth = 0; parent instanceof View && depth < 16; depth++, parent = parent.getParent()) {
            if (aodClass.isInstance(parent) || lockClass.isInstance(parent)) return true;
        }
        return false;
    }

    private boolean isClockDigit(TextView view) {
        if (knownClockKinds.containsKey(view)) return true;
        if (perspectiveClass.isInstance(view)) return true;
        if (systemClockClass.isInstance(view)) {
            String id = clockResourceName(view);
            return "clock".equals(id) || "mz_clock".equals(id);
        }
        ViewParent parent = view.getParent();
        try {
            for (int depth = 0; parent instanceof View && depth < 16; depth++, parent = parent.getParent()) {
                Field digits = lockClass.isInstance(parent) ? lockDigits : aodClass.isInstance(parent) ? aodDigits : null;
                if (digits != null) {
                    TextView[] children = (TextView[]) digits.get(parent);
                    if (children != null) for (TextView child : children) if (child == view) return true;
                    return false;
                }
            }
        } catch (IllegalAccessException | RuntimeException error) {
            fail(error);
        }
        return false;
    }

    private Typeface resolve(TextView view, Typeface nativeFont) {
        if (failed) return null;
        try {
            Context context = view.getContext();
            settings.accept(context);
            int family;
            String clockId = clockKind(view);
            if ("mz_clock".equals(clockId)) {
                family = controlCenterFont.getAsInt();
            } else if ("clock".equals(clockId) || "status_extra".equals(clockId)) {
                family = statusBarFont.getAsInt();
            } else {
                PowerManager power = context.getSystemService(PowerManager.class);
                family = power != null && !power.isInteractive() ? aodFont.getAsInt() : lockFont.getAsInt();
            }
            if (family <= 0 || family >= ModuleSettings.CLOCK_FONT_NAMES.length) return null;
            int selectedWeight = "mz_clock".equals(clockId) ? controlCenterWeight.getAsInt()
                    : ("clock".equals(clockId) || "status_extra".equals(clockId)) ? statusBarWeight.getAsInt() : weight.getAsInt();
            int fontWeight = selectedWeight == 0 ? nativeFont == null ? 400 : nativeFont.getWeight() : selectedWeight;
            fontWeight = Math.max(100, Math.min(900, fontWeight));
            if (family == 2) fontWeight = Math.max(200, Math.min(800, fontWeight));
            if (family == 3) fontWeight = Math.max(300, fontWeight);
            if (family == 4) fontWeight = fontWeight >= 600 ? 700 : fontWeight >= 500 ? 500 : 400;
            int key = family * 1000 + fontWeight;
            Typeface cached = fonts.get(key);
            if (cached != null) return cached;
            if (moduleContext == null) moduleContext = context.createPackageContext("com.rikumi.flymemod", 0);
            String asset;
            if (family == 4) {
                asset = fontWeight >= 600 ? "Lato-Bold.ttf" : fontWeight >= 500 ? "Lato-Medium.ttf" : "Lato-Regular.ttf";
            } else asset = ModuleSettings.CLOCK_FONT_NAMES[family] + ".ttf";
            Typeface.Builder builder = new Typeface.Builder(moduleContext.getAssets(), "clock_fonts/" + asset);
            if (family != 4) builder.setFontVariationSettings("'wght' " + fontWeight
                    + (family == 1 ? ", 'opsz' 32" : ""));
            Typeface result = builder.setWeight(fontWeight).setItalic(false).build();
            if (result == null) throw new IllegalStateException("Cannot load clock font " + asset);
            fonts.put(key, result);
            return result;
        } catch (Exception | LinkageError error) {
            fail(error);
            return null;
        }
    }

    private String clockResourceName(TextView view) {
        String known = knownClockKinds.get(view);
        if (known != null) return known;
        try { return view.getResources().getResourceEntryName(view.getId()); }
        catch (RuntimeException ignored) { return ""; }
    }

    private String clockKind(TextView view) {
        String known = knownClockKinds.get(view);
        if (known != null) return known;
        return systemClockClass.isInstance(view) ? clockResourceName(view) : null;
    }

    private void fail(Throwable error) {
        if (!failed) log.accept("Disabled clock font override after font loading failed", error);
        failed = true;
    }
}
