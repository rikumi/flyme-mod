package dev.rikumi.flymemod;

public final class LauncherExtraRowPolicyTest {
    public static void main(String[] args) {
        check(LauncherExtraRowPolicy.isSupportedSize(1368, 3192));
        check(LauncherExtraRowPolicy.isSupportedSize(3192, 1368));
        check(LauncherExtraRowPolicy.isSupportedSize(1080, 2520));
        check(!LauncherExtraRowPolicy.isSupportedSize(1200, 2600));
        check(!LauncherExtraRowPolicy.isSupportedSize(1080, 2400));
        check(!LauncherExtraRowPolicy.isSupportedSize(1080, 1920));
        check(!LauncherExtraRowPolicy.isSupportedSize(0, 2520));
        for (int rows = 4; rows < 12; rows++) {
            for (int height = 100; height < 500; height++) {
                int scaled = LauncherExtraRowPolicy.rowHeight(height, rows + 1);
                check(Math.abs(scaled * (rows + 1) - height * rows) <= (rows + 1) / 2.0);
                check(scaled > 0 && scaled < height);
            }
        }
        System.out.println("PASS: physical display ratio gate and 3200 row-height configurations");
    }
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
}
