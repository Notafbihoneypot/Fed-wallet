#!/usr/bin/env python3
"""Make sure dummy credential variables cannot reach cargo-ndk diagnostics."""
import importlib.util
import pathlib
import unittest

spec = importlib.util.spec_from_file_location("native_build", pathlib.Path(__file__).with_name("native-build.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class NativeBuildEnvironmentTests(unittest.TestCase):
    def test_credentials_and_proxy_secrets_are_not_inherited(self):
        source = {
            "PATH": "/public/toolchain/bin", "HOME": "/public/home",
            "CARGO_HOME": "/public/cache", "ANDROID_NDK_HOME": "/public/ndk",
            "CARGO_REGISTRY_TOKEN": "DUMMY_SECRET_FIXTURE",
            "AWS_SECRET_ACCESS_KEY": "DUMMY_SECRET_FIXTURE",
            "ANDROID_SIGNING_PASSWORD": "DUMMY_SECRET_FIXTURE",
            "HTTPS_PROXY": "http://fixture:dummy@proxy.invalid:8080",
            "UNRELATED_NEW_TOKEN": "DUMMY_SECRET_FIXTURE",
        }
        result = module.build_environment(source)
        self.assertEqual(set(result), {"PATH", "HOME", "CARGO_HOME", "ANDROID_NDK_HOME"})
        self.assertFalse(any("DUMMY_SECRET_FIXTURE" in value or "dummy@" in value for value in result.values()))


if __name__ == "__main__":
    unittest.main()
