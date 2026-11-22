#!/bin/sh
set -eu
case "${1:-remove}" in
 remove|deconfigure)
  # A package installed without SUDO_UID may never have configured a service.
  if [ -e /etc/systemd/system/kawa-vpn-helper.service ]; then
   /usr/libexec/kawa-vpn/KawaVPNHelper --uninstall-service
  fi
  ;;
esac
# Package manager owns binaries; service removal preserves them.
exit 0
