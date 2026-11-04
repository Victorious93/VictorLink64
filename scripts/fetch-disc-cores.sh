#!/usr/bin/env bash
# Victorious Framework: refreshes the prebuilt arm64-v8a PSP and GameCube libretro cores in app/src/main/jniLibs.
#   PPSSPP  (GPL-2.0-or-later) source: https://github.com/hrydgard/ppsspp     (libretro/ target)
#   Dolphin (GPL-2.0-or-later) source: https://github.com/libretro/dolphin
# Binaries come from the libretro buildbot nightly. Copies committed on 2026-10-31 (buildbot files dated
# 2026-10-30 UTC) have these SHA-256 values; the script refuses to install anything else unless ALLOW_NEW=1.
set -euo pipefail
BASE=https://buildbot.libretro.com/nightly/android/latest/arm64-v8a
DEST="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/jniLibs/arm64-v8a"
declare -A SHA=(
  [ppsspp]=0c119025c0c3c1120a5ec2adc9c3d828a298db6c2e2277e2ba7411232ee2fd01
  [dolphin]=0fe6a6c25aa05efb06e624f530ab4b2df5249d618259e29c7a95a91da80e7f02
)
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
for c in ppsspp dolphin; do
  curl -fsSL "$BASE/${c}_libretro_android.so.zip" -o "$TMP/$c.zip"
  unzip -q -o "$TMP/$c.zip" -d "$TMP"
  got=$(sha256sum "$TMP/${c}_libretro_android.so" | cut -d' ' -f1)
  if [ "$got" != "${SHA[$c]}" ] && [ "${ALLOW_NEW:-0}" != 1 ]; then
    echo "$c: buildbot binary changed ($got); re-run with ALLOW_NEW=1 and update the hashes"; exit 1
  fi
  cp "$TMP/${c}_libretro_android.so" "$DEST/lib${c}_libretro.so"
  echo "$c -> $DEST/lib${c}_libretro.so ($got)"
done
