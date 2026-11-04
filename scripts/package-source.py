#!/usr/bin/env python3
"""Create a reproducible source archive without build output or toolchains."""
import hashlib
import pathlib
import sys
import zipfile

root = pathlib.Path(__file__).resolve().parents[1]
destination = pathlib.Path(sys.argv[1]).resolve() if len(sys.argv) == 2 else root.parent / "independent-wallet-v0.11.10-source.zip"
blocked = {"target", "build", ".git", ".gradle", ".kotlin", "jniLibs", "captures", "__pycache__"}
root_names = {"AGENTS.md", "README.md", "NOTICE.md", "CHANGELOG.md", "LICENSE", "Cargo.toml", "Cargo.lock", "rust-toolchain.toml", ".gitignore"}
def source_file(path):
    relative = path.relative_to(root)
    if not path.is_file() or any(parent.is_symlink() for parent in (path, *path.parents) if parent != root.parent):
        return False
    if blocked.intersection(relative.parts):
        return False
    if any(part.startswith(".") and part != ".gitignore" for part in relative.parts):
        return False
    if len(relative.parts) == 1:
        return path.name in root_names
    directory = relative.parts[0]
    if directory == "android":
        if path.suffix == ".properties":
            return relative.as_posix() in {"android/gradle.properties", "android/gradle/wrapper/gradle-wrapper.properties"}
        return path.name != "local.properties" and (
            path.suffix in {".kt", ".kts", ".xml", ".pro", ".bat"}
            or path.name in {"gradlew", ".gitignore"}
            or relative.as_posix() == "android/gradle/wrapper/gradle-wrapper.jar"
        )
    # Do not sweep arbitrary local wallets, credentials, certificates or binaries
    # into a deliverable. The suffix allowlist applies outside Android as well.
    if directory in {"crates", "vendor"}:
        return path.suffix in {".rs", ".toml", ".md"} or path.name in {"LICENSE", "NOTICE"}
    if directory == "docs":
        return path.suffix == ".md"
    if directory == "scripts":
        return path.suffix in {".py", ".sh"}
    return False


files = [root / name for name in root_names if source_file(root / name)]
for directory in ("crates", "vendor", "docs", "scripts", "android"):
    for path in (root / directory).rglob("*"):
        if source_file(path):
            files.append(path)
files = sorted(set(files), key=lambda path: path.relative_to(root).as_posix())
required = {"Cargo.toml", "Cargo.lock", "crates/wallet-keys/src/lib.rs", "android/app/src/main/java/app/independentwallet/bindings/independent_wallet.kt"}
assert required.issubset({path.relative_to(root).as_posix() for path in files})
manifest = root / "SHA256SUMS"
manifest.write_text("".join(hashlib.sha256(path.read_bytes()).hexdigest() + "  " + path.relative_to(root).as_posix() + "\n" for path in files))
files.append(manifest)
destination.parent.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(destination, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
    for path in sorted(files):
        relative = path.relative_to(root).as_posix()
        info = zipfile.ZipInfo("independent-wallet/" + relative, date_time=(2026, 10, 21, 0, 0, 0))
        info.create_system = 3
        executable = path.suffix == ".sh" or path.name in {"gradlew", "package-source.py"}
        info.external_attr = (0o100755 if executable else 0o100644) << 16
        info.compress_type = zipfile.ZIP_DEFLATED
        archive.writestr(info, path.read_bytes(), compresslevel=9)
print(f"{destination}: {len(files)} files, {destination.stat().st_size} bytes")
print("SHA256 " + hashlib.sha256(destination.read_bytes()).hexdigest())
