#!/usr/bin/env bash
# Rebuilds the prebuilt bsnes libretro core (GPLv3) shipped in app/src/main/jniLibs.
# Source: https://github.com/libretro/bsnes (built from commit 63ee22f)
set -euo pipefail
REV=${BSNES_REV:-63ee22f}
WORK=${WORK:-/tmp/bsnes-build}
NDK=${ANDROID_NDK_HOME:-$ANDROID_SDK_ROOT/ndk/26.1.10909125}
rm -rf "$WORK"; git clone https://github.com/libretro/bsnes.git "$WORK"; git -C "$WORK" checkout "$REV"
cd "$WORK/bsnes/target-libretro/jni"
"$NDK/ndk-build" -j"$(nproc)" APP_ABI="armeabi-v7a arm64-v8a x86 x86_64" APP_PLATFORM=android-28
DEST="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/jniLibs"
for a in armeabi-v7a arm64-v8a x86 x86_64; do mkdir -p "$DEST/$a"; cp "../libs/$a/libretro.so" "$DEST/$a/libbsnes_libretro.so"; done
