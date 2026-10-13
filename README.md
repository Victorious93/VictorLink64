<p align="center"><img src="assets/logo.png" width="256"></p>

# VictorLink64

A Victorious Framework project by Victorious: an Android N64 game app with an integrated emulator, built on [Mupen64Plus-AE](https://github.com/mupen64plus-ae/mupen64plus-ae) (GPL v3), pinned at commit `33bf7021549d7873e976884370fbd1a8e7333bc7`.

## Status (honest summary)

| Level | What |
| --- | --- |
| Compiled | Debug APK builds from source for arm64-v8a, armeabi-v7a, x86, x86_64 |
| Automated-tested | APK payload verifier (17 synthetic-fixture tests) |
| Device-tested | **Nothing.** No gameplay, audio, controller, or streaming test has been run |
| Not started | Direct import-and-play flow, Receiver mode, Controller mode, wired transports |

Today the app is upstream Mupen64Plus-AE with VictorLink64 identity (label, application ID `org.victoriousframework.victorlink64`, artifact name). The Play/Receiver/Controller product described in `docs/` is the plan, not the current behaviour.

## Documents

- `BUILDING.md` — toolchain, build, verification, known environment issues
- `TESTING.md` — what is tested and what is not
- `ARCHITECTURE.md` — current structure and planned layers
- `CLAUDE.md` — instructions for agents resuming work
- `docs/Victorious-Framework-Status.md` — phase checklist and compatibility matrix
- `docs/Victorious-Framework-Upstream-Audit.md` — source map, binary provenance, package-bound assumptions
- `docs/Victorious-Framework-APK-Validation.md` — artifact digest and inspection evidence
- `docs/superpowers/` — approved design and milestone plan
- `UPSTREAM-README.md`, `gpl-license` — upstream notices, preserved

## Game content

No ROM is included or downloaded. The only ROM-like file is upstream's diagnostic `m64p_test_rom.v64`. Import your own legally obtained game on-device; never commit one.

— Victorious
