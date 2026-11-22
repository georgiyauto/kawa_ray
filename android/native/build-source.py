#!/usr/bin/env python3
"""Build unmodified pinned native sources; requires Go1.27.2, JDK21 and NDKr30."""
from pathlib import Path
import hashlib
import os
import shutil
import subprocess
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parent
cache = ROOT / "cache"
cache.mkdir(exist_ok=True)
archive = cache / "libxray-source.zip"
expected = "93a7e14d36028b048dc623cc15a17fb761088be59ebf27015f37ee582340800c"
if not archive.exists():
    with urllib.request.urlopen("https://codeload.github.com/XTLS/libXray/zip/refs/tags/v26.10.21", timeout=180) as response:
        archive.write_bytes(response.read())
if hashlib.sha256(archive.read_bytes()).hexdigest() != expected:
    raise SystemExit("Native source checksum mismatch")
with zipfile.ZipFile(archive) as source:
    source.extractall(cache)
project = cache / "libXray-26.10.21"
version = subprocess.check_output(["go", "version"], text=True)
if "go1.27.2 " not in version:
    raise SystemExit("Use pinned Go1.27.2")
if not os.environ.get("ANDROID_NDK_HOME"):
    raise SystemExit("Set ANDROID_NDK_HOME to NDKr30 and ANDROID_HOME to SDK")
for name in ["go.mod", "go.sum"]:
    locked = ROOT / (name + ".upstream")
    if (project / name).read_bytes() != locked.read_bytes():
        raise SystemExit("Native module lock mismatch")
mobile = "v0.0.0-20261011034528-197770444867"
environment = os.environ.copy()
environment["GOTOOLCHAIN"] = "local"
for tool in ["gomobile", "gobind"]:
    subprocess.run(["go", "install", f"golang.org/x/mobile/cmd/{tool}@{mobile}"], check=True, env=environment)
gopath = subprocess.check_output(["go", "env", "GOPATH"], text=True).strip().split(os.pathsep)[0]
environment["PATH"] = str(Path(gopath) / "bin") + os.pathsep + environment["PATH"]
subprocess.run(["gomobile", "init"], check=True, cwd=project, env=environment)
subprocess.run(["gomobile", "bind", "-target", "android/arm64,android/amd64", "-androidapi", "26", "-trimpath",
                "-ldflags=-checklinkname=0 -s -w -buildid= -extldflags=-Wl,-z,max-page-size=16384"], check=True, cwd=project, env=environment)
destination = ROOT.parent / "app/libs/libXray.aar"
destination.parent.mkdir(parents=True, exist_ok=True)
shutil.copy2(project / "libXray.aar", destination)
print("Pinned native source build complete. Verify APK/device behaviour before release.")
