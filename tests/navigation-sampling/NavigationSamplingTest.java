package dev.rikumi.flymemod;

public final class NavigationSamplingTest {
    private static int checks;
    private static void check(boolean value, String name) {
        checks++;
        if (!value) throw new AssertionError(name);
    }
    public static void main(String[] args) {
        NavigationSampling sampling = new NavigationSampling(0, 2400, 1200, 80);
        final int white = 0xffffffff, grey = 0xff808080;
        check(sampling.sample((x,y) -> x < 16 || x >= 1184 ? white : grey) == white,
                "Central grey covering 97% of width keeps white sides");
        check(sampling.sample((x,y) -> grey) == grey, "Uniform grey remains grey");
        check(sampling.sample((x,y) -> x < 600 ? white : 0xff000000) == null,
                "Disagreeing edges reject arbitrary selection and blended result");
        check(sampling.sample((x,y) -> y >= sampling.captureHeight - 3 ? white
                : (x < 600 ? grey : 0xff000000)) == white,
                "Nonuniform sides fall back to stable bottom line");
        check(sampling.sample((x,y) -> 0x00ffffff) == null, "Transparent capture rejected");
        check(sampling.sample((x,y) -> ((x + y) % 2 == 0 ? white : 0xfffdfdfd)) == 0xfffefefe,
                "Small color noise accepted with mean color");
        check(sampling.sample((x,y) -> (x + y) % 2 == 0 ? white : grey) == null,
                "High variance pattern rejected");
        check(sampling.captureTop + sampling.captureHeight == sampling.navigationTop - 1,
                "Capture excludes backing seam");
        for (int width : new int[]{1, 2, 3, 4, 8, 17, 19, 20, 100, 1216}) {
            for (int top : new int[]{2, 3, 8, 11, 2400}) {
                NavigationSampling small = new NavigationSampling(0, top, width, 1);
                check(small.sample((x,y) -> {
                    if (x < 0 || x >= small.width || y < 0 || y >= small.captureHeight)
                        throw new AssertionError("Out of bounds");
                    return white;
                }) == white, "Narrow and shallow bounds");
            }
        }
        NavigationColorSampler boundary = new NavigationColorSampler();
        boundary.add(0xff000000); boundary.add(0xff0a0a0a);
        check(boundary.color(25) == 0xff050505, "Exact variance threshold accepted");
        check(boundary.color(24) == null, "Above variance threshold rejected");
        System.out.println("PASS: " + checks + " navigation variance checks");
    }
}
