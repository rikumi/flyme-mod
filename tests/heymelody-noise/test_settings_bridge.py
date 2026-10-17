"""Check the cross-process setting contract, not only the SystemUI-local switch."""
from pathlib import Path
root = Path(__file__).resolve().parents[2] / 'app/src/main/java/dev/rikumi/flymemod'
publisher = (root / 'CameraSettingsBridge.java').read_text()
consumer = (root / 'HeyMelodyNoiseBridge.java').read_text()
entry = (root / 'XposedInit.java').read_text()
key = 'ModuleSettings.EARPHONE_NOISE_CONTROL'
assert f'{key}.equals(key)' in publisher.split('private void publish()')[0]
assert f'.putBoolean({key},\n                                local.getBoolean({key}, false))' in publisher
assert f'preferences.getBoolean({key}, false)' in consumer
assert 'new HeyMelodyNoiseBridge(getRemotePreferences(CameraSettingsBridge.REMOTE_FILE)' in entry
print('PASS: HeyMelody remote setting publication/listener/default/consumer contract')
