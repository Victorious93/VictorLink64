# VictorLink64 emulator netplay plan (N64 / SNES)

Status: **design + stub**. Chess is the first fully working multiplayer game (any transport).
All emulator netplay reuses the same `Transport` layer (Online relay, Wi-Fi LAN, Bluetooth).

## SNES (bsnes via libretro) — deterministic input lockstep with optional rollback
bsnes is fully deterministic and exposes `retro_serialize`/`retro_unserialize` (already wired in
`snesfront.c` for save states), so the standard libretro-netplay approach applies:

1. **Start sync**: host sends ROM hash (CRC32/SHA1) + a savestate blob; guest verifies the same ROM and loads the state.
   (States are ~400 KB; fine for relay/LAN, slow but OK for Bluetooth.)
2. **Input lockstep**: each frame N, each peer sends `{f:N, pad:mask}` for its own port (host=P1, guest=P2)
   with an input delay of D frames (D=2 LAN/BT, 3–5 online, adaptive from RTT). Frame N runs only when both
   inputs for N are known. Needs port 2 input in the frontend (`state_cb` currently answers port 0 only).
3. **Rollback (phase 2)**: run ahead with predicted remote input (repeat last), keep a ring of serialized
   states (bsnes serialize is fast enough at ~1 ms on modern phones), and on misprediction restore the state
   from the mispredicted frame and re-simulate. This is what RetroArch netplay does.
4. **Desync detection**: every 60 frames exchange a CRC of the serialized state; on mismatch, host resends a state.

Message types (JSON over the existing pipe, batched every frame): `np_hello{rom,core,ver}`, `np_state{b64}`,
`np_in{f,pad}`, `np_crc{f,crc}`, `np_pause`, `np_quit`. A binary frame option should be added for states.

Effort: lockstep ~1–2 weeks incl. testing; rollback another ~1–2 weeks.

## N64 (Mupen64Plus core)
- Mupen64Plus core ≥2.6 has a built-in **netplay protocol** (UDP input exchange + TCP for settings/save
  sync) designed for a central dedicated server (`mupen64plus-netplay-server`, as used by simple64/m64p).
  Mupen64Plus-AE already ships a netplay UI path (`NETPLAY_ENABLED`/`NETPLAY_SERVER` extras, UPnP via
  miniupnp-bridge), which we kept in the core glue.
- **Feasible approach**: (a) LAN — reuse the AE netplay path directly (the host runs the netplay server in-process);
  (b) online — the core protocol needs UDP, which a Cloudflare Worker cannot relay. Options: run the open-source
  netplay server on a small VPS/Fly.io, or tunnel the UDP messages through our WebSocket relay (adds latency,
  but works for a 2-player casual game).
- Rollback is not practical for N64 (save states are large and slow), so N64 stays delay-based lockstep.
- Bluetooth is too slow/jittery for N64 netplay; Wi-Fi and online only.

## Stub in this release
- Connect tab shows "N64 / SNES netplay is coming soon".
- `snesfront.c` already exposes serialize/unserialize to Kotlin (`SnesCore.nativeSaveState/LoadState`).
