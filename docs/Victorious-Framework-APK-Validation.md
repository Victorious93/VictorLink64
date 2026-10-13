# Victorious Framework — APK Validation (VictorLink64 debug)

Date: 2026-10-13 · Author: Victorious · Evidence is structural only; **no runtime result is claimed.**

## Artifact

| Item | Value |
| --- | --- |
| Path | `app/build/outputs/apk/debug/VictorLink64-debug.apk` (untracked build output) |
| Size | 51,232,817 bytes |
| SHA-256 | `13a74d6d00e7b905dfb3e074f97aa93aefb2208140d81a1b59f8e52e1a9e1d6a` |
| Built from | this repository, working tree after the identity commit; upstream pin `33bf7021…` |

## Inspection (aapt2 / apksigner from build-tools 36.0.0)

- Package `org.victoriousframework.victorlink64.debug`, label `VictorLink64`, versionCode 249 / `3.0.249 (beta)` (inherited from upstream).
- minSdk 28, targetSdk 36, compileSdk 36.
- `native-code`: arm64-v8a, armeabi-v7a, x86, x86_64.
- Leanback launchable activity `SplashActivity`; gamepad and leanback features declared `required=false`.
- FileProvider authority and RetroAchievements actions use the new ID.
- Signature: verifies under APK Signature Scheme v2 (SDK debug key). v1/v3/v4 not present.

## Payload verifier (`scripts/verify_apk_payload.py`)

Run with all four ABIs and the 28 libraries observed in arm64-v8a: **PASS**. Each ABI directory holds those 28 libraries (112 `lib/` entries in total), ELF class/machine matched per ABI, no unsafe or duplicate entries. The only ROM-like entry is `assets/mupen64plus_data/m64p_test_rom.v64`, allowed by exact path and SHA-256.

The unmodified upstream baseline (`Mupen64PlusAE-debug.apk`, SHA-256 `701c7a82db60a9f527948656465e46f6ef922177a9d06accec60598f815552e3`) was built first and passed the same checks.

## Not verified

Installation, launch, rendering, audio, ROM import, input, saves, and anything on Android TV. No device or emulator was available. Release signing, `minifyEnabled` release builds, and lint were not run.

— Victorious
