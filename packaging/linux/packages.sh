#!/bin/sh
set -eu
cd "$(dirname "$0")/../.."
. packaging/common.sh
ARCH=${1:-$(uname -m)}
case "$ARCH" in x86_64) DEB_ARCH=amd64 ;; aarch64|arm64) ARCH=aarch64; DEB_ARCH=arm64 ;; *) exit 2 ;; esac
ROOT=packaging/linux/root-$ARCH
[ -d "$ROOT" ] || sh packaging/linux/stage.sh "$ARCH"
mkdir -p "$ROOT/DEBIAN" dist
cat > "$ROOT/DEBIAN/control" <<EOF
Package: kawa-vpn
Version: $VERSION
Architecture: $DEB_ARCH
Maintainer: Kawa VPN contributors
Section: net
Priority: optional
Depends: libc6 (>= 2.35), libgcc-s1, libstdc++6, libfontconfig1, libxkbcommon0, libx11-6, libwayland-client0, systemd, policykit-1
Description: Kawa VPN desktop client and protected tunnel helper
EOF
install -m755 packaging/linux/pkg/postinstall.sh "$ROOT/DEBIAN/postinst"
install -m755 packaging/linux/pkg/preremove.sh "$ROOT/DEBIAN/prerm"
dpkg-deb --root-owner-group --build "$ROOT" "dist/kawa-vpn_${VERSION}_${DEB_ARCH}.deb"
sha256sum "dist/kawa-vpn_${VERSION}_${DEB_ARCH}.deb" > "dist/linux-$ARCH-deb.sha256"
