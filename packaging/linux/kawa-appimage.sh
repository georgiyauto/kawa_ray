#!/bin/sh
set -eu
cd "$(dirname "$0")/../.."
. packaging/common.sh
ARCH=${1:-$(uname -m)}
case "$ARCH" in
 x86_64) TOOL_SHA=ed4ce84f0d9caff66f50bcca6ff6f35aae54ce8135408b3fa33abfc3cb384eb0; RUNTIME_SHA=2fca8b443c92510f1483a883f60061ad09b46b978b2631c807cd873a47ec260d ;;
 aarch64|arm64) ARCH=aarch64; TOOL_SHA=f0837e7448a0c1e4e650a93bb3e85802546e60654ef287576f46c71c126a9158; RUNTIME_SHA=00cbdfcf917cc6c0ff6d3347d59e0ca1f7f45a6df1a428a0d6d8a78664d87444 ;;
 *) exit 2 ;;
esac
ROOT=packaging/linux/root-$ARCH
[ -d "$ROOT" ] || sh packaging/linux/stage.sh "$ARCH"
WORK=packaging/linux/appimage-$ARCH
APPDIR=$WORK/AppDir
[ ! -e "$APPDIR" ] || { echo "Review existing $APPDIR before rebuilding" >&2; exit 1; }
mkdir -p "$APPDIR" dist
cp -a "$ROOT/usr" "$APPDIR/"
cp packaging/linux/kawa-vpn.desktop "$APPDIR/kawa-vpn.desktop"
cp crates/kawaray-gui/ui/assets/kawa-brand.png "$APPDIR/kawa-vpn.png"
ln -s kawa-vpn.png "$APPDIR/.DirIcon"
cat > "$APPDIR/AppRun" <<'EOF'
#!/bin/sh
HERE=$(dirname "$(readlink -f "$0")")
exec "$HERE/usr/lib/kawa-vpn/KawaVPN" "$@"
EOF
chmod 755 "$APPDIR/AppRun"
desktop-file-validate "$APPDIR/kawa-vpn.desktop"
TOOL=packaging/linux/cache/appimagetool-1.9.1-$ARCH.AppImage
fetch_checked "https://github.com/AppImage/appimagetool/releases/download/1.9.1/appimagetool-$ARCH.AppImage" "$TOOL_SHA" "$TOOL"
RUNTIME=packaging/linux/cache/runtime-20251108-$ARCH
fetch_checked "https://github.com/AppImage/type2-runtime/releases/download/20251108/runtime-$ARCH" "$RUNTIME_SHA" "$RUNTIME"
chmod 755 "$TOOL"
ARCH=$ARCH APPIMAGE_EXTRACT_AND_RUN=1 "$TOOL" --runtime-file "$RUNTIME" --mksquashfs-opt=-processors --mksquashfs-opt=2 --no-appstream "$APPDIR" "dist/KawaVPN-$VERSION-$ARCH.AppImage"
sha256sum "dist/KawaVPN-$VERSION-$ARCH.AppImage" > "dist/linux-$ARCH-appimage.sha256"
