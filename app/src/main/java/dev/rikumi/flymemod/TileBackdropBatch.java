package dev.rikumi.flymemod;

import android.graphics.Rect;
import android.graphics.RuntimeShader;
import android.os.Parcel;
import android.os.Handler;
import android.os.Looper;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Array;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Flatten tile, notification and media SF effects into one live background sample and one foreground sample. */
final class TileBackdropBatch {
    static final int MAX_TILES = 24;
    static final String SHADER = shader(MAX_TILES);
    private final Field id, rect, alpha, uniforms, drawableRect, scalarMap, vectorMap;
    private final Constructor<?> regionConstructor;
    private final Method createEffect, createDrawable, writeUniforms;
    private final Class<?> regionClass;
    private final Set<Long> tileIds;
    private final Map<Object, WeakReference<Batch>> batches = new WeakHashMap<>();
    private final Map<Object, Boolean> failedRoots = new WeakHashMap<>();
    private final Map<Object, Tile> tiles = new WeakHashMap<>();
    private final Consumer<String> trace;
    private final BiConsumer<String, Throwable> log;
    private final Handler main = new Handler(Looper.getMainLooper());

    TileBackdropBatch(ClassLoader loader, Set<Long> tileIds, Consumer<String> trace,
                      BiConsumer<String, Throwable> log) throws ReflectiveOperationException {
        this.tileIds=tileIds; this.trace=trace; this.log=log;
        new RuntimeShader(SHADER);
        Class<?> root=loader.loadClass("android.view.ViewRootImpl");
        Class<?> drawable=loader.loadClass("com.android.internal.graphics.drawable.FlymeEffectDrawable");
        regionClass=loader.loadClass(drawable.getName()+"$FlymeEffectRegion");
        createEffect=root.getMethod("createFlymeEffect",String.class);
        createDrawable=root.getMethod("createFlymeEffectDrawable",long.class);
        id=regionClass.getField("flymeEffectId"); rect=regionClass.getField("rect");
        alpha=regionClass.getField("alpha"); uniforms=regionClass.getField("mUniforms");
        regionConstructor=regionClass.getDeclaredConstructor(drawable); regionConstructor.setAccessible(true);
        writeUniforms=regionClass.getDeclaredMethod("writeUniforms",drawable); writeUniforms.setAccessible(true);
        drawableRect=drawable.getDeclaredField("mRect"); drawableRect.setAccessible(true);
        scalarMap=drawable.getDeclaredField("mFloatUniforms"); scalarMap.setAccessible(true);
        vectorMap=drawable.getDeclaredField("mFloat4Uniforms"); vectorMap.setAccessible(true);
    }

    synchronized Batch prepare(Object root) {
        if(failedRoots.containsKey(root)) return null;
        WeakReference<Batch> existing=batches.get(root);
        Batch batch=existing==null?null:existing.get();
        if(batch!=null) return batch;
        try {
            long shaderId=(Long)createEffect.invoke(root,SHADER);
            if(shaderId==0) throw new IllegalStateException("Flyme rejected batched backdrop shader");
            batch=new Batch(createDrawable.invoke(root,shaderId),MAX_TILES);
            batches.put(root,new WeakReference<>(batch));
            return batch;
        } catch(ReflectiveOperationException|RuntimeException error) {
            failedRoots.put(root,true);
            log.accept("Cannot prepare batched live tile backdrop",error);
            return null;
        }
    }

