#!/usr/bin/env python3
"""Run the pinned native helper offline with a minimal environment."""
import os
import pathlib
import subprocess


def build_environment(source):
    # These locate the already installed compiler and caches. In particular do
    # not pass registry/signing tokens, cloud credentials, or proxy passwords.
    allowed = {
        "PATH", "HOME", "RUSTUP_HOME", "RUSTUP_TOOLCHAIN", "CARGO_HOME",
        "ANDROID_NDK_HOME", "ANDROID_HOME", "ANDROID_SDK_ROOT", "TMPDIR",
        "TEMP", "TMP", "LANG", "LC_ALL", "SYSTEMROOT", "CARGO_BUILD_JOBS",
    }
    return {name: value for name, value in source.items() if name in allowed}


if __name__ == "__main__":
    root = pathlib.Path(__file__).resolve().parents[1]
    command = [
        "cargo", "ndk", "-t", "arm64-v8a", "-t", "x86_64", "--platform", "28",
        "-o", "android/app/src/main/jniLibs", "build", "--offline", "--locked",
        "--release", "-p", "wallet-ffi",
    ]
    raise SystemExit(subprocess.run(command, cwd=root, env=build_environment(os.environ)).returncode)
