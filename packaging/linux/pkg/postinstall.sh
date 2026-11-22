#!/bin/sh
set -eu
# Root-owned binary performs service maintenance. Never authorize UID 0.
case "${SUDO_UID:-}" in ''|*[!0-9]*|0)
 if [ -d /run/systemd/system ] && [ -e /etc/systemd/system/kawa-vpn-helper.service ]; then
  /usr/bin/systemctl try-restart kawa-vpn-helper.service
 fi
 ;; *) /usr/libexec/kawa-vpn/KawaVPNHelper --install-service --allowed-uid "$SUDO_UID" ;; esac
command -v update-desktop-database >/dev/null 2>&1 && update-desktop-database -q /usr/share/applications || true
exit 0
