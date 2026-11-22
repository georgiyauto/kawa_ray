#!/bin/sh
# Shared fixed dependency catalog. Call from repository root.
set -eu
# macOS ships shasum; Linux/Git Bash ship sha256sum.
if ! command -v sha256sum >/dev/null 2>&1; then
    sha256sum() { shasum -a 256 "$@"; }
fi
VERSION=$(sed -n 's/^version = "\(.*\)"/\1/p' crates/duoray-gui/Cargo.toml | head -1 | tr -d '\r')
XRAY_VERSION=v26.3.27
fetch_checked() {
    url=$1; checksum=$2; destination=$3
    mkdir -p "$(dirname "$destination")"
    if ! echo "$checksum  $destination" | sha256sum -c - >/dev/null 2>&1; then
        curl -fL --retry 3 --connect-timeout 20 --max-time 600 "$url" -o "$destination.tmp"
        echo "$checksum  $destination.tmp" | sha256sum -c -
        mv "$destination.tmp" "$destination"
    fi
    echo "$checksum  $destination" | sha256sum -c -
}
source_archive() {
    if [ -n "${KAWA_SOURCE_ARCHIVE:-}" ]; then
        # Remote package-only builds may have an older Android tree: embed the
        # caller's final audited snapshot only after checking its exact digest.
        test -n "${KAWA_SOURCE_SHA256:-}" || { echo 'KAWA_SOURCE_SHA256 required for supplied source archive' >&2; return 1; }
        echo "$KAWA_SOURCE_SHA256  $KAWA_SOURCE_ARCHIVE" | sha256sum -c -
        if [ "$KAWA_SOURCE_ARCHIVE" != "dist/KawaVPN-$VERSION-source.zip" ]; then
            cp "$KAWA_SOURCE_ARCHIVE" "dist/KawaVPN-$VERSION-source.zip"
        fi
    else
        # Existence cannot establish that an archive represents current sources.
        python3 tools/make-source-archive.py --version "$VERSION" --output "dist/KawaVPN-$VERSION-source.zip"
    fi
    sha256sum "dist/KawaVPN-$VERSION-source.zip" > dist/source.sha256
}
copy_notices() {
    install -m644 LICENSE LICENSE-EXCEPTION.md THIRD-PARTY-NOTICES.md THIRD-PARTY-CRATES.txt README-KawaVPN.txt "$1/"
    install -m644 "dist/KawaVPN-$VERSION-source.zip" "$1/"
    install -m644 vendor/DUOTUN/LICENSE "$1/LICENSE-DUOTUN.txt"
}

extract_runtime_zip() {
    archive=$1; directory=$2; shift 2
    python3 - "$archive" "$directory" "$@" <<'PY'
import pathlib,sys,zipfile
destination=pathlib.Path(sys.argv[2]);destination.mkdir(parents=True,exist_ok=True)
with zipfile.ZipFile(sys.argv[1]) as archive:
    for name in sys.argv[3:]:
        if pathlib.PurePosixPath(name).name != name:
            raise ValueError('Only explicit top-level runtime files are permitted')
        (destination/name).write_bytes(archive.read(name))
PY
}
