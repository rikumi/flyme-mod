package dev.rikumi.flymemod;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.*;
import android.view.View;
import java.nio.charset.StandardCharsets;

/** Shared white contour geometry for backgrounds and alpha silhouettes. */
final class LauncherContourRenderer {
    private final RuntimeShader round, nativeCurve;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] near = {0, .4f, .2f, .2f, 0, 0};
    private final float[] far = {0, .2f, .2f, .2f, 0, 0};
    private Bitmap pathMask;
    private Bitmap coverageMask;
    private float coverageHeight;
    private Path lastPath;
    private int lastGeneration, lastWidth, lastHeight;
    private float lastStroke;

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
            BitmapShader input = new BitmapShader(mask, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Matrix scale = new Matrix(); scale.setScale(bounds.width()/mask.getWidth(), bounds.height()/mask.getHeight());
            input.setLocalMatrix(scale); shader.setInputBuffer("uMask", input);
        }
        float width = bounds.width(), height = bounds.height();
        radius = Math.min(radius, Math.min(width, height)/2f);
        shader.setFloatUniform("u_size", width, height);
        float coverageCorner = mask == null ? radius : maskCoverageHeight(mask, width, height);
        shader.setFloatUniform("u_fadeDistance", ContourLightGeometry.fadeDistance(width, height, coverageCorner, false));
        shader.setFloatUniform("uRatio", .5f + width/(4f*(width+height)));
        shader.setFloatUniform("u_corner", radius); shader.setFloatUniform("u_weight", .2f);
        shader.setColorUniform("uColor", Color.WHITE);
        near[0] = far[0] = 2f * context.getResources().getDisplayMetrics().density;
        boolean night = (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        near[1] = night ? .2f : .4f;
        shader.setFloatUniform("uNearLineParams", near); shader.setFloatUniform("uFarLineParams", far);
        paint.setShader(shader); paint.setAlpha(Math.round(255f*Math.max(0f, Math.min(1f, alpha))));
        int save = canvas.save(); canvas.translate(bounds.left, bounds.top);
        try { canvas.drawRect(0, 0, width, height, paint); }
        finally { canvas.restoreToCount(save); }
    }
    private float maskCoverageHeight(Bitmap mask, float width, float height) {
        if (coverageMask != mask) {
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
        }
        return Math.min(width/2f,coverageHeight*height);
    }
    Bitmap pathMask(View view, Path path, int width, int height) {
        float stroke = 2f * view.getResources().getDisplayMetrics().density;
        if (lastPath == path && lastGeneration == path.getGenerationId() && lastWidth == width
                && lastHeight == height && lastStroke == stroke) return pathMask;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas mask = new Canvas(bitmap); mask.clipPath(path);
        Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG); maskPaint.setStyle(Paint.Style.STROKE);
        // Same inward-distance mask encoding as ColorOsMaterialHooks. AGSL determines the light.
        for (float distance = stroke; distance > 0f; distance -= .5f) {
            maskPaint.setColor(Color.argb(255, Math.round(255f*Math.min(1f, distance/stroke)), 0, 0));
            maskPaint.setStrokeWidth(distance*2f); mask.drawPath(path, maskPaint);
        }
        pathMask = bitmap; lastPath = path; lastGeneration = path.getGenerationId();
        lastWidth = width; lastHeight = height; lastStroke = stroke;
        return bitmap;
    }
}
