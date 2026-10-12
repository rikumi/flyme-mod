package dev.rikumi.flymemod;

import android.graphics.ColorFilter;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import java.lang.reflect.Method;

/** A pixel color filter preserves neutral glyph details inside multicolored UI resources. */
final class ThemeIconFilter {
    private static final String BLUE_AGSL = "layout(color) uniform half4 themeColor;\n"
            + "half4 main(half4 c) {\n"
            + "  if (c.a <= 0.0001) return c;\n"
            + "  half3 rgb = c.rgb / c.a;\n"
            + "  half chroma = max(max(rgb.r, rgb.g), rgb.b) - min(min(rgb.r, rgb.g), rgb.b);\n"
            + "  if (chroma < 0.04 || rgb.b <= rgb.r + 0.08 || rgb.b <= rgb.g + 0.05 || rgb.g < rgb.r * 0.7) return c;\n"
            + "  half amount = smoothstep(0.04, 0.10, chroma);\n"
            + "  return half4(mix(rgb, themeColor.rgb, amount) * c.a, c.a);\n"
            + "}\n";
    static final String AGSL = "layout(color) uniform half4 themeColor;\n"
            + "uniform float cutoutWhite;\n"
            + "uniform float preserveRed;\n"
            + "half4 main(half4 c) {\n"
            + "  if (c.a <= 0.0001) return c;\n"
            + "  half3 rgb = c.rgb / c.a;\n"
            + "  half chroma = max(max(rgb.r, rgb.g), rgb.b) - min(min(rgb.r, rgb.g), rgb.b);\n"
            + "  half red = step(max(rgb.g, rgb.b), rgb.r) * step(abs(rgb.g - rgb.b), chroma * 0.25 + 0.001) * step(0.01, chroma);\n"
            + "  if (preserveRed > 0.5 && red > 0.5) return c;\n"
            + "  half amount = max(half(cutoutWhite), smoothstep(0.03, 0.10, chroma));\n"
            + "  half white = smoothstep(0.90, 0.98, min(min(rgb.r, rgb.g), rgb.b)) * (1 - smoothstep(0.02, 0.08, chroma));\n"
            + "  half alpha = c.a * (1 - white * half(cutoutWhite));\n"
            + "  return half4(mix(rgb, themeColor.rgb, amount) * alpha, alpha);\n"
            + "}\n";
    final ColorFilter colorParts;
    final ColorFilter whiteCutout;
    final ColorFilter preserveRedParts;
    final ColorFilter blueParts;
    private final ColorFilter inactiveLight = new PorterDuffColorFilter(0xff757575, PorterDuff.Mode.SRC_IN);
    private final ColorFilter inactiveDark = new PorterDuffColorFilter(0xffb0b0b0, PorterDuff.Mode.SRC_IN);

    ThemeIconFilter(int color) throws ReflectiveOperationException {
        // Verified against the MEIZU 22 framework; avoid linking this new API on older systems.
        Class<?> type = Class.forName("android.graphics.RuntimeColorFilter");
        Method setColor = type.getMethod("setColorUniform", String.class, int.class);
        colorParts = create(type, setColor, color, 0f, 0f);
        whiteCutout = create(type, setColor, color, 1f, 0f);
        preserveRedParts = create(type, setColor, color, 0f, 1f);
        Object blue = type.getConstructor(String.class).newInstance(BLUE_AGSL);
        setColor.invoke(blue, "themeColor", color | 0xff000000);
        blueParts = (ColorFilter) blue;
    }

    ColorFilter forMode(int mode, boolean night) {
        if (mode == ThemeIconPolicy.INACTIVE_TAB) return night ? inactiveDark : inactiveLight;
        return mode == ThemeIconPolicy.WHITE_CUTOUT ? whiteCutout :
                mode == ThemeIconPolicy.PRESERVE_RED ? preserveRedParts : colorParts;
    }

    private static ColorFilter create(Class<?> type, Method setColor, int color, float cutout, float preserveRed)
            throws ReflectiveOperationException {
        Object filter = type.getConstructor(String.class).newInstance(AGSL);
        setColor.invoke(filter, "themeColor", color | 0xff000000);
        type.getMethod("setFloatUniform", String.class, float.class).invoke(filter, "cutoutWhite", cutout);
        type.getMethod("setFloatUniform", String.class, float.class).invoke(filter, "preserveRed", preserveRed);
        return (ColorFilter) filter;
    }
}
