"""Check the cross-process setting contract, not only the SystemUI-local switch."""
from pathlib import Path
root = Path(__file__).resolve().parents[2] / 'app/src/main/java/dev/rikumi/flymemod'
publisher = (root / 'CameraSettingsBridge.java').read_text()
consumer = (root / 'HeyMelodyNoiseBridge.java').read_text()
entry = (root / 'XposedInit.java').read_text()
for key in ('ModuleSettings.EARPHONE_NOISE_CONTROL', 'ModuleSettings.HEYMELODY_NOISE_CONTROL'):
    assert f'{key}.equals(key)' in publisher.split('private void publish()')[0]
    assert f'.putBoolean({key},\n                                local.getBoolean({key}, false))' in publisher
    assert f'preferences.getBoolean({key}, false)' in consumer
    assert f'{key}.equals(key)' in consumer.split('HeyMelodyNoiseBridge(SharedPreferences')[0]
provider = (root / 'SettingsProvider.java').read_text()
assert 'columns.add(ModuleSettings.HEYMELODY_NOISE_CONTROL)' in provider
assert 'values.add(prefs.getBoolean(ModuleSettings.HEYMELODY_NOISE_CONTROL, false) ? 1 : 0)' in provider
assert '&& preferences.getBoolean(ModuleSettings.HEYMELODY_NOISE_CONTROL, false)' in consumer
catalog = (root / 'SettingsCatalog.kt').read_text()
assert catalog.index('SwitchItem(ModuleSettings.EARPHONE_NOISE_CONTROL') < catalog.index('SwitchItem(ModuleSettings.HEYMELODY_NOISE_CONTROL') < catalog.index('SelectItem(ModuleSettings.VOLUME_FIRST_FOUR')
assert 'enabledSubtitle = "需安装欢律并完成绑定"' in catalog
assert 'new HeyMelodyNoiseBridge(getRemotePreferences(CameraSettingsBridge.REMOTE_FILE)' in entry
print('PASS: HeyMelody remote setting publication/listener/default/consumer contract')
