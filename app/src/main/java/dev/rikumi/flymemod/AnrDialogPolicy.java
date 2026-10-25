package dev.rikumi.flymemod;

final class AnrDialogPolicy {
    static int buttonColor(int original, int selected) {
        return ThemeColorPalette.family(original) == 1
                ? (original & 0xff000000) | (selected & 0xffffff) : original;
    }
}
