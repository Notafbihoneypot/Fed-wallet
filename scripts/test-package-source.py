#!/usr/bin/env python3
"""Exercise the deliverable boundary with dummy secrets, never real wallets."""
import hashlib
import pathlib
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile

SCRIPT = pathlib.Path(__file__).with_name("package-source.py")


class SourceArchiveTests(unittest.TestCase):
    def test_only_source_files_enter_archive(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory) / "project"
            paths = {
                "Cargo.toml": "[workspace]\n",
                "Cargo.lock": "# Public fixture\n",
                "crates/wallet-keys/src/lib.rs": "// Public fixture\n",
                "vendor/example/LICENSE": "Public fixture\n",
                "docs/review.md": "Public fixture\n",
                "android/app/src/main/java/app/independentwallet/bindings/independent_wallet.kt": "// Public fixture\n",
                "android/gradle/wrapper/gradle-wrapper.jar": "Dummy wrapper fixture\n",
            }
            excluded = [
                "crates/.env", "crates/wallet-keys/wallet-v1.bin",
                "vendor/device.pem", "docs/wallet.keys", "scripts/credentials.json",
                "scripts/signing.keystore", "android/local.properties",
                "android/signing.properties", "android/app/keystore.properties",
                "android/app/src/main/res/raw/seed.txt", "crates/target/generated.rs",
                "docs/.env.backup", "vendor/.git/config",
            ]
            for path, value in paths.items():
                target = root / path
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(value)
            for path in excluded:
                target = root / path
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text("DUMMY_SECRET_FIXTURE_DO_NOT_PACKAGE\n")
            (root / "scripts").mkdir(exist_ok=True)
            shutil.copy2(SCRIPT, root / "scripts/package-source.py")
            outside = pathlib.Path(directory) / "outside.rs"
            outside.write_text("DUMMY_SECRET_FIXTURE_DO_NOT_PACKAGE\n")
            (root / "crates/symlink.rs").symlink_to(outside)
            (root / "README.md").symlink_to(outside)
            archive = pathlib.Path(directory) / "source.zip"
            result = subprocess.run([sys.executable, str(root / "scripts/package-source.py"), str(archive)], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertFalse((root / "SHA256SUMS").exists(), "packaging must not write into the source tree")
            with zipfile.ZipFile(archive) as zipped:
                members = {path.removeprefix("independent-wallet/") for path in zipped.namelist()}
                self.assertTrue(set(paths).issubset(members))
                self.assertFalse(set(excluded).intersection(members))
                self.assertNotIn("crates/symlink.rs", members)
                self.assertNotIn("README.md", members)
                for name in zipped.namelist():
                    self.assertNotIn(b"DUMMY_SECRET_FIXTURE_DO_NOT_PACKAGE", zipped.read(name))
                for line in zipped.read("independent-wallet/SHA256SUMS").decode().splitlines():
                    digest, name = line.split("  ", 1)
                    self.assertEqual(digest, hashlib.sha256(zipped.read("independent-wallet/" + name)).hexdigest())


if __name__ == "__main__":
    unittest.main()
