package dev.rikumi.flymemod;

/** Native slider tracks verified in Flyme APK resources, never arbitrary icon artwork. */
final class ThemeControlResources {
    static boolean spectralSlider(String pkg, Class<?> type) {
        if (!"com.android.settings".equals(pkg)) return false;
        for (; type != null; type = type.getSuperclass())
            if ("com.meizu.settings.coloradjust.MzBreakerSeekbar".equals(type.getName())) return true;
        return false;
    }
    static boolean inputView(Class<?> type) {
        for(;type!=null;type=type.getSuperclass())
            if("android.widget.EditText".equals(type.getName()))return true;
        return false;
    }

    static boolean iconStyleBorder(String pkg,String id) {
        return "com.meizu.flyme.launcher".equals(pkg) && "icon_pack_item_bg".equals(id);
    }

    static boolean sliderView(Class<?> type) {
        for(;type!=null;type=type.getSuperclass()) {
            String name=type.getName();
            if("android.widget.AbsSeekBar".equals(name)
                    ||"com.meizu.common.widget.AbsSeekBar".equals(name))return true;
        }
        return false;
    }

    static boolean sliderTrack(String name) {
        return "qs_slider_progress_drawable".equals(name)
                || "mz_seekbar_progress".equals(name)
                || "mz_seekbar_rectangle_progress".equals(name);
    }
}
