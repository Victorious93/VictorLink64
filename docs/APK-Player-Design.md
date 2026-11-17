# APK Player — design, engine choice and phase 2 plan

Victorious Framework · written 2026-11-17 · Author: Victorious

Status of this document: **design plus research**. Section 1 says what exists and how well each part is evidenced. Anything not
listed as tested has not been tested.

## 1. What phase 1 delivers

Goal: pick an APK file, run it inside VictorLink64 in a private sandbox **without installing it on the phone**, including old apps made
for Android 2.3–4.x, with no compatibility gate (nothing in the UI is ever disabled because of a version or chip check).

| Piece | Where | Evidence level |
| --- | --- | --- |
| Pick an APK (Storage Access Framework), copy into private storage, de-duplicate by SHA-256, size cap, no picker-supplied path is trusted | `apkplayer/ApkStore.kt`, `ApkPlayerActivity.kt` | **Automated-tested** on the JVM (`ApkStoreTest`, 8 tests). SAF picker UI **not device-tested** |
| Read the binary manifest: package, version, min/target SDK, label, icon reference, permissions, launcher activity | `apkplayer/AxmlReader.kt`, `ApkInspector.kt` | **Automated-tested** against manifests compiled by `aapt2` during the build (`ApkInspectorTest`, 6 tests, includes truncation/corruption sweeps) |
| Find native ABIs from `lib/<abi>/*.so` | `ApkInspector` | **Automated-tested** (fake `.so` entries) |
| Plain-language status per app ("your phone has 32-bit support / doesn't / translation coming") | `apkplayer/Compatibility.kt` | **Automated-tested** (`CompatibilityTest`, 10 tests) |
| Label and icon via `PackageManager.getPackageArchiveInfo` | `ApkPlayerActivity.kt` | **Compiled** only. Falls back to manifest text. Not run on a device |
| 32-bit helper app (`:box32`, armeabi-v7a only) with signature-checked entry point and a PING that reports the helper process and the engine process separately | `box32/` | **Compiled** (debug APK built; `aapt2 dump badging` shows `native-code: 'armeabi-v7a'`); entry point, gate and hand-off **exercised on x86_64 emulators** (section 7). Not run on ARM hardware |
| BlackBox container core inside the helper | `third_party/blackbox/` | **Compiled only.** Not run on any ARM device by this project |
| Hand-off main app → helper (explicit intent, one-time read grant on a `FileProvider` URI, same-signature check in both directions) | `HelperClient.kt`, `HelperActivity.java` | Protocol constants **automated-tested** for sync (`HelperProtocolSyncTest`). Hand-off and both signature checks **observed on an Android 11 x86_64 emulator**; not on a real phone |
| Phase 2 translator for 64-bit-only phones | — | **Not implemented, deliberately.** Plan in section 6 |

## 2. Why a separate 32-bit helper app

Android starts every process of an app in one mode, chosen when the app is installed from the native libraries in its APK
(`lib/arm64-v8a` wins over `lib/armeabi-v7a` on a phone that has both). A 64-bit process cannot `dlopen` a 32-bit `.so`, so the
main VictorLink64 app (which carries arm64 libraries for the emulators) can never host an armeabi-v7a-only game itself, even in
a separate `android:process`. The repository's existing Classic Android check (PR #25) demonstrates exactly this: its
`:classic32` process runs as 64-bit when the app does.

The same constraint is documented by the VirtualApp project (its README describes a main package plus a "plugin" package for the other
bitness) and enforced in the BlackBox core itself (`AbiUtils.isSupport`: "The box does not support 32-bit Application" when running as 64-bit).
So the design is:

```
 VictorLink64 (64-bit, targetSdk 36)              VictorLink64 32-bit Helper (armeabi-v7a only, targetSdk 28)
 ┌────────────────────────────────────┐          ┌────────────────────────────────────────────────────┐
 │ APK Player screen                  │ explicit │ ":ui" process: HelperActivity                      │
 │  • SAF picker → ApkStore (private) │ intent + │   checks caller package + signing certificate      │
 │  • ApkInspector / Compatibility    │ read     │   PING    → own bitness + engine status            │
 │  • HelperClient (state, signature) │ grant    │   RUN_APK → copy into private storage              │
 └────────────────────────────────────┘ ───────▶ │      │ Messenger (20 s timeout)                    │
          ◀─────── result: status/message ────── │      ▼                                             │
                                                 │ default process: EngineService + BlackBox core     │
                                                 │   install into virtual user 0, launch via stubs    │
                                                 │ ":black" server process, ":p0…:p49" app processes  │
                                                 └────────────────────────────────────────────────────┘
```

