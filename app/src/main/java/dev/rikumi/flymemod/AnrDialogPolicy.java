package dev.rikumi.flymemod;

/** Native Flyme ANR accent selector is blue; red danger states and neutrals remain native. */
final class AnrDialogPolicy {
    static int buttonColor(int original, int selected) {
        return ThemeColorPalette.family(original) == 1
                ? (original & 0xff000000) | (selected & 0xffffff) : original;
    }
}
