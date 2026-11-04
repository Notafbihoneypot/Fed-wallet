#!/usr/bin/env python3
"""Fail closed if an APK is signed, corrupted, or lacks required native libraries.

An unsigned release APK cannot be installed until the owner signs it.
Do not use this as a replacement for signature verification after signing.
"""
import argparse
from pathlib import Path
import re
import sys
import zipfile

SIGNING_EXTENSIONS = ('.SF', '.RSA', '.DSA', '.EC')
SIGNING_MAGIC = b'APK Sig Block 42'   # v2 / v3 APK signing block footer


def validate_unsigned(path: Path, libraries: list[str], abis: list[str]):
    if not path.is_file():
        raise ValueError(f'APK not found: {path}')
    if path.stat().st_size < 1024:
        raise ValueError('APK is missing or unexpectedly small')
    with zipfile.ZipFile(path, 'r') as apk:
        bad = apk.testzip()
        if bad:
            raise ValueError(f'corrupt APK ZIP member: {bad}')
        names = apk.namelist()
        if len(names) != len(set(names)):
            raise ValueError('duplicate APK ZIP member names')
        signature_members = [
            name for name in names if name.upper().startswith('META-INF/')
            and name.upper().endswith(SIGNING_EXTENSIONS)
        ]
        if signature_members:
            raise ValueError(f'v1 JAR signature found: {signature_members[:3]}')
        if 'AndroidManifest.xml' not in names:
            raise ValueError('missing Android manifest')
        if 'classes.dex' not in names:
            raise ValueError('missing classes.dex')
        for abi in abis:
            for lib in libraries:
                member = f'lib/{abi}/{lib}'
                if member not in names:
                    raise ValueError(f'missing native library: {member}')
                if apk.getinfo(member).file_size < 1024:
                    raise ValueError(f'native library unexpectedly small: {member}')
        central_directory_offset = apk.start_dir
    with path.open('rb') as fp:
        if central_directory_offset >= 24:
            fp.seek(central_directory_offset - len(SIGNING_MAGIC))
            if fp.read(len(SIGNING_MAGIC)) == SIGNING_MAGIC:
                raise ValueError('v2/v3 APK signing block detected')
    print(f'UNSIGNED VERIFIED: {path.name} ({path.stat().st_size:,} bytes)')
    print('Expected libraries:', ', '.join(libraries), 'for', ', '.join(abis))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('apk', type=Path)
    parser.add_argument('--lib', action='append', required=True)
    parser.add_argument('--abi', action='append', required=True)
    args = parser.parse_args()
    try:
        validate_unsigned(args.apk, args.lib, args.abi)
    except (ValueError, zipfile.BadZipFile, OSError) as error:
        sys.exit(f'FAIL: {error}')
