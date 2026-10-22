# Victorious Framework — Agent Instructions for VictorLink64

Read `README.md`, `docs/Victorious-Framework-Status.md`, and `docs/Victorious-Framework-Upstream-Audit.md` first; they hold current state and avoid repeated work. Facts below were checked against the repo on 2026-10-15 unless marked otherwise.

## What this is
VictorLink64 is an Android app (fork-derived from Mupen64Plus-AE, GPL-3.0) that plays N64 games (Mupen64Plus core) and SNES games (bsnes libretro core + an original minimal frontend), has a built-in chess game, and a "Connect" tab for multiplayer over an online relay, Wi-Fi LAN or Bluetooth. Current version `0.3.1` (versionCode 254, `build_common/version_common.gradle`), minSdk 28, targetSdk/compileSdk 36. README describes it as an early beta; Cast to TV and online N64/SNES netplay are not implemented (`docs/NETPLAY.md` is "design + stub"; chess is the only working multiplayer game per that doc).

## Layout
- `app/` main app module (Java upstream code under `paulscode.android.mupen64plusae`, new Kotlin/Compose code under `org.victoriousframework.victorlink64`, native SNES frontend in `app/src/main/cpp`).
- `mupen64plus-*` modules: core, audio, input (android, raphnet), RSP (hle, cxd4, parallel), video plugins (gliden64, glide64mk2, gln64, rice, angrylion-plus, parallel). `ae-bridge`, `miniupnp-bridge`: JNI glue. `ndkLibs/`: vendored native libs. Module list is in `settings.gradle`.
- `server/relay/`: Cloudflare Worker + Durable Object (`Room`) relay for room codes; deploy with `npx wrangler deploy` (needs a Cloudflare account). The app's `RELAY_URL` buildConfigField in `app/build.gradle` points at `wss://victorlink64-relay.victorlink64.workers.dev`.
- `scripts/`: `verify_apk_payload.py` (APK structure/ROM-payload verifier) + `tests/`, `build-bsnes.sh`, `gen_icons.py`. `tools/`: upstream helper scripts (auto-build, translations).
- Docs: `BUILDING.md`, `TESTING.md`, `ARCHITECTURE.md`, `docs/` (status, upstream audit, APK validation, NETPLAY plan, `superpowers/` plans/specs), `UPSTREAM-README.md`.

## Rules
- Inspect before editing. Keep upstream attribution, `gpl-license`, nested notices, and `UPSTREAM-README.md`.
- Keep Java namespace `paulscode.android.mupen64plusae` and library `namespace` values unless inspection proves a change is required. `applicationId` is `org.victoriousframework.victorlink64` (`.debug` suffix on debug).
- Never commit a ROM, save, or APK. The only allowed ROM-like file is `app/src/main/assets/mupen64plus_data/m64p_test_rom.v64` (SHA-256 `b5fe9d65…6224c`), enforced by `scripts/verify_apk_payload.py`.
- Do not claim gameplay, controller, or streaming works without device evidence. Distinguish implemented / compiled / automated-tested / device-tested / unverified.
- Never add release publishing, tag force-pushes, or force pushes. Do not run two Gradle builds on one tree.
- Sign authored documentation "Victorious" and include "Victorious Framework" in new artifacts.

## Commands
Full toolchain and setup in `BUILDING.md` (JDK 21, Gradle wrapper, Android SDK platform 36, build-tools 36.0.0, NDK 26.1.10909125, CMake 3.22.1, host `gawk` + `file`, untracked `local.properties` with `sdk.dir`).
- Script tests: `python3 -I -m unittest discover -s scripts/tests -v` (21 tests: 17 APK verifier, 4 SNES colour conversion; the colour tests need a host C compiler and skip without one).
- Build: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/VictorLink64-debug.apk`.
- Verify APK: see the `Verify APK payload` step in `.github/workflows/build.yml` / `BUILDING.md`. The verifier checks structure only, not that the app runs.
- JVM unit tests exist in `app/src/test` (`chess/ChessTest.kt`, `chess/OnlineCodeTest.kt`, upstream `ExampleUnitTest.java`). They are **not run in CI**; `./gradlew :app:testDebugUnitTest` should run them but was not verified here.

## CI
- `.github/workflows/build.yml` ("VictorLink64 debug build"): runs on `pull_request`, `workflow_dispatch`, and pushes to any branch **except** `main`/`master` — so `main` itself has no CI runs. Steps: pinned SDK install, verifier unit tests, `assembleDebug`, APK payload verification, artifact upload (7-day retention). Read-only permissions; never publishes releases. Takes ~8–12 min.

## Environment quirks
Need `gawk` and `file` on the host; Maven Central may return 429 in sandboxes, so retry (Gradle caches progress).

## Don's standing rules (apply to every change)
- Work on a branch and open a PR. Never push to `main`.
- Never merge a PR, and never ask to merge one, until every test/check on the PR passes.
- Never commit secrets (API keys, tokens, keystore passwords, `.env` files, credentials).
- Don't claim something works unless it was actually tested; say what was and wasn't run.

— Victorious
