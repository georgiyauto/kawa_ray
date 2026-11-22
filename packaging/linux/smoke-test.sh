#!/bin/sh
# No privileged operation, network changes, helper service or GUI is started.
set -eu
cd "$(dirname "$0")/../.."
. packaging/common.sh
ARCH=${1:-$(uname -m)}
case "$ARCH" in x86_64) DEB_ARCH=amd64; MACHINE='x86-64' ;; aarch64|arm64) ARCH=aarch64; DEB_ARCH=arm64; MACHINE='aarch64' ;; *) exit 2 ;; esac
ROOT=packaging/linux/root-$ARCH
test -x "$ROOT/usr/lib/kawa-vpn/KawaVPN"
test -x "$ROOT/usr/libexec/kawa-vpn/KawaVPNHelper"
file "$ROOT/usr/lib/kawa-vpn/KawaVPN" | grep -i "$MACHINE"
file "$ROOT/usr/libexec/kawa-vpn/KawaVPNHelper" | grep -i "$MACHINE"
file "$ROOT/usr/lib/kawa-vpn/xray" | grep -i "$MACHINE"
ldd "$ROOT/usr/lib/kawa-vpn/KawaVPN" > "dist/linux-$ARCH-ldd.txt"
if grep -q 'not found' "dist/linux-$ARCH-ldd.txt"; then cat "dist/linux-$ARCH-ldd.txt"; exit 1; fi
"$ROOT/usr/lib/kawa-vpn/xray" version | grep '26.3.27'
"$ROOT/usr/libexec/kawa-vpn/KawaVPNHelper" --protocol > "dist/linux-$ARCH-helper-protocol.txt"
desktop-file-validate "$ROOT/usr/share/applications/kawa-vpn.desktop"
test "$(dpkg-deb -f "dist/kawa-vpn_${VERSION}_${DEB_ARCH}.deb" Package)" = kawa-vpn
test "$(dpkg-deb -f "dist/kawa-vpn_${VERSION}_${DEB_ARCH}.deb" Architecture)" = "$DEB_ARCH"
# Verify package ownership/modes using tar metadata, not the current staging owner.
dpkg-deb --fsys-tarfile "dist/kawa-vpn_${VERSION}_${DEB_ARCH}.deb" | python3 -c 'import sys,tarfile; t=tarfile.open(fileobj=sys.stdin.buffer,mode="r|"); matches=[m for m in t if m.name.endswith("/usr/libexec/kawa-vpn/KawaVPNHelper")]; assert len(matches)==1; h=matches[0]; assert (h.uid,h.gid,h.mode)==(0,0,0o755)'
test -s "dist/KawaVPN-$VERSION-$ARCH.AppImage"
echo 'Package structure/architecture/dependencies verified; service/VPN integration requires isolated systemd test.'
