"""Execute the full production State against a host view tree, including both rows."""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
source = (root / "app/src/main/java/dev/rikumi/flymemod/ChargingInfoHooks.java").read_text()


def declaration(signature):
    start = source.index("{", source.index(signature))
    depth, end = 1, start + 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return signature + source[start:end]


java = r'''
package dev.rikumi.flymemod;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.*;
import android.graphics.Typeface;
public class ViewLifecycleTest {
    static class SystemClock {static long now=100;static long elapsedRealtime(){return now;}}
    static class ViewTreeObserver {
        int adds,removes;boolean isAlive(){return true;}
        void addOnPreDrawListener(Object x){adds++;}void removeOnPreDrawListener(Object x){removes++;}
    }
    static class Context {}
    static class Resources {
        static class Metrics {float density=1f,scaledDensity=1f;}
        Metrics metrics=new Metrics();Metrics getDisplayMetrics(){return metrics;}
        int getIdentifier(String a,String b,String c){return a.equals("statusIcons")?10:0;}int getDimensionPixelSize(int id){return 0;}
    }
    public static class View extends android.view.ViewGroup {
        static final int GONE=8,VISIBLE=0,LAYOUT_DIRECTION_RTL=1,IMPORTANT_FOR_ACCESSIBILITY_NO=2;
        public boolean mCharging=true,mBypassCharging;
        public TextView mBatteryPercentView;
        public int mFilterColor=0xffabcdef;
        boolean attached=true,shown=true;int id,visibility=VISIBLE,height=60;
        int getHeight(){return height;}Object parent,tag;
        ViewTreeObserver observer=new ViewTreeObserver();Context context=new Context();Resources resources=new Resources();
        android.view.ViewGroup.MarginLayoutParams params=new android.view.ViewGroup.MarginLayoutParams();
        Object getParent(){return parent;}Context getContext(){return context;}Resources getResources(){return resources;}
        ViewTreeObserver getViewTreeObserver(){return observer;}
        boolean isAttachedToWindow(){return attached;}boolean isShown(){return shown;}
        void setTag(Object tag){this.tag=tag;}Object getTag(){return tag;}void setVisibility(int v){visibility=v;}
        void setImportantForAccessibility(int v){}
        android.view.ViewGroup.MarginLayoutParams getLayoutParams(){return params;}
        void setLayoutParams(android.view.ViewGroup.MarginLayoutParams value){params=value;}
    }
    static class TextView extends View {
        String text="";boolean singleLine=true,fontPadding=true;int maxLines=1,gravity,color;
        float size=32f,spacingAdd,spacingMultiplier;Typeface typeface=new Typeface("native",400,false);
        static class Paint {boolean bold;void setFakeBoldText(boolean b){bold=b;}}
        Paint paint=new Paint();Paint getPaint(){return paint;}
        TextView(Context context){this.context=context;}
        CharSequence getText(){return text;}CharSequence received;void setText(CharSequence value){received=value;text=value.toString();}
        void setSingleLine(boolean value){singleLine=value;}void setMaxLines(int value){maxLines=value;}
        void setGravity(int value){gravity=value;}void setIncludeFontPadding(boolean value){fontPadding=value;}
        boolean getIncludeFontPadding(){return fontPadding;}float getTextSize(){return size;}
        void setLineSpacing(float add,float mult){spacingAdd=add;spacingMultiplier=mult;}
        int sizeUnit;void setTextSize(int unit,float value){sizeUnit=unit;size=unit==2?value*resources.metrics.scaledDensity:value;}
        int getLineHeight(){return (int)Math.ceil(size*1.38f);}
        Typeface getTypeface(){return typeface;}void setTypeface(Typeface value){typeface=value;}int getCurrentTextColor(){return color;}void setTextColor(int value){color=value;}
    }
    static class Gravity {static int CENTER=17,CENTER_VERTICAL=16,CENTER_HORIZONTAL=1;}
    static class LinearLayout extends View {
        static final int VERTICAL=1;int orientation,gravity,direction;List<View> children=new ArrayList<>();
        LinearLayout(Context context){this.context=context;}
        void setOrientation(int v){orientation=v;}void setGravity(int v){gravity=v;}
        int getLayoutDirection(){return direction;}int indexOfChild(View child){return children.indexOf(child);}
        int getChildCount(){return children.size();}View findViewById(int id){return children.stream().filter(v->v.id==id).findFirst().orElse(null);}
        void addView(View child,LayoutParams params){addView(child,children.size(),params);}
        void addView(View child,int index,LayoutParams params){children.add(index,child);child.parent=this;child.params=params;}
        @Override public void removeView(Object child){super.removeView(child);children.remove(child);((View)child).parent=null;}
        static class LayoutParams extends android.view.ViewGroup.MarginLayoutParams {
            static final int WRAP_CONTENT=-2,MATCH_PARENT=-1;int width,height,gravity;float weight;
            LayoutParams(int width,int height){this.width=width;this.height=height;}
        }
    }
    CONFIGURE_MODE
    CONFIGURE_MARGINS
    BATTERY_INDEX
    int receiverCalls,reconciles,selectedMode=2,fontCalls;boolean screenOn=true,disconnected;
    ChargingInfo.Sample sample=new ChargingInfo.Sample(17463000,1290000);long sampledAt=100;
    java.util.function.IntSupplier mode=()->selectedMode;
    java.util.function.Consumer<Context> settings=x->{};
    java.util.function.Consumer<String> trace=x->{};
    java.util.function.BiConsumer<String,Throwable> log=(x,e)->{throw new AssertionError(x,e);};
    java.util.function.BiConsumer<TextView,TextView> font=(target,reference)->{fontCalls++;target.typeface=reference.typeface;target.size=reference.size;};
    Field charging,bypass,percent,tint;
    ViewLifecycleTest() throws Exception {
        charging=View.class.getField("mCharging");bypass=View.class.getField("mBypassCharging");
        percent=View.class.getField("mBatteryPercentView");tint=View.class.getField("mFilterColor");
    }
    void receivers(){receiverCalls++;}void reconcile(){reconciles++;}
    STATE
    static int checks;static void check(boolean value){checks++;if(!value)throw new AssertionError("check "+checks);}
    void run(){
        Context context=new Context();LinearLayout parent=new LinearLayout(context);View battery=new View();
        TextView reference=new TextView(context);reference.params.setMarginStart(3);battery.mBatteryPercentView=reference;
        parent.addView(new View(),new LinearLayout.LayoutParams(-2,-1));
        LinearLayout icons=new LinearLayout(context);icons.id=10;parent.addView(icons,new LinearLayout.LayoutParams(0,-1));
        parent.addView(battery,new LinearLayout.LayoutParams(-2,-1));
        parent.addView(reference,new LinearLayout.LayoutParams(-2,-1));reference.params.setMarginStart(3);
        State state=new State(battery);state.onViewAttachedToWindow(battery);
        check(!state.failed && state.label!=null && state.label.orientation==LinearLayout.VERTICAL);
        check(state.label.children.size()==2 && state.label.children.get(0)==state.primaryText && state.label.children.get(1)==state.secondaryText);
        check(state.primaryText.text.equals("18V 1.3A") && state.secondaryText.text.isEmpty());
        check(state.primaryText.received instanceof String);
        check(state.primaryText.visibility==View.VISIBLE && state.secondaryText.visibility==View.GONE);
        check(state.label.gravity==Gravity.CENTER && state.primaryText.gravity==Gravity.CENTER && state.secondaryText.gravity==Gravity.CENTER);
        check(state.primaryText.size==32f && state.secondaryText.size==32f);
        check(state.primaryText.typeface==reference.typeface && state.secondaryText.typeface==reference.typeface);
        check(state.primaryText.singleLine && state.secondaryText.singleLine && state.primaryText.maxLines==1);
        check(state.primaryText.fontPadding && state.primaryText.spacingAdd==0f && state.primaryText.spacingMultiplier==1);
        check(state.primaryText.color==battery.mFilterColor && state.secondaryText.color==battery.mFilterColor);
        check(parent.indexOfChild(state.label)+1==parent.indexOfChild(battery));
        check(state.label.parent==parent && icons.children.isEmpty());
        check(((LinearLayout.LayoutParams)state.label.params).width==LinearLayout.LayoutParams.WRAP_CONTENT);
        check(((LinearLayout.LayoutParams)icons.params).width==0);
        check(state.label.params.getMarginStart()==2 && state.label.params.getMarginEnd()==2);
        check(((LinearLayout.LayoutParams)state.label.params).height==LinearLayout.LayoutParams.MATCH_PARENT);
        check(((LinearLayout.LayoutParams)state.primaryText.params).height==LinearLayout.LayoutParams.WRAP_CONTENT);
        check(((LinearLayout.LayoutParams)state.secondaryText.params).height==LinearLayout.LayoutParams.WRAP_CONTENT);
        check(state.primaryText.params.topMargin==0 && state.secondaryText.params.topMargin==0);
        check(((LinearLayout.LayoutParams)state.primaryText.params).weight==0f && !state.primaryText.paint.bold && !state.secondaryText.paint.bold);
        state.onViewAttachedToWindow(battery);check(battery.observer.adds==1);
        LinearLayout original=state.label;TextView originalTop=state.primaryText;
        for(int i=0;i<20;i++)state.update(true);
        check(state.label==original && state.primaryText==originalTop && parent.children.size()==5);
        check(state.primaryText.size==32f && state.secondaryText.size==32f);
        selectedMode=1;state.update(false);check(state.primaryText.text.equals("23W") && state.secondaryText.visibility==View.GONE);
        check(state.primaryText.size==32f && state.primaryText.fontPadding && state.label==original);
        check(state.primaryText.typeface==reference.typeface && state.primaryText.typeface.weight==400);
        check(!state.primaryText.paint.bold && ((LinearLayout.LayoutParams)state.primaryText.params).weight==0f);
        check(state.secondaryText.params.topMargin==0 && state.primaryText.spacingAdd==0);
        check(state.label.params.getMarginEnd()==1);
        selectedMode=2;state.primaryText.resources.metrics.density=2.625f;state.secondaryText.resources.metrics.density=2.625f;
        state.update(true);check(state.primaryText.spacingAdd==0f && state.secondaryText.spacingAdd==0f);
        check(state.secondaryText.params.topMargin==0);
        for(int i=0;i<20;i++)state.update(true);check(state.secondaryText.params.topMargin==0);
        state.primaryText.resources.metrics.density=1;state.secondaryText.resources.metrics.density=1;state.update(true);
        battery.resources.metrics.density=2.625f;state.update(false);
        check(state.label.params.getMarginStart()==5 && state.label.params.getMarginEnd()==5);
        battery.resources.metrics.density=1;state.update(false);
        selectedMode=2;parent.height=72;reference.size=40f;state.update(false);check(state.primaryText.size==40f && state.secondaryText.size==40f);
        reference.typeface=new Typeface("alternate",800,true);state.update(false);check(state.primaryText.typeface==reference.typeface && state.secondaryText.typeface==reference.typeface);
        check(state.primaryText.typeface.isItalic() && state.primaryText.sizeUnit==0);
        parent.height=30;state.update(false);check(state.primaryText.size==40f && state.primaryText.typeface.weight==800);
        parent.height=72;state.update(false);check(state.primaryText.size==40f);
        selectedMode=1;state.update(false);check(state.primaryText.typeface==reference.typeface && state.primaryText.typeface.weight==800 && state.primaryText.size==40f);
        selectedMode=0;state.update(false);check(state.label==null && parent.children.size()==4);
        selectedMode=2;state.update(false);check(state.label!=null && state.label!=original && parent.children.size()==5);
        battery.mCharging=false;state.update(false);check(state.label==null && parent.children.size()==4);
        battery.mCharging=true;state.update(false);battery.shown=false;state.update(false);check(state.label==null);
        battery.shown=true;state.update(false);SystemClock.now=7101;state.render();check(state.label.visibility==View.GONE);
        SystemClock.now=100;state.render();check(state.label.visibility==View.VISIBLE);
        battery.attached=false;state.onViewDetachedFromWindow(battery);check(state.label==null && state.primaryText==null && state.secondaryText==null);
        check(!state.attached && battery.observer.removes==1 && parent.children.size()==4);
        battery.attached=true;parent.direction=View.LAYOUT_DIRECTION_RTL;state.onViewAttachedToWindow(battery);
        check(parent.indexOfChild(state.label)==parent.indexOfChild(battery)+1);
        for(int i=0;i<20;i++)state.update(false);check(parent.children.size()==5 && parent.indexOfChild(state.label)==parent.indexOfChild(battery)+1);
        parent.direction=0;state.update(false);check(parent.indexOfChild(state.label)+1==parent.indexOfChild(battery));
        parent.direction=View.LAYOUT_DIRECTION_RTL;state.update(false);
        check(state.label.params.getMarginStart()==2 && state.label.params.getMarginEnd()==2);
        sample=null;state.render();check(state.label.visibility==View.GONE && state.primaryText.text.isEmpty() && state.secondaryText.text.isEmpty());
        LinearLayout.LayoutParams margins=new LinearLayout.LayoutParams(-2,-1);
        check(configureMargins(margins,3,1f,false));check(margins.start==2 && margins.end==1);
        for(int i=0;i<20;i++)check(!configureMargins(margins,3,1f,false));
        check(configureMargins(margins,3,1f,true));check(margins.start==1 && margins.end==2);
        check(configureMargins(margins,8,2.625f,false));check(margins.start==5 && margins.end==3);
        check(configureMargins(margins,1,2.625f,false));check(margins.start==5 && margins.end==0);
        check(configureMargins(margins,1,2.625f,true));check(margins.start==0 && margins.end==5);
        System.out.println("PASS: "+checks+" full production view hierarchy/rows/font/spacing/mode/lifecycle checks");
    }
    public static void main(String[] args) throws Exception {new ViewLifecycleTest().run();}
}
'''
for placeholder, signature in {
    "CONFIGURE_MODE": "private static void configureMode(TextView text, TextView reference, int mode)",
    "CONFIGURE_MARGINS": "private static boolean configureMargins(LinearLayout.LayoutParams params, int nativeGap, float density, boolean rtl)",
    "BATTERY_INDEX": "private static int batteryIndex(LinearLayout parent, View label, View battery)",
    "STATE": "private final class State implements View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener",
}.items():
    value = declaration(signature)
    if placeholder == "STATE":
        value = value.replace(signature, "private final class State").replace("@Override ", "")
    java = java.replace(placeholder, value)

