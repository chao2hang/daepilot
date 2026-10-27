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
if command -v zip >/dev/null 2>&1; then
  (cd "$SRC" && zip -r -9 -X "$DIST/$NAME" . \
    -x '*.DS_Store' -x '__MACOSX/*' -x '*.map' >/dev/null)
else
  python - "$SRC" "$DIST/$NAME" <<'PY'
import os, sys, zipfile

src, dst = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
    for root, dirs, files in os.walk(src):
        dirs.sort()
        for name in sorted(files):
            if name == ".DS_Store":
                continue
            path = os.path.join(root, name)
            archive.write(path, os.path.relpath(path, src).replace(os.sep, "/"))
PY
fi

cp "$DIST/$NAME" "$ASSETS/$NAME"
echo "built $DIST/$NAME ($(du -h "$DIST/$NAME" | cut -f1))"
echo "embedded $ASSETS/$NAME"
