#!/usr/bin/env python3
"""Read-only device inventory and APK/resource extraction for Flyme theme targets."""
import argparse
import concurrent.futures
import hashlib
import json
from pathlib import Path
import re
import subprocess


def run(*args):
    return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT)


def package_blocks(text):
    text = text.split("\nHidden system packages:", 1)[0]
    return {m[1]: m[2] for m in re.finditer(
        r'^  Package \[([^]]+)\][^\n]*\n(.*?)(?=^  Package \[|\Z)', text, re.M | re.S)}



def launcher_activities(manifest, package):
    result = []
    for match in re.finditer(r'^([ ]*)E: (?:activity|activity-alias) .*?\n(.*?)(?=^\1E: |\Z)', manifest, re.M | re.S):
        block = match[2]
        if 'android.intent.action.MAIN' not in block or 'android.intent.category.LAUNCHER' not in block:
            continue
        name = re.search(r'android:name\([^)]*\)="([^"\n]+)"', block)
        if name:
            result.append(package + name[1] if name[1].startswith('.') else name[1])
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--build-tools', type=Path, required=True)
    args = parser.parse_args()
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=True)
    adb = lambda *parts: run('adb', '-s', args.serial, *parts)
    fingerprint = adb('shell', 'getprop', 'ro.build.fingerprint').strip()
    dump = adb('shell', 'dumpsys', 'package', '--all-components', 'packages')
    packages = package_blocks(dump)
    installed_user0 = sorted(line.removeprefix('package:') for line in
                             adb('shell', 'pm', 'list', 'packages', '--user', '0').splitlines())
    paths = dict(line.removeprefix('package:').rsplit('=', 1)[::-1]
                 for line in adb('shell', 'pm', 'list', 'packages', '-f').splitlines())
    seeds = ('com.android.settings', 'com.meizu.flyme.launcher', 'com.meizu.media.camera')
    signature = lambda b: re.search(r'signatures:\[([^]]*)\]', b)[1]
    trusted = {signature(packages[p]) for p in seeds}
    inventory = []
    for pkg, path in sorted(paths.items()):
        block = packages.get(pkg, '')
        if not block:
            continue
        meizu = pkg.startswith(('com.meizu.', 'com.flyme.'))
        firmware = (' SYSTEM ' in block and signature(block) in trusted
                    and not pkg.startswith(('com.qualcomm.', 'com.qti.', 'vendor.', 'org.codeaurora.')))
        if not meizu and not firmware:
            continue
        match = re.search(r'^    activities:\n(.*?)(?=^    \S|\Z)', block, re.M | re.S)
        activities = [line.strip() for line in match[1].splitlines()] if match else []
        activities = [a for a in activities if not a.endswith('/android.app.AppDetailsActivity')]
        if not activities and pkg != 'com.android.systemui':
            continue
        version = re.search(r'versionCode=(\d+)', block)[1]
        inventory.append(dict(package=pkg, path=path, version_code=int(version),
                              activities=activities, ownership='meizu-package' if meizu else 'firmware-signer'))
    print(f'{len(inventory)} UI packages selected on {fingerprint}', flush=True)

    def extract(entry):
        pkg = entry['package']
        directory = out / pkg
        directory.mkdir(exist_ok=True)
        apk_paths = [line.removeprefix('package:') for line in adb('shell', 'pm', 'path', pkg).splitlines()]
        artifacts = []
        for i, source in enumerate(apk_paths):
            target = directory / ('base.apk' if i == 0 else Path(source).name)
            if not target.exists():
                adb('pull', source, str(target))
            certs = run(str(args.build_tools / 'apksigner'), 'verify', '--print-certs', str(target))
            digest = re.findall(r'certificate SHA-256 digest: (\w+)', certs)
            manifest = run(str(args.build_tools / 'aapt2'), 'dump', 'xmltree', '--file', 'AndroidManifest.xml', str(target))
            if i == 0: entry['launcher_activities'] = launcher_activities(manifest, pkg)
            resources = run(str(args.build_tools / 'aapt2'), 'dump', 'resources', str(target))
            (directory / (target.stem + '-resources.txt')).write_text(resources)
            resource_entries = []
            for m in re.finditer(r'^    resource (0x[0-9a-f]+) color/(\S+)\n(.*?)(?=^    resource |^  type |\Z)', resources, re.M | re.S):
                colors = re.findall(r'^      \((.*?)\) #(\w{8})', m[3], re.M)
                refs = re.findall(r'@color/(\S+)', m[3])
                resource_entries.append(dict(id=m[1], name=m[2], colors=colors, references=refs))
            artifacts.append(dict(path=str(target.relative_to(out)), device_path=source,
                                  sha256=hashlib.sha256(target.read_bytes()).hexdigest(),
                                  certificate_sha256=digest, colors=resource_entries))
        entry['artifacts'] = artifacts
        print(f'Extracted {pkg}: {len(entry["activities"])} activities, {sum(len(a["colors"]) for a in artifacts)} color resources', flush=True)
        return entry

    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        results = list(pool.map(extract, inventory))
    (out / 'inventory.json').write_text(json.dumps(dict(fingerprint=fingerprint,
                                                       installed_user0_packages=installed_user0,
                                                       applications=results), ensure_ascii=False, indent=2))
    print(f'Saved {len(results)} verified packages to {out}', flush=True)


if __name__ == '__main__':
    main()