Consequences, stated plainly:

* The helper is a **second APK** the user installs once. Android refuses to install an armeabi-v7a-only APK on a phone with no
  32-bit support (`INSTALL_FAILED_NO_MATCHING_ABIS`), which is a truthful gate on its own.
* The helper targets API 28. The container core hooks Android internals and its upstream host app does the same. Google Play
  requires new apps and updates to target a recent API level, so **the helper cannot be published on Google Play**; it is sideload-only.
  The main app stays at targetSdk 36.
* A failure in the engine is contained in the helper's processes; the main app never loads engine code.
* Phase 1 routes **every** app (32-bit, 32+64, Java-only) to the 32-bit helper. 64-bit-only apps are not supported yet: the status
  says so, and "Try anyway" still sends them to the helper so the user can see what happens.

## 3. Engine choice

Requirement: open source, license compatible with distributing inside a GPL-3.0 project, source actually available, maintained recently
enough to have a chance on Android 16, and able to run 32-bit apps (needs a 32-bit host build).

Facts below were read from the GitHub API and repository files on 2026-11-17.

| Candidate | License | Last activity | Finding | Verdict |
| --- | --- | --- | --- | --- |
| `asLody/VirtualApp` | **none** on GitHub (repository has no license file) | public source frozen Dec 2017; README says current code is sold under a commercial license | No license means no permission to copy. Pre-Android-8 code | **Rejected** (no license, stale) |
| `android-hacker/VirtualXposed` | GPL-3.0 | 2024-03 | Built on the 2017 VirtualApp fork; README: "Supports Android 5.0~10.0". Compatible license but too old for Android 16 | Rejected (too old) |
| `FBlackBox/BlackBox` | none detected | 2024-04 | Repository contains only a README (10 files in tree); no source | **Rejected** (no source, no license) |
| `BlackBoxing/BlackBox` | Apache-2.0 | 2022-11 | Source present; README: "supports Android 5.0 ～ 12.0", does not consider Android 4.x | Fallback if the chosen fork fails |
| **`ALEX5402/NewBlackbox`** | **Apache-2.0** | 2026-07-19 | Maintained fork of BlackBox; README claims Android 5–14+, 32-bit and 64-bit builds; sets the virtual app's `targetSdkVersion` on the runtime (`BActivityThread`, relevant to old native libraries) | **Chosen**, with the removals listed in section 4 |

License compatibility (not legal advice): Apache-2.0 code can be included in a GPL-3.0 work, and the combined work is then distributed
under GPL-3.0. The reverse does not hold. GPL-3.0 sources such as VirtualXposed would also be compatible but failed on age. The helper
app is built from this repository, so its complete source (including `third_party/blackbox`) ships under GPL-3.0 terms with the
Apache-2.0 notices preserved (`NOTICE`, `third_party/blackbox/LICENSE`, `third_party/blackbox/UPSTREAM.md`). Credit appears in the
**About screen** of the main app only (and `NOTICE`), as requested; the helper's own screen carries no engine branding.

Not verified: that NewBlackbox runs on **Android 16**. Its README stops at "14+"; its own changelog mentions Android 10–15 fixes. This
is the single largest unknown in phase 1 and the first thing the device checklist tests.

## 4. Audit of the upstream engine (what was removed and why)

The code was read before it was added to the repository. Findings that matter for a product with the VictorLink64 brand:

| Finding | Action |
| --- | --- |
| `BlackBoxCore.sendLogs` uploaded a full logcat and device info to `logs-sender-api.vercel.app` after detected crashes, enabled by default through a hard-coded chat id | Removed (`LogSender.java` deleted, method is a local log line) |
| `startLogcat()` copied logcat into the public Downloads folder | Disabled |
| `closecode/Entry.java`: native-library injection hook (`inject.so`) | Removed |
| `AntiDetection.cpp` / `VirtualSpoof.cpp`: hides root and the virtual environment from apps, spoofs properties | Removed from the build |
| Library manifest requested 323 permissions (camera, microphone, SMS, contacts, vendor-specific) and a required camera feature, which would be inherited by every sandboxed app | All removed; helper declares five normal permissions |
| Stub components exported to every app on the phone | Set to not exported (the system and this app can still start them); receiver left as upstream |
| Bundled `junit.jar` (JUnit is EPL-1.0, not GPL-compatible; unused at runtime) | Removed |
| Prebuilt `libdobby.a` binaries | Kept (Apache-2.0 project); **provenance unverified** — rebuild from source before any public release |

