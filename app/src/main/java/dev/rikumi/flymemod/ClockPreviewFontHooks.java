package dev.rikumi.flymemod;

import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The editor also renders the lockscreen/AOD cards on the personalization homepage. */
final class ClockPreviewFontHooks {
    private static final String CLOCK="com.flyme.systemuieditor.widget.view.clockview.EditorClockTextView";
    private static final String PERSPECTIVE="com.flyme.systemui.plugins.clocks.core.ui.view.PerspectiveDigitClockView";
    private static final Pattern WEIGHT=Pattern.compile("'?wght'?\\s+([0-9]+(?:\\.[0-9]+)?)");
    private final SharedPreferences preferences;
    private final Class<?> clock,perspective;
    private final ClockTypefaceLoader fonts=new ClockTypefaceLoader();
    private final Map<TextView,Typeface> originals=Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<TextView,String> variations=Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<TextView,Float> spacings=new WeakHashMap<>();
    private final Map<View,Integer> margins=new WeakHashMap<>();
    private final Set<TextView> views=Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private final ThreadLocal<Boolean> applying=ThreadLocal.withInitial(() -> false);
    private final BiConsumer<String,Throwable> log;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private boolean failed;
    private final SharedPreferences.OnSharedPreferenceChangeListener changes=(prefs,key) -> {
        if(key==null || ModuleSettings.LOCK_CLOCK_FONT.equals(key) || ModuleSettings.CLOCK_FONT_WEIGHT.equals(key)
                || ModuleSettings.LOCK_CLOCK_SPACING_ENABLED.equals(key) || ModuleSettings.LOCK_CLOCK_SPACING.equals(key))
            handler.post(() -> { synchronized(views) { for(TextView view:views) { view.requestLayout();view.invalidate(); } } });
    };

    ClockPreviewFontHooks(ClassLoader loader,SharedPreferences preferences,BiConsumer<String,Throwable> log) throws ClassNotFoundException {
        this.preferences=preferences;this.log=log;clock=loader.loadClass(CLOCK);perspective=loader.loadClass(PERSPECTIVE);
    }

    private boolean digit(TextView view) {
        if(perspective.isInstance(view))return true;
        for(View parent=view;parent!=null;parent=parent.getParent() instanceof View next?next:null)
            if(clock.isInstance(parent))return true;
        return false;
    }

    private Typeface replacement(TextView view,Typeface original) {
        if(failed)return null;
        try {
            int weight=preferences.getInt(ModuleSettings.CLOCK_FONT_WEIGHT,0);
            if(weight==0) {
                String variation=variations.get(view);
                Matcher match=variation==null?null:WEIGHT.matcher(variation);
                if(match!=null && match.find())weight=Math.round(Float.parseFloat(match.group(1)));
            }
            return fonts.resolve(view.getContext(),preferences.getInt(ModuleSettings.LOCK_CLOCK_FONT,0),weight,original);
        } catch(Exception | LinkageError error) {
            failed=true;log.accept("Cannot load editor clock preview font",error);return null;
        }
    }

    private void apply(TextView view) {
        if(!applying.get() && digit(view)) {
            views.add(view);
            if(perspective.isInstance(view))applyTextSpacing(view);
            Typeface original=originals.containsKey(view)?originals.get(view):view.getTypeface();
            Typeface replacement=replacement(view,original);
            if(replacement!=null)originals.putIfAbsent(view,original);
            Typeface target=replacement==null?original:replacement;
            if(view.getTypeface()!=target) {
                applying.set(true);
                try {
                    view.setTypeface(target);
                    if(replacement==null && variations.containsKey(view))view.setFontVariationSettings(variations.get(view));
                } finally{applying.remove();}
            }
            if(replacement==null)originals.remove(view);
        }
    }
    private Float selectedSpacing() {
        return preferences.getBoolean(ModuleSettings.LOCK_CLOCK_SPACING_ENABLED, false)
                ? Math.max(-10,Math.min(10,preferences.getInt(ModuleSettings.LOCK_CLOCK_SPACING,0)))*0.01f : null;
    }

