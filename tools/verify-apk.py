#!/usr/bin/env python3
"""Verify a release APK's signature, ABI alignment and embedded source manifest."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import zipfile

def command(executable, arguments, environment):
    argv = [str(executable), *arguments]
    if os.name == "nt" and Path(executable).suffix.lower() == ".bat":
        argv = [environment.get("COMSPEC", "cmd.exe"), "/d", "/c", *argv]
    return subprocess.check_output(argv, env=environment, text=True, stderr=subprocess.STDOUT)

def verify_source(source, expected_source):
    if hashlib.sha256(source).digest() != hashlib.sha256(expected_source).digest():
        raise ValueError("Embedded source differs from the final distributed source archive")
    with zipfile.ZipFile(io.BytesIO(source)) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("Duplicate source archive members")
        manifests = [name for name in names if name.endswith("/SOURCE-MANIFEST.json")]
        if len(manifests) != 1:
            raise ValueError("Expected exactly one source manifest")
        manifest = json.loads(archive.read(manifests[0]))
        version = manifest["version"]
        if not re.fullmatch(r"\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?", version):
            raise ValueError("Invalid source version")
        prefix = f"KawaVPN-{version}/"
        expected_names = {prefix + name for name in manifest["files"]} | {prefix + "SOURCE-MANIFEST.json"}
        if set(names) != expected_names or manifests[0] != prefix + "SOURCE-MANIFEST.json":
            raise ValueError("Source membership differs from its manifest")
        for name, digest in manifest["files"].items():
            if name.startswith("/") or "\\" in name or any(part in {"", ".", ".."} for part in name.split("/")):
                raise ValueError("Unsafe source archive path")
            if hashlib.sha256(archive.read(prefix + name)).hexdigest() != digest:
                raise ValueError(f"Embedded source hash mismatch: {name}")
        for required in ["android/app/src/main/AndroidManifest.xml", "android/native/go.sum.upstream", "vendor/KAWATUN/LICENSE"]:
            if required not in manifest["files"]:
                raise ValueError(f"Incomplete corresponding source: {required}")
        return manifest

def verify(apk, tools, java, expected_certificate, expected_source):
    environment = os.environ.copy()
    if java:
        environment["JAVA_HOME"] = str(java)
    suffix = ".bat" if os.name == "nt" else ""
    signature = command(tools / ("apksigner" + suffix), ["verify", "--verbose", "--print-certs", str(apk)], environment)
    certificate = re.search(r"certificate SHA-256 digest: ([0-9a-f]{64})", signature)
    if not certificate or certificate.group(1) != expected_certificate.lower():
        raise ValueError("APK certificate does not match the declared Kawa release key")
    command(tools / ("zipalign.exe" if os.name == "nt" else "zipalign"), ["-c", "-P", "16", "4", str(apk)], environment)
    required_abis = {"arm64-v8a": 183, "x86_64": 62}
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        for abi, machine in required_abis.items():
            libraries = [name for name in names if name.startswith(f"lib/{abi}/") and name.endswith(".so")]
            if not libraries:
                raise ValueError(f"Missing native engine for {abi}")
            for name in libraries:
                elf = archive.read(name)
                if elf[:5] != b"\x7fELF\x02" or struct.unpack_from("<H", elf, 18)[0] != machine:
                    raise ValueError(f"Wrong ELF architecture: {name}")
                offset = struct.unpack_from("<Q", elf, 32)[0]
                entry_size, count = struct.unpack_from("<HH", elf, 54)
                for index in range(count):
                    header = offset + index * entry_size
                    if struct.unpack_from("<I", elf, header)[0] == 1 and struct.unpack_from("<Q", elf, header + 48)[0] < 16384:
                        raise ValueError(f"Native LOAD alignment below 16 KiB: {name}")
        for notice in ["assets/LICENSE", "assets/LICENSE-EXCEPTION.md", "assets/THIRD-PARTY-NOTICES.md", "assets/licenses/LICENSE-libXray.txt", "assets/licenses/NATIVE-DEPENDENCIES.txt"]:
            if notice not in names:
                raise ValueError(f"Missing legal notice: {notice}")
        source = archive.read("assets/source.zip")
    manifest = verify_source(source, expected_source.read_bytes())
    return {"apk": apk.name, "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(), "certificate_sha256": certificate.group(1),
            "abis": sorted(required_abis), "alignment_bytes": 16384, "source_sha256": hashlib.sha256(source).hexdigest(),
            "source_files": len(manifest["files"]), "version": manifest["version"]}

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--build-tools", required=True, type=Path)
    parser.add_argument("--java-home", type=Path)
    parser.add_argument("--certificate-sha256", required=True)
    parser.add_argument("--source-archive", required=True, type=Path, help="Final distributed source ZIP, compared byte-for-byte")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    result = verify(args.apk.resolve(), args.build_tools.resolve(), args.java_home, args.certificate_sha256, args.source_archive.resolve())
    text = json.dumps(result, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text)
    print(text)