Residual risk worth knowing about: a container that hooks the framework is high-privilege-looking code. The helper limits what a sandboxed
app can do to what the helper's five permissions allow, but a sandboxed app can still use the network and read/write the helper's own
storage. Do not run untrusted apps in it.

## 5. Trust model between the two apps

* The helper accepts requests only from `getCallingPackage()` equal to the matching VictorLink64 package (`…victorlink64[.debug]`) **and**
  signed with the same certificate (`checkSignatures`). The caller package comes from the system, not from the intent.
* The main app only sends an APK if the helper package is signed with the same certificate as itself, and has the expected protocol
  version in its manifest meta-data. Otherwise the UI explains what to reinstall.
* The APK travels as a `content://` URI from a dedicated `FileProvider` (`apk_share_paths.xml` exposes only `files/apk-player/`) with a
  one-time read grant; the helper copies it to its own storage and hashes it. The existing general-purpose provider is not used (its path
  config is wide open).
* Debug builds are signed with a per-machine debug key, so the two APKs must come **from the same build/download**. If a different
  key is detected the UI says so. A release signing story does not exist yet (project-wide).

## 6. Phase 2 plan: 64-bit-only phones (design only, nothing shipped)

Why it matters: Android allows OEMs to ship 64-bit-only builds, and new Play-certified devices are moving that way. Whether the
owner's Moto G Play 2026 (MediaTek Dimensity 6300, Android 16) is one is **unknown until the Classic Android check or the APK Player
"Your phone" card runs on it**. (From memory of Arm's documentation, the Cortex-A76 and A55 cores in that SoC can execute AArch32 at EL0, so
the hardware may be capable even if the system image is not; the report from the device is the only answer that counts.)

A 64-bit-only system has no 32-bit zygote, no 32-bit bionic and no 32-bit ART, and the CPU may not even run AArch32 for apps. A translator
therefore has to run the app's 32-bit **native libraries** inside a 64-bit process. Options considered:

| Option | Idea | Assessment |
| --- | --- | --- |
| A. In-process ARM32→ARM64 dynamic recompiler + thunks | Load the game's `.so` with our own ELF loader, execute ARM code through a JIT, forward libc/log/EGL/GLES/OpenSL/JNI calls to the 64-bit host libraries | The only approach that fits the architecture. Large, Box64-style effort. Candidate JIT: **dynarmic** (0BSD, has an AArch32 guest front end and an AArch64 host back end; used by Citra/Yuzu). Needs: guest memory in the low 4 GiB, a 32-bit syscall/TLS/threading shim, 32↔64-bit handle mapping for JNI and GL objects |
| B. QEMU user-mode (qemu-arm) | Run a whole 32-bit userland under emulation | Process-level, not library-level: would need a full 32-bit Android userland (bionic, ART, zygote). Not realistic. GPL-2.0 |
| C. Android's `libndk_translation` | Google's AOSP binary translator | Targets x86_64/riscv64 hosts translating ARM guests; not ARM32 on an ARM64 host |
| D. Game-specific port | Recompile the owner's game | Out of scope; no source |

Proposed staging, each with a go/no-go gate and its own PR:

1. **Measure the device.** Owner runs the APK Player "Test the 32-bit helper" and the Classic check; record `abilist32`, `ro.zygote`, kernel AArch32 support. If the phone has 32-bit, phase 2 is not needed for this owner (it still is for "universal").
2. **Feasibility spike (separate module, off by default).** CI-built tiny armeabi-v7a test library (compiled by the NDK in the build; never committed) loaded through dynarmic in a 64-bit app: arithmetic, NEON, threads, `printf` thunk. Gate: correct results on the owner's phone and a measured slowdown.
3. **Bionic and JNI bridge.** `libc`, `libm`, `libdl`, `liblog`, `libandroid`, then JNI (`JNIEnv` function table thunks and a handle table). Gate: a hello-world JNI app runs.
4. **Graphics and audio.** EGL/GLES2 and OpenSL ES/AAudio thunks (pointer-width conversions for every GL entry point that takes pointers).
5. **Container integration.** Run the translator inside a *separate 64-bit helper* (mirror of `:box32`) so the main app stays clean, and teach `Compatibility` to prefer it when `has32BitArm` is false.
6. Only then: the owner's actual game.

