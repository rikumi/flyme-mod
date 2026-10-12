"""Resolve each APK's declared theme through its real style inheritance, not bundled palettes."""
from collections import Counter
from pathlib import Path
import re
import subprocess

FAMILIES = {'red', 'yellow', 'blue', 'green', 'orange'}
AAPT = Path('/Users/rikumi/Library/Android/sdk/build-tools/36.0.0/aapt2')


def resources(text):
    result = {}
    for match in re.finditer(r'^    resource (0x[0-9a-f]+) (\w+)/([^\n]+)\n(.*?)(?=^    resource |\Z)', text, re.M | re.S):
        rid, kind, name, body = match.groups()
        name = name.split()[0]
        parent = re.search(r'parent=.*?(0x[0-9a-f]+)', body)
        items = dict(re.findall(r'^        ([^=\n]+)=(.+)$', body, re.M))
        result[rid] = dict(kind=kind, name=name, parent=parent[1] if parent else None, items=items, body=body)
    return result


def audit(app, root, framework, family):
    data = dict(framework)
    for artifact in app['artifacts']:
        path = root / 'reference-apks/theme-meizu22' / artifact['path']
        dump = path.with_name(path.stem + '-resources.txt')
        if dump.exists():
            data.update(resources(dump.read_text()))
    named = {(v['kind'], v['name']): k for k, v in data.items()}

    def resolve_color(value, seen=None):
        seen = set() if seen is None else seen
        if value.startswith('#'):
            return family(int(value[1:], 16) & 0xffffff)
        if value.startswith('@'):
            ref = value[1:]
            rid = ref if ref.startswith('0x') else named.get(tuple(ref.split('/', 1)))
            if rid in data and rid not in seen:
                seen.add(rid)
                body = data[rid]['body']
                literal = re.search(r'^      \(\) #(\w+)', body, re.M)
                if literal:
                    return family(int(literal[1], 16) & 0xffffff)
                reference = re.search(r'^      \(\) (@[^\s]+)', body, re.M)
                if reference:
                    return resolve_color(reference[1], seen)
        return None

    def theme(rid):
        seen, chain = set(), []
        while rid in data and rid not in seen:
            seen.add(rid)
            style = data[rid]
            chain.append(style['name'])
            # Explicit per-application attributes override the inherited Flyme theme.
            for attribute in ('colorAccent', 'colorPrimary'):
                for key, value in style['items'].items():
                    attr_id = re.search(r'0x[0-9a-f]+', key)
                    attr_name = data.get(attr_id[0], {}).get('name') if attr_id else None
                    if key.startswith(attribute + '(') or attr_name == attribute:
                        found = resolve_color(value)
                        if found in FAMILIES:
                            return found, chain + [attribute + '=' + value]
            color = re.search(r'(?:^|\.)Color\.(Red|Yellow|Blue|Green|Orange)(?:\.|$)', style['name'], re.I)
            if color:
                return color[1].lower(), chain
            rid = style['parent']
        return None, chain

    declared_themes = []
    if app['package'] == 'android':
        candidates = [k for k, v in data.items() if v['kind'] == 'style' and v['name'] == 'Theme.Flyme.Light']
        checks = [(theme(k), 100, 'framework default') for k in candidates]
    else:
        apk = root / 'reference-apks/theme-meizu22' / app['artifacts'][0]['path']
        manifest = subprocess.check_output([str(AAPT), 'dump', 'xmltree', str(apk), '--file', 'AndroidManifest.xml'], text=True)
        checks, element = [], ''
        for line in manifest.splitlines():
            node = re.search(r'E: ([\w-]+)', line)
            if node:
                element = node[1]
            declared = re.search(r':theme\(0x01010000\)=@(0x[0-9a-f]+)', line)
            if declared:
                declared_themes.append((declared[1], element))
                checks.append((theme(declared[1]), 100 if element == 'application' else 1, element))
    scores = Counter()
    for (color, chain), weight, element in checks:
        if color:
            scores[color] += weight
    chosen = scores.most_common(1)[0][0] if scores else None
    evidence = [dict(element=e, family=c, styles=s, weight=w) for (c, s), w, e in checks]
    # The AOSP application theme is only a trampoline/default dialog. Flyme's installation,
    # uninstallation and progress activities all declare green FlymeDialog/LitePopup themes.
    if app['package'] == 'com.android.packageinstaller' and any(
            item['family'] == 'green' and any(style.startswith(('FlymeDialogActivity', 'FlymePackageInstallerLitePopupTheme',
                                                              'FlymeLightLitePopupTheme')) for style in item['styles'])
            for item in evidence):
        chosen = 'green'
        evidence.append(dict(element='reviewed installer interfaces', family='green',
                             styles=['FlymeDialogActivity', 'FlymePackageInstallerLitePopupTheme'], weight=1000))

    window = None
    application_theme = next((rid for rid, element in declared_themes if element == 'application'), None)
    seen = set()
    while application_theme in data and application_theme not in seen:
        seen.add(application_theme)
        style = data[application_theme]
        for key, value in style['items'].items():
            if '0x01010054' in key:
                window = dict(style=style['name'], value=value)
                break
        if window:
            break
        application_theme = style['parent']
    legacy = 0
    # Shared Flyme base styles are bundled by new and old applications alike; they are not evidence.
    if window and not window['style'].startswith(('Theme.Platform.', 'Theme.Flyme.', 'Base.Theme.Flyme.', 'Theme.DeviceDefault.')):
        if window['value'] in ('?attr/colorSurfaceBright', '@color/fd_sys_color_surface_bright_default'):
            legacy = 1
        elif window['value'] in ('?attr/colorSurfaceContainerLowest', '@color/fd_sys_color_surface_container_lowest_default'):
            legacy = 2
    if app['package'] == 'com.meizu.connectivitysettings' and window and window['value'] == '?attr/colorSurfaceBright':
        legacy = 1  # Standalone Wi-Fi/Bluetooth/hotspot UI uses this declared window surface.
    if app['package'] == 'com.meizu.mstore':
        legacy = 1  # Device feedback confirms the store uses the old page surface, despite mixed resources.
    return dict(family=chosen, status='resolved' if chosen else 'unresolved', evidence=evidence, window_background=window, legacy_background_family=legacy)


if __name__ == '__main__':
    import argparse
    import importlib.util
    import json
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('inventory', type=Path)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parent.parent)
    args = parser.parse_args()
    spec = importlib.util.spec_from_file_location('generator', Path(__file__).with_name('generate-theme-targets.py'))
    generator = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(generator)
    inventory = json.loads(args.inventory.read_text())
    framework = resources((args.root / 'reference-apks/theme-meizu22/android/base-resources.txt').read_text())
    result = {app['package']: audit(app, args.root, framework, generator.theme_family)
              for app in inventory['applications'] if app['package'] not in generator.EXCLUDED}
    (args.root / 'reference-apks/theme-meizu22/theme-audit.json').write_text(json.dumps(result, indent=2) + '\n')
    print('Theme audit:', dict(Counter(value['family'] or 'unresolved' for value in result.values())))
