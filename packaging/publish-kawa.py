"""Sign locally and atomically publish a Windows release to Germany.

Requires cryptography + paramiko. Private release key stays on the release
machine and must never be bundled with installers/source archives.
"""
import argparse
import hashlib
import json
import shlex
import subprocess
import socket
import struct
import os
import shutil
from pathlib import Path

from cryptography.hazmat.primitives import serialization
import paramiko


def make_release(installer, version, key_file, notes, build, linux_appimage=None, linux_metadata=None):
    key = serialization.load_pem_private_key(Path(key_file).read_bytes(), password=None)
    public = key.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw).hex()
    trusted_source = Path(__file__).resolve().parents[1] / 'crates/duoray-gui/src/update.rs'
    if public not in trusted_source.read_text(encoding='utf-8'):
        raise ValueError('Signing key does not match this client trust key')
    name = f'KawaVPN-Setup-{version}-x64.exe'
    size = Path(installer).stat().st_size
    with Path(installer).open('rb') as file:
        digest = hashlib.file_digest(file, 'sha256').hexdigest()
    manifest = {'version': version, 'build': build, 'notes': notes,
                'page': f'https://hub.example.com/kawa-app/v1/update/files/{name}',
                'assets': {'windows-x86_64-setup': {'file': name, 'sha256': digest, 'size': size}}}
    if linux_appimage:
        linux = Path(linux_appimage)
        expected = f'KawaVPN-{version}-x86_64.AppImage'
        if linux.name != expected:
            raise ValueError('Linux filename must match the immutable release version')
        with linux.open('rb') as file:
            linux_digest = hashlib.file_digest(file, 'sha256').hexdigest()
        manifest['assets']['linux-x86_64-appimage'] = {'file': expected, 'sha256': linux_digest, 'size': linux.stat().st_size}
    elif linux_metadata:
        a = json.loads(Path(linux_metadata).read_text(encoding='utf-8'))
        if a['file'] != f'KawaVPN-{version}-x86_64.AppImage' or len(a['sha256']) != 64 or any(c not in '0123456789abcdef' for c in a['sha256']) or not 0 < a['size'] <= 1024**3:
            raise ValueError('Invalid Linux release metadata')
        manifest['assets']['linux-x86_64-appimage'] = {k:a[k] for k in ('file','sha256','size')}
    payload = json.dumps(manifest, ensure_ascii=False, separators=(',', ':')).encode()
    return name, payload, key.sign(payload).hex().encode()


