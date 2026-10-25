package dev.rikumi.flymemod;

import android.graphics.ColorFilter;
import java.lang.reflect.Method;

final class ThemeBackgroundFilter {
    final ColorFilter filter;

    ThemeBackgroundFilter() throws ReflectiveOperationException { this(false); }

    ThemeBackgroundFilter(boolean storeGlobal) throws ReflectiveOperationException {
        this(storeGlobal ? DarkBackgroundPolicy.STORE_GLOBAL_SHADES : DarkBackgroundPolicy.SHADES);
    }

    ThemeBackgroundFilter(int[] shades) throws ReflectiveOperationException {
        StringBuilder agsl = new StringBuilder();
        for (int i = 0; i < shades.length; i++)
            agsl.append("layout(color) uniform half4 shade").append(i).append(";\n");
        agsl.append("half4 main(half4 c) {\n if (c.a <= 0.0001) return c;\n half3 rgb = c.rgb / c.a;\n half distance = 1.0;\n half3 delta;\n");
        for (int i = 0; i < shades.length; i++)
            agsl.append("delta = abs(rgb - shade").append(i).append(".rgb);\n")
                    .append("distance = min(distance, max(max(delta.r, delta.g), delta.b));\n");
        agsl.append("return distance <= 0.004 ? half4(0, 0, 0, c.a) : c;\n}\n");
        Class<?> type = Class.forName("android.graphics.RuntimeColorFilter");
        filter = (ColorFilter) type.getConstructor(String.class).newInstance(agsl.toString());
        Method setColor = type.getMethod("setColorUniform", String.class, int.class);
        for (int i = 0; i < shades.length; i++)
            setColor.invoke(filter, "shade" + i, 0xff000000 | shades[i]);
    }
}
