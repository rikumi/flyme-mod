"""Both device-information toggles must publish on individual changes."""
from pathlib import Path
root = Path('app/src/main/java/dev/rikumi/flymemod')
bridge = (root / 'CameraSettingsBridge.java').read_text()
listener = bridge.split('OnSharedPreferenceChangeListener changes =')[1].split('@Override public void onCreate')[0]
catalog = (root / 'SettingsCatalog.kt').read_text()
group = catalog.split('SettingsGroup("系统设置", listOf(')[1].split('        )),')[0]
for key in ('ANDROID_VERSION_KERNEL', 'STATUS_BATTERY_CAPACITY'):
    assert f'ModuleSettings.{key}.equals(key)' in listener
    assert f'.putBoolean(ModuleSettings.{key},' in bridge
    assert f'SwitchItem(ModuleSettings.{key},' in group
print('PASS: both toggles publish changes and belong to system settings')
