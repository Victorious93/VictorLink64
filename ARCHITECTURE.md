# Victorious Framework — VictorLink64 Architecture

## Current (verified structure)

One Gradle project, `:app` plus native plugin modules, all unmodified from upstream Mupen64Plus-AE:

- Java/Android layer in `app/` (`paulscode.android.mupen64plusae`): activities, `GameActivity`, `CoreService`, input controllers, profiles, ROM scanning.
- Native layer: `mupen64plus-core`, video (`gliden64`, `glide64mk2`, `gln64`, `rice`, `angrylion-plus`, `parallel`), RSP (`cxd4`, `hle`, `parallel`), audio (`audio-android`, Oboe), input (`input-android`, `input-raphnet`), JNI bridges (`ae-bridge`, `miniupnp-bridge`).
- Prebuilt third-party binaries in `ndkLibs/libs` (see the audit).

File-level map and unverified areas: `docs/Victorious-Framework-Upstream-Audit.md`.

## Planned (not implemented)

Per `docs/superpowers/specs/…`: three modes in one app (Play/Host, Display/Receiver, Controller), a single N64 input model fed by touch, physical, and remote sources, an authenticated LAN streaming transport, and capability-gated wired modes. The streaming design depends on whether renderer/audio capture is practical; that has **not** been evaluated. Each phase must reuse the proven upstream emulation path and add narrowly scoped components beside it.

— Victorious
