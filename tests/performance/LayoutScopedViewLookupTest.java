package dev.rikumi.flymemod;

import android.view.View;
import android.view.ViewGroup;

public final class LayoutScopedViewLookupTest {
    static final class Grid extends ViewGroup { }
    static void check(boolean condition,String message) {
        if(!condition)throw new AssertionError(message);
    }
    public static void main(String[] args) {
        LayoutScopedViewLookup lookup=new LayoutScopedViewLookup(Grid.class);
        ViewGroup root=new ViewGroup(); View row=new View(); root.add(row); root.attach();
        check(lookup.find(row)==null,"initial missing grid");
        int scans=root.scans;
        for(int i=0;i<1000;i++)check(lookup.find(row)==null,"cached miss");
        check(root.scans==scans,"missing grid must not rescan every notification frame");
        Grid grid=new Grid(); root.add(grid); root.getViewTreeObserver().layout();
        check(lookup.find(row)==grid,"layout must invalidate a cached miss");
        scans=root.scans;
        for(int i=0;i<1000;i++)check(lookup.find(row)==grid,"cached hit");
        check(root.scans==scans,"known grid must not rescan");
        Grid second=new Grid(); View tile=new View(); second.add(tile); root.add(second);
        check(lookup.find(tile)==second,"own page must take priority over another cached grid");
        root.remove(grid);
        check(lookup.find(row)==second,"detached grid must not remain cached");
        root.detach(); root.remove(second); root.attach();
        check(lookup.find(row)==null,"reattachment must discard old hierarchy");
        check(root.getViewTreeObserver().listeners.size()==1,"one listener per attached window");
        ViewGroup other=new ViewGroup(); Grid otherGrid=new Grid(); other.add(otherGrid); other.attach();
        root.remove(row); other.add(row);
        check(lookup.find(row)==otherGrid,"moving views between windows must use new root");
        root.detach();
        check(root.getViewTreeObserver().listeners.isEmpty(),"detach must unregister observer");
        System.out.println("PASS: cached misses/hits, layout replacement, detach, reparent, own-page priority");
    }
}
