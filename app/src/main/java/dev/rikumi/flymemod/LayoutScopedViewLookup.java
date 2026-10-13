package dev.rikumi.flymemod;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

/** Cache both hits and misses until the window hierarchy has been laid out again. */
final class LayoutScopedViewLookup {
    private final Class<?> target;
    private final Map<View, Window> windows=new WeakHashMap<>();

    LayoutScopedViewLookup(Class<?> target) { this.target=target; }

    View find(View view) {
        // A tile's own grid is authoritative even if another page was cached first.
        for(View ancestor=view; ancestor!=null;
                ancestor=ancestor.getParent() instanceof View parent?parent:null)
            if(target.isInstance(ancestor))return ancestor;
        View root=view.getRootView();
        Window window=windows.get(root);
        if(window==null) {
            window=new Window(); windows.put(root,window);
            root.addOnAttachStateChangeListener(window);
            if(root.isAttachedToWindow())window.onViewAttachedToWindow(root);
        }
        View found=window.result.get();
        if(found!=null && found.getRootView()==root)return found;
        if(found!=null)window.searched=false;
        if(!window.searched) {
            found=search(root);
            window.result=new WeakReference<>(found);
            window.searched=true;
        }
        return found;
    }

    private View search(View view) {
        if(target.isInstance(view))return view;
        if(view instanceof ViewGroup group)for(int i=0;i<group.getChildCount();i++) {
            View found=search(group.getChildAt(i));
            if(found!=null)return found;
        }
        return null;
    }

    private static final class Window implements View.OnAttachStateChangeListener,
            ViewTreeObserver.OnGlobalLayoutListener {
        WeakReference<View> result=new WeakReference<>(null);
        boolean searched;
        ViewTreeObserver observer;
        @Override public void onGlobalLayout() { result.clear(); searched=false; }
        @Override public void onViewAttachedToWindow(View view) {
            if(observer==view.getViewTreeObserver() && observer.isAlive())return;
            onGlobalLayout();
            observer=view.getViewTreeObserver(); observer.addOnGlobalLayoutListener(this);
        }
        @Override public void onViewDetachedFromWindow(View view) {
            if(observer!=null && observer.isAlive())observer.removeOnGlobalLayoutListener(this);
            observer=null; onGlobalLayout();
        }
    }
}