def main():
    p = argparse.ArgumentParser()
    for name in ['version', 'installer', 'signing-key', 'ssh-key', 'known-hosts']:
        p.add_argument('--'+name, required=True)
    p.add_argument('--notes', default='Обновление Kawa VPN')
    p.add_argument('--build-id', help='Exact KAWA_BUILD embedded by the artifact build machine')
    p.add_argument('--interface-index', type=int, default=0, help='Windows physical adapter index for SSH while VPN is active')
    p.add_argument('--ssh-host', default='204.11.2.178', help='Germany update server address')
    p.add_argument('--windows-stage', required=True, help='Exact stage directory packaged in this installer, with trusted Authenticode signatures')
    p.add_argument('--allow-unsigned-publisher', action='store_true', help='Explicit owner approval to publish without Windows publisher signing; Ed25519 verification stays mandatory')
    p.add_argument('--linux-appimage', help='Optional verified Linux x86_64 AppImage of the same version')
    p.add_argument('--linux-staged-file', help='Already-built remote AppImage; contents are checked before publication')
    p.add_argument('--linux-metadata', help='Size and SHA256 obtained over trusted SSH from the staged Linux artifact')
    p.add_argument('--windows-staged-file', help='Already-built remote installer; contents are checked before publication')
    args = p.parse_args()
    if args.linux_metadata and not args.linux_staged_file:
        raise ValueError('Linux metadata requires a staged artifact')
    if not all(part.isdigit() for part in args.version.split('.')) or len(args.version.split('.')) != 3:
        raise ValueError('Version must be x.y.z')
    root = Path(__file__).resolve().parents[1]
    # Ed25519 protects the update channel, but cannot satisfy Windows publisher
    # verification. Reject unsigned releases by default; an explicit owner-approved
    # exception does not relax Ed25519 signing or download integrity checks.
    if os.name != 'nt':
        raise RuntimeError('Run Windows Authenticode preflight on a Windows release machine before publishing')
    def ps_literal(value):
        return "'" + str(value).replace("'", "''") + "'"
    # Execute our checked-in verification source as ordinary inline PowerShell;
    # no execution-policy override, root-store edits or security setting changes.
    verifier = root / 'packaging/windows/verify-authenticode.ps1'
    command = ("& ([scriptblock]::Create([IO.File]::ReadAllText(" + ps_literal(verifier)
               + "))) -Installer " + ps_literal(Path(args.installer).resolve())
               + " -Stage " + ps_literal(Path(args.windows_stage).resolve()))
    shell = shutil.which('pwsh.exe') or shutil.which('powershell.exe')
    if not shell:
        raise RuntimeError('PowerShell is required to verify Windows publisher signatures')
    shell_env = os.environ.copy()
    # Do not load a PowerShell 7 module binary into a Windows PowerShell 5 host.
    shell_env.pop('PSModulePath', None)
    if args.allow_unsigned_publisher:
        print('Owner-approved unsigned Windows publisher release; update channel remains Ed25519 signed')
    else:
        subprocess.run([shell, '-NoProfile', '-NonInteractive', '-Command', command], check=True, env=shell_env)
    build = args.build_id
    if build is None:
        build = subprocess.check_output(['git','rev-parse','--short=12','HEAD'], cwd=root, text=True).strip()
        if subprocess.check_output(['git','status','--porcelain','--untracked-files=no'], cwd=root, text=True).strip():
            build += '-dirty'
    name, manifest, sig = make_release(args.installer, args.version, args.signing_key, args.notes, build, args.linux_appimage, args.linux_metadata)
    c = paramiko.SSHClient(); c.load_host_keys(args.known_hosts); c.set_missing_host_key_policy(paramiko.RejectPolicy())
    sock = None
    if args.interface_index:
        sock=socket.socket();sock.settimeout(20)
        sock.setsockopt(socket.IPPROTO_IP,31,struct.pack('!I',args.interface_index))
        sock.connect((args.ssh_host,22222))
    c.connect(args.ssh_host, port=22222, username='vpnadmin', key_filename=args.ssh_key, sock=sock, timeout=20, banner_timeout=45, auth_timeout=20)
    temp = '/home/vpnadmin/kawa-release-'+args.version
    _,o,e=c.exec_command('install -d -m 700 '+shlex.quote(temp),timeout=10)
    if o.channel.recv_exit_status():raise RuntimeError('release staging failed')
    s=c.open_sftp()
    if not args.windows_staged_file:s.put(args.installer,temp+'/'+name)
    if args.linux_appimage and not args.linux_staged_file:
        s.put(args.linux_appimage,temp+'/'+Path(args.linux_appimage).name)
    for filename, data in [('manifest.json',manifest),('manifest.sig',sig)]:
        with s.file(temp+'/'+filename,'wb') as f:f.write(data)
        s.chmod(temp+'/'+filename,0o600)
    s.close()
    # Immutable installer first, pointer last. Never overwrite a previously
    # published version with different bytes; old installers remain available.
    code = '''from pathlib import Path
import hashlib,json,shutil,os,pwd,datetime
stage=Path(STAGE);dest=Path('/var/lib/kawa-hub/updates');files=dest/'files';files.mkdir(parents=True,exist_ok=True)
m=json.loads((stage/'manifest.json').read_text());uid=pwd.getpwnam('kawa-hub')
for key,a in m['assets'].items():
 source=Path(LINUX_STAGED) if key=='linux-x86_64-appimage' and LINUX_STAGED else Path(WINDOWS_STAGED) if key=='windows-x86_64-setup' and WINDOWS_STAGED else stage/a['file'];target=files/a['file']
 with source.open('rb') as f:assert hashlib.file_digest(f,'sha256').hexdigest()==a['sha256']
 assert source.stat().st_size==a['size']
 if target.exists():
  with target.open('rb') as f:assert hashlib.file_digest(f,'sha256').hexdigest()==a['sha256'],'existing version differs; bump version'
 else:
  part=target.with_suffix('.part');shutil.copyfile(source,part);os.chmod(part,0o600);os.replace(part,target)
 os.chown(target,uid.pw_uid,uid.pw_gid)
signatures=dest/'signatures';signatures.mkdir(exist_ok=True)
digest=hashlib.sha256((stage/'manifest.json').read_bytes()).hexdigest()
signature=signatures/(digest+'.sig');shutil.copyfile(stage/'manifest.sig',signature);os.chmod(signature,0o600);os.chown(signature,uid.pw_uid,uid.pw_gid)
backup=dest/'history'/datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ');backup.mkdir(parents=True,exist_ok=True)
for name in ['manifest.json','manifest.sig']:
 old=dest/name
 if old.exists():shutil.copy2(old,backup/name)
for name in ['manifest.sig','manifest.json']:
 part=dest/(name+'.part');shutil.copyfile(stage/name,part);os.chmod(part,0o600);os.chown(part,uid.pw_uid,uid.pw_gid);os.replace(part,dest/name)
print('Published',m['version'],list(m['assets']))
'''.replace('LINUX_STAGED',repr(args.linux_staged_file)).replace('WINDOWS_STAGED',repr(args.windows_staged_file)).replace('STAGE',repr(temp))
    _,o,e=c.exec_command('sudo -n python3 -c '+shlex.quote(code),timeout=45)
    result=o.read().decode();error=e.read().decode();rc=o.channel.recv_exit_status();c.close()
    print(result)
    if rc:raise RuntimeError(error[:1000])


if __name__ == '__main__':
    main()