    private void applyTextSpacing(TextView view) {
        Float desired=selectedSpacing();
        if(desired!=null)spacings.putIfAbsent(view,view.getLetterSpacing());
        Float target=desired!=null?desired:spacings.remove(view);
        if(target!=null && Float.compare(view.getLetterSpacing(),target)!=0) {
            applying.set(true);
            try {view.setLetterSpacing(target);} finally {applying.remove();}
        }
    }

    private float textSize(View view) {
        if(view instanceof TextView text){views.add(text);return text.getTextSize();}
        if(view instanceof ViewGroup group)
            for(int i=0;i<group.getChildCount();i++) {
                float size=textSize(group.getChildAt(i));
                if(size>0)return size;
            }
        return 0;
    }

    private void applySplitSpacing(ViewGroup group,Float spacing) {
        boolean horizontal=group instanceof LinearLayout row && row.getOrientation()==LinearLayout.HORIZONTAL;
        int visibleIndex=0;
        for(int i=0;i<group.getChildCount();i++) {
            View child=group.getChildAt(i);
            if(child instanceof ViewGroup nested)applySplitSpacing(nested,spacing);
            if(!(child.getLayoutParams() instanceof ViewGroup.MarginLayoutParams params))continue;
            float size=textSize(child);
            if(size<=0)continue;
            boolean visible=child.getVisibility()!=View.GONE;
            Integer original=margins.get(child);
            if(spacing!=null && original==null){original=params.getMarginStart();margins.put(child,original);}
            if(original!=null) {
                int extra=horizontal&&visible&&visibleIndex>0&&spacing!=null?Math.round(spacing*size):0;
                int target=original+extra;
                if(params.getMarginStart()!=target){params.setMarginStart(target);child.setLayoutParams(params);}
                if(spacing==null)margins.remove(child);
            }
            if(visible)visibleIndex++;
        }
    }
    void install(SignalHooks.Installer installer,Consumer<Method> deoptimize) {
        preferences.registerOnSharedPreferenceChangeListener(changes);
        installer.hook("android.widget.LinearLayout","onMeasure",chain -> {
            if(clock.isInstance(chain.getThisObject()))applySplitSpacing((ViewGroup)chain.getThisObject(),selectedSpacing());
            return chain.proceed();
        },int.class,int.class);
        installer.hook("android.widget.TextView","setLetterSpacing",chain -> {
            TextView view=(TextView)chain.getThisObject();
            if(applying.get() || !perspective.isInstance(view))return chain.proceed();
            views.add(view);
            Float desired=selectedSpacing();
            if(desired==null){spacings.remove(view);return chain.proceed();}
            spacings.put(view,(Float)chain.getArg(0));
            return chain.proceed(new Object[]{desired});
        },float.class);
        for(boolean styled:new boolean[]{false,true}) {
            installer.hook("android.widget.TextView","setTypeface",chain -> {
                TextView view=(TextView)chain.getThisObject();
                if(applying.get() || !digit(view))return chain.proceed();
                views.add(view);
                Typeface original=(Typeface)chain.getArg(0);
                if(styled)original=Typeface.create(original,(Integer)chain.getArg(1));
                originals.put(view,original);
                Typeface replacement=replacement(view,original);
                if(replacement==null)return chain.proceed();
                applying.set(true);
                try{return chain.proceed(styled?new Object[]{replacement,Typeface.NORMAL}:new Object[]{replacement});}
                finally{applying.remove();}
            },styled?new Class<?>[]{Typeface.class,int.class}:new Class<?>[]{Typeface.class});
        }
        installer.hook("android.widget.TextView","setFontVariationSettings",chain -> {
            TextView view=(TextView)chain.getThisObject();
            if(!applying.get() && digit(view)) { views.add(view);variations.put(view,(String)chain.getArg(0)); }
            return chain.proceed();
        },String.class);
        for(String owner:new String[]{"android.widget.TextView",PERSPECTIVE})
            installer.hook(owner,"onMeasure",chain -> {
                apply((TextView)chain.getThisObject());
                return chain.proceed();
            },int.class,int.class);
        for(Class<?> type:new Class<?>[]{clock,perspective,TextView.class,LinearLayout.class,View.class})
            for(Method method:type.getDeclaredMethods())
                if(type==clock ||method.getName().equals("measure") ||method.getName().equals("onMeasure")
                        ||method.getName().equals("setFontVariationSettings"))deoptimize.accept(method);
    }
}
