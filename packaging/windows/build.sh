#!/bin/sh
# Native Windows (Git Bash) or Linux cargo-xwin; x64 release only.
set -eu
cd "$(dirname "$0")/../.."
. packaging/common.sh
[ "${1:-x64}" = x64 ] || { echo 'Supported Windows package architecture: x64' >&2; exit 1; }
TARGET=x86_64-pc-windows-msvc
if [ "${KAWA_SKIP_BUILD:-0}" != 1 ]; then
    case "$(uname -s)" in
        MINGW*|MSYS*) cargo build --locked --release --target "$TARGET" -p duoray-gui -p duoray-helper ;;
        *) cargo xwin build --locked --release --target "$TARGET" -p duoray-gui -p duoray-helper ;;
    esac
fi
stage=packaging/windows/stage-x64
[ ! -e "$stage" ] || { echo "Review/remove existing $stage before packaging" >&2; exit 1; }
mkdir -p "$stage" dist
fetch_checked "https://github.com/XTLS/Xray-core/releases/download/$XRAY_VERSION/Xray-windows-64.zip" d004c39288ce9ada487c6f398c7c545f7d749e44bdfdd59dbc9f865afba4e1ad packaging/windows/cache/xray-x64.zip
extract_runtime_zip packaging/windows/cache/xray-x64.zip "$stage" xray.exe geoip.dat geosite.dat wintun.dll LICENSE LICENSE-wintun.txt
mv "$stage/LICENSE" "$stage/LICENSE-xray.txt"
BIN=${KAWA_WINDOWS_BIN:-target/$TARGET/release}
install -m755 "$BIN/KawaVPN.exe" "$BIN/KawaVPNHelper.exe" "$stage/"
source_archive
copy_notices "$stage"
mv "$stage/LICENSE" "$stage/LICENSE.txt"
# Optional publisher signing is performed locally, before NSIS/portable packaging.
# KAWA_SIGN_COMMAND continues to sign installer/uninstaller through signing.nsh.
(cd packaging/windows && makensis -INPUTCHARSET UTF8 -V2 -DVERSION="$VERSION" -DARCH=x64 duoray.nsi)
python3 - "$stage" "dist/KawaVPN-$VERSION-windows-x64-portable.zip" <<'PY'
import pathlib,sys,zipfile
with zipfile.ZipFile(sys.argv[2],'w',zipfile.ZIP_DEFLATED) as z:
    for p in sorted(pathlib.Path(sys.argv[1]).iterdir()):
        if p.is_file(): z.write(p,p.name)
PY
sha256sum "dist/KawaVPN-Setup-$VERSION-x64.exe" "dist/KawaVPN-$VERSION-windows-x64-portable.zip" > dist/windows-x64.sha256
