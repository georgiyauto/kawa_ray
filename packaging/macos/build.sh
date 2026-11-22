#!/bin/sh
# Run natively on macOS. Produces ad-hoc app, protected-helper pkg, and DMG.
set -eu
cd "$(dirname "$0")/../.."
. packaging/common.sh
ARCH=${1:-$(uname -m)}
case "$ARCH" in
 aarch64|arm64) ARCH=aarch64; ZIP=Xray-macos-arm64-v8a.zip; SHA=2e93a67e8aa1936ecefb307e120830fcbd4c643ab9b1c46a2d0838d5f8409eaf ;;
 x86_64) ZIP=Xray-macos-64.zip; SHA=f5b0471d3459eff1b82e48af0aeac186abcc3298210070afbbbd8437a4e8b203 ;;
 *) exit 2 ;;
esac
TARGET=$ARCH-apple-darwin
WORK=packaging/macos/work-$ARCH
APP="$WORK/Kawa VPN.app"
export MACOSX_DEPLOYMENT_TARGET=12.0
[ "$(uname -s)" = Darwin ] || { echo 'Native macOS tools required.' >&2; exit 1; }
if [ "${KAWA_SKIP_BUILD:-0}" != 1 ]; then
 cargo build --locked --release --target "$TARGET" -p duoray-gui -p duoray-helper
fi
[ ! -e "$WORK" ] || { echo "Review/remove existing $WORK before packaging" >&2; exit 1; }
BIN=${KAWA_MACOS_BIN:-target/$TARGET/release}
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources" "$WORK/dmg" "$WORK/payload/Library/PrivilegedHelperTools" dist
fetch_checked "https://github.com/XTLS/Xray-core/releases/download/$XRAY_VERSION/$ZIP" "$SHA" "packaging/macos/cache/$ZIP"
extract_runtime_zip "packaging/macos/cache/$ZIP" "$WORK/xray" xray geoip.dat geosite.dat LICENSE
install -m755 "$BIN/KawaVPN" "$WORK/xray/xray" "$APP/Contents/MacOS/"
install -m644 "$WORK/xray/geoip.dat" "$WORK/xray/geosite.dat" "$APP/Contents/MacOS/"
install -m644 "$WORK/xray/LICENSE" "$APP/Contents/Resources/LICENSE-xray.txt"
source_archive
copy_notices "$APP/Contents/Resources"
ICONSET="$WORK/kawa-vpn.iconset"
mkdir -p "$ICONSET"
for size in 16 32 128 256 512; do
 sips -z "$size" "$size" crates/duoray-gui/ui/assets/kawa-brand.png --out "$ICONSET/icon_${size}x${size}.png" >/dev/null
 double=$((size * 2))
 sips -z "$double" "$double" crates/duoray-gui/ui/assets/kawa-brand.png --out "$ICONSET/icon_${size}x${size}@2x.png" >/dev/null
done
iconutil -c icns "$ICONSET" -o "$APP/Contents/Resources/kawa-vpn.icns"
cat > "$APP/Contents/Info.plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleName</key><string>Kawa VPN</string>
<key>CFBundleDisplayName</key><string>Kawa VPN</string>
<key>CFBundleIdentifier</key><string>pro.vinnikov.kawavpn</string>
<key>CFBundleVersion</key><string>$VERSION</string>
<key>CFBundleShortVersionString</key><string>$VERSION</string>
<key>CFBundleExecutable</key><string>KawaVPN</string>
<key>CFBundleIconFile</key><string>kawa-vpn</string>
<key>CFBundlePackageType</key><string>APPL</string>
<key>LSMinimumSystemVersion</key><string>12.0</string>
<key>LSApplicationCategoryType</key><string>public.app-category.utilities</string>
<key>NSHighResolutionCapable</key><true/>
</dict></plist>
EOF
# Ad-hoc integrity signature: no Developer ID/notarization claim.
codesign --force --sign - "$APP/Contents/MacOS/xray"
codesign --force --sign - "$APP"
codesign --verify --deep --strict "$APP"
plutil -lint "$APP/Contents/Info.plist"
# Root Installer installs the helper. The GUI never copies from a writable app.
HELPER="$WORK/payload/Library/PrivilegedHelperTools/pro.vinnikov.kawavpn.helper"
install -m755 "$BIN/KawaVPNHelper" "$HELPER"
codesign --force --sign - "$HELPER"
mkdir -p "$WORK/scripts"
install -m755 packaging/macos/postinstall "$WORK/scripts/postinstall"
pkgbuild --root "$WORK/payload" --ownership recommended --scripts "$WORK/scripts" \
 --identifier pro.vinnikov.kawavpn.helper --version "$VERSION" \
 "$WORK/dmg/KawaVPN-$VERSION-helper-$ARCH.pkg"
cp -R "$APP" "$WORK/dmg/"
ln -s /Applications "$WORK/dmg/Applications"
cp packaging/macos/INSTALL.txt "$WORK/dmg/INSTALL.txt"
hdiutil create -volname 'Kawa VPN' -srcfolder "$WORK/dmg" -ov -format UDZO "dist/KawaVPN-$VERSION-macos-$ARCH.dmg" >/dev/null
hdiutil verify "dist/KawaVPN-$VERSION-macos-$ARCH.dmg"
ditto -c -k --keepParent "$APP" "dist/KawaVPN-$VERSION-macos-$ARCH-app.zip"
sha256sum "dist/KawaVPN-$VERSION-macos-$ARCH.dmg" "dist/KawaVPN-$VERSION-macos-$ARCH-app.zip" > "dist/macos-$ARCH.sha256"
