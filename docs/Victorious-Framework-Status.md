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
| Picture size | In a 1604×720 landscape window the picture was 720×540, 48 px from the top. 720 is the phone's portrait width and the 48 px margin exists only in the portrait branch. `SnesActivity` sized the picture once (`surface.post`) at launch from the portrait layout and kept it after rotating (the activity handles `configChanges`, so it is not recreated). | Picture is re-fitted on every layout pass (`fitVideo`); manifest locks `SnesActivity` to `sensorLandscape`, as `ZombiesActivity` already does. | Diagnosis **inferred** from screenshot geometry + code reading, not reproduced on a device. Fit math **automated-tested** (`SnesGeometryTest`, 12 JVM tests: all passed in a scratch JVM project against the repo files; CI's `:app:testDebugUnitTest` step also passed on the head commit, per-test results not inspected). Activity wiring **compiled** (CI `assembleDebug` passed on `0365b90`), **not device-tested**. |
| Picture size | At correct 4:3 the largest picture on a 20:9 phone is full height (960×720 here); anything larger means stretching. | New SIZE chip cycles 4:3 → 16:9 → FILL (landscape only, remembered in preferences). | Not device-tested. 16:9 and FILL distort the picture by design. |
| Touch D-pad | Four separate buttons with a dead gap in the middle: diagonals needed two fingers. | One 8-way touch surface; the finger that lands on the pad keeps control until it lifts. | Direction math **automated-tested**; Compose touch handler **not device-tested**. |
| Touch buttons | Each button tracked its own finger, so a thumb could not slide or roll between A/B/X/Y. | One handler watches all fingers and hit-tests every event; neighbouring face buttons overlap at the seam so one thumb can press two. | **Not device-tested.** The hit reach values (14 dp face, 10 dp L/R, 6 dp Select/Start, 18 dp D-pad) are untuned guesses. |

Not changed, no evidence either way yet: emulation speed of the prebuilt bsnes core on real devices; audio buffer of about 100 ms (audio likely trails video by that much); scaling filter used when the compositor enlarges the picture; no analog-stick handling beyond what Android synthesizes into D-pad keys.

## SNES native sweep 2026-10-22

Method: ran the repo's real `snesfront.c` against the real prebuilt x86_64 bsnes core on this Linux host, using stubbed Android headers, a fake JNI environment and a hand-assembled homebrew test ROM (no commercial data). The core binary was patched in a scratch copy only so glibc can load it. The harness is **not committed**. Limits: x86_64 Linux, not an Android device; the Kotlin layer is not exercised by it.

| Area | Finding | Change | Evidence |
| --- | --- | --- | --- |
| Save/Load state | `SnesActivity` called `nativeSaveState`/`nativeLoadState` on a spare thread while the emulation thread could be inside `retro_run`. The `paused` flag does not stop a frame already running. libretro forbids concurrent core calls. | Both now run on the emulation thread between frames (task queue). State files are written via temp file + rename; a failed save no longer toasts "State saved". | Crash **reproduced on host**: with the emulator running flat out 5/5 runs segfaulted; at a 15% duty cycle, 16/16 trials crashed within 4–45 presses (load-only and save-only each 8/8). Same-thread design: 8/8 trials survived 150 presses; 600 sequential save+load pairs clean. Kotlin change **compiled by CI only, not device-tested**. |
| Colour | Pixel conversion shifted without bit replication, so full white rendered as 248/248/248. | New `snes_color.h` with bit replication. | **Reproduced on host** (white backdrop read 248, now 255). `scripts/tests/test_snes_color.py` (4 tests, run in CI) fails on the old code. |

Checked and found correct on the real core: all 12 buttons map to the right SNES registers, including simultaneous presses; state load replays identically from the second frame on (the first frame after a load differs in pixels, probably because the framebuffer is not part of the state); empty and garbage state data is rejected without a crash; repeated load/unload in one process works; audio is 48000 Hz at 60.0988 fps with about 800 sample pairs per frame; battery RAM persists across clean exits.

**Open, not changed (needs a decision):** the core writes battery RAM (`<save dir>/<rom name>.srm`) only when the game is unloaded, and exposes no memory to the frontend (all `retro_get_memory_*` ids report size 0). Reproduced on host: three runs killed without unload saved nothing. In the app, a process kill while backgrounded loses in-game saves made since the last clean exit; the SAVE chip state files are written immediately and are not affected. Options: auto-save a resume state when the app is stopped and offer it at next launch; or rebuild the core to flush or expose save RAM (needs the NDK).

Seen but not tested: `AudioTrack.write` return codes are ignored, so an audio error would let the loop run unthrottled; ROMs with the same file name share their `.srm` and `.state` files; the core accepts any file as a ROM, so a corrupt file runs as noise instead of failing to load.

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
