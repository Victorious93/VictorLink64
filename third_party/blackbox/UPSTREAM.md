# BlackBox container core — provenance and local changes

Victorious Framework. This directory holds the virtual container core used by the **32-bit helper** app (`:box32`).
It is not linked into the main VictorLink64 app.

| | |
| --- | --- |
| Upstream | NewBlackbox, https://github.com/ALEX5402/NewBlackbox |
| Revision | `89b59836c66f173756a4ae258cf379a957649820` (committed 2026-07-19) |
| Taken | `Bcore/` (container core), `black-reflection/`, `compiler/` (annotation processor), `LICENSE` |
| Not taken | upstream demo app, README/Docs, release notes, build system files, `FreeReflection` dependency |
| License | Apache License 2.0 (`LICENSE` in this directory is upstream's, unchanged) |

Gradle project names: `Bcore` → `:bb-core`, `black-reflection` → `:bb-reflection`, `compiler` → `:bb-compiler` (see `settings.gradle`).

## Changes made by Victorious Framework

Everything not listed here is upstream code, unmodified. These are the only edits (the review that found them is in
`docs/APK-Player-Design.md`, "Audit of the upstream engine").

1. **Build files.** `Bcore/build.gradle` was rewritten for this repository's toolchain (compileSdk 36, NDK 26.1, Java 17,
   ABIs `armeabi-v7a` and `arm64-v8a`). `FreeReflection` (JitPack) was replaced by LSPosed `HiddenApiBypass` from Maven Central
   (one call in `BlackBoxCore.java`). The unused `toml4j` dependency was dropped.
2. **Log upload removed.** Upstream sent a full `logcat` dump plus device information to `https://logs-sender-api.vercel.app/`
   (a third-party server, with a hard-coded chat id) after detected crashes. `utils/LogSender.java` was deleted,
   `BlackBoxCore.sendLogs(...)` now only writes a local log line, and `ClientConfiguration.getLogSenderChatId()` returns `null`.
3. **Logcat-to-Downloads removed.** `BlackBoxCore.startLogcat()` copied logcat into the public Downloads folder. It is now a no-op.
4. **Native-library injection hook removed.** `closecode/Entry.java` (loads `inject.so` into a target package) was deleted and
   `closeCodeInit()` is a no-op.
5. **Detection-evasion native code removed.** `cpp/Utils/AntiDetection.cpp` (hides root and virtualization from apps) and
   `cpp/Utils/VirtualSpoof.cpp` (spoofs device properties) were deleted and dropped from `cpp/Android.mk`. Nothing else referenced them.
6. **Manifest hardened.** Upstream's library manifest declared 323 permissions (camera, microphone, contacts, SMS, several
   vendor-specific ones) and required a camera. All `uses-permission` and `uses-feature` entries were removed; the helper app's own manifest
   declares only INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE, WAKE_LOCK and VIBRATE. Stub activities, services and content
   providers were changed from `exported="true"` to `exported="false"`; the system and this app can still start them. The stub
   broadcast receiver stays exported, as upstream.

7. **`assets/junit.jar` removed.** It is a dexed copy of JUnit (Eclipse Public License 1.0, which is not GPL-compatible) plus Hamcrest, marked
   optional upstream and used by nothing at runtime; the engine logs that an optional JAR is missing and carries on.

## Known leftovers (not changed, reviewed only)

* `fake/service/AntiVirtualDetectProxy.java` is registered by upstream but is effectively a no-op (it only logs). It also makes the
  engine skip a few known "detect the sandbox" content providers. Not extended or relied on.
* `ClientConfiguration.isHideRoot()` exists upstream; the helper never overrides it (default `false`).
* Location, device-id and GMS proxy classes exist upstream and are active inside the container. Whether they behave on Android 16 is unverified.
* `assets/empty.jar` (1.9 KB, a dexed empty stub from upstream, required by the engine's fallback class loading) is kept; its origin is not documented upstream.
* Prebuilt `cpp/Dobby/*/libdobby.a` (Apache-2.0 project, binaries from the upstream repository) were **not rebuilt from source**. Their
  correspondence to the Dobby source is unverified. Replace them with a source build before any public release.
* No upstream tests are included, and none exist for the engine here: the engine is **compiled only**, not run on a device by this project.

## Updating

Re-copy the three directories from a newer upstream revision, re-apply the changes above (the commit that introduced this
directory shows the exact edits), update the revision in this file and in `NOTICE`, and rebuild.
