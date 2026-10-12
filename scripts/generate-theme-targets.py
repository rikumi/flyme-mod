#!/usr/bin/env python3
"""Generate application targets and verified theme RGB values from extracted APK resources."""
import argparse
import colorsys
import json
from pathlib import Path
import re

# These components host common interfaces even without a launcher icon.
BROAD_UI = {
    "com.android.systemui", "com.android.settings", "com.android.packageinstaller",
    "com.android.permissioncontroller", "com.android.intentresolver", "com.android.phone",
    "com.android.incallui", "com.meizu.connectivitysettings", "com.meizu.callsetting",
    "com.meizu.picker", "com.meizu.account", "com.meizu.flyme.sdkstage",
    "com.meizu.flyme.launcher",
}
EXCLUDED = {"com.meizu.media.camera", "com.meizu.media.music"}
# Keep these packages scoped for other hooks and pure-black backgrounds, but do not tint them.
COLOR_EXCLUDED = {"com.meizu.filemanager", "com.meizu.flyme.weather"}
# Weather must remain in the scope for its other hooks and pure-black background.
RETAIN_UNINSTALLED = {"com.meizu.flyme.weather"}
CORE_HOOKS = {"com.meizu.media.camera", "com.meizu.media.gallery", "com.meizu.mstore"}

# Checked against each app's resource values and call sites; broad name matching misses them.
EXTRA_THEME_NAMES = {
    "com.meizu.notepaper": {
        "color_ffbe0a", "dynamic_color", "coral_dark", "note_edit_tag",
        "rcpb_start_color", "rcpb_end_color", "text_dynamic_effect",
    },
}

THEME_NAME = re.compile(r'blue|green|red|yellow|orange|purple|cyan|polestar|primary|accent|theme|highlight|selected|activated|checked|positive|progress|link|focus|error|warning|button|btn|switch|spinner|loading|tab', re.I)


def theme_family(rgb):
    r, g, b = ((rgb >> shift & 255) / 255 for shift in (16, 8, 0))
    h, s, v = colorsys.rgb_to_hsv(r, g, b)
    hue = h * 360
    if s < (.04 if v >= .75 else .45) or v <= .05:
        return None
    if 195 <= hue <= 250:
        return 'blue'
    if 85 <= hue <= 165:
        return 'green'
    if hue <= 15 or hue >= 345:
        return 'red'
    if 36 <= hue <= 75:
        return 'yellow'
    if 15 < hue < 36:
        return 'orange'
    if 250 < hue < 345:
        return 'purple'
    if 165 < hue < 195:
        return 'cyan'
    return None


def blue(rgb):
    return theme_family(rgb) == 'blue'