    synchronized Object[] compose(Object root,Object[] regions) throws ReflectiveOperationException {
        WeakReference<Batch> reference=batches.get(root);
        Batch batch=reference==null?null:reference.get();
        if(batch==null) return regions;
        int first=-1,count=0;
        for(int i=0;i<regions.length;i++) if(tileIds.contains(id.getLong(regions[i]))) {
            if(first<0) first=i;
            count++;
        }
        if(count<2) return regions;
        if(count>batch.capacity) {
            int capacity=batch.capacity;
            while(capacity<count) capacity*=2;
            long shaderId=(Long)createEffect.invoke(root,shader(capacity));
            if(shaderId==0) throw new IllegalStateException("Flyme rejected enlarged backdrop union");
            Batch replacement=new Batch(createDrawable.invoke(root,shaderId),capacity);
            // Attached states retain the first batch. Keep its larger replacement
            // alive too, without introducing a strong ViewRoot map entry.
            batch.successor=replacement;
            batch=replacement;
            batches.put(root,new WeakReference<>(batch));
        }
        batch.bounds.setEmpty();
        int slot=0;
        float maximumSaturation=0f;
        float maximumBrightnessBoost=0f;
        for(Object region:regions) {
            if(!tileIds.contains(id.getLong(region))) continue;
            Tile tile=tiles.get(region);
            if(tile==null) {tile=new Tile((Parcel)uniforms.get(region));tiles.put(region,tile);}
            Rect bounds=(Rect)rect.get(region);
            batch.bounds.union(bounds);
            int group=slot/4;
            includeBounds(batch.groupBounds[group], bounds.left, bounds.top, bounds.right, bounds.bottom, slot%4==0);
            float[][] values=batch.values[slot++];
            values[0][0]=bounds.left; values[0][1]=bounds.top;
            values[0][2]=bounds.right; values[0][3]=bounds.bottom;
            values[1][0]=tile.radius; values[1][1]=tile.smooth;
            values[1][2]=tile.width; values[1][3]=tile.height;
            for(int j=0;j<4;j++) System.arraycopy(tile.corners[j],0,values[j+2],0,4);
            System.arraycopy(tile.clip,0,values[6],0,4);
            System.arraycopy(tile.hole,0,values[7],0,4);
            System.arraycopy(tile.holeRadii,0,values[8],0,4);
            System.arraycopy(tile.notificationCorners,0,values[9],0,4);
            for(int j=0;j<4;j++) {
                System.arraycopy(tile.mediaClips[j],0,values[10+j*2],0,4);
                System.arraycopy(tile.mediaRadii[j],0,values[11+j*2],0,4);
            }
            values[5][2]=alpha.getFloat(region);
            values[5][3]=tile.brightnessBoost;
            values[8][2]=tile.notification; values[8][3]=tile.saturation;
            maximumSaturation=Math.max(maximumSaturation,tile.saturation);
            maximumBrightnessBoost=Math.max(maximumBrightnessBoost,tile.brightnessBoost);
        }
        batch.scalars.put("tileCount",(float)count);
        batch.scalars.put("maximumSaturation",maximumSaturation);
        batch.scalars.put("maximumBrightnessBoost",maximumBrightnessBoost);
        // Reuse the native Parcel buffer instead of creating uniform arrays and Parcels per frame.
        Parcel packet=(Parcel)uniforms.get(batch.region);
        packet.setDataSize(0); packet.setDataPosition(0);
        writeUniforms.invoke(batch.region,batch.drawable);
        Object[] result=(Object[])Array.newInstance(regionClass,regions.length-count+1);
        int output=0;
        for(int i=0;i<regions.length;i++) {
            if(i==first) result[output++]=batch.region;
            if(!tileIds.contains(id.getLong(regions[i]))) result[output++]=regions[i];
        }
        if(!batch.reported) {
            batch.reported=true;
            final int merged=count;
            main.post(()->trace.accept("Live tile backdrop batched: "+merged+" tile effects -> 1 compositor effect"));
        }
        return result;
    }

