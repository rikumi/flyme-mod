from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "app/src/main/java/dev/rikumi/flymemod"


def test_custom_system_theme_color_is_removed_and_independent_appearance_hooks_remain():
    settings = (JAVA / "SettingsCatalog.kt").read_text()
    preset = (JAVA / "OneClickPreset.kt").read_text()
    provider = (JAVA / "SettingsProvider.java").read_text()
    bridge = (JAVA / "CameraSettingsBridge.java").read_text()
    hooks = (JAVA / "ThemeColorHooks.java").read_text()
    anr = (JAVA / "AnrDialogHooks.java").read_text()
    module_theme = (JAVA / "ModuleTheme.kt").read_text()
    readme = (ROOT / "README.md").read_text()

    assert "自定义系统主题色" not in settings
    assert "SYSTEM_THEME_COLOR" not in preset
    assert "SYSTEM_THEME_COLOR" not in provider
    assert "SYSTEM_THEME_COLOR" not in bridge
    assert "boolean nextEnabled = false;" in hooks
    assert "DARK_SYSTEM_APP_BLACK_BACKGROUND" in hooks
    assert "WIRELESS_DEBUG_THEME_FIX" in hooks or "wirelessDebugText.install" in hooks
    assert "SYSTEM_THEME_COLOR" not in anr
    assert "system_theme_color" not in module_theme
    assert "自定义系统主题色" not in readme
    assert not (JAVA / "LauncherAccentHooks.java").exists()
    assert not (JAVA / "ThemeColorTargets.java").exists()


if __name__ == "__main__":
    test_custom_system_theme_color_is_removed_and_independent_appearance_hooks_remain()
    print("PASS: custom system theme color removal and independent hooks")
