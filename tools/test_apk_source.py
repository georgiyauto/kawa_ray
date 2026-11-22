"""The APK release gate must reject stale and ambiguous corresponding source."""
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import unittest
import warnings
import zipfile

spec = importlib.util.spec_from_file_location("verify_apk", Path(__file__).with_name("verify-apk.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

def fixture(extra=None, duplicate=False):
    files = {"android/app/src/main/AndroidManifest.xml": b"manifest", "android/native/go.sum.upstream": b"checksums", "vendor/KAWATUN/LICENSE": b"MIT"}
    manifest = {"version": "0.4.26", "files": {name: hashlib.sha256(data).hexdigest() for name, data in files.items()}}
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, "w") as archive:
        for name, data in files.items(): archive.writestr("KawaVPN-0.4.26/" + name, data)
        archive.writestr("KawaVPN-0.4.26/SOURCE-MANIFEST.json", json.dumps(manifest))
        if extra: archive.writestr(extra, b"unlisted")
        if duplicate:
            with warnings.catch_warnings():
                warnings.simplefilter("ignore", UserWarning)
                archive.writestr("KawaVPN-0.4.26/vendor/KAWATUN/LICENSE", b"different")
    return stream.getvalue()

class SourceGate(unittest.TestCase):
    def test_exact_archive_passes(self):
        source = fixture()
        self.assertEqual(module.verify_source(source, source)["version"], "0.4.26")

    def test_stale_but_consistent_source_fails(self):
        with self.assertRaisesRegex(ValueError, "differs from the final"):
            module.verify_source(fixture(), fixture(extra="new-file"))

    def test_unlisted_member_fails(self):
        source = fixture(extra="KawaVPN-0.4.26/unlisted.key")
        with self.assertRaisesRegex(ValueError, "membership"):
            module.verify_source(source, source)

    def test_duplicate_member_fails(self):
        source = fixture(duplicate=True)
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            module.verify_source(source, source)

if __name__ == "__main__": unittest.main()
