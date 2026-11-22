"""Distribution checks: provenance, portability and secret/build exclusions."""
import json
import hashlib
from pathlib import Path
import subprocess
import sys
import tempfile
import tomllib
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]
LEGAL = {"LICENSE-EXCEPTION.md", "THIRD-PARTY-NOTICES.md", "vendor/DUOTUN/LICENSE", "THIRD-PARTY-CRATES.txt"}
LEGAL |= {"android/app/src/main/assets/" + name for name in list(LEGAL) if "/" not in name}

class SourceDistribution(unittest.TestCase):
    def test_helper_dependency_is_inside_checkout(self):
        manifest = ROOT / "crates/duoray-helper/Cargo.toml"
        dependency = tomllib.loads(manifest.read_text())["dependencies"]["duotun"]["path"]
        resolved = (manifest.parent / dependency).resolve()
        self.assertTrue(resolved.is_relative_to(ROOT), "clean checkout must include DUOTUN")
        self.assertTrue((resolved / "LICENSE").is_file())

    def test_original_notices_and_provenance_survive(self):
        self.assertEqual(hashlib.sha256((ROOT / "LICENSE-EXCEPTION.md").read_bytes()).hexdigest(), "7ed0cfe006d10ba02640e7b6b31037157eef8aa3213cc60f7012877b12dd5396")
        self.assertEqual(hashlib.sha256((ROOT / "vendor/DUOTUN/LICENSE").read_bytes()).hexdigest(), "5c3cecfa8a9223595afc783bb16ad900656cfafa6a6311feabd0141b23b82b24")
        self.assertIn("https://github.com/m-a-prod/DUORAY", (ROOT / "README.md").read_text())
        self.assertIn("GPL-3.0-only", (ROOT / "README.md").read_text())

    def test_branding_is_retired_outside_legal_notices(self):
        hits = []
        for file in ROOT.rglob("*"):
            if not file.is_file() or file.suffix.lower() not in {".md", ".rs", ".sh", ".txt", ".yml", ".yaml", ".toml"}: continue
            rel = file.relative_to(ROOT)
            if any(part in {"target", "dist", ".git", "build", ".gradle"} for part in rel.parts) or rel.as_posix() in LEGAL: continue
            if file.name == "test_source_distribution.py": continue
            text = file.read_text(errors="replace").lower()
            if "dual" + "izm" in text or "дуал" + "изм" in text: hits.append(rel.as_posix())
        self.assertEqual(hits, [])

    def test_archive_contains_source_and_excludes_private_and_build_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "source"; root.mkdir()
            files = {"Cargo.toml": "[workspace]", "README.md": "example", "LICENSE": "license",
                     "crates/example/src/lib.rs": "pub fn source() {}", "vendor/DUOTUN/LICENSE": "MIT",
                     ".env": "private", "packaging/windows/stage-x64/private.key": "private",
                     "target/release/app.exe": "binary", "account.json": "private", ".git/config": "private",
                     "android/app/libs/native.aar": "binary", "android/app/src/main/assets/xray/geoip.dat": "data",
                     "packaging/linux/root-x64/private.txt": "private", "packaging/windows/cache/tool.zip": "binary"}
            for name, content in files.items():
                path = root / name; path.parent.mkdir(parents=True, exist_ok=True); path.write_text(content)
            archive = Path(directory) / "source.zip"
            result = subprocess.run([sys.executable, str(ROOT / "tools/make-source-archive.py"),
                                     "--root", str(root), "--version", "0.4.26", "--output", str(archive)], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            with zipfile.ZipFile(archive) as z:
                names = set(z.namelist())
                self.assertIn("KawaVPN-0.4.26/Cargo.toml", names)
                self.assertIn("KawaVPN-0.4.26/crates/example/src/lib.rs", names)
                self.assertIn("KawaVPN-0.4.26/vendor/DUOTUN/LICENSE", names)
                self.assertFalse(any(x in n for n in names for x in [".env", ".key", ".git/", "account.json", "target/", "stage-x64/", ".aar", "assets/xray/", "root-x64/", "/cache/"]))
                manifest = json.loads(z.read("KawaVPN-0.4.26/SOURCE-MANIFEST.json"))
                import hashlib
                for name, expected in manifest["files"].items(): self.assertEqual(hashlib.sha256(z.read("KawaVPN-0.4.26/" + name)).hexdigest(), expected)

if __name__ == "__main__": unittest.main()
