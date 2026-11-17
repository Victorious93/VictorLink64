#!/usr/bin/env bash
# Victorious Framework: refreshes the prebuilt arm64-v8a disc-console libretro cores in app/src/main/jniLibs.
#   PPSSPP  (GPL-2.0-or-later) source: https://github.com/hrydgard/ppsspp     (libretro/ target)
#   Dolphin (GPL-2.0-or-later) source: https://github.com/libretro/dolphin
#   LRPS2   (GPL-3.0)          source: https://github.com/libretro/ps2        (PCSX2-derived PlayStation 2 core)
# Binaries come from the libretro buildbot nightly. The script refuses to install anything whose SHA-256 differs
# from the pinned value below unless ALLOW_NEW=1. Provenance (dates, source commits) is recorded in NOTICE and
# docs/PS2.md; the buildbot binary does not embed its git commit, so for LRPS2 the commit is inferred from the
# buildbot file date (see docs/PS2.md).
#   ppsspp, dolphin: copies committed on 2026-10-31 (buildbot files dated 2026-10-30 UTC).
#   lrps2:           buildbot file "pcsx2_libretro_android.so.zip" last-modified Sun, 15 Nov 2026 22:58:08 GMT.
# The LRPS2 core is installed as liblrps2_libretro.so (the buildbot calls it "pcsx2").
set -euo pipefail
BASE=https://buildbot.libretro.com/nightly/android/latest/arm64-v8a
DEST="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/jniLibs/arm64-v8a"
# buildbot name | installed name | pinned SHA-256 of the extracted .so
CORES=(
  "ppsspp|ppsspp|0c119025c0c3c1120a5ec2adc9c3d828a298db6c2e2277e2ba7411232ee2fd01"
  "dolphin|dolphin|0fe6a6c25aa05efb06e624f530ab4b2df5249d618259e29c7a95a91da80e7f02"
  "pcsx2|lrps2|7fbc421819bade622e04886b1ddb84b0cfdb9bc7a7ad97fdcee824ff82a4df06"
)
ONLY="${1:-}"
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
for entry in "${CORES[@]}"; do
  IFS='|' read -r src name pin <<<"$entry"
  if [ -n "$ONLY" ] && [ "$ONLY" != "$name" ]; then continue; fi
  curl -fsSL "$BASE/${src}_libretro_android.so.zip" -o "$TMP/$src.zip"
  unzip -q -o "$TMP/$src.zip" -d "$TMP"
  got=$(sha256sum "$TMP/${src}_libretro_android.so" | cut -d' ' -f1)
  if [ "$got" != "$pin" ] && [ "${ALLOW_NEW:-0}" != 1 ]; then
    echo "$name: buildbot binary changed ($got); re-run with ALLOW_NEW=1 and update the hashes"; exit 1
  fi
  cp "$TMP/${src}_libretro_android.so" "$DEST/lib${name}_libretro.so"
  echo "$name -> $DEST/lib${name}_libretro.so ($got)"
done
