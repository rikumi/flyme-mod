package dev.rikumi.flymemod;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.*;
import android.view.View;
import java.nio.charset.StandardCharsets;

/** Shared white contour geometry for backgrounds and alpha silhouettes. */
final class LauncherContourRenderer {
    private final RuntimeShader round, nativeCurve;
    private final UniformState roundUniforms = new UniformState(), nativeUniforms = new UniformState();
    private Bitmap inputMask;
    private int inputGeneration;
    private BitmapShader maskInput;
    private final Matrix inputScale = new Matrix();
    private float inputWidth, inputHeight;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] near = {0, .4f, .2f, .2f, 0, 0};
    private final float[] far = {0, .2f, .2f, .2f, 0, 0};
    private Bitmap pathMask;
    private Bitmap coverageMask;
    private float coverageHeight;
    private int coverageGeneration;
    private final ContourMaskCache pathCache = new ContourMaskCache();

    LauncherContourRenderer(View view) throws Exception { this(view.getContext()); }
    LauncherContourRenderer(Context context) throws Exception {
        Context module = context.createPackageContext("dev.rikumi.flymemod", 0);
        round = new RuntimeShader(source(module, "contour_round"));
        nativeCurve = new RuntimeShader(source(module, "contour_icon_path"));
    }
    private static String source(Context context, String name) throws Exception {
        try (java.io.InputStream input = context.getAssets().open("coloros_material/" + name + ".agsl")) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    void drawMenu(View view, Canvas canvas, RectF bounds, float radius) {
        draw(view, canvas, bounds, radius, 1f, null);
    }
    void draw(View view, Canvas canvas, RectF bounds, float radius, float alpha, Bitmap mask) {
        draw(view.getContext(),canvas,bounds,radius,alpha,mask);
    }
    void draw(Context context, Canvas canvas, RectF bounds, float radius, float alpha, Bitmap mask) {
        if (bounds.isEmpty() || alpha <= 0) return;
        RuntimeShader shader = mask == null ? round : nativeCurve;
        if (mask != null) {
            boolean changed=inputMask!=mask || inputGeneration!=mask.getGenerationId();
            if(changed) {
                inputMask=mask; inputGeneration=mask.getGenerationId();
                maskInput=new BitmapShader(mask,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP);
            }
            if(changed || inputWidth!=bounds.width() || inputHeight!=bounds.height()) {
                inputWidth=bounds.width(); inputHeight=bounds.height();
                inputScale.setScale(inputWidth/mask.getWidth(),inputHeight/mask.getHeight());
                maskInput.setLocalMatrix(inputScale); shader.setInputBuffer("uMask",maskInput);
            }
        }
        float width = bounds.width(), height = bounds.height();
        radius = Math.min(radius, Math.min(width, height)/2f);
        float coverageCorner = mask == null ? radius : maskCoverageHeight(mask, width, height);
        float density=context.getResources().getDisplayMetrics().density;
        boolean night = (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        UniformState state=mask==null?roundUniforms:nativeUniforms;
        if(!state.ready || state.width!=width || state.height!=height || state.radius!=radius
                || state.coverage!=coverageCorner || state.density!=density || state.night!=night) {
            shader.setFloatUniform("u_size", width, height);
            shader.setFloatUniform("u_fadeDistance", ContourLightGeometry.fadeDistance(width, height, coverageCorner, false));
            shader.setFloatUniform("uRatio", .5f + width/(4f*(width+height)));
            shader.setFloatUniform("u_corner", radius); shader.setFloatUniform("u_weight", .2f);
            shader.setColorUniform("uColor", Color.WHITE);
            near[0] = far[0] = 2f*density;
            near[1] = .4f * (night ? .4f : 1f);
            far[1] = .2f * (night ? .4f : 1f);
            ContourInnerLight.configure(shader,density,night);
            shader.setFloatUniform("uNearLineParams",near); shader.setFloatUniform("uFarLineParams",far);
            state.width=width; state.height=height; state.radius=radius; state.coverage=coverageCorner;
            state.density=density; state.night=night; state.ready=true;
        }
        paint.setShader(shader); paint.setAlpha(Math.round(255f*Math.max(0f, Math.min(1f, alpha))));
        int save = canvas.save(); canvas.translate(bounds.left, bounds.top);
        try { canvas.drawRect(0, 0, width, height, paint); }
        finally { canvas.restoreToCount(save); }
    }
    private float maskCoverageHeight(Bitmap mask, float width, float height) {
        if (coverageMask != mask || coverageGeneration!=mask.getGenerationId()) {
            int w=mask.getWidth(), h=mask.getHeight(); int[] pixels=new int[w*h];
            mask.getPixels(pixels,0,w,0,0,w,h);
            int left=w, right=-1, top=h, bottom=-1;
            for(int y=0;y<h;y++) for(int x=0;x<w;x++) if((pixels[y*w+x]>>>24)>=128) {
                left=Math.min(left,x); right=Math.max(right,x); top=Math.min(top,y); bottom=Math.max(bottom,y);
            }
            int leftTop=top, rightTop=top;
            if(right>=left) {
                while(leftTop<=bottom && (pixels[leftTop*w+left]>>>24)<128) leftTop++;
                while(rightTop<=bottom && (pixels[rightTop*w+right]>>>24)<128) rightTop++;
            }
            coverageHeight=Math.max(0,Math.min(h/2f,Math.max(leftTop,rightTop)-top))/(float)h;
            coverageMask=mask;
            coverageGeneration=mask.getGenerationId();
        }
        return Math.min(width/2f,coverageHeight*height);
    }
    private static final class UniformState {
        boolean ready, night;
        float width,height,radius,coverage,density;
    }
    Bitmap pathMask(View view, Path path, int width, int height) {
        float stroke = 2f * view.getResources().getDisplayMetrics().density;
        // Native outlines may rebuild an identical Path each frame. Compare the
        // geometry before rasterizing another bitmap, as notification contours do.
        if (pathCache.matches(path,width,height,stroke)) return pathMask;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas mask = new Canvas(bitmap); mask.clipPath(path);
        Paint coverage = new Paint(Paint.ANTI_ALIAS_FLAG);
        coverage.setColor(Color.RED); mask.drawPath(path, coverage);
        Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG); maskPaint.setStyle(Paint.Style.STROKE);
        // Same inward-distance mask encoding as ColorOsMaterialHooks. AGSL determines the light.
        for (float distance = stroke; distance > 0f; distance -= .5f) {
            maskPaint.setColor(Color.argb(255, Math.round(255f*Math.min(1f, distance/stroke)), 0, 0));
            maskPaint.setStrokeWidth(distance*2f); mask.drawPath(path, maskPaint);
        }
        pathMask = bitmap; pathCache.remember(path,width,height,stroke);
        return bitmap;
    }
}