Risks to record up front: performance of JIT on mid-range cores for heavy 3D games; self-modifying code and text relocations in
old libraries; threading/signal semantics; any DRM/license checks in the game; and the sheer size of stage 3–4. Nothing from this plan
is in the repository except this paragraph.

## 7. What was tested, and the limits of the test environment

* **JVM unit tests:** 26 new tests (see section 1) run in the repository's normal `:app:testDebugUnitTest`. The test APKs are produced from
  XML fixtures by `aapt2` in the Gradle task `generateTestApks` and exist only under `app/build/`; no APK, ROM or key is committed.
* **Build:** `:box32:assembleDebug` and `assembleDebug` compile everything, including the engine's JNI library for armeabi-v7a.
* **Emulator (x86_64, KVM).** x86_64 Android images cannot validate ARM 32-bit behaviour: ARM code runs, if at all, through
  Google's `libndk_translation` binary translator, so "started as 32-bit" there says nothing about a real ARM phone, and the engine's
  ART/Dobby hooks are not expected to behave under translation. Two images were used for what they *can* show:
  * *Android 14 (API 34) Google APIs x86_64* reports `abilist=x86_64,arm64-v8a` — a convenient stand-in for a **64-bit-only phone**.
    Results: main APK installs; the APK Player card says "64-bit only: no 32-bit support"; a non-APK file is refused with a plain message; a
    generated armeabi-v7a test APK imports and shows "Your phone does not have 32-bit support … phase 2 … not in this version" with a
    cyan "Try anyway" button; the helper APK **fails to install** with `INSTALL_FAILED_NO_MATCHING_ABIS`, which confirms the natural gate.
  * *Android 11 (API 30) Google APIs x86_64* reports `armeabi-v7a`/`armeabi` through the translator. Results: helper installs and is
    recognised as installed + trusted; "Test the 32-bit helper" reports the helper UI process as 32-bit (translated, so not meaningful) and,
    **separately**, that the engine process did not answer (it died with `SIGSEGV` inside `libndk_translation` right after the engine's
    native library started — expected under translation, not a verdict on real hardware); "Play" completes the hand-off (explicit intent,
    `content://` grant, the APK arrives byte-identical in the helper's private storage under its SHA-256 name) and then shows the same
    engine failure as a plain message; a request from the shell (not VictorLink64) is rejected (`Rejected request from null`); a helper
    re-signed with a throwaway key makes the card say "not trusted" and "Play" becomes "How to fix".
  * The engine itself was **not** exercised: no sandboxed app was ever started. The Android 11 run found two real bugs that are now fixed
    (the helper's intent filter did not match a request carrying a data URI; engine start-up ran in the same process as the ping, so a native
    crash hid the helper's own answer — the helper screen now runs in a separate `:ui` process that never loads engine code).
* **Real device:** nothing. See `APK-Player-Device-Test-Checklist.md`.

## 8. What will and will not likely work (honest expectations)

Likely, if the phone reports 32-bit support: installing the helper; the PING reporting a 32-bit process; the APK Player screens, intake and messages.

Uncertain until tried on the owner's phone: whether the engine starts at all on Android 16; whether the specific game starts; touch,
audio and GL ES 1.x/2.0 behaviour of a 2.3–4.0 era game; fullscreen/orientation handling.

Unlikely or known-problematic: apps that need Google Play services sign-in, licence servers (LVL) or anti-tamper checks; apps that read
OBB expansion files (phase 1 does not copy or redirect `.obb` files — games that need them will not find their data); apps relying
on old external-storage paths; anything 64-bit-only; any app on a phone with no 32-bit support (phase 2).

Policy risk: running arbitrary user-supplied executable code is the kind of behaviour Google Play's policies treat carefully
(dynamic code loading, "device and network abuse"). VictorLink64's main app is distributed as sideloaded builds today; shipping an APK-running
container through Google Play could get it rejected or removed, and the helper (targetSdk 28) cannot be on Play at all. Keep the
APK Player out of any Play listing until policy is checked with Google's current text. Separately, running someone else's paid app from a
copied APK can infringe their licence; the UI tells the user to run only apps they trust and own.

— Victorious
