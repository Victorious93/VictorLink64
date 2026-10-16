# Victorious Framework — Testing VictorLink64

## Automated, currently implemented

- `scripts/tests/test_verify_apk_payload.py` — 17 `unittest` cases using synthetic ZIPs only (no ROM data): missing manifest/DEX, missing library per ABI, wrong or non-ELF library, unexpected ROM (all three extensions, case-insensitive), diagnostic asset allowed only at exact path **and** digest, traversal and absolute entries, duplicates, non-ZIP input, missing file, CLI exit codes. Run: `python3 -I -m unittest discover -s scripts/tests -v`. Result on 2026-10-13: 17 passed.

## Not yet written

Import safety, save integrity, input normalization, disconnect behaviour, pairing authorization, session expiry, and capability decisions have **no tests yet** because those features are not implemented (Phases 2–4).

## Device evidence rules

No physical device or emulator was available. Gameplay, audio, touch, Bluetooth/USB controller, streaming, and Onn latency claims need recorded device evidence before they are marked tested. See the matrix in `docs/Victorious-Framework-Status.md`.

— Victorious
- `server/relay/test/room.test.mjs` — 7 `node:test` cases for the relay `Room` (host/guest roles, 8-peer cap, relay-to-others, ping/pong, forged control messages dropped, oversize dropped, close handling and peer count) against in-memory fakes of the Cloudflare runtime. Run: `cd server/relay && npm test`. Result on 2026-10-16: 7 passed; against the previous relay code 2 of them fail (forged control messages, close handling). Not a test against a deployed Worker.
- Chess engine (local, outside CI): `ChessTest` (10 cases) passed when compiled with a standalone `kotlinc` 2.1.0 and run with JUnit 4.13.2 on 2026-10-16; perft also matched the published counts for the start position (depth 4), Kiwipete (depth 3), positions 3–6 (depth 3–5, up to 674,624 nodes).
- Device checks for the v0.3.2 bug-sweep fixes (none run yet): `docs/Victorious-Framework-Device-Test-Checklist.md`.
