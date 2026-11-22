"""Distribution contract checks; run without installing or starting a VPN."""
from pathlib import Path
import hashlib
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]

class PackagingContracts(unittest.TestCase):
    def test_source_archive_refreshes_changed_source_and_checks_explicit_snapshot(self):
        shell = shutil.which('sh') or os.environ.get('KAWA_TEST_SH')
        if not shell:
            self.skipTest('POSIX sh required for executable packaging contract')
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ('packaging', 'tools', 'crates/kawaray-gui', 'vendor/KAWATUN', 'dist'):
                (root / name).mkdir(parents=True, exist_ok=True)
            for name in ('packaging/common.sh', 'tools/make-source-archive.py'):
                shutil.copyfile(ROOT / name, root / name)
            (root / 'crates/kawaray-gui/Cargo.toml').write_text('version = "0.4.26"\n')
            for name in ('Cargo.toml', 'LICENSE', 'vendor/KAWATUN/LICENSE'):
                (root / name).write_text('fixture\n')
            readme = root / 'README.md'
            readme.write_bytes(b'before\n')
            env = os.environ.copy()
            env['PATH'] = str(Path(shell).parent) + os.pathsep + str(Path(sys.executable).parent) + os.pathsep + env['PATH']
            # Provide python3 explicitly on Windows, where only python.exe exists.
            (root / 'checksum.py').write_text('''import hashlib,sys
from pathlib import Path
if sys.argv[1:] == ['-c', '-']:
    digest, name = sys.stdin.read().strip().split(None, 1)
    sys.exit(0 if hashlib.sha256(Path(name).read_bytes()).hexdigest() == digest else 1)
print(hashlib.sha256(Path(sys.argv[1]).read_bytes()).hexdigest() + '  ' + sys.argv[1])
''')
            command = 'python3() { "' + Path(sys.executable).as_posix() + '" "$@"; }; sha256sum() { python3 checksum.py "$@"; }; . packaging/common.sh; source_archive'
            def run():
                return subprocess.run([shell, '-c', command], cwd=root, env=env, capture_output=True, text=True)
            result = run()
            self.assertEqual(result.returncode, 0, result.stderr)
            archive = root / 'dist/KawaVPN-0.4.26-source.zip'
            before = archive.read_bytes()
            readme.write_bytes(b'after\n')
            result = run()
            self.assertEqual(result.returncode, 0, result.stderr)
            with zipfile.ZipFile(archive) as zipped:
                self.assertEqual(zipped.read('KawaVPN-0.4.26/README.md'), b'after\n')
            self.assertNotEqual(before, archive.read_bytes())
            after = archive.read_bytes()
            self.assertEqual(run().returncode, 0)
            self.assertEqual(after, archive.read_bytes(), 'unchanged source must remain deterministic')
            supplied = root / 'final-source.zip'
            supplied.write_bytes(before)
            env['KAWA_SOURCE_ARCHIVE'] = supplied.as_posix()
            env['KAWA_SOURCE_SHA256'] = hashlib.sha256(before).hexdigest()
            self.assertEqual(run().returncode, 0)
            self.assertEqual(before, archive.read_bytes(), 'explicit verified final snapshot must be retained')
            env['KAWA_SOURCE_SHA256'] = '0' * 64
            self.assertNotEqual(run().returncode, 0, 'incorrect supplied snapshot must fail closed')
            del env['KAWA_SOURCE_SHA256']
            self.assertNotEqual(run().returncode, 0, 'supplied snapshot requires an explicit expected digest')

    def test_no_private_checkout_or_publication_in_ci(self):
        workflow = (ROOT / '.github/workflows/release.yml').read_text()
        for forbidden in ('KAWATUN_SSH_KEY', 'm-a-prod/', 'contents: write', 'action-gh-release'):
            self.assertNotIn(forbidden, workflow)
        self.assertIn('1.99.0', workflow)

    def test_protected_helper_packages(self):
        linux = (ROOT / 'packaging/linux/stage.sh').read_text()
        self.assertIn('/usr/libexec/kawa-vpn/KawaVPNHelper', linux)
        mac = (ROOT / 'packaging/macos/build.sh').read_text()
        self.assertIn('pkgbuild', mac)
        self.assertIn('/Library/PrivilegedHelperTools/pro.vinnikov.kawavpn.helper', mac)

    def test_pinned_appimage_both_architectures(self):
        script = (ROOT / 'packaging/linux/kawa-appimage.sh').read_text()
        self.assertNotIn('/continuous/', script)
        self.assertIn('aarch64', script)
        self.assertIn('sha256', script)
        self.assertIn('--runtime-file', script)
        self.assertIn('type2-runtime/releases/download/20251108', script)
        self.assertIn('--mksquashfs-opt=-processors', script)

    def test_maintenance_never_authorizes_root_or_changes_other_services(self):
        linux = (ROOT / 'packaging/linux/pkg/postinstall.sh').read_text()
        mac = (ROOT / 'packaging/macos/postinstall').read_text()
        for script in (linux, mac):
            self.assertIn('--install-service --allowed-uid', script)
            self.assertIn("|0)", script)
        uninstall = (ROOT / 'packaging/linux/uninstall.sh').read_text()
        self.assertIn('apt-get remove kawa-vpn', uninstall)
        self.assertNotIn('rm -rf', uninstall)
        prerm = (ROOT / 'packaging/linux/pkg/preremove.sh').read_text()
        self.assertIn('[ -e /etc/systemd/system/kawa-vpn-helper.service ]', prerm)

    def test_runtime_extraction_uses_available_python(self):
        common = (ROOT / 'packaging/common.sh').read_text()
        self.assertIn('extract_runtime_zip()', common)
        self.assertIn('zipfile.ZipFile', common)
        for target in ('linux/stage.sh','windows/build.sh','macos/build.sh'):
            script = (ROOT / 'packaging' / target).read_text()
            self.assertNotIn('unzip ', script)
            self.assertIn('extract_runtime_zip', script)

    def test_legal_notices_are_embedded(self):
        common = (ROOT / 'packaging/common.sh').read_text()
        for required in ('LICENSE-EXCEPTION.md', 'THIRD-PARTY-NOTICES.md', 'THIRD-PARTY-CRATES.txt', 'vendor/KAWATUN/LICENSE', '-source.zip'):
            self.assertIn(required, common)
        nsis = (ROOT / 'packaging/windows/kawaray.nsi').read_text()
        self.assertIn('LICENSE-KAWATUN.txt', nsis)
        self.assertIn('signing.nsh', nsis)

    def test_windows_current_binaries_and_portable(self):
        script = (ROOT / 'packaging/windows/build.sh').read_text()
        self.assertIn('KawaVPN.exe', script)
        self.assertIn('KawaVPNHelper.exe', script)
        self.assertIn('portable', script)
        self.assertIn('--locked', script)

if __name__ == '__main__':
    unittest.main()
