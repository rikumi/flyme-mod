package dev.rikumi.flymemod;

import android.graphics.Rect;
import android.graphics.RuntimeShader;
import android.view.View;
import android.view.SurfaceControl;
import android.hardware.SyncFence;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

final class TileBackdropBatch {
    static final int MAX_TILES = 24;
    static final String SHADER = shader(MAX_TILES);
    private final Field id, rect, alpha, uniforms, drawableRect, scalarMap, vectorMap;
    private final Constructor<?> regionConstructor;
    private final Method createEffect, createDrawable, writeUniforms;
    private final Class<?> regionClass;
    private final Set<Long> tileIds;
    private final Map<Object, Tile> tiles = new WeakHashMap<>();
    private final Consumer<String> trace;
    private final BiConsumer<String, Throwable> log;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService compiler = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "FlymeMod-shader-warmup"); thread.setDaemon(true); return thread;
    });
    private final Map<Object, Warmup> warmups = new WeakHashMap<>();
    private final Method surfaceControl, setRegions, completed, presentFence;
    private Runnable refresh = () -> {};
    private long quietUntil;
    private boolean touching;
    private WeakReference<Object> panelOwner = new WeakReference<>(null);
    private final Field expandedFraction;
    private final Method barState;
    private float centerFraction;

    void setRefresh(Runnable refresh) { this.refresh = refresh; }

    void install(SignalHooks.Installer installer) {
        installer.hook("com.android.systemui.shade.NotificationPanelViewController", "onFinishInflate", chain -> {
            Object result = chain.proceed();
            panelOwner = new WeakReference<>(chain.getThisObject());
            return result;
        });
        installer.hook("com.flyme.systemui.controlcenter.phone.CenterController", "setExpandedFraction", chain -> {
            centerFraction = (Float) chain.getArg(0);
            return chain.proceed();
        }, float.class);
        installer.hook("com.android.systemui.shade.NotificationShadeWindowView", "dispatchTouchEvent", chain -> {
            int action = ((android.view.MotionEvent) chain.getArg(0)).getActionMasked();
            if (action == android.view.MotionEvent.ACTION_DOWN) touching = true;
            if (action == android.view.MotionEvent.ACTION_UP || action == android.view.MotionEvent.ACTION_CANCEL) touching = false;
            quietUntil = android.os.SystemClock.uptimeMillis() + 1000;
            return chain.proceed();
        }, android.view.MotionEvent.class);

    }

    synchronized void observe(Object root, View view) {
        Warmup warmup = warmups.get(root);
        if (warmup == null) {
            warmup = new Warmup(root, view);
            warmups.put(root, warmup);
            Warmup observed = warmup;
            view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View view) {}
                @Override public void onViewDetachedFromWindow(View view) {
                    synchronized (TileBackdropBatch.this) {
                        Object owner = observed.root.get();
                        if (owner != null) warmups.remove(owner);
                        observed.gate.invalidate(); observed.phase = Phase.FAILED;
                        observed.prepared = null; observed.batch = null;
                    }
                    view.removeOnAttachStateChangeListener(this);
                }
            });
        }
        schedule(warmup);
    }

    private boolean windowIdle() {
        Object panel = panelOwner.get();
        if (panel == null) return false;
        try {
            return ShaderWarmupGate.windowIdle((Integer) barState.invoke(panel), expandedFraction.getFloat(panel), centerFraction);
        } catch (ReflectiveOperationException error) {
            return false;
        }
    }

    private static boolean usable(View view) {
        return view.isAttachedToWindow() && view.isShown() && view.getWindowVisibility() == View.VISIBLE
                && view.getWidth() > 4 && view.getHeight() > 16 && view.getAlpha() > 0f;
    }

    private void schedule(Warmup warmup) {
        if (warmup.prepared != null || warmup.scheduled || warmup.phase == Phase.PREPARING || warmup.phase == Phase.PRESENTING || warmup.phase == Phase.READY || warmup.phase == Phase.FAILED) return;
        warmup.scheduled = true;
        main.postDelayed(() -> Looper.myQueue().addIdleHandler(() -> {
            warmup.scheduled = false;
            View view = warmup.view.get();
            Object root = warmup.root.get();
            if (warmup.phase == Phase.FAILED || view == null || root == null || !view.isAttachedToWindow()) return false;
            if (!ShaderWarmupGate.canCompile(usable(view) && windowIdle(), touching, android.os.SystemClock.uptimeMillis(), quietUntil)) {
                schedule(warmup); return false;
            }
            warmup.ticket = warmup.gate.begin();
            warmup.phase = Phase.PREPARING;
            compiler.execute(() -> {
                try {
                    String source = shader(warmup.capacity);
                    new RuntimeShader(source);
                    if (warmup.baseId == 0) {
                        warmup.baseId = (Long) createEffect.invoke(root, TileBackdropHooks.SHADER);
                        warmup.mediaId = (Long) createEffect.invoke(root, TileBackdropHooks.MEDIA_SHADER);
                        tileIds.add(warmup.baseId); tileIds.add(warmup.mediaId);
                    }
                    long shaderId = (Long) createEffect.invoke(root, source);
                    if (shaderId == 0 || warmup.baseId == 0 || warmup.mediaId == 0)
                        throw new IllegalStateException("Flyme rejected idle backdrop shader");
                    Batch batch = new Batch(createDrawable.invoke(root, shaderId), warmup.capacity);
                    main.post(() -> submit(warmup, batch));
                } catch (ReflectiveOperationException | RuntimeException error) { fail(warmup, error); }
            });
            return false;
        }), 1000);
    }

    private void submit(Warmup warmup, Batch batch) {
        View view = warmup.view.get(); Object root = warmup.root.get();
        if (warmup.phase == Phase.FAILED || view == null || root == null) return;
        if (!ShaderWarmupGate.canCompile(usable(view) && windowIdle(), touching, android.os.SystemClock.uptimeMillis(), quietUntil)) {
            // Keep registration off the input path, even if input arrived during CPU preparation.
            warmup.prepared = batch; warmup.phase = Phase.WAITING_IDLE;
            main.postDelayed(() -> Looper.myQueue().addIdleHandler(() -> {
                if (warmup.prepared == batch) submit(warmup, batch);
                return false;
            }), 1000);
            return;
        }
        try {
            warmup.prepared = null;
            int x = Math.max(1, view.getWidth() / 4), y = Math.min(8, Math.max(0, view.getHeight() - 1));
            batch.bounds.set(x, y, x + 1, y + 1);
            System.arraycopy(new float[]{x, y, x + 1, y + 1}, 0, batch.values[0][0], 0, 4);
            // The native drawable's uniform maps retain the original arrays.
            System.arraycopy(new float[]{x, y, x + 1, y + 1}, 0, batch.groupBounds[0], 0, 4);
            batch.values[0][1][2] = batch.values[0][1][3] = 1f;
            batch.values[0][5][2] = .001f; batch.values[0][5][3] = .1f;
            batch.values[0][6][2] = batch.values[0][6][3] = 1f;
            batch.values[0][8][3] = .5f;
            batch.scalars.put("tileCount", 1f);
            batch.scalars.put("maximumSaturation", .5f); batch.scalars.put("maximumBrightnessBoost", .1f);
            Parcel packet = (Parcel) uniforms.get(batch.region); packet.setDataSize(0); packet.setDataPosition(0);
            writeUniforms.invoke(batch.region, batch.drawable);
            warmup.batch = batch; warmup.phase = Phase.PRESENTING;
            long ticket = warmup.ticket;
            SurfaceControl surface = (SurfaceControl) surfaceControl.invoke(root);
            if (surface == null || !surface.isValid()) { warmup.phase = Phase.WAITING_IDLE; schedule(warmup); return; }
            Object[] regions = append(warmup.nativeRegions, batch.region);
            try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
                setRegions.invoke(transaction, surface, regions);
                completed.invoke(transaction, compiler, (Consumer<Object>) stats -> {
                    try (SyncFence fence = (SyncFence) presentFence.invoke(stats)) {
                        if (fence == null || !fence.isValid() || !fence.await(Duration.ofSeconds(8)))
                            throw new IllegalStateException("No presentation fence for backdrop warmup");
                        main.post(() -> {
                            if (warmup.phase != Phase.PRESENTING || warmup.batch != batch) return;
                            View current = warmup.view.get();
                            if (current == null || !usable(current) || !surface.isValid()) {
                                warmup.phase = Phase.WAITING_IDLE; schedule(warmup); return;
                            }
                            // Only a presented compositor frame can make this shader available to input.
                            restoreNative(warmup);
                            if (!warmup.gate.presented(ticket)) return;
                            warmup.phase = Phase.READY;
                            trace.accept("Backdrop GPU shader precompiled while idle: capacity=" + batch.capacity);
                            refresh.run(); current.invalidate();
                        });
                    } catch (ReflectiveOperationException | RuntimeException error) { fail(warmup, error); }
                });
                transaction.apply();
            }
            view.invalidate();
            main.postDelayed(() -> {
                if (warmup.phase == Phase.PRESENTING && warmup.batch == batch)
                    fail(warmup, new IllegalStateException("Backdrop presentation timed out"));
            }, 10000);
        } catch (ReflectiveOperationException | RuntimeException error) { fail(warmup, error); }
    }

    private void fail(Warmup warmup, Throwable error) {
        main.post(() -> {
            if (warmup.phase == Phase.FAILED) return;
            restoreNative(warmup);
            warmup.gate.invalidate();
            warmup.phase = Phase.FAILED;
            log.accept("Cannot precompile backdrop GPU shader; retaining native backgrounds", error);
            View view = warmup.view.get(); if (view != null) view.invalidate();
        });
    }

    private void restoreNative(Warmup warmup) {
        Object root = warmup.root.get();
        if (root == null) return;
        try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
            SurfaceControl surface = (SurfaceControl) surfaceControl.invoke(root);
            if (surface != null && surface.isValid()) {
                Object[] nativeRegions = warmup.nativeRegions == null
                        ? (Object[]) Array.newInstance(regionClass, 0) : warmup.nativeRegions;
                setRegions.invoke(transaction, surface, nativeRegions);
                transaction.apply();
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            log.accept("Cannot restore native regions after shader warmup", error);
        }
    }

    synchronized long sourceId(Object root, boolean media) {
        Warmup warmup = warmups.get(root);
        return warmup != null && warmup.phase == Phase.READY && warmup.gate.ready() ? (media ? warmup.mediaId : warmup.baseId) : 0;
    }

    private enum Phase { WAITING_IDLE, PREPARING, PRESENTING, READY, FAILED }

    private static final class Warmup {
        final WeakReference<Object> root;
        final WeakReference<View> view;
        final ShaderWarmupGate gate = new ShaderWarmupGate();
        volatile Phase phase = Phase.WAITING_IDLE;
        long ticket;
        int capacity = MAX_TILES;
        boolean scheduled;
        long baseId, mediaId;
        Batch batch, prepared;
        volatile Object[] nativeRegions;
        Warmup(Object root, View view) { this.root = new WeakReference<>(root); this.view = new WeakReference<>(view); }
    }


    TileBackdropBatch(ClassLoader loader, Set<Long> tileIds, Consumer<String> trace,
                      BiConsumer<String, Throwable> log) throws ReflectiveOperationException {
        this.tileIds=tileIds; this.trace=trace; this.log=log;
        Class<?> root=loader.loadClass("android.view.ViewRootImpl");
        Class<?> panel = loader.loadClass("com.android.systemui.shade.NotificationPanelViewController");
        expandedFraction = panel.getDeclaredField("mExpandedFraction"); expandedFraction.setAccessible(true);
        barState = panel.getMethod("getBarState");
        surfaceControl = root.getMethod("getSurfaceControl");
        setRegions = SurfaceControl.Transaction.class.getMethod("setFlymeEffectRegions", SurfaceControl.class,
                Array.newInstance(loader.loadClass("com.android.internal.graphics.drawable.FlymeEffectDrawable$FlymeEffectRegion"), 0).getClass());
        completed = SurfaceControl.Transaction.class.getMethod("addTransactionCompletedListener", java.util.concurrent.Executor.class, Consumer.class);
        presentFence = loader.loadClass("android.view.SurfaceControl$TransactionStats").getMethod("getPresentFence");
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
        Warmup warmup = warmups.get(root);
        return warmup != null && warmup.phase == Phase.READY && warmup.gate.ready() ? warmup.batch : null;
    }

    synchronized Object[] withoutOwned(Object[] regions) throws IllegalAccessException { return nativeOnly(regions); }

    private Object[] nativeOnly(Object[] regions) throws IllegalAccessException {
        int count = 0;
        for (Object region : regions) if (!tileIds.contains(id.getLong(region))) count++;
        if (count == regions.length) return regions;
        Object[] result = (Object[]) Array.newInstance(regionClass, count);
        int i = 0;
        for (Object region : regions) if (!tileIds.contains(id.getLong(region))) result[i++] = region;
        return result;
    }

    private Object[] append(Object[] original, Object extra) {
        int count = original == null ? 0 : original.length;
        Object[] result = (Object[]) Array.newInstance(regionClass, count + 1);
        if (count != 0) System.arraycopy(original, 0, result, 0, count);
        result[count] = extra;
        return result;
    }

    synchronized Object[] compose(Object root,Object[] regions) throws ReflectiveOperationException {
        Warmup warmup = warmups.get(root);
        Object[] nativeRegions = nativeOnly(regions);
        if (warmup == null) return nativeRegions;
        warmup.nativeRegions = nativeRegions;
        if (warmup.phase == Phase.PRESENTING) return append(nativeRegions, warmup.batch.region);
        if (warmup.phase != Phase.READY || !warmup.gate.ready()) return nativeRegions;
        Batch batch = warmup.batch;
        int first=-1,count=0;
        for(int i=0;i<regions.length;i++) if(tileIds.contains(id.getLong(regions[i]))) {
            if(first<0) first=i;
            count++;
        }
        if(count==0) return nativeRegions;
        if(count>batch.capacity) {
            // A new capacity is never compiled or first-dispatched from an interactive frame.
            while(warmup.capacity<count) warmup.capacity*=2;
            warmup.gate.invalidate();
            warmup.phase=Phase.WAITING_IDLE;
            main.post(() -> schedule(warmup));
            return nativeRegions;
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
