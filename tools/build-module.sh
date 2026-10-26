#!/usr/bin/env bash
# Build the DaePilot Magisk module and embed it in the APK assets.
#
# The payload (dae binary + geo data) lives in magisk/payload and is expected
# to be populated before running this script. See README for how to fetch it.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/magisk"
DIST="$ROOT/dist"
ASSETS="$ROOT/app/src/main/assets"
NAME="daepilot-module.zip"

for f in payload/bin/dae payload/bin/daepilotctl payload/geo/geosite.dat payload/geo/geoip.dat; do
  if [ ! -f "$SRC/$f" ]; then
    echo "missing: magisk/$f" >&2
    exit 1
  fi
done

chmod 755 "$SRC/payload/bin/dae" "$SRC/payload/bin/daepilotctl" \
  "$SRC/customize.sh" "$SRC/service.sh" "$SRC/action.sh" "$SRC/uninstall.sh"

mkdir -p "$DIST" "$ASSETS"
rm -f "$DIST/$NAME" "$ASSETS/$NAME"

# Build from inside magisk/ so the archive root matches a Magisk module.
(cd "$SRC" && zip -r -9 -X "$DIST/$NAME" . \
  -x '*.DS_Store' -x '__MACOSX/*' -x '*.map' >/dev/null)

cp "$DIST/$NAME" "$ASSETS/$NAME"
echo "built $DIST/$NAME ($(du -h "$DIST/$NAME" | cut -f1))"
echo "embedded $ASSETS/$NAME"
