#!/usr/bin/env python3
"""Create a deterministic, manifest-checked source distribution, never build output."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import zipfile

SOURCE_DIRS = {"crates", "vendor", "android", "packaging", "server", "tools", "docs", "design", ".cargo", ".github"}
ROOT_FILES = {"Cargo.toml", "Cargo.lock", "LICENSE", "LICENSE-EXCEPTION.md", "THIRD-PARTY-NOTICES.md",
              "THIRD-PARTY-CRATES.txt", "README.md", "README-KawaVPN.txt", "README-UPSTREAM.md",
              "SOURCE-ORIGIN.json", ".gitignore", "rust-toolchain.toml", "BUILDING.md"}
EXCLUDE_DIRS = {".git", ".gradle", ".idea", ".kotlin", "target", "dist", "build", "work", "__pycache__",
                ".pytest_cache", "node_modules", "superpowers", "kawa-appimage", "cache"}
PRIVATE_NAMES = {"store.json", "account.json", "account.dat", "local.properties", "key.properties", "keystore.properties"}


def source_files(root):
    for base, dirs, names in os.walk(root, followlinks=False):
        parent = Path(base)
        rel_parent = parent.relative_to(root)
        dirs[:] = sorted(d for d in dirs if d not in EXCLUDE_DIRS and not d.startswith(("stage-", "work-", "root-", "appimage-"))
                         and not (parent / d).is_symlink() and (rel_parent.parts or d in SOURCE_DIRS))
        for name in sorted(names):
            file = parent / name
            rel = file.relative_to(root)
            if rel.as_posix().startswith("android/app/src/main/assets/xray/"):
                continue
            if rel.as_posix() == "android/app/src/main/assets/source.zip":
                continue
            if len(rel.parts) == 1 and name not in ROOT_FILES:
                continue
            if name.startswith(".env") or name in PRIVATE_NAMES or file.is_symlink():
                continue
            if file.suffix.lower() in {".pem", ".key", ".p12", ".pfx", ".jks", ".keystore", ".log", ".pyc", ".exe", ".apk", ".aab", ".aar", ".dmg", ".appimage"}:
                continue
            yield rel.as_posix(), file.read_bytes()


def write_archive(root, output, version):
    if not re.fullmatch(r"\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?", version):
        raise ValueError("version must be a safe semantic version")
    files = dict(source_files(root))
    for required in ["Cargo.toml", "README.md", "LICENSE", "vendor/KAWATUN/LICENSE"]:
        if required not in files:
            raise ValueError(f"required source file missing: {required}")
    manifest = {"version": version, "algorithm": "sha256", "files": {
        name: hashlib.sha256(data).hexdigest() for name, data in sorted(files.items())}}
    files["SOURCE-MANIFEST.json"] = json.dumps(manifest, indent=2, ensure_ascii=False).encode("utf-8") + b"\n"
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name, data in sorted(files.items()):
            entry = zipfile.ZipInfo(f"KawaVPN-{version}/{name}", (1980, 1, 1, 0, 0, 0))
            entry.create_system = 3
            mode = 0o100755 if name.endswith(".sh") or Path(name).name == "gradlew" else 0o100644
            entry.external_attr = mode << 16
            archive.writestr(entry, data, compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
    return len(manifest["files"])


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--version", required=True)
    args = parser.parse_args()
    count = write_archive(args.root.resolve(), args.output.resolve(), args.version)
    print(json.dumps({"archive": str(args.output.resolve()), "source_files": count,
                      "sha256": hashlib.sha256(args.output.read_bytes()).hexdigest()}))