    final class Batch {
        final Object drawable,region;
        final Rect bounds;
        final Map<String,Float> scalars;
        final int capacity;
        final float[][][] values;
        final float[][] groupBounds;
        Batch successor;
        boolean reported;
        @SuppressWarnings("unchecked")
        Batch(Object drawable,int capacity) throws ReflectiveOperationException {
            this.capacity=capacity; values=new float[capacity][18][4];
            groupBounds=new float[(capacity+3)/4][4];
            this.drawable=drawable;
            bounds=(Rect)drawableRect.get(drawable);
            scalars=(Map<String,Float>)scalarMap.get(drawable);
            Map<String,float[]> vectors=(Map<String,float[]>)vectorMap.get(drawable);
            scalars.put("tileCount",0f);
            for(int i=0;i<groupBounds.length;i++) vectors.put("group"+i,groupBounds[i]);
            for(int i=0;i<capacity;i++) for(int j=0;j<18;j++) vectors.put("tile"+i+"_"+j,values[i][j]);
            // This drawable is a uniform carrier only; it never registers an extra position tracker.
            region=regionConstructor.newInstance(drawable);
        }
    }

    private static final class Tile {
        float radius,smooth,saturation=1,width,height,notification,brightnessBoost;
        final float[][] corners=new float[4][4],mediaClips=new float[4][4],mediaRadii=new float[4][4];
        final float[] clip=new float[4];
        final float[] hole=new float[4], holeRadii=new float[4], notificationCorners=new float[4];
        Tile(Parcel packet) {
            boolean hasClip=false;
            int cursor=packet.dataPosition();
            try {
                packet.setDataPosition(0);
                for(int size=1;size<=4;size++) {
                    int count=packet.readInt();
                    for(int i=0;i<count;i++) {
                        String name=packet.readString();
                        float[] values=new float[size];
                        for(int j=0;j<size;j++) values[j]=packet.readFloat();
                        if(name==null) continue;
                        switch(name) {
                            case "cornerRadius" -> radius=values[0];
                            case "smoothCorner" -> smooth=values[0];
                            case "saturationAmount" -> saturation=values[0];
                            case "brightnessBoost" -> brightnessBoost=values[0];
                            case "shapeSize" -> {width=values[0];height=values[1];}
                            case "shapeClip" -> {System.arraycopy(values,0,clip,0,4);hasClip=true;}
                            case "clipHole" -> System.arraycopy(values,0,hole,0,4);
                            case "clipHoleRadii" -> System.arraycopy(values,0,holeRadii,0,4);
                            case "notificationShape" -> notification=values[0];
                            case "notificationCorners" -> System.arraycopy(values,0,notificationCorners,0,4);
                            default -> {
                                if(size==4&&name.startsWith("mediaClip")&&name.length()==10) {
                                    int n=name.charAt(9)-'0';
                                    if(n>=0&&n<4)System.arraycopy(values,0,mediaClips[n],0,4);
                                } else if(size==4&&name.startsWith("mediaRadius")&&name.length()==12) {
                                    int n=name.charAt(11)-'0';
                                    if(n>=0&&n<4)System.arraycopy(values,0,mediaRadii[n],0,4);
                                } else if(name.startsWith("corner")&&name.length()==7) {
                                    int n=name.charAt(6)-'0';
                                    if(n>=0&&n<4&&size==4) System.arraycopy(values,0,corners[n],0,4);
                                }
                            }
                        }
                    }
                }
            } finally {packet.setDataPosition(cursor);}
            if(width<=0||height<=0) throw new IllegalArgumentException("Missing live tile shape size");
            if(!hasClip) {clip[2]=width;clip[3]=height;}
        }
    }

    // Include every member's full rectangle. Shader inside() expands by one pixel
    // at both levels, preserving the existing antialiasing support at all edges.
    static void includeBounds(float[] group,float left,float top,float right,float bottom,boolean first) {
        if(first) {group[0]=left;group[1]=top;group[2]=right;group[3]=bottom;}
        else {group[0]=Math.min(group[0],left);group[1]=Math.min(group[1],top);
            group[2]=Math.max(group[2],right);group[3]=Math.max(group[3],bottom);}
    }

