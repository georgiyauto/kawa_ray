#!/bin/sh
set -eu
exec sh "$(dirname "$0")/kawa-appimage.sh" "$@"