def generate(inventory, root):
    audit_path = root / 'reference-apks/theme-meizu22/theme-audit.json'
    audits = json.loads(audit_path.read_text()) if audit_path.exists() else {}
    common = set()
    family_colors = {name: set() for name in ("blue", "green", "red", "yellow", "orange", "purple", "cyan")}
    records = []
    resource_names = set()
    primary_names = set()
    installed = set(inventory.get('installed_user0_packages', ()))
    active = lambda pkg: not installed or pkg in installed or pkg in RETAIN_UNINSTALLED
    for app in inventory['applications']:
        if app['package'] in EXCLUDED or not active(app['package']):
            continue
        audit = audits.get(app['package'], {'family': None, 'status': 'unresolved', 'evidence': []})
        app_family = audit['family']
        rgb_names = {}
        by_name = {}
        for artifact in app['artifacts']:
            for color in artifact['colors']:
                by_name.setdefault(color['name'], []).append(color)
        selected = {name for name in by_name if app['package'] not in COLOR_EXCLUDED
                    and (THEME_NAME.search(name) or name in EXTRA_THEME_NAMES.get(app['package'], ()))
                    and not re.search(r"blue[_-]?(grey|gray)", name, re.I)
                    and not (app['package'] == 'com.meizu.flyme.launcher' and
                             re.search(r'badge|red_point|notification_(dot|count)', name, re.I))}
        resource_names.update(app["package"] + ":" + name for name in selected)
        primary_names.update(app["package"] + ":" + name for name in selected if re.fullmatch(
            r'fd_sys_color_primary_(?:blue|green|red|yellow|orange|purple|cyan|polestar)|(?:mz_)?theme_color_(?:blue|green|red|yellow|orange|purple|cyan|polestar)|(?:mzpay_)?(?:Blue|Green|Red|Yellow|Orange|Purple|Cyan)_6|color_primary|color_accent|primary_color|accent_color', name, re.I))
        pending = list(selected)
        while pending:
            name = pending.pop()
            for color in by_name[name]:
                for ref in color['references']:
                    if ref in by_name and ref not in selected:
                        selected.add(ref)
                        pending.append(ref)
                        resource_names.add(app["package"] + ":" + ref)
        for name in selected:
            for color in by_name[name]:
                for config, value in color['colors']:
                    rgb = int(value, 16) & 0xffffff
                    family = theme_family(rgb)
                    if family and family == app_family:
                        family_colors[family].add(rgb)
                        rgb_names.setdefault(f'{rgb:06x}', []).append(name)
                        common.add(rgb)
        records.append(dict(package=app['package'], theme_family=app_family, theme_audit=audit, version_code=app['version_code'],
                            activity_count=len(app['activities']),
                            launcher_activities=app.get('launcher_activities', []),
                            artifacts=[{k: a[k] for k in ('path','sha256','certificate_sha256')} for a in app['artifacts']],
                            color_resource_names=sorted(selected),
                            theme_colors={k: sorted(set(v)) for k,v in sorted(rgb_names.items())}))
    # android supplies shared framework resources; its system-server process has no application UI.
    targets = sorted(app['package'] for app in inventory['applications'] if app['package'] != 'android'
                     and app['package'] not in EXCLUDED and active(app['package']))
    recommended = sorted({'system'} | {
        app['package'] for app in inventory['applications']
        if app['package'] != 'android' and active(app['package'])
        and (app['package'] not in EXCLUDED or app['package'] in CORE_HOOKS) and
        (app.get('launcher_activities') or app['package'] in BROAD_UI | CORE_HOOKS)
    })
    src = root / 'app/src/main/java/dev/rikumi/flymemod/ThemeColorTargets.java'
    text = '''package dev.rikumi.flymemod;

import java.util.Set;

/** Generated by scripts/generate-theme-targets.py from MEIZU 22 device APKs. */
final class ThemeColorTargets {
    private static final Set<String> PACKAGES = Set.of(
'''
    text += ',\n'.join('            "'+p+'"' for p in targets)+'\n    );\n\n'
    text += '    private static final Set<String> RECOMMENDED = Set.of(\n'
    text += ',\n'.join('            "'+p+'"' for p in recommended)+'\n    );\n\n'
    helpers = ""
    for field, names in [('COLOR_NAMES', resource_names), ('PRIMARY_NAMES', primary_names)]:
        chunks = [sorted(names)[i:i+400] for i in range(0, len(names), 400)]
        text += f'    private static final Set<String> {field} = {field.lower()}();\n\n'
        helpers += f'    private static Set<String> {field.lower()}() {{\n        Set<String> result = new java.util.HashSet<>();\n'
        for i, chunk in enumerate(chunks):
            helpers += f'        result.addAll({field.title().replace("_", "")}Chunk{i}.values());\n'
        helpers += '        return Set.copyOf(result);\n    }\n\n'
        for i, chunk in enumerate(chunks):
            helpers += f'    private static final class {field.title().replace("_", "")}Chunk{i} {{\n        static Set<String> values() {{\n            return Set.of(\n'
            helpers += ',\n'.join('            "'+name+'"' for name in chunk)+'\n            );\n        }\n    }\n\n'
    text += '    static int legacyBackgroundFamily(String packageName) {\n        return switch (packageName) {\n'
    for record in records:
        family = record['theme_audit'].get('legacy_background_family', 0)
        if family:
            text += f'            case "{record["package"]}" -> {family};\n'
    text += '            default -> 0;\n        };\n    }\n\n'
    text += '    static int themeFamily(String packageName) {\n        return switch (packageName) {\n'
    codes = {'blue': 1, 'green': 2, 'red': 3, 'yellow': 4, 'orange': 5}
    for record in records:
        if record['theme_family'] in codes:
            text += f'            case "{record["package"]}" -> {codes[record["theme_family"]]};\n'
    text += '            default -> 0;\n        };\n    }\n\n'
    text += '    static boolean colorExcluded(String packageName) {\n        return switch (packageName) {\n'
    for package in sorted(COLOR_EXCLUDED):
        text += f'            case "{package}" -> true;\n'
    text += '            default -> false;\n        };\n    }\n\n'
    text += '    private static final int[] THEME_RGB = {\n'
    values = [f'0x{x:06x}' for x in sorted(common)]
    for i in range(0,len(values),8):
        text += '            '+', '.join(values[i:i+8])+',\n'
    text += '''    };

    static boolean contains(String packageName) { return PACKAGES.contains(packageName); }
    static int[] colors() { return THEME_RGB.clone(); }
    static boolean isColorResource(String pkg, String name) { return COLOR_NAMES.contains(pkg + ":" + name); }
    static boolean isPrimaryResource(String pkg, String name) { return PRIMARY_NAMES.contains(pkg + ":" + name); }
    static Set<String> recommended() { return RECOMMENDED; }
}
'''
    text = text[:-2] + helpers + '}\n'
    src.write_text(text)
    scope = root / 'app/src/main/resources/META-INF/xposed/scope.list'
    scope.write_text('\n'.join(recommended)+'\n')
    manifest = root/'app/src/main/AndroidManifest.xml'
    if manifest.exists():
        content=manifest.read_text()
        content=re.sub(r'        <!-- Recommended scope visibility -->.*?        <!-- End recommended scope visibility -->\n', '', content, flags=re.S)
        present=set(re.findall(r'<package android:name="([^"]+)"', content))
        visibility='        <!-- Recommended scope visibility -->\n'
        visibility+=''.join(f'        <package android:name="{pkg}" />\n' for pkg in recommended if pkg!='system' and pkg not in present)
        visibility+='        <!-- End recommended scope visibility -->\n'
        manifest.write_text(content.replace('    </queries>', visibility+'    </queries>'))
    report = dict(fingerprint=inventory['fingerprint'], target_count=len(targets),
                  rgb_count=len(common), color_family_counts={name: len(values) for name, values in family_colors.items()}, recommended_scopes=recommended, selection='Meizu/Flyme package or firmware signer, substantive Activity; SystemUI included; camera and QQMusic excluded',
                  applications=records)
    (root/'reference-apks/theme-meizu22/theme-targets.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(f'Generated {len(targets)} injection targets, {len(recommended)} recommended scopes and {len(common)} theme RGB values')


if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('inventory',type=Path)
    parser.add_argument('--root',type=Path,default=Path(__file__).resolve().parent.parent)
    args=parser.parse_args()
    generate(json.loads(args.inventory.read_text()),args.root)
