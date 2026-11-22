#!/bin/sh
# Run as root INSIDE a disposable, network-disabled Docker lab only.
set -eu
[ -f /.dockerenv ] || { echo 'Disposable Docker container required' >&2; exit 1; }
[ "$(id -u)" = 0 ] || exit 1
package=${1:?path to deb required}
unset SUDO_UID
# Missing optional desktop integration packages are reported by dpkg. This
# validates unpack/ownership/maintenance/removal without downloading packages.
dpkg --force-depends -i "$package"
test "$(stat -c '%u:%g:%a' /usr/libexec/kawa-vpn/KawaVPNHelper)" = 0:0:755
test -L /usr/bin/kawa-vpn
test -x /usr/lib/kawa-vpn/KawaVPN
/usr/libexec/kawa-vpn/KawaVPNHelper --protocol
/usr/lib/kawa-vpn/xray version
if /usr/libexec/kawa-vpn/KawaVPNHelper --install-service --allowed-uid 0; then
 echo 'Helper unexpectedly authorized root UID' >&2; exit 1
fi
test ! -e /etc/systemd/system/kawa-vpn-helper.service
dpkg --remove kawa-vpn
test ! -e /usr/bin/kawa-vpn
test ! -e /usr/libexec/kawa-vpn/KawaVPNHelper
echo 'Disposable deb install/ownership/root-UID rejection/removal passed.'
