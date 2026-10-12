import json
import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]

def module(name, path):
    spec = importlib.util.spec_from_file_location(name, ROOT / path)
    loaded = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(loaded)
    return loaded

extract = module('extract_theme', 'scripts/extract-theme-apps.py')
generator = module('generate_theme', 'scripts/generate-theme-targets.py')

class InventoryTests(unittest.TestCase):
    def test_ignore_hidden_old_versions(self):
        text = ('Packages:\n  Package [com.meizu.test] (current):\n    versionCode=20\n'
                'Hidden system packages:\n  Package [com.meizu.test] (old):\n    versionCode=10\n')
        self.assertIn('versionCode=20', extract.package_blocks(text)['com.meizu.test'])

    def test_rgb_filter(self):
        self.assertTrue(generator.blue(0x206cff))
        self.assertTrue(generator.blue(0xe1ecfe))
        self.assertTrue(generator.blue(0x001749))
        self.assertFalse(generator.blue(0xff0000))
        self.assertFalse(generator.blue(0xffffff))
        self.assertFalse(generator.blue(0x00bfbf))
        self.assertFalse(generator.blue(0x37474f))
        self.assertTrue(generator.blue(0xeff6ff))

    def test_theme_families(self):
        for rgb, family in [(0x35c44f, 'green'), (0x29b15b, 'green'), (0xeffbf3, 'green'),
                            (0x012d09, 'green'), (0xe42d22, 'red'), (0xba1a1a, 'red'),
                            (0xffedea, 'red'), (0x410001, 'red'), (0xffbe0a, 'yellow'),
                            (0xdca50c, 'yellow'), (0xfff2ce, 'yellow'), (0x392600, 'yellow')]:
            self.assertEqual(generator.theme_family(rgb), family)
        for rgb, family in [(0xff7024, "orange"), (0x722ae4, "purple"), (0x00bfbf, "cyan")]:
            self.assertEqual(generator.theme_family(rgb), family)
        for rgb in [0x808080, 0xffffff]:
            self.assertIsNone(generator.theme_family(rgb))

    def test_aliases_framework_and_scope(self):
        with tempfile.TemporaryDirectory(dir='/tmp') as temporary:
            root = Path(temporary)
            (root/'app/src/main/java/dev/rikumi/flymemod').mkdir(parents=True)
            scopes = root/'app/src/main/resources/META-INF/xposed/scope.list'
            scopes.parent.mkdir(parents=True)
            scopes.write_text('system\ncom.meizu.test\n')
            (root/'reference-apks/theme-meizu22').mkdir(parents=True)
            artifact = dict(path='base.apk', sha256='test', certificate_sha256=['test'], colors=[
                dict(name='theme_primary', colors=[], references=['actual_value']),
                dict(name='actual_value', colors=[['night','801564ed']], references=[]),
                dict(name='theme_error', colors=[['','ffff0000']], references=[]),
            ])
            applications = [dict(package=p,version_code=1,activities=['screen'],launcher_activities=['launcher'] if p!='android' else [],artifacts=[artifact])
                            for p in ['android','com.meizu.test','com.meizu.media.camera']]
            (root/'reference-apks/theme-meizu22/theme-audit.json').write_text(json.dumps({p: dict(family='blue', status='resolved', evidence=[]) for p in ['android','com.meizu.test']}))
            generator.generate(dict(fingerprint='fixture',applications=applications), root)
            generated=(root/'app/src/main/java/dev/rikumi/flymemod/ThemeColorTargets.java').read_text()
            self.assertIn('0x1564ed',generated)
            self.assertIn('"com.meizu.test:actual_value"', generated)
            self.assertNotIn('com.meizu.media.camera', generated.split('private static final Set<String> RECOMMENDED')[0])
            self.assertNotIn('0xff0000',generated)
            self.assertNotIn('            "android",',generated)
            self.assertEqual(set(scopes.read_text().splitlines()),{'system','com.meizu.test','com.meizu.media.camera'})
            generator.generate(dict(fingerprint='fixture', applications=applications,
                                    installed_user0_packages=['android', 'com.meizu.media.camera']), root)
            self.assertEqual(set(scopes.read_text().splitlines()), {'system', 'com.meizu.media.camera'})
            self.assertNotIn('"com.meizu.test"',
                             (root/'app/src/main/java/dev/rikumi/flymemod/ThemeColorTargets.java').read_text())

    def test_reviewed_app_theme_families(self):
        audit = json.loads((ROOT/'reference-apks/theme-meizu22/theme-audit.json').read_text())
        for package, family in {'com.meizu.flyme.launcher': 'blue', 'com.meizu.filemanager': 'yellow',
                                'com.meizu.media.gallery': 'red', 'com.android.mms': 'green',
                                'com.android.alarmclock': 'orange', 'com.android.packageinstaller': 'green'}.items():
            self.assertEqual(audit[package]['family'], family)
            self.assertTrue(audit[package]['evidence'])
        self.assertEqual(audit['com.android.alarmclock']['legacy_background_family'], 1)
        self.assertEqual(audit['com.meizu.mstore']['legacy_background_family'], 1)
        self.assertEqual(audit['com.android.settings']['legacy_background_family'], 0)
        generated = (ROOT/'app/src/main/java/dev/rikumi/flymemod/ThemeColorTargets.java').read_text()
        self.assertIn('com.android.settings:mz_btn_color_state', generated)
        report = json.loads((ROOT/'reference-apks/theme-meizu22/theme-targets.json').read_text())
        for app in report['applications']:
            for rgb in app['theme_colors']:
                self.assertEqual(generator.theme_family(int(rgb,16)), app['theme_family'])

if __name__ == '__main__':
    unittest.main()
