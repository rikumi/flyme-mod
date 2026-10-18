"""Exercise production tint and refresh bodies against native background lifecycle doubles."""
from pathlib import Path
import subprocess
import re
import tempfile

source = Path('app/src/main/java/dev/rikumi/flymemod/NotificationAppearanceHooks.java').read_text()

def body(marker):
    start = source.index('{', source.index(marker))
    end, depth = start + 1, 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]

# Drawing an extra white layer would make an otherwise correct native tint ineffective.
assert 'canvas.drawColor' not in source and 'drawRoundRect' not in source
assert '"dispatchDraw"' not in source and '"drawChild"' not in source

java = r'''
import java.lang.ref.WeakReference;
import java.lang.reflect.*;
import java.util.function.*;
public class NotificationLightTest {
    static NotificationLightTest host;
    static int checks;
    static void check(boolean v,String m){checks++;if(!v)throw new AssertionError(m);}
    static class Color {static int argb(int a,int r,int g,int b){return a<<24|r<<16|g<<8|b;}}
    static class ModuleSettings {static final int LIGHT_OPACITY_MAX=40;}
    static class ColorFilter {int tint;ColorFilter(int tint){this.tint=tint;}}
    static class PorterDuff {enum Mode {SRC_IN}}
    static class PorterDuffColorFilter extends ColorFilter {
        PorterDuffColorFilter(int tint,PorterDuff.Mode mode){super(tint);}
    }
    static class Drawable {
        int tint;ColorFilter filter;
        Drawable mutate(){return this;} void setTint(int v){tint=v;}
        ColorFilter getColorFilter(){return filter;}
        void setColorFilter(ColorFilter v){filter=v;}
        int color(){return filter==null?tint:filter.tint;}
    }
    static class BackgroundBlurDrawable extends Drawable {}
    static class Background {public Drawable mBackground; public void setTintForNoBlur(int tint){mBackground.tint=tint;}}
    static class View {
        boolean night, floating, blur, attached=true;
        int nativeTint=0xB2FFFFFF, layouts, resets;
        Drawable background; State state;
        Drawable getBackground(){return background;}
        boolean isAttachedToWindow(){return attached;}
        void requestLayout(){layouts++;} void invalidateOutline(){}
        public void setBackground() throws Exception {
            if(this instanceof Landscape)state.restoreLandscapeTint();
            resets++;
            if(!(this instanceof Landscape) || background==null || blur || background instanceof BackgroundBlurDrawable)
                background=blur?new BackgroundBlurDrawable():new Drawable();
            background.tint=blur?host.backgroundColor(this,nativeTint,true):nativeTint;
            if(this instanceof Row row) row.mBackgroundFlyme.mBackground=background;
            state.finish();
        }
        public void applyRoundnessAndInvalidate(){}
    }
    static class Row extends View {public Object mPushNotificationBg; public Background mBackgroundFlyme=new Background();}
    static class Media extends View {}
    static class Landscape extends View {Landscape(){floating=true;}}
    boolean enabled;int amount=12;
    BooleanSupplier light=()->enabled,corners=()->false;
    IntSupplier opacity=()->amount;
    static final int DARK_BLUR_COLOR=0xB21A1A1A,DARK_SOLID_COLOR=0xFF1A1A1A;
    Class<?> mediaClass=Media.class,landscapeClass=Landscape.class;
    Field flymeBackground,pushBackground,nativeBackground;
    Method tintNoBlur,applyRoundness,rowBackground,mediaBackground,landscapeBackground;
    BiConsumer<String,Throwable> log=(m,e)->{throw new AssertionError(m,e);};
    NotificationLightTest() throws Exception {
        host=this;
        flymeBackground=Row.class.getField("mBackgroundFlyme");pushBackground=Row.class.getField("mPushNotificationBg");
        nativeBackground=Background.class.getField("mBackground");tintNoBlur=Background.class.getMethod("setTintForNoBlur",int.class);
        applyRoundness=View.class.getMethod("applyRoundnessAndInvalidate");
        rowBackground=Row.class.getMethod("setBackground");mediaBackground=Media.class.getMethod("setBackground");
        landscapeBackground=Landscape.class.getMethod("setBackground");
    }
    boolean night(View v){return v.night;}
    boolean floating(View v)throws ReflectiveOperationException{return v.floating;}
    float radius(View v){return 12;}
    private int color(View view) COLOR
    private int backgroundColor(View view,int nativeColor,boolean blur)throws ReflectiveOperationException TINT
    class State {
        final WeakReference<View> owner;
        boolean media,landscape,running,failed,lastNight,lastFloating,lastCorners,lastLight;
        int lastColor=Integer.MIN_VALUE;float lastRadius=-1;
        Drawable tintedLandscape;ColorFilter originalLandscapeFilter;
        State(View v){owner=new WeakReference<>(v);media=v instanceof Media;landscape=v instanceof Landscape;v.state=this;}
        void restoreLandscapeTint() RESTORE
        void finish()throws ReflectiveOperationException FINISH
        void update() UPDATE
    }
    void run()throws Exception {
        for(int kind=0;kind<4;kind++)for(boolean blur:new boolean[]{false,true}){
            View v=kind==0?new Row():kind==1?new Media():kind==2?new Landscape():new Row();
            v.floating=kind>=2;v.blur=blur;State state=new State(v);
            enabled=false;v.setBackground();check(v.background.color()==v.nativeTint,"off must retain light native background");
            enabled=true;
            for(int a:new int[]{0,5,12,24,50,95,100}){
                amount=a;state.update();check(v.background.color()==((Math.round((Math.max(0,Math.min(40,a))+60)*255f/100f)<<24)|0xFFFFFF),"light slider ignored");
            }
            int resets=v.resets;
            for(int i=0;i<100;i++)state.update();
            check(v.resets==resets,"stable frame must not recreate background");
            enabled=false;state.update();check(v.background.color()==v.nativeTint,"off must restore native background");
            v.night=true;v.nativeTint=blur?0xB21A1A1A:0xFF1A1A1A;state.update();
            check(v.background.color()==v.nativeTint,"off must retain dark native background");
            enabled=true;amount=12;state.update();
            int expected=v.floating?(blur?DARK_BLUR_COLOR:DARK_SOLID_COLOR):0x1FFFFFFF;
            check(v.background.color()==expected,"dark policy changed");
            v.night=false;v.nativeTint=0xB2FFFFFF;state.update();
            check(v.background.color()==0xB8FFFFFF,"theme change must refresh tint");
            enabled=false;state.update();check(v.background.color()==v.nativeTint,"second off transition must restore");
        }
        Landscape reused=new Landscape();State state=new State(reused);
        enabled=false;reused.setBackground();Drawable original=reused.background;
        ColorFilter nativeFilter=new ColorFilter(0xCC123456);original.setColorFilter(nativeFilter);
        enabled=true;state.update();check(reused.background==original,"native landscape drawable must be reused");
        check(original.color()==color(reused),"module tint must override native filter while enabled");
        amount=24;state.update();enabled=false;state.update();
        check(original.getColorFilter()==nativeFilter,"off must restore original filter after repeated slider updates");
        enabled=false;
        for(boolean night:new boolean[]{false,true})for(boolean floating:new boolean[]{false,true}){
            View v=new View();v.night=night;v.floating=floating;
            for(int tint:new int[]{0,0x00123456,0xFF336699,0xB2FFFFFF})
                check(backgroundColor(v,tint,true)==tint,"off altered supplied native/live color");
        }
        System.out.println("PASS: "+checks+" tint/native restoration/theme/lifecycle checks and no extra draw layer");
    }
    public static void main(String[] args)throws Exception{new NotificationLightTest().run();}
}
'''
for key, marker in [('COLOR','private int color(View view)'),('TINT','private int backgroundColor('),
                    ('RESTORE','void restoreLandscapeTint()'),('FINISH','void finish()'),('UPDATE','void update()')]:
    java=re.sub(r"\b"+key+r"\b",lambda _: body(marker),java)
with tempfile.TemporaryDirectory(dir='/private/tmp',prefix='flyme-notification-light-') as tmp:
    root=Path(tmp);f=root/'NotificationLightTest.java';f.write_text(java)
    jdk=Path('/opt/homebrew/opt/openjdk@21/bin')
    policy=root/'LightBackgroundOpacity.java';policy.write_text(Path('app/src/main/java/dev/rikumi/flymemod/LightBackgroundOpacity.java').read_text().replace('package dev.rikumi.flymemod;', ''))
    subprocess.run([str(jdk/'javac'),'-d',tmp,str(f),str(policy)],check=True)
    subprocess.run([str(jdk/'java'),'-cp',tmp,'NotificationLightTest'],check=True)
