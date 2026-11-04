#!/usr/bin/env python3
import hashlib
import importlib.util
import pathlib
import struct
import tempfile
import unittest
import zipfile
import zlib

spec = importlib.util.spec_from_file_location("apk", pathlib.Path(__file__).with_name("check-apk.py"))
apk = importlib.util.module_from_spec(spec)
spec.loader.exec_module(apk)


def fixture():
    # Minimal structural class table; not an executable Android application.
    name = b"Lfixture/Example;"
    b = bytearray(152) + bytes([len(name)]) + name + b"\0"
    b[:8] = b"dex\n039\0"
    for offset, value in {32: len(b), 36: 112, 40: 0x12345678, 56: 1, 60: 112, 64: 1, 68: 116, 96: 1, 100: 120, 112: 152}.items():
        struct.pack_into("<I", b, offset, value)
    b[12:32] = hashlib.sha1(b[32:]).digest()
    struct.pack_into("<I", b, 8, zlib.adler32(b[12:]) & 0xffffffff)
    return bytes(b)


class PackagingIntegrity(unittest.TestCase):
    def test_detects_the_observed_truncated_prefix_and_modified_payload(self):
        data = fixture()
        self.assertEqual(apk.dex_classes(data), [b"Lfixture/Example;"])
        for damaged in [data[:-1], data[:112], data[:-1] + b"x"]:
            with self.assertRaises(ValueError):
                apk.dex_classes(damaged)

    def test_duplicate_class_in_an_extra_dex_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "fixture.apk"
            with zipfile.ZipFile(path, "w") as z:
                z.writestr("classes.dex", fixture())
            self.assertEqual(apk.check(path)["classes"], 1)
            with zipfile.ZipFile(path, "a") as z:
                z.writestr("classes2.dex", fixture())
            with self.assertRaises(ValueError):
                apk.check(path)


if __name__ == "__main__":
    unittest.main()
