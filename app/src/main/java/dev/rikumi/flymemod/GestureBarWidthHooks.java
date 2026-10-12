package dev.rikumi.flymemod;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/** Preserve Flyme's handle thickness, colors and pulse animation; change its visible length. */
final class GestureBarWidthHooks {
    private static final String MBACK = "com.flyme.systemui.navigationbar.MBackButtonView";
    private static final String HANDLE = "com.android.systemui.navigationbar.gestural.NavigationHandle";
    private final Consumer<Context> settings;
    private final BooleanSupplier enabled;
    private final IntSupplier widthDp;
    private final Field mbackRadius, mbackPaint, handleRadius, handlePaint, bottom, progress, shrink,
            shrinkWidth, additionalWidth, additionalHeight;
    private final Set<View> views = Collections.newSetFromMap(new WeakHashMap<>());

    GestureBarWidthHooks(ClassLoader loader, Consumer<Context> settings, BooleanSupplier enabled, IntSupplier widthDp)
            throws ReflectiveOperationException {
        this.settings=settings; this.enabled=enabled; this.widthDp=widthDp;
        Class<?> mback=loader.loadClass(MBACK), handle=loader.loadClass(HANDLE);
        mbackRadius=mback.getField("mRadius"); mbackPaint=mback.getField("mPaint");
        handleRadius=handle.getField("mRadius"); handlePaint=handle.getField("mPaint");
        bottom=handle.getField("mBottom"); progress=handle.getField("mPulseAnimationProgress");
        shrink=handle.getField("mShrink"); shrinkWidth=handle.getField("mShrinkWidthForAnimation");
        additionalWidth=handle.getField("mAdditionalWidthForAnimation");
        additionalHeight=handle.getField("mAdditionalHeightForAnimation");
        mback.getDeclaredMethod("onDraw",Canvas.class); handle.getDeclaredMethod("onDraw",Canvas.class);
    }

    void install(SignalHooks.Installer installer) {
        installer.hook(MBACK,"onDraw",chain -> {
            View view=(View)chain.getThisObject(); views.add(view); settings.accept(view.getContext());
            if(!enabled.getAsBoolean())return chain.proceed();
            float radius=mbackRadius.getInt(view), width=length(view), left=(view.getWidth()-width)/2f;
            // Match the native integer center calculation, including odd pixel heights.
            float top=view.getHeight()/2-radius;
            ((Canvas)chain.getArg(0)).drawRoundRect(left,top,left+width,top+radius*2f,
                    radius,radius,(Paint)mbackPaint.get(view));
            return null;
        },Canvas.class);
        installer.hook(HANDLE,"onDraw",chain -> {
            View view=(View)chain.getThisObject(); views.add(view); settings.accept(view.getContext());
            if(!enabled.getAsBoolean())return chain.proceed();
            float pulse=progress.getFloat(view);
            boolean shrinking=shrink.getBoolean(view);
            float extraHeight=shrinking?0f:additionalHeight.getFloat(view)*pulse;
            float extraWidth=(shrinking?-shrinkWidth.getFloat(view):additionalWidth.getFloat(view))*pulse;
            float height=handleRadius.getFloat(view)*2f+extraHeight;
            float top=view.getHeight()-bottom.getFloat(view)-height+extraHeight/2f;
            float width=length(view), left=(view.getWidth()-width)/2f;
            ((Canvas)chain.getArg(0)).drawRoundRect(left-extraWidth,top,left+width+extraWidth,top+height,
                    height/2f,height/2f,(Paint)handlePaint.get(view));
            return null;
        },Canvas.class);
    }
    private float length(View view) {
        return ModuleSettings.gestureBarWidth(widthDp.getAsInt())*view.getResources().getDisplayMetrics().density;
    }
    void refresh() { for(View view:views) if(view!=null)view.invalidate(); }
}
