package dev.rikumi.flymemod;

import android.content.Context;
import android.graphics.Typeface;
import java.util.HashMap;
import java.util.Map;

/** Shared font files and weight rules for live clocks and their editor previews. */
final class ClockTypefaceLoader {
    private final Map<Integer,Typeface> fonts=new HashMap<>();
    private Context moduleContext;

    synchronized Typeface resolve(Context context,int family,int selectedWeight,Typeface nativeFont) throws Exception {
        if(family<=0 || family>=ModuleSettings.CLOCK_FONT_NAMES.length)return null;
        int fontWeight=selectedWeight==0?(nativeFont==null?400:nativeFont.getWeight()):selectedWeight;
        fontWeight=Math.max(100,Math.min(900,fontWeight));
        if(family==2)fontWeight=Math.max(200,Math.min(800,fontWeight));
        if(family==3)fontWeight=Math.max(300,fontWeight);
        if(family==4)fontWeight=fontWeight>=600?700:fontWeight>=500?500:400;
        int key=family*1000+fontWeight;
        Typeface cached=fonts.get(key);
        if(cached!=null)return cached;
        if(moduleContext==null)moduleContext=context.createPackageContext("dev.rikumi.flymemod",0);
        String asset=family==4?(fontWeight>=600?"Lato-Bold.ttf":fontWeight>=500?"Lato-Medium.ttf":"Lato-Regular.ttf")
                :ModuleSettings.CLOCK_FONT_NAMES[family]+".ttf";
        Typeface.Builder builder=new Typeface.Builder(moduleContext.getAssets(),"clock_fonts/"+asset);
        if(family!=4)builder.setFontVariationSettings("'wght' "+fontWeight+(family==1?", 'opsz' 32":""));
        Typeface result=builder.setWeight(fontWeight).setItalic(false).build();
        if(result==null)throw new IllegalStateException("Cannot load clock font "+asset);
        fonts.put(key,result);return result;
    }
}
