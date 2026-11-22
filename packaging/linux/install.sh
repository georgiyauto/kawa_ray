#!/bin/sh
# A system package protects the helper; never elevate an extracted user tree.
set -eu
cd "$(dirname "$0")/../.."
. packaging/common.sh
case "$(uname -m)" in x86_64) ARCH=amd64 ;; aarch64|arm64) ARCH=arm64 ;; *) exit 2 ;; esac
package="dist/kawa-vpn_${VERSION}_${ARCH}.deb"
test -f "$package" || { echo 'Build packages.sh first.' >&2; exit 1; }
exec sudo apt-get install "./$package"
