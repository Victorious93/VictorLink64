# Victorious Framework — VictorLink64 Upstream Audit

Author: Victorious · Audit date: 2026-10-13 · Scope: Milestone 1, Task 1

Confidence labels: **[Certain]** directly observed in this checkout or build, **[Likely]** strong inference, **[Guessing]** assumption.

## 1. Pinned upstream identity

| Item | Value |
| --- | --- |
| Upstream | https://github.com/mupen64plus-ae/mupen64plus-ae |
| Pinned commit | `33bf7021549d7873e976884370fbd1a8e7333bc7` **[Certain]** (`git rev-parse HEAD` of the clone; matches the previous session's handoff) |
| Integration method | Unmodified `git archive HEAD` snapshot, imported as the first commit after the repository's initial commit **[Certain]** |
| Submodules | None. `git submodule status --recursive` printed nothing and `.gitmodules` does not exist; third-party emulator plugins are vendored in-tree under `*/upstream/` **[Certain]** |
| Upstream license | GNU GPL v3 (`gpl-license` at repo root); nested components carry their own notices, all preserved **[Certain]** for the root file, **[Likely]** that nested notices are complete (not individually audited) |

Why a snapshot rather than a fork or submodule: the repository you named was empty (one README commit), upstream has no submodule structure to preserve, and a plain snapshot gives a reviewable "unmodified baseline" commit to diff every later change against. Cost: no upstream history; future upstream updates must be merged by diffing against the pinned commit.

## 2. Toolchain declared by the checkout

| Setting | Value | Where |
| --- | --- | --- |
| Gradle | 9.5.0 (`-all` distribution) | `gradle/wrapper/gradle-wrapper.properties` |
| Android Gradle Plugin | 9.3.1 | root `build.gradle` |
| compileSdk / targetSdk | 36 | observed in built APK via `aapt2 dump badging` |
| minSdk | 28 | same |
| NDK | 26.1.10909125 | `build_common/version_common.gradle` |
| Java | CI uses 21; app source compatibility is Java 17 | `.github/workflows/build.yml` (upstream), `app/build.gradle` |
| ABIs | armeabi-v7a, arm64-v8a, x86, x86_64 | `app/build.gradle` `abiFilters` |

All **[Certain]**.

## 3. Source map for the planned features (Milestones 2–4)

Package root: `app/src/main/java/paulscode/android/mupen64plusae/`. Paths below were listed from the checkout; **behaviour has not been read in depth**, so each "extension point" is **[Likely]** until the owning milestone reads the code.

| Concern | Files | Notes |
| --- | --- | --- |
| Launcher / TV entry | `SplashActivity.java`; manifest declares `LEANBACK_LAUNCHER` category and `android.software.leanback` as not required | Same APK can already be launched from an Android TV home screen **[Certain]** for the manifest entries; the runtime TV UX is untested |
| ROM discovery/import | `ScanRomsActivity`, `ScanRomsFragment`, `task/CacheRomInfoService.java`, `util/FileUtil.java`, `util/SevenZInputStream.java`, `GalleryActivity` | Upstream uses ZIP/7z reading and a scan-based library, not a single "import once" flow. Milestone 2 must check its ZIP bounds/traversal handling before reusing it **[Guessing]** that gaps exist |
| Game lifecycle | `game/GameActivity.java`, `game/GameDataManager.java`, `jni/CoreService.java`, `jni/CoreFragment.java`, `jni/CoreInterface.java` | Emulation runs in a service plus native core; `CoreService` references `m64p_test_rom.v64` for disk-only games |
| Rendering | `game/GameSurface.java`, video plugins `mupen64plus-video-{gliden64,glide64mk2,gln64,rice,angrylion-plus,parallel}`, `ae-bridge` | Frame-capture feasibility for streaming is **unknown** until the renderer path is read |
| Audio | `mupen64plus-audio-android` (Oboe 1.6.1 via Maven), `jni/AndroidAudioLibrary.java` | Capture point for streaming unknown |
| Input | `input/AbstractController`, `TouchController`, `PeripheralController`, `SensorController`, `input/map/`, `input/provider/`, `jni/NativeInput.java`, `mupen64plus-input-android`, `mupen64plus-input-raphnet` | Existing split between touch and peripheral controllers is the natural seam for a single N64 input model |
| Controller profiles | `profile/ControllerProfileActivity*`, `ManageControllerProfilesActivity` | Existing remapping UI, reusable **[Likely]** |
| Touch layouts | `profile/TouchscreenProfileActivity`, `persistent/TouchscreenPrefsActivity`, `game/GameOverlay.java` | Existing editable touchscreen profiles **[Likely]** |
| Saves / states | `game/GameActivity.java`, `jni/CoreService.java`, `jni/NativeConfigFiles.java`, `ImportExportActivity` | Upstream behaviour for incompatible states not yet examined |
| Netplay | `netplay/` | Emulator netplay is **not** screen streaming and is not a substitute |

## 4. Package-bound assumptions checked

**[Certain]** unless marked.

- `applicationId` was `org.mupen64plusae.v3.alpha` with a `.debug` debug suffix; VictorLink64 now uses `org.victoriousframework.victorlink64` with the same `.debug` suffix.
- Java namespace stays `paulscode.android.mupen64plusae`; library modules keep `namespace` values such as `org.mupen64plusae.v3.alpha` (`ae-bridge`). `applicationId` and `namespace` are independent in AGP, so changing one does not move the other.
- `FileProvider` authority is `${applicationId}.filesprovider` in the manifest, and `FileUtil.java` derives it from `getPackageName()` — both follow the new ID.
- RetroAchievements broadcast actions use `${applicationId}` in the manifest — follow the new ID.
- One hardcoded string: `RaphnetControllerHandler.ACTION_USB_PERMISSION` was `"org.mupen64plusae.v3.alpha.USB_PERMISSION"`; changed to `"org.victoriousframework.victorlink64.USB_PERMISSION"` so the two apps cannot cross-trigger each other's permission receivers.
- JNI symbol names were **not** audited. **[Likely]** they are unaffected, because JNI binds to the Java package and class names (unchanged), not to `applicationId`. A mismatch would surface only at runtime, which has not been exercised.
- Signing: the debug build is signed with the SDK debug key (APK Signature Scheme v2). Upstream's `release` build type also uses `signingConfigs.debug`; no release signing exists. Anything installed over an existing install must share a signing key, which is another reason the distinct ID matters.

## 5. Precompiled and third-party binary inventory

The previous handoff warned not to claim everything was rebuilt from source. Findings:

### 5.1 Precompiled binaries committed to Git (not rebuilt by this build)

- `ndkLibs/libs/{debug,release}/<abi>/` — **80 files** **[Certain]**: for each of 4 ABIs × 2 build types, 10 files: `libfreetype.so`, `libSDL2_net.so`, `librcheevos.a`, `libnatpmp.so`, `libsoundtouch_fp.so`, `libpng.a`, `libsoundtouch.so`, `libEGLLoader.a`, `libhidapi.so`, `libminiupnp-build.so`.
  They are consumed as `jniLibs` by `app/build.gradle` (`../ndkLibs/libs/{debug,release}`). Matching source directories exist beside them (`ndkLibs/freetype`, `png`, `SDL_net`, `libnatpmp`, `miniupnp`, `rcheevos`), but **this build did not rebuild these from that source, and I did not verify that the committed binaries correspond to it** **[Certain]** for the first part, unverified for the second.
- `mupen64plus-video-gliden64/upstream/src/GLideNHQ/lib/{libdxtn,libpng,libz}.a` — prebuilt static archives, provenance unverified.
- `gradle/wrapper/gradle-wrapper.jar` — standard Gradle wrapper binary; its checksum has not been compared with Gradle's published value.

### 5.2 Binaries pulled from Maven at build time

Not committed, but present in the APK **[Certain]** that they exist; their exact origin per library is **[Likely]**: `libjnidispatch.so` (JNA 5.17.0 AAR), `liboboe.so` (Oboe 1.6.1), `libc++_shared.so` (NDK runtime). `libusb1.0.so` is built from `mupen64plus-input-raphnet/libusb` **[Likely]**.

### 5.3 Compiled from in-tree source by this build

`libmupen64plus-core.so`, `libmupen64plus-audio-android(-fp).so`, `libmupen64plus-input-android.so`, `libmupen64plus-input-raphnet.so`, `libmupen64plus-rsp-{cxd4,hle,parallel}.so`, `libmupen64plus-video-{GLideN64,angrylion-plus,glide64mk2,glide64mk2-egl,gln64,parallel,rice}.so`, `libae-bridge.so`, `libminiupnp-bridge.so` — built by `assembleDebug` via NDK-build/CMake **[Certain]** (task log shows NDK 26.1 compiling them).

## 6. Game-content check

- `git ls-files` finds exactly one ROM-extension file: `app/src/main/assets/mupen64plus_data/m64p_test_rom.v64`, 1,048,576 bytes, SHA-256 `b5fe9d650a67091c97838386f5102ad94c79232240f9c5bcc72334097d76224c` **[Certain]**, matching the handoff value. It is upstream's diagnostic asset (used by `CoreService` for disk-only games), not commercial content **[Likely]**; I identified it from the handoff and the file name, and did not boot it.
- The payload verifier allows this file only at its exact APK path and SHA-256 digest.
- The user-supplied ZIP/ROM and APK were **not present in this environment** and are not in the repository.

## 7. Blockers and risks recorded during the audit

1. **Maven Central rate-limits this sandbox's egress (HTTP 429).** Gradle resolution succeeds after several retries because successful downloads are cached. This is an environment property, not a project defect. See `BUILDING.md`.
2. Upstream's CI `release` job force-pushes a `Pre-release` tag and publishes a release on `master`; it has been removed from this repository's workflow.
3. No Android device or emulator is available here, so no runtime claim can be made at this milestone.

— Victorious
