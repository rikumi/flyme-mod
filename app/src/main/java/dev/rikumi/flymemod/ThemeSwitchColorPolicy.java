package dev.rikumi.flymemod;

/** Only the verified Flyme Switch thumb state slots use this conversion. */
final class ThemeSwitchColorPolicy {
    static int thumb(int nativeColor, boolean enabled) {
        return enabled ? (nativeColor & 0xff000000) | 0xffffff : nativeColor;
    }
}
