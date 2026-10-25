package dev.rikumi.flymemod;

final class ThemeSwitchColorPolicy {
    static int thumb(int nativeColor, boolean enabled) {
        return enabled ? (nativeColor & 0xff000000) | 0xffffff : nativeColor;
    }
}
