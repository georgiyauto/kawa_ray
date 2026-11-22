#!/bin/sh
set -eu
cd "$(dirname "$0")/../.."
. packaging/common.sh
ARCH=${1:-$(uname -m)}
case "$ARCH" in
 x86_64) ZIP=Xray-linux-64.zip; SHA=23cd9af937744d97776ee35ecad4972cf4b2109d1e0fe6be9930467608f7c8ae ;;
 aarch64|arm64) ARCH=aarch64; ZIP=Xray-linux-arm64-v8a.zip; SHA=4d30283ae614e3057f730f67cd088a42be6fdf91f8639d82cb69e48cde80413c ;;
 *) exit 2 ;;
esac
TARGET=$ARCH-unknown-linux-gnu
if [ "${KAWA_SKIP_BUILD:-0}" != 1 ]; then
 cargo build --locked --release --target "$TARGET" -p kawaray-gui -p kawaray-helper
fi
BIN=${KAWA_LINUX_BIN:-target/$TARGET/release}
ROOT=packaging/linux/root-$ARCH
# Refuse mixing stale files with release output.
[ ! -e "$ROOT" ] || { echo "Remove/review existing $ROOT before packaging" >&2; exit 1; }
fetch_checked "https://github.com/XTLS/Xray-core/releases/download/$XRAY_VERSION/$ZIP" "$SHA" "packaging/linux/cache/$ZIP"
mkdir -p "$ROOT/usr/lib/kawa-vpn" "$ROOT/usr/libexec/kawa-vpn" "$ROOT/usr/share/doc/kawa-vpn" "$ROOT/usr/bin"
install -m755 "$BIN/KawaVPN" "$ROOT/usr/lib/kawa-vpn/KawaVPN"
install -m755 "$BIN/KawaVPNHelper" "$ROOT/usr/libexec/kawa-vpn/KawaVPNHelper"
extract_runtime_zip "packaging/linux/cache/$ZIP" "$ROOT/usr/lib/kawa-vpn" xray geoip.dat geosite.dat LICENSE
mv "$ROOT/usr/lib/kawa-vpn/LICENSE" "$ROOT/usr/share/doc/kawa-vpn/LICENSE-xray.txt"
chmod 755 "$ROOT/usr/lib/kawa-vpn/xray"
ln -s ../lib/kawa-vpn/KawaVPN "$ROOT/usr/bin/kawa-vpn"
install -Dm644 packaging/linux/kawa-vpn.desktop "$ROOT/usr/share/applications/kawa-vpn.desktop"
install -Dm644 crates/kawaray-gui/ui/assets/kawa-brand.png "$ROOT/usr/share/icons/hicolor/256x256/apps/kawa-vpn.png"
source_archive
copy_notices "$ROOT/usr/share/doc/kawa-vpn"
