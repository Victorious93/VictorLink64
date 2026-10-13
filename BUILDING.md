# Victorious Framework — Building VictorLink64

Verified on 2026-10-13 in a Linux x86_64 cloud container. **[Certain]** unless marked.

## Toolchain (versions read from the checkout, then installed and observed)

| Component | Version | Source of requirement |
| --- | --- | --- |
| JDK | OpenJDK 21.0.12 (full JDK, `javac` present) | upstream CI uses 21 |
| Gradle | 9.5.0 via `./gradlew` | `gradle/wrapper/gradle-wrapper.properties` |
| Android Gradle Plugin | 9.3.1 | root `build.gradle` |
| Android SDK | platform `android-36`, build-tools `36.0.0` | compileSdk 36 |
| NDK | `26.1.10909125` | `build_common/version_common.gradle` |
| CMake | `3.22.1` (SDK package) | native modules use CMake |
| Host packages | `gawk`, `file` | upstream CI installs them; **without `gawk` the build fails** generating `asm_defines_gas.h` for arm64 |

Install SDK pieces (after accepting licenses):

```
sdkmanager "platforms;android-36" "build-tools;36.0.0" "ndk;26.1.10909125" "cmake;3.22.1"
```

Create an untracked `local.properties` containing `sdk.dir=/path/to/android-sdk` (it is git-ignored).

## Build

```
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/VictorLink64-debug.apk`. A clean native build of four ABIs took about 6 minutes in the verification environment (warm Gradle cache).

## Verify

```
python3 -I -m unittest discover -s scripts/tests -v
APK=app/build/outputs/apk/debug/VictorLink64-debug.apk
LIBS=$(unzip -l "$APK" | awk '{print $4}' | grep '^lib/arm64-v8a/' | xargs -n1 basename | sort | paste -sd,)
python3 -I scripts/verify_apk_payload.py "$APK" --abis arm64-v8a,armeabi-v7a,x86,x86_64 --libs "$LIBS"
$ANDROID_HOME/build-tools/36.0.0/aapt2 dump badging "$APK"
$ANDROID_HOME/build-tools/36.0.0/apksigner verify --verbose "$APK"
```

The verifier checks structure only. Passing it is not evidence that the app runs.

## Known environment issues

- **Maven Central HTTP 429.** In the sandbox used for verification, `repo.maven.apache.org` answered some parallel requests with `429 Too Many Requests`. Gradle caches what it downloads, so repeating `./gradlew assembleDebug` converged (the baseline needed 3 attempts; the VictorLink64 build, run afterwards, needed 1). Do not treat a 429 as a project defect. This does not occur on a normal network **[Likely]**.
- **Two Gradle builds on one tree corrupt each other** (`unable to rename temporary`, missing `configure_stdout.txt`). Run one build at a time.
- **Missing `gawk`** produces `gawk: not found` followed by `'asm_defines_gas.h' file not found`.

## Identity

Application ID `org.victoriousframework.victorlink64` (debug build adds `.debug`). Java namespace `paulscode.android.mupen64plusae` is intentionally unchanged. It is a distinct package from upstream `org.mupen64plusae.v3.alpha`, so it installs alongside it and does not touch its data.

## CI

`.github/workflows/build.yml` builds the debug APK, runs the verifier tests and payload check, and uploads the debug APK as a workflow artifact. It holds read-only permissions and never creates tags or releases. On a GitHub runner it built the APK (18m17s) and the verifier passed; the final artifact upload failed with `Artifact storage quota has been hit` (account/repo limit, not code). It uses the runner's preinstalled Android SDK and locates `sdkmanager` itself, because `android-actions/setup-android@v3` fails with `Failed to find package 'tools'`. Builds are **not bit-for-bit reproducible**: the runner APK (SHA-256 `f146b839…90ea`) differs from the sandbox APK (`13a74d6d…1d6a`).

— Victorious
