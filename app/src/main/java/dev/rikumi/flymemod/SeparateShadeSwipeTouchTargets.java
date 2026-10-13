package dev.rikumi.flymemod;

import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.SeekBar;

final class SeparateShadeSwipeTouchTargets {
    private SeparateShadeSwipeTouchTargets() {}

    static boolean reserved(View view, float x, float y, View tilePager) {
        if (view == null || view.getVisibility() != View.VISIBLE) return false;
        Rect bounds = new Rect();
        if (!view.getGlobalVisibleRect(bounds) || !bounds.contains((int) x, (int) y)) return false;
        String name = view.getClass().getName();
        // ViewPager2$RecyclerViewImpl also contains "ViewPager" in its name. It belongs
        // to this tile pager, rather than being an independent nested paging target.
        boolean tilePagerMember = belongsTo(view, tilePager);
        boolean pagerInternal = tilePagerMember && name.startsWith(tilePager.getClass().getName() + "$");
        if (view instanceof SeekBar || name.contains("Slider") || name.contains("MediaScrollView")
                || (name.contains("ViewPager") && view != tilePager && !pagerInternal) || name.contains("HorizontalScrollView")
                || name.endsWith("ExpandableNotificationRow")) return true;
        if (view instanceof ViewGroup group) {
            for (int i = group.getChildCount() - 1; i >= 0; i--) if (reserved(group.getChildAt(i), x, y, tilePager)) return true;
        }
        return false;
    }

    private static boolean belongsTo(View view, View pager) {
        if (pager == null) return false;
        if (view == pager) return true;
        for (ViewParent parent = view.getParent(); parent != null; parent = parent.getParent()) if (parent == pager) return true;
        return false;
    }
}
