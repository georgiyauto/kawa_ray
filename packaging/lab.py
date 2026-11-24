#!/usr/bin/env python3
"""Bounded, opt-in Docker build lab; never installs packages on the host.

Caller supplies SSH credentials/known_hosts outside the source tree. `prepare`
uploads a manifest source archive; `start` launches one bounded worker; `status`
and `fetch` inspect/download only that run. No production container is selected.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shlex
import socket
import struct
import sys

REMOTE = r'''
import hashlib,json,os,pathlib,pwd,subprocess,zipfile
c=CONFIG
base=pathlib.Path('/home/vpnadmin/kawa-multiplatform-0.4.26')
run=base/c['run_id']; src=run/'KawaVPN-0.4.26'
u=pwd.getpwnam('vpnadmin')
if c['action']=='prepare':
    archive=base/(c['run_id']+'.zip')
    assert hashlib.sha256(archive.read_bytes()).hexdigest()==c['sha256'], 'archive digest mismatch'
    assert not run.exists(), 'run already exists'
    run.mkdir(mode=0o700)
    with zipfile.ZipFile(archive) as z:
        for i in z.infolist():
            p=pathlib.PurePosixPath(i.filename)
            assert not p.is_absolute() and '..' not in p.parts and p.parts[0]=='KawaVPN-0.4.26'
            assert ((i.external_attr>>16)&0o170000)!=0o120000, 'symlink archive entry'
        z.extractall(run)
    manifest=json.loads((src/'SOURCE-MANIFEST.json').read_text())
    for name,digest in manifest['files'].items():
        assert hashlib.sha256((src/name).read_bytes()).hexdigest()==digest, name
    (src/'dist').mkdir(exist_ok=True)
    import shutil
    shutil.copyfile(archive,src/'dist/KawaVPN-0.4.26-source.zip')
    for p in [run,*run.rglob('*')]: os.chown(p,u.pw_uid,u.pw_gid)
    print('Prepared verified source',run)
elif c['action']=='refresh':
    archive=base/c['upload_name']
    assert hashlib.sha256(archive.read_bytes()).hexdigest()==c['sha256']
    old=json.loads((src/'SOURCE-MANIFEST.json').read_text())
    with zipfile.ZipFile(archive) as z:
        prefix='KawaVPN-0.4.26/'
        new=json.loads(z.read(prefix+'SOURCE-MANIFEST.json'))
        protected=lambda n: n.startswith(('crates/','vendor/','.cargo/')) or n in ('Cargo.toml','Cargo.lock','rust-toolchain.toml')
        assert {n:h for n,h in old['files'].items() if protected(n)}=={n:h for n,h in new['files'].items() if protected(n)}, 'compiled Rust sources changed; new build required'
        for name,digest in new['files'].items():
            relative=pathlib.PurePosixPath(name)
            assert not relative.is_absolute() and '..' not in relative.parts
            assert (src/name).resolve().is_relative_to(src), 'archive path escapes source root'
            assert hashlib.sha256(z.read(prefix+name)).hexdigest()==digest, name
            if name.startswith('packaging/'):
                p=src/name
                assert not p.is_symlink()
                p.parent.mkdir(parents=True,exist_ok=True)
                p.write_bytes(z.read(prefix+name));os.chown(p,u.pw_uid,u.pw_gid)
    import shutil
    shutil.copyfile(archive,src/'dist/KawaVPN-0.4.26-source.zip')
    os.chown(src/'dist/KawaVPN-0.4.26-source.zip',u.pw_uid,u.pw_gid)
    (run/'packaging-refresh.json').write_text(json.dumps({'sha256':c['sha256'],'compiled_sources_unchanged':True}))
    print('Refreshed packaging/source archive; exact Rust/vendor/Cargo source hashes unchanged')
elif c['action']=='start':
    platform=c['platform']; marker=run/(platform+'.exit'); log=run/(platform+'.log')
    assert src.is_dir(), 'missing source'
    if marker.exists():
        assert marker.read_text().strip()!='0', 'successful run is immutable'
        attempt=1
        while (run/(platform+'-attempt-'+str(attempt)+'.log')).exists(): attempt+=1
        log.rename(run/(platform+'-attempt-'+str(attempt)+'.log'))
        marker.rename(run/(platform+'-attempt-'+str(attempt)+'.exit'))
    assert not log.exists(), 'worker still running'
    free=__import__('shutil').disk_usage(base).free
    assert free>=6*1024**3, 'less than 6GiB free; no cleanup performed'
    running=subprocess.check_output(['docker','ps','--format','{{.Names}}'],text=True).splitlines()
    assert not any('kawa' in n and any(s in n for s in ('build','package','lab')) for n in running), 'another Kawa build is active'
    target='x86_64-unknown-linux-gnu' if platform=='linux' else 'x86_64-pc-windows-gnu'
    cache=pathlib.Path('/home/vpnadmin/kawa-dot-0416')
    assert (cache/'cargo').is_dir() and (cache/'src/kawa-vpn-app/target').is_dir(), 'cache missing'
    image='kawa-build-ubuntu:22.04-1.99' if platform=='linux' else 'kawa-build-rust:1.99'
    subprocess.run(['docker','image','inspect',image],check=True,stdout=subprocess.DEVNULL)
    docker=['docker','run','--rm','--name','kawa-lab-'+platform+'-'+c['run_id'],'--user',f'{u.pw_uid}:{u.pw_gid}',
      '--cpus=2','--memory=3g','--memory-swap=4g','--pids-limit=512','--cap-drop=ALL','--security-opt=no-new-privileges',
      '-v',str(run)+':/source','-v',str(cache/'cargo')+':/cargo','-v',str(cache/'src/kawa-vpn-app/target')+':/target',
      '-w','/source/KawaVPN-0.4.26','-e','CARGO_HOME=/cargo','-e','CARGO_TARGET_DIR=/target','-e','CARGO_BUILD_JOBS=2']
    build='cargo build --config profile.release.package.kawaray-gui.opt-level=1 --locked --release --target '+target+' -p kawaray-gui -p kawaray-helper'
    if platform=='windows':
        build='mkdir -p /tmp/bin; ln -s /usr/bin/x86_64-w64-mingw32-windres /tmp/bin/windres; export PATH=/tmp/bin:$PATH; export CARGO_TARGET_X86_64_PC_WINDOWS_GNU_LINKER=x86_64-w64-mingw32-gcc; '+build
    else:
        build+=' && cargo test --config profile.release.package.kawaray-gui.opt-level=1 --release --locked --target '+target+' --workspace'
    source_digest=hashlib.sha256((src/'dist/KawaVPN-0.4.26-source.zip').read_bytes()).hexdigest()
    package='export KAWA_SOURCE_ARCHIVE=dist/KawaVPN-0.4.26-source.zip; export KAWA_SOURCE_SHA256='+source_digest+'; export KAWA_SKIP_BUILD=1; export KAWA_'+('LINUX' if platform=='linux' else 'WINDOWS')+'_BIN=/target/'+target+'/release; '
    if platform=='linux':
        package+='sh packaging/linux/stage.sh x86_64 && sh packaging/linux/packages.sh x86_64 && sh packaging/linux/kawa-appimage.sh x86_64 && sh packaging/linux/smoke-test.sh x86_64'
    else:
        package+='sh packaging/windows/build.sh x64'
    import shlex
    commands=[docker+[image,'sh','-c',build],docker+[(image if platform=='linux' else 'kawa-build-nsis:3'),'sh','-c',package]]
    # Separate monitor process records full logs and the final exit status.
    worker='import subprocess,pathlib; cmds='+repr(commands)+'; rc=0\nfor cmd in cmds:\n rc=subprocess.call(cmd)\n if rc: break\npathlib.Path('+repr(str(marker))+').write_text(str(rc)+"\\n")\n'
    (run/(platform+'-worker.py')).write_text(worker)
    p=subprocess.Popen(['python3',str(run/(platform+'-worker.py'))],stdin=subprocess.DEVNULL,stdout=open(log,'wb'),stderr=subprocess.STDOUT,start_new_session=True)
    print('Started isolated worker',platform,p.pid,'log',log)
elif c['action']=='package':
    running=subprocess.check_output(['docker','ps','--format','{{.Names}}'],text=True).splitlines()
    assert not any('kawa' in n and any(s in n for s in ('build','package','lab')) for n in running), 'another Kawa build active'
    platform=c['platform'] or 'linux'
    directories=[src/'packaging/linux/root-x86_64',src/'packaging/linux/appimage-x86_64'] if platform=='linux' else [src/'packaging/windows/stage-x64']
    if c['reset_stage']:
        import shutil
        for directory in directories:
            assert directory.resolve().is_relative_to(src/'packaging'/platform) and not directory.is_symlink()
            if directory.exists(): shutil.rmtree(directory)
    cache=pathlib.Path('/home/vpnadmin/kawa-dot-0416')
    bin_env='KAWA_LINUX_BIN=/target/x86_64-unknown-linux-gnu/release' if platform=='linux' else 'KAWA_WINDOWS_BIN=/target/x86_64-pc-windows-gnu/release'
    package_command='sh packaging/linux/stage.sh x86_64 && sh packaging/linux/packages.sh x86_64 && sh packaging/linux/kawa-appimage.sh x86_64 && sh packaging/linux/smoke-test.sh x86_64' if platform=='linux' else 'sh packaging/windows/build.sh x64'
    image='kawa-build-ubuntu:22.04-1.99' if platform=='linux' else 'kawa-build-nsis:3'
    source_digest=hashlib.sha256((src/'dist/KawaVPN-0.4.26-source.zip').read_bytes()).hexdigest()
    command=['docker','run','--rm','--name','kawa-lab-package-'+platform+'-'+c['run_id'],'--user',f'{u.pw_uid}:{u.pw_gid}',
      '--cpus=2','--memory=3g','--memory-swap=4g','--pids-limit=512','--cap-drop=ALL','--security-opt=no-new-privileges',
      '-v',str(run)+':/source','-v',str(cache/'src/kawa-vpn-app/target')+':/target',
      '-w','/source/KawaVPN-0.4.26','-e','KAWA_SKIP_BUILD=1','-e',bin_env,
      '-e','KAWA_SOURCE_ARCHIVE=dist/KawaVPN-0.4.26-source.zip','-e','KAWA_SOURCE_SHA256='+source_digest,
      image,'sh','-c',package_command]
    marker=run/(platform+'-package.exit')
    worker='import subprocess,pathlib; rc=subprocess.call('+repr(command)+'); pathlib.Path('+repr(str(marker))+').write_text(str(rc)+"\\n")'
    p=subprocess.Popen(['python3','-c',worker],stdin=subprocess.DEVNULL,stdout=open(run/(platform+'-package.log'),'wb'),stderr=subprocess.STDOUT,start_new_session=True)
    print('Started package-only worker',p.pid)
elif c['action']=='tools':
    for image in ['kawa-build-ubuntu:22.04-1.99','kawa-build-nsis:3']:
        print('TOOLS',image,flush=True)
        subprocess.run(['docker','run','--rm','--network=none','--cpus=1','--memory=128m','--cap-drop=ALL',image,'sh','-c','for c in python3 unzip makensis desktop-file-validate dpkg-deb; do command -v "$c" || true; done'],check=True)
elif c['action']=='install-test':
    package=src/'dist/kawa-vpn_0.4.26_amd64.deb'
    assert package.is_file(), 'deb not yet built'
    command=['docker','run','--rm','--name','kawa-lab-package-test-'+c['run_id'],'--network=none',
      '--cpus=1','--memory=512m','--pids-limit=128','--cap-drop=ALL','--security-opt=no-new-privileges',
      '-v',str(src)+':/source:ro','kawa-build-ubuntu:22.04-1.99','sh',
      '/source/packaging/linux/install-smoke.sh','/source/dist/kawa-vpn_0.4.26_amd64.deb']
    with open(run/'install-smoke.log','wb') as log:
        result=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT,timeout=60)
    print((run/'install-smoke.log').read_text(errors='replace')[-3000:])
    assert result.returncode==0, 'deb install smoke failed'
elif c['action']=='stop':
    name='kawa-lab-'+c['platform']+'-'+c['run_id']
    subprocess.run(['docker','stop','--timeout','20',name],check=True)
    print('Stopped only isolated lab container',name)
elif c['action']=='status':
    import shutil,stat
    kvm=pathlib.Path('/dev/kvm')
    print('HOST_FREE_GIB',round(shutil.disk_usage(base).free/1024**3,2))
    print('KVM_EXISTS',kvm.exists())
    if kvm.exists():
        st=kvm.stat(); print('KVM_MODE_UID_GID',oct(stat.S_IMODE(st.st_mode)),st.st_uid,st.st_gid)
        import fcntl
        try:
            fd=os.open('/dev/kvm',os.O_RDWR)
            try: print('KVM_ROOT_API_VERSION',fcntl.ioctl(fd,0xAE00,0))
            finally: os.close(fd)
        except OSError as error: print('KVM_ROOT_API_ERROR',str(error))
        print('KVM_VPNADMIN_READ',subprocess.run(['runuser','-u','vpnadmin','--','test','-r','/dev/kvm']).returncode==0)
        print('KVM_VPNADMIN_WRITE',subprocess.run(['runuser','-u','vpnadmin','--','test','-w','/dev/kvm']).returncode==0)
    for platform in ['linux','windows']:
        marker=run/(platform+'.exit'); log=run/(platform+'.log')
        print(platform,'exit='+marker.read_text().strip() if marker.exists() else ('running' if log.exists() else 'not started'))
        if log.exists(): print(log.read_text(errors='replace')[-(2000 if not marker.exists() else 500):])
    for marker in run.glob('*-package.exit'): print(marker.name,marker.read_text().strip())
    for package_log in run.glob('*-package.log'): print(package_log.name,package_log.read_text(errors='replace')[-1000:])
    print('ARTIFACTS',json.dumps({p.name:p.stat().st_size for p in (src/'dist').glob('*') if p.is_file()}))
elif c['action']=='list':
    print(json.dumps([str(p.relative_to(run)) for p in run.glob('*.log')]+[str(p.relative_to(run)) for p in run.glob('*.exit')]+[str(p.relative_to(run)) for p in run.glob('*.json')]+[str(p.relative_to(run)) for p in (src/'dist').glob('*') if p.is_file()]))
'''

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('action',choices=['prepare','refresh','start','stop','status','tools','install-test','package','fetch'])
    p.add_argument('--run-id',required=True)
    p.add_argument('--platform',choices=['linux','windows'])
    p.add_argument('--source-zip',type=Path)
    p.add_argument('--reset-stage',action='store_true')
    p.add_argument('--output',type=Path)
    p.add_argument('--known-hosts',required=True,type=Path)
    p.add_argument('--ssh-key',required=True,type=Path)
    p.add_argument('--python-deps',type=Path)
    a=p.parse_args()
    if not re.fullmatch(r'[a-z0-9-]{1,40}',a.run_id): p.error('safe run-id required')
    if a.action in ('start','stop') and not a.platform: p.error('--platform required')
    if a.python_deps: sys.path.insert(0,str(a.python_deps.resolve()))
    import paramiko
    sock=socket.socket();sock.settimeout(25)
    if sys.platform=='win32': sock.setsockopt(socket.IPPROTO_IP,31,struct.pack('!I',3))
    sock.connect(('203.0.113.18',22222))
    c=paramiko.SSHClient();c.load_host_keys(str(a.known_hosts));c.set_missing_host_key_policy(paramiko.RejectPolicy())
    c.connect('203.0.113.17',port=22222,username='vpnadmin',key_filename=str(a.ssh_key),sock=sock,banner_timeout=45,auth_timeout=20)
    cfg={'action':a.action,'run_id':a.run_id,'platform':a.platform,'reset_stage':a.reset_stage}
    remote='/home/vpnadmin/kawa-multiplatform-0.4.26'
    if a.action in ('prepare','refresh'):
        if not a.source_zip: p.error('--source-zip required')
        cfg['sha256']=hashlib.sha256(a.source_zip.read_bytes()).hexdigest()
        _,o,e=c.exec_command('install -d -m 700 '+shlex.quote(remote));assert o.channel.recv_exit_status()==0
        cfg['upload_name']=a.run_id+('-refresh-'+cfg['sha256'][:12] if a.action=='refresh' else '')+'.zip'
        s=c.open_sftp();s.put(str(a.source_zip),remote+'/'+cfg['upload_name']);s.close()
    if a.action=='fetch': cfg['action']='list'
    code='CONFIG='+repr(cfg)+'\n'+REMOTE
    _,o,e=c.exec_command('sudo -n python3 -c '+shlex.quote(code),timeout=60)
    out=o.read().decode();err=e.read().decode();rc=o.channel.recv_exit_status()
    if rc: raise RuntimeError(out+'\n'+err)
    if a.action=='fetch':
        if not a.output: p.error('--output required')
        a.output.mkdir(parents=True,exist_ok=True)
        s=c.open_sftp()
        for item in json.loads(out):
            destination=a.output/Path(item).name
            s.get(remote+'/'+a.run_id+'/'+item,str(destination))
            print(destination.name,destination.stat().st_size)
        s.close()
    else: print(out,end='')
    c.close()

if __name__=='__main__': main()
