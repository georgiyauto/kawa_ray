#!/usr/bin/env python3
"""Fetch only pinned public engine/data assets; source archives exclude binaries."""
from pathlib import Path
import hashlib
import io
import json
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
lock = json.loads((ROOT / "native/dependencies.lock.json").read_text())

def fetch(url, expected):
    with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "KawaVPN-source-builder"}), timeout=180) as response:
        data = response.read(160 * 1024 * 1024)
    if hashlib.sha256(data).hexdigest() != expected:
        raise SystemExit("Public dependency SHA-256 mismatch; no files installed")
    return zipfile.ZipFile(io.BytesIO(data))

native = lock["libxray"]
with fetch(native["url"], native["sha256"]) as archive:
    destination = ROOT / "app/libs/libXray.aar"
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_bytes(archive.read(native["member"]))

data = lock["dataArchive"]
with fetch(data["url"], "d004c39288ce9ada487c6f398c7c545f7d749e44bdfdd59dbc9f865afba4e1ad") as archive:
    for name, item in lock["data"].items():
        content = archive.read(item["member"])
        if hashlib.sha256(content).hexdigest() != item["sha256"]:
            raise SystemExit("Data SHA-256 mismatch")
        destination = ROOT / "app/src/main/assets/xray" / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(content)
print("Pinned Xray engine and geographic rules verified.")
