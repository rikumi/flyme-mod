package dev.rikumi.flymemod;

public final class IconContourDrawScopeTest {
    private static int contours;
    private static void drawIcon() {
        if (!IconContourDrawScope.isRaw()) contours++;
    }
    public static void main(String[] args) throws Exception {
        try (IconContourDrawScope recording = IconContourDrawScope.enter()) {
            drawIcon();
            try (IconContourDrawScope adaptive = IconContourDrawScope.enter()) { drawIcon(); }
            drawIcon();
            Thread desktop = new Thread(IconContourDrawScopeTest::drawIcon);
            desktop.start(); desktop.join();
            if (contours != 1) throw new AssertionError("Recording leaked decoration or blocked desktop thread");
        }
        drawIcon();
        if (contours != 2) throw new AssertionError("Desktop contour did not resume");
        try (IconContourDrawScope failed = IconContourDrawScope.enter()) {
            throw new IllegalStateException("Native recording failure");
        } catch (IllegalStateException expected) { }
        if (IconContourDrawScope.isRaw()) throw new AssertionError("Failure left decoration suppressed");
        System.out.println("PASS: raw nested recordings, concurrent desktop drawing, restoration after failure");
    }
}
