# Victorious Framework — VictorLink64 Status

Updated 2026-10-13 · Author: Victorious

## Phase checklist

Legend: ☑ done and evidenced · ◐ partly done · ☐ not started

### Phase 1 — Audit and reproducible foundation
- ☑ Pin upstream (`33bf7021…`), snapshot into repository, record provenance
- ☑ Toolchain: JDK 21, SDK 36, build-tools 36.0.0, NDK 26.1.10909125, CMake 3.22.1, `gawk`/`file`
- ☑ Unmodified baseline debug build (4 ABIs) and payload check
- ☑ Payload verifier with tests (17 passing), diagnostic-ROM exact path + SHA-256 exception
- ☑ VictorLink64 label, application ID, artifact name, USB action string
- ☑ Debug-only CI workflow: on a GitHub runner (head `a08b3f4`) `assembleDebug` succeeded in 18m17s and the payload verifier passed — ☐ artifact upload **blocked by the repository's Actions artifact-storage quota** (owner action: free/raise storage, re-run)
- ◐ Binary provenance: committed binaries inventoried, **correspondence to source not verified**
- ☐ Release signing (none exists)

### Phase 2 — Local gameplay and controls
☐ direct launch / one-time import · ☐ ZIP/ROM hardening review of upstream code · ☐ save-state metadata + incompatibility refusal · ☐ unified N64 input model · ☐ stuck-input clearing on controller loss · ☐ UI reduced to Play/Resume/Connect/Controls/Settings

### Phase 3 — Receiver and remote controller
☐ capture-point evaluation · ☐ transport selection (WebRTC etc.) · ☐ pairing/auth/expiry/revocation · ☐ Receiver mode · ☐ Controller mode · ☐ measured latency

### Phase 4 — Wired and compatibility
☐ external display · ☐ USB network routes · ☐ capability gating

## Tally

Finished: Phase 1 build/identity/verifier. In progress: Phase 1 provenance and CI confirmation. Deferred / abandoned: none.

## Compatibility matrix

| Target | Status |
| --- | --- |
| Build host Linux x86_64, JDK 21 | built ✔ |
| arm64-v8a / armeabi-v7a / x86 / x86_64 native libs present | payload-verified ✔ |
| Any phone/tablet runtime | **unverified** |
| Onn Android TV / Google TV runtime | **unverified** (model, API level, ABI unknown) |
| Bluetooth / USB controllers | **unverified** |
| Wi-Fi streaming, Cast, Miracast, Wi-Fi Direct, wired | **not implemented** |

## Next steps

1. Free or raise Actions artifact storage and re-run the failed job once so the CI-built APK can be downloaded.
2. Obtain an Android device or emulator; install `VictorLink64-debug.apk` and boot the diagnostic asset, then a user-supplied ROM; record results here.
3. Read upstream `FileUtil`/`CacheRomInfoService` import paths against the hardening requirements; then start Phase 2.

— Victorious
