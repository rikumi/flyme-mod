#!/usr/bin/env python3
"""Verify APK validity and the repository's pinned signing certificate."""
import os
from pathlib import Path
import re
import subprocess
import sys

root = Path(__file__).resolve().parent.parent
expected = (root / 'signing/certificate.sha256').read_text().strip().lower()
if not re.fullmatch(r'[0-9a-f]{64}', expected):
    raise SystemExit('Invalid pinned signing certificate SHA-256')
sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
if not sdk:
    raise SystemExit('Set ANDROID_HOME or ANDROID_SDK_ROOT')
def version(path):
    return tuple(int(part) for part in re.findall(r'\d+', path.parent.name))
candidates = list((Path(sdk) / 'build-tools').glob('*/apksigner'))
if not candidates:
    raise SystemExit('No apksigner found in Android build-tools')
apksigner = max(candidates, key=version)
if len(sys.argv) < 2:
    raise SystemExit('Usage: verify-apk-signature.py <apk> [<apk> ...]')
for argument in sys.argv[1:]:
    apk = Path(argument)
    result = subprocess.run([str(apksigner), 'verify', '--print-certs', str(apk)],
                            text=True, capture_output=True)
    if result.returncode:
        raise SystemExit(f'APK signature verification failed: {apk}\n{result.stderr}')
    digests = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)$',
                         result.stdout, re.MULTILINE)
    if digests != [expected]:
        raise SystemExit(f'Unexpected signing certificate for {apk}: {digests}; expected {expected}')
    print(f'{apk.name}: verified SHA-256 {expected}')
