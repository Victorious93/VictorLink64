# Victorious Framework — Agent Instructions for VictorLink64

Read `README.md`, `docs/Victorious-Framework-Status.md`, and `docs/Victorious-Framework-Upstream-Audit.md` first; they hold current state and avoid repeated work.

## Rules
- Inspect before editing. Keep upstream attribution, `gpl-license`, nested notices, and `UPSTREAM-README.md`.
- Keep Java namespace `paulscode.android.mupen64plusae` and library `namespace` values unless inspection proves a change is required. `applicationId` is `org.victoriousframework.victorlink64` (`.debug` suffix on debug).
- Never commit a ROM, save, or APK. The only allowed ROM-like file is `app/src/main/assets/mupen64plus_data/m64p_test_rom.v64` (SHA-256 `b5fe9d65…6224c`), enforced by `scripts/verify_apk_payload.py`.
- Do not claim gameplay, controller, or streaming works without device evidence. Distinguish implemented / compiled / automated-tested / device-tested / unverified.
- Never add release publishing, tag force-pushes, or force pushes. Do not run two Gradle builds on one tree.
- Sign authored documentation "Victorious" and include "Victorious Framework" in new artifacts.

## Commands
See `BUILDING.md`. Tests: `python3 -I -m unittest discover -s scripts/tests -v`.

## Environment quirks
Need `gawk` and `file` on the host; Maven Central may return 429 in sandboxes, so retry (Gradle caches progress).

— Victorious
