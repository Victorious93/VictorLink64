# Victorious Framework — VictorLink64 Status

Updated 2026-10-16 · Author: Victorious

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
- ☑ Branding: `assets/logo.png` wired into the adaptive launcher icon (foreground inside the 66/108 dp safe zone on `#031227`), legacy icons, and the Android TV banner. Compiled and resource-checked with `aapt2`; **how it looks on a real launcher/TV is unverified** (only a mask preview was inspected). The logo tagline reads "PLAY · CONNECT · CAST"; Cast is **not implemented**.

### Phase 2 — Local gameplay and controls
☐ direct launch / one-time import · ☐ ZIP/ROM hardening review of upstream code · ☐ save-state metadata + incompatibility refusal · ☐ unified N64 input model · ☐ stuck-input clearing on controller loss · ☐ UI reduced to Play/Resume/Connect/Controls/Settings

### Phase 3 — Receiver and remote controller
☐ capture-point evaluation · ☐ transport selection (WebRTC etc.) · ☐ pairing/auth/expiry/revocation · ☐ Receiver mode · ☐ Controller mode · ☐ measured latency

### Phase 4 — Wired and compatibility
☐ external display · ☐ USB network routes · ☐ capability gating

## Bug sweep 2026-10-16

Method: code read-through of the Kotlin app code, relay, and the zip-ROM change from v0.3.2; chess engine run locally. **No Android SDK in this environment, so the app was not built and nothing was run on a device.**

| Area | Finding | Fix | Evidence |
| --- | --- | --- | --- |
| Chess UI | "New game" during an AI search cancelled the search before `thinking = false` ran, leaving "Phone is thinking…" with Resign/Undo disabled | `thinking` is derived from `aiTurn` instead of stored | reasoned from code; **not compiled for Android, not device-tested** |
| LAN/Bluetooth | `send()` started one thread per message, so messages could be reordered | single ordered writer thread | reasoned from code; **not tested** |
| Zip ROMs | extracted file was cached by inner filename only, so two zips both containing `game.z64` would launch the wrong ROM | cache name includes a hash of archive URI, entry and size | reasoned from code; **not tested** |
| Relay | peers could forge `welcome`/`peer_join`/`peer_leave`; close never replied; leave count could be off by one | reserved types dropped; `ws.close()` on close; count excludes the leaving socket | **automated** (7 node tests; 2 fail on old code). Needs `npx wrangler deploy` to take effect |
| Online codes | `newRoomCode` accepted any HTTP response | checks status and code format | reasoned from code |
| Chess engine | looked for bugs | none found: perft matches published counts on 7 positions; `ChessTest` 10/10 | **automated, local only** |

Known, not changed: a peer can send `resign` for the other side (trusted-friend model); the zip scan cache in SharedPreferences is never pruned; only the last extracted zip ROM is kept in cache.

## VictorOps Zombies integration 2026-10-21

Built-in game (`:zombies` module + `ZombiesActivity`) with Bluetooth/Wi-Fi/online co-op through `ConnectSession`. Details, status table and device checklist: [`ZOMBIES.md`](ZOMBIES.md). **Co-op, touch controls and performance are unverified on devices.**

## SNES screen size and touch controls 2026-10-22

Reported from a device screenshot: SNES picture far smaller than the screen, controls awkward. Items below are labelled by evidence level.

| Area | Finding | Change | Evidence |
| --- | --- | --- | --- |
| Picture size | In a 1604×720 landscape window the picture was 720×540, 48 px from the top. 720 is the phone's portrait width and the 48 px margin exists only in the portrait branch. `SnesActivity` sized the picture once (`surface.post`) at launch from the portrait layout and kept it after rotating (the activity handles `configChanges`, so it is not recreated). | Picture is re-fitted on every layout pass (`fitVideo`); manifest locks `SnesActivity` to `sensorLandscape`, as `ZombiesActivity` already does. | Diagnosis **inferred** from screenshot geometry + code reading, not reproduced on a device. Fit math **automated-tested** (`SnesGeometryTest`, 12 JVM tests, run in a scratch JVM project against the repo files). Activity wiring **compiled by CI only, not device-tested**. |
| Picture size | At correct 4:3 the largest picture on a 20:9 phone is full height (960×720 here); anything larger means stretching. | New SIZE chip cycles 4:3 → 16:9 → FILL (landscape only, remembered in preferences). | Not device-tested. 16:9 and FILL distort the picture by design. |
| Touch D-pad | Four separate buttons with a dead gap in the middle: diagonals needed two fingers. | One 8-way touch surface; the finger that lands on the pad keeps control until it lifts. | Direction math **automated-tested**; Compose touch handler **not device-tested**. |
| Touch buttons | Each button tracked its own finger, so a thumb could not slide or roll between A/B/X/Y. | One handler watches all fingers and hit-tests every event; neighbouring face buttons overlap at the seam so one thumb can press two. | **Not device-tested.** The hit reach values (14 dp face, 10 dp L/R, 6 dp Select/Start, 18 dp D-pad) are untuned guesses. |

Not changed, no evidence either way yet: emulation speed of the prebuilt bsnes core on real devices; audio buffer of about 100 ms (audio likely trails video by that much); scaling filter used when the compositor enlarges the picture; no analog-stick handling beyond what Android synthesizes into D-pad keys. JVM unit tests under `app/src/test` are still not run in CI.

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
