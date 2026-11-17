# Victorious Framework — APK Player Device Test Checklist

Written 2026-11-17 · Author: Victorious · Covers the APK Player (phase 1) and the 32-bit helper.

**Why this exists.** The APK Player's file intake, manifest reading, status messages and helper hand-off were exercised on x86_64 emulators
and in JVM tests. The container engine inside the helper has **never run on an ARM device**. Nothing below counts as working until a result is
written into the table at the end.

**Rules.** Never commit an APK, ROM, save or key. Use apps you own. Record exactly what you saw, including failures and blank screens.
Read `docs/APK-Player-Design.md` section 8 first so you know what is expected to fail.

## 0. Setup

| Item | Value |
| --- | --- |
| Builds | From the **same** CI run (or the same local build): `VictorLink64-debug.apk` and `VictorLink64-Box32-debug.apk`. The two must be signed with the same key, or the app reports the helper as "not trusted" |
| Install | `adb install -r VictorLink64-debug.apk` then `adb install -r VictorLink64-Box32-debug.apk` (or open both files from a file manager) |
| Record | phone model · Android version · `getprop ro.product.cpu.abilist32` · both APK `sha256sum` · date · who tested |
| Log capture | `adb logcat -c`, run the test, then `adb logcat -d > apk-player-<test>.txt`. Look for `Box32`, `BlackBoxCore`, `libblackbox`, `SIGSEGV` |
| Test app | The owner's old 32-bit game. For a control, any small app you own that has no native code |

## 1. Does this phone have 32-bit support? (decides everything else)

1. Open VictorLink64 → Library → **APK Player** (or Settings → APK Player).
2. Read the **Your phone** card. **Expect:** it either lists 32-bit types (`armeabi-v7a`, `armeabi`) or says "64-bit only: no 32-bit support".
3. Cross-check with `adb shell getprop ro.product.cpu.abilist32` and Settings → **Classic Android check** (no `adb` needed).
4. If it says 64-bit only: the helper APK will refuse to install (`INSTALL_FAILED_NO_MATCHING_ABIS`). Stop here and record it; phase 2 (translation) is needed for this phone.

## 2. Helper install and trust

1. Install `VictorLink64-Box32-debug.apk`. **Expect:** installs on a 32-bit-capable phone. (Android may warn that the app was built for an older Android version; that is the helper's targetSdk 28.)
2. Reopen APK Player. **Expect:** the card says "32-bit helper installed" and shows **Test the 32-bit helper**.
3. Tap **Test the 32-bit helper**. **Expect, in this order of importance:**
   * Line 1: "The helper started as 32-bit …" (if it says 64-bit, record that and stop: the phone is running even a 32-bit-only APK as 64-bit, which would be a surprise).
   * Last line: "BlackBox container core attached …" **or** a message that the engine did not answer. Both are valid results; record which. An engine that does not start on Android 16 is the main risk.
4. Optional: install a helper signed with a different key. **Expect:** card says "not trusted", Play becomes "How to fix".

## 3. Importing an APK

1. **Choose an APK file**, pick your game's `.apk` from Downloads. **Expect:** it appears with its name and icon (icon may be the Android robot if Android refuses to read it), package name, version, size, "Needs: 32-bit ARM (armeabi-v7a)", and a status line.
2. Pick the same file again. **Expect:** no duplicate.
3. Pick a non-APK file. **Expect:** a plain message, nothing added.
4. Check the original file is untouched and the app is **not** in Settings → Apps.
5. Remove the entry. **Expect:** it disappears; the original file still exists.

## 4. Launching (the real question)

1. Tap **Play** on the old game. **Expect one of:**
   * The game appears full screen (record how it looks, whether touch works, whether sound works, how it runs).
   * A message "The app did not start …" with a reason from the helper. Copy it verbatim.
   * Nothing visible. Capture logcat.
2. Press Home, return to VictorLink64, tap Play again. Record whether a second launch works.
3. Does the game need an `.obb` expansion file or Google Play? (Phase 1 does not support either.)

## 5. Control run

Repeat section 4 with a small Java-only app you own, to separate "the engine is broken on this phone" from "this game needs something special".

## Results table

| Test | Phone / Android | Result (exactly what you saw) | Logcat file | Date |
| --- | --- | --- | --- | --- |
| 1 phone report | | | | |
| 2 helper install + test | | | | |
| 3 import | | | | |
| 4 launch old game | | | | |
| 5 launch control app | | | | |

— Victorious