with tempfile.TemporaryDirectory(prefix="flyme-charging-tree-", dir="/private/tmp") as directory:
    path = Path(directory)
    (path / "ViewLifecycleTest.java").write_text(java)
    stub = path / "android/view/ViewGroup.java"
    stub.parent.mkdir(parents=True)
    stub.write_text("""package android.view;public class ViewGroup {
        public boolean removed;public void removeView(Object view){removed=true;}
        public static class MarginLayoutParams {public int start,end,topMargin;
        public int getMarginStart(){return start;}public int getMarginEnd(){return end;}
        public void setMarginStart(int value){start=value;}public void setMarginEnd(int value){end=value;}}
    }""")
    units = path / "android/util/TypedValue.java"
    units.parent.mkdir(parents=True)
    units.write_text("package android.util;public class TypedValue {public static final int COMPLEX_UNIT_PX=0,COMPLEX_UNIT_SP=2;public static float deriveDimension(int unit,float px,Object metrics){try{var f=metrics.getClass().getDeclaredField(\"scaledDensity\");f.setAccessible(true);return px/f.getFloat(metrics);}catch(Exception e){throw new RuntimeException(e);}}}")
    face=path/"android/graphics/Typeface.java";face.parent.mkdir(parents=True)
    face.write_text("package android.graphics;public class Typeface{public final String family;public final int weight;public final boolean italic;public Typeface(String f,int w,boolean i){family=f;weight=w;italic=i;}public boolean isItalic(){return italic;}public int getWeight(){return weight;}public static Typeface create(Typeface f,int w,boolean i){return new Typeface(f==null?\"native\":f.family,w,i);}}")
    build=path/"android/os/Build.java";build.parent.mkdir(parents=True);build.write_text("package android.os;public class Build{public static class VERSION{public static int SDK_INT=36;}}")
    jdk = Path("/opt/homebrew/opt/openjdk@21/bin")
    subprocess.run([str(jdk/"javac"), "-d", directory, str(stub), str(units), str(face), str(build),  str(root/"app/src/main/java/dev/rikumi/flymemod/ChargingInfo.java"), str(path/"ViewLifecycleTest.java")], check=True)
    subprocess.run([str(jdk/"java"), "-cp", directory, "dev.rikumi.flymemod.ViewLifecycleTest"], check=True)