    private static String shader(int capacity) {
        String original=TileBackdropHooks.MEDIA_SHADER
                .replace("uniform float4 mediaClip0, mediaClip1, mediaClip2, mediaClip3;", "")
                .replace("uniform float4 mediaRadius0, mediaRadius1, mediaRadius2, mediaRadius3;", "");
        String functions=original.substring(original.indexOf("float2 cubic"),original.indexOf("half4 main"));
        functions=functions.replace("float mask(float2 p)","float mask(float2 p, float4 box, float4 shape, float4 corner0, float4 corner1, float4 corner2, float4 corner3, float4 clip, float4 hole, float4 holeRadii, float4 notificationCorners)")
                .replace("sksl_left","box.x").replace("sksl_top","box.y")
                .replace("sksl_right","box.z").replace("sksl_bottom","box.w")
                .replace("shapeSize","shape.zw").replace("shapeClip","clip")
                .replace("clipHoleRadii","holeRadii").replace("clipHole","hole")
                .replace("notificationShape","holeRadii.z")
                .replace("cornerRadius","shape.x").replace("smoothCorner","shape.y");
        StringBuilder shader=new StringBuilder("uniform shader sksl_layer; uniform shader sksl_background;\n")
                .append("uniform float sksl_left, sksl_top, sksl_right, sksl_bottom, sksl_alpha;\n")
                .append("uniform float tileCount, maximumSaturation, maximumBrightnessBoost;\n");
        for(int i=0;i<capacity;i++) for(int j=0;j<18;j++) shader.append("uniform float4 tile").append(i).append('_').append(j).append(";\n");
        for(int i=0;i<(capacity+3)/4;i++) shader.append("uniform float4 group").append(i).append(";\n");
        shader.append(functions).append("bool inside(float2 p,float4 b) { return p.x>=b.x-1.0 && p.y>=b.y-1.0 && p.x<=b.z+1.0 && p.y<=b.w+1.0; }\n")
                .append("half4 main(float2 p) { half4 layer=sksl_layer.eval(p); if(layer.a>=1.0) return layer; float coverage=0.0, amount=0.0, boost=0.0;\n");
        for(int i=0;i<capacity;i++) {
            if(i%4==0) shader.append("if(tileCount>").append(i)
                    .append(".0 && inside(p,group").append(i/4).append(")) {\n");
            String t="tile"+i+"_";
            // No later tile can exceed full coverage or this batch's largest
            // saturation. Avoid testing every other tile for each covered pixel.
            shader.append("if((coverage<1.0 || amount<maximumSaturation || boost<maximumBrightnessBoost) && tileCount>")
                    .append(i).append(".0 && inside(p,").append(t).append("0)) { float c=clamp(mask(p,");
            for(int j=0;j<10;j++) shader.append(j==0?"":",").append(t).append(j);
            shader.append(")*").append(t).append("5.z,0.0,1.0);");
            // Ordinary tiles have no media masks; avoid extra corner evaluation for them.
            shader.append("if(").append(t).append("10.z>").append(t).append("10.x) { float2 local=(p-")
                    .append(t).append("0.xy)*").append(t).append("1.zw/(").append(t).append("0.zw-").append(t).append("0.xy);");
            for(int clip=0;clip<4;clip++) shader.append("c*=mediaCoverage(local,").append(t).append(10+clip*2)
                    .append(",").append(t).append(11+clip*2).append(");");
            shader.append("} coverage=max(coverage,c); amount=max(amount,c*").append(t)
                    .append("8.w); boost=max(boost,c*").append(t).append("5.w); }\n");
            if(i%4==3 || i==capacity-1) shader.append("}\n");
        }
        return shader.append("if(coverage<=0.0) return layer; half4 bg=sksl_background.eval(p); half lum=dot(bg.rgb,half3(0.2126,0.7152,0.0722));")
                .append("half3 rgb=min(clamp(half3(lum)+(bg.rgb-half3(lum))*(1.0+amount/coverage),0.0,bg.a)*(1.0+boost/coverage),half3(bg.a));")
                .append("return layer+half4(rgb,bg.a)*(1.0-layer.a)*coverage; }").toString();
    }
}
