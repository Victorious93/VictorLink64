# Victorious Framework — Testing VictorLink64

## Automated, currently implemented

- `scripts/tests/test_verify_apk_payload.py` — 17 `unittest` cases using synthetic ZIPs only (no ROM data): missing manifest/DEX, missing library per ABI, wrong or non-ELF library, unexpected ROM (all three extensions, case-insensitive), diagnostic asset allowed only at exact path **and** digest, traversal and absolute entries, duplicates, non-ZIP input, missing file, CLI exit codes. Run: `python3 -I -m unittest discover -s scripts/tests -v`. Result on 2026-10-13: 17 passed.

## Not yet written

Import safety, save integrity, input normalization, disconnect behaviour, pairing authorization, session expiry, and capability decisions have **no tests yet** because those features are not implemented (Phases 2–4).

## Device evidence rules

No physical device or emulator was available. Gameplay, audio, touch, Bluetooth/USB controller, streaming, and Onn latency claims need recorded device evidence before they are marked tested. See the matrix in `docs/Victorious-Framework-Status.md`.

— Victorious
