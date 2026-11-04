#!/usr/bin/env python3
"""Reject damaged/stale DEX packaging. This is not Android's bytecode verifier."""
import hashlib
import pathlib
import struct
import sys
import zipfile
import zlib


def dex_classes(data):
    if len(data) < 112 or data[:4] != b"dex\n" or data[4:8] not in {
        b"035\0", b"037\0", b"038\0", b"039\0", b"040\0"
    }:
        raise ValueError("Unsupported or truncated DEX header")
    def u32(offset):
        if offset < 0 or offset + 4 > len(data):
            raise ValueError("DEX reference is outside the file")
        return struct.unpack_from("<I", data, offset)[0]
    if u32(32) != len(data) or u32(36) != 112 or u32(40) != 0x12345678:
        raise ValueError("DEX declared size/header does not match its bytes")
    if data[12:32] != hashlib.sha1(data[32:]).digest():
        raise ValueError("DEX signature mismatch")
    if u32(8) != zlib.adler32(data[12:]) & 0xffffffff:
        raise ValueError("DEX checksum mismatch")
    strings, string_offset = u32(56), u32(60)
    types, type_offset = u32(64), u32(68)
    count, class_offset = u32(96), u32(100)
    for n, offset, width in [(strings, string_offset, 4), (types, type_offset, 4), (count, class_offset, 32)]:
        if offset + n * width > len(data):
            raise ValueError("DEX table is outside the file")
    result = []
    for offset in range(class_offset, class_offset + count * 32, 32):
        index = u32(offset)
        if index >= types:
            raise ValueError("Invalid DEX class type")
        string = u32(type_offset + index * 4)
        if string >= strings:
            raise ValueError("Invalid DEX class descriptor")
        pos = u32(string_offset + string * 4)
        for _ in range(5):
            if pos >= len(data):
                raise ValueError("Truncated DEX string")
            byte = data[pos]
            pos += 1
            if byte < 128:
                break
        else:
            raise ValueError("Invalid DEX string length")
        end = data.find(b"\0", pos)
        if end < pos or end - pos > 65535:
            raise ValueError("Invalid DEX string bounds")
        result.append(data[pos:end])
    return result


def check(path):
    seen = set()
    with zipfile.ZipFile(path) as archive:
        if sum(i.file_size for i in archive.infolist()) > 1024 * 1024 * 1024:
            raise ValueError("APK exceeds the inspection limit")
        if archive.testzip() is not None:
            raise ValueError("APK ZIP checksum failed")
        names = [n for n in archive.namelist() if n.endswith(".dex")]
        if not names:
            raise ValueError("APK has no DEX files")
        expected = {"classes.dex"} | {f"classes{i}.dex" for i in range(2, len(names) + 1)}
        if set(names) != expected or len(names) != len(set(names)):
            raise ValueError("APK DEX names are noncanonical, duplicated or incomplete")
        for name in names:
            classes = dex_classes(archive.read(name))
            if len(classes) != len(set(classes)) or seen.intersection(classes):
                raise ValueError("APK contains duplicate class definitions")
            seen.update(classes)
        if not seen:
            raise ValueError("APK has no class definitions")
    return {"dex_files": len(names), "classes": len(seen)}


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: python3 scripts/check-apk.py APK")
    try:
        print(check(pathlib.Path(sys.argv[1])))
    except (ValueError, OSError, zipfile.BadZipFile) as error:
        raise SystemExit(str(error)) from None
