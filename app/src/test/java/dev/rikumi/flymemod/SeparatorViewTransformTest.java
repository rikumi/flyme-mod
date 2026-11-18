package dev.rikumi.flymemod;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;

/** Runs with app_process against Android's real View/Matrix implementation. */
public final class SeparatorViewTransformTest {
    private static int checks;
    private static void near(float expected, float actual) {
        checks++;
        if (Math.abs(expected-actual) > .001f) throw new AssertionError(expected+" != "+actual);
    }
    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getMethod("systemMain").invoke(null);
        Context context = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
        FrameLayout root = new FrameLayout(context), panel = new FrameLayout(context), branch = new FrameLayout(context);
        View stack = new View(context);
        root.addView(panel); root.addView(branch); branch.addView(stack);
        root.layout(0,0,1000,2000); panel.layout(20,40,920,1840);
        branch.layout(10,30,990,1950); stack.layout(5,7,905,1807);
        Matrix matrix = new Matrix(); RectF rect = new RectF();
        rect.set(400,600,444,604);
        near(1, SeparatorViewTransform.map(stack,panel,rect,matrix));
        near(400,rect.left); near(600,rect.top); near(44,rect.width());
        branch.setTranslationY(90);
        rect.set(400,600,444,604);
        SeparatorViewTransform.map(stack,panel,rect,matrix);
        near(690,rect.top);
        stack.setPivotX(450); stack.setPivotY(600); stack.setScaleX(.5f); stack.setScaleY(.5f);
        stack.setAlpha(.5f); branch.setAlpha(.6f); root.setAlpha(.4f);
        rect.set(400,600,444,604);
        near(.3f,SeparatorViewTransform.map(stack,panel,rect,matrix));
        near(422.5f,rect.left); near(688.5f,rect.top); near(22,rect.width()); near(2,rect.height());
        // Shared ancestor scrolling must cancel between sibling coordinate systems.
        root.scrollTo(30,50); rect.set(400,600,444,604);
        SeparatorViewTransform.map(stack,panel,rect,matrix);
        near(422.5f,rect.left); near(688.5f,rect.top);
        // Legacy layouts keep the notification stack inside the panel.
        branch.removeView(stack); panel.addView(stack); stack.layout(5,7,905,1807);
        stack.setScaleX(1); stack.setScaleY(1); stack.setAlpha(1);
        rect.set(400,600,444,604);
        near(1,SeparatorViewTransform.map(stack,panel,rect,matrix));
        near(400,rect.left); near(600,rect.top);
        panel.removeView(stack); rect.set(400,600,444,604);
        near(-1,SeparatorViewTransform.map(stack,panel,rect,matrix));
        System.out.println("PASS: "+checks+" separator sibling/ancestor animation checks");
        System.exit(0);
    }
}
