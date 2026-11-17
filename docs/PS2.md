# PlayStation 2 (LRPS2)

Written 2026-11-17 · Author: Victorious · Victorious Framework

VictorLink64 plays PlayStation 2 games with **LRPS2**, the libretro PlayStation 2 core (<https://github.com/libretro/ps2>, GPL-3.0, derived from PCSX2 by the PCSX2 Team and contributors, with a native arm64 port). It runs through the same GL ES libretro frontend (`libvl64hw`, `hwfront.c`) as the PSP and GameCube cores and the same on-screen overlay.

**Status: implemented and compiled; JVM-tested for the pure logic; not run on an arm64 device.** See [What was tested](#what-was-tested).

## Core provenance (GPL source offer)

| | |
| --- | --- |
| Core | LRPS2, `libretro/ps2`, licence GPL-3.0 (`COPYING.GPLv3` in that repository) |
| Binary | libretro buildbot nightly, `https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/pcsx2_libretro_android.so.zip` (the buildbot still calls the core "pcsx2") |
| Buildbot file date | `Last-Modified: Sun, 15 Nov 2026 22:58:08 GMT` |
| Zip / extracted size | 2,755,127 bytes / 8,641,120 bytes |
| SHA-256 of the extracted `.so` | `7fbc421819bade622e04886b1ddb84b0cfdb9bc7a7ad97fdcee824ff82a4df06` |
| Installed as | `app/src/main/jniLibs/arm64-v8a/liblrps2_libretro.so` (arm64-v8a only) |
| Pinned by | `scripts/fetch-disc-cores.sh` (refuses any other digest unless `ALLOW_NEW=1`) and the CI APK check (`--expect-sha256`) |
| ELF | AArch64, stripped, needs only `libdl`, `libm`, `libc`; BuildID `c0080cbd02c85bae976d432aaa225404542f1c06` |

**Source commit.** The buildbot binary does not embed its git commit. The nightly was finished at 22:58:08 UTC on 2026-11-15. The `libretro/ps2` `master` commits around that time are:

| Commit | Committed (UTC) |
| --- | --- |
| `4480355068686cdd174ca57c556693332d0b2646` | 2026-11-15 20:15 |
| `e0d67a29c4eefa1097a59a0ab30d9491c02ce4d7` | 2026-11-15 22:30 |
| `c487b802b0d336af85bf88704ab108e30b5897dd` | 2026-11-16 01:10 |

The binary was therefore built from `master` at **`e0d67a29c4eefa1097a59a0ab30d9491c02ce4d7`** or the commit just before it (`4480355…`). This is **inferred from timestamps, not read from the binary**. Anyone who wants the exact source: <https://github.com/libretro/ps2/tree/e0d67a29c4eefa1097a59a0ab30d9491c02ce4d7> corresponds to the build, and the GPL source offer is met by that public repository. To pin the commit beyond doubt, rebuild from that commit in CI and replace the binary; the pinned digest would then change.

The core was inspected only as far as needed to integrate it (core option names, BIOS scan rules, hardware-render contexts). The `libretro/ps2` README states it was "verified booting to real in-game content … on a Snapdragon-class device (Adreno 618)". That is the upstream author's statement about their build, not a result from this app.

## BIOS (never bundled)

A PS2 needs a BIOS dumped from the user's own console. VictorLink64 does not ship, download or commit one; `.gitignore` blocks common BIOS names and `scripts/verify_apk_payload.py` fails CI if an APK carries a BIOS (by name, or by content: any non-library entry of 1–16 MiB that has a PS2 ROM directory).

**Import flow** (`Ps2SetupActivity`, reached from Settings → PlayStation 2, or automatically when a PS2 game is tapped with no BIOS):

1. Plain-language explanation of what a BIOS is and where it comes from (your own PS2, a file like `scph10000.bin`, 4 MB).
2. **Add BIOS file(s)** opens the system file picker (multi-select). Each file is copied into private app storage `filesDir/ps2/system/pcsx2/bios/`, the folder the core scans, and kept only if it passes the same check the core makes (`Ps2Bios.inspect` mirrors `pcsx2/ps2/BiosTools.c`: size 4–8 MiB, a `RESET` entry in the ROM directory, a readable `ROMVER` entry).
3. Each file gets a plain result line: "Added scph10000.bin: USA v01.60 (14/06/2004)" or "Skipped x.bin: This doesn't look like a PS2 BIOS…" (also "too small", "bigger than any PS2 BIOS", "could not read").
4. With several BIOS files, the user picks which to use; **Remove** deletes a file. `.rom1`, `.rom2`, `.nvm`, `.mec` add-on files are copied as they are (the core reads or creates them beside the BIOS); they are not required.
5. A game cannot start without a BIOS: the Play button says "Add a BIOS to play"; if the game is launched anyway, `HwActivity` opens the setup screen and shows "PlayStation 2 needs a BIOS first…". If the core still refuses to load, the message names the BIOS and the disc image as the things to check.

Why the app passes `pcsx2_bios` itself: unlike RetroArch, this frontend does not apply a core option's default. Without a value the core stops with "Could not find any valid PS2 BIOS". `Ps2Options.build` supplies it from the selected file.

## Games the library recognises

Detection is in `hw/Ps2Disc.kt` (pure) and `hw/DiscDetect.kt` (Android file access). A PS1 and a PS2 disc both say "PLAYSTATION" in the disc header, so recognition reads `SYSTEM.CNF`: PS2 discs boot with `BOOT2`, PS1 discs with `BOOT`.

| Format | How it is decided |
| --- | --- |
| `.iso` | GameCube magic first (as before); else ISO 9660 `SYSTEM.CNF` has `BOOT2` → PS2; else PSP (as before) |
| `.cso` | CISO blocks are read and inflated, then the same `SYSTEM.CNF` check; else PSP (as before) |
| `.cue` + `.bin` | The first track file named in the cue (found beside it, any case) is read as 2048- or raw 2352-byte sectors; `BOOT2` → PS2. Launch copies the cue to the app cache with the track files linked beside it. A `.bin` with no cue (over 32 MB) is checked the same way; BIOS `.bin` files fail the disc check and are ignored |
| `.chd` | CHD v5 header: a **CD** CHD is listed as PS2 (it cannot be told from PS1 without decompressing; PS1 is not supported, so the core refuses it); a **DVD** CHD is PS2 only above 1.85 GB (larger than any PSP UMD). Smaller DVD CHDs are ambiguous with PSP and are **not claimed** |

Known limits: a small PS2 DVD `.chd` is not listed; a PS1 CD `.chd` is listed as PS2; `.zso` is not detected.

## Picture size and speed

`Ps2Resolution` offers three choices; the saved one is sent to the core as `pcsx2_upscale_multiplier` at launch (changes apply on the next start):

| Choice | Core value | Output (NTSC) |
| --- | --- | --- |
| Native (default) | `1x (Native)` | 640×448 |
| 2× sharper | `2x` | 1280×896 (between 720p and 1080p) |
| 3× sharpest | `3x` | 1920×1344 (about 1080p) |

`3x` is **not one of the values the core lists** (`1x (Native)`, `2x`, `4x`, `8x`). The core reads the value with `atoi`, and upstream PCSX2 supports any multiplier, so `3x` should work, but it is **untested** and is the first thing to try on a device. If it misbehaves, replace it with the listed `4x`.

The setup screen shows a plain-language **speed note** at all times, and a stronger one when the phone has less than about 6 GB of memory (`Ps2Performance`): PS2 is by far the heaviest system here; entry-level phones such as one with a MediaTek Dimensity 6300 may run it slowly or not at all, and VictorLink64 has **not been tested on one**. Choosing 2× or 3× on a weaker phone shows a warning. The check is a rule of thumb from memory size and 64-bit support, not a benchmark.

Rendering uses OpenGL ES (`pcsx2_renderer = Auto`, which the core resolves to the GL ES 3.1 context this frontend provides). This frontend has no Vulkan path; the core's Vulkan and software renderers are not used. If a phone's GL ES driver lacks what the core's GL renderer needs, the game will not start and there is no automatic fallback.

## Controls

DualShock 2 layout in the shared overlay (`ConsoleLayouts`, `Console.PS2`): D-pad, **two analog sticks**, ✕ ○ □ △ (brand cyan, no red), L1/L2 and R1/R2, L3/R3 as small buttons beside the sticks, Select/Start. Previews: landscape and portrait renders produced by `LayoutPreviewTool` + `scripts/render_layout_preview.py` (see the PR). The in-game EDIT mode, per-orientation layouts and haptics work as for the other consoles.

Mapping (checked against `libretro/ps2` `pcsx2/PAD/PAD.c`): ✕ = RetroPad B, ○ = A, △ = X, □ = Y, L1/R1 = L/R, L2/R2 = L2/R2 (the core also reads analog pressure; hardware analog triggers pass it through), L3/R3 = bits 14/15, sticks as RetroPad analog axes.

Hardware controllers use Settings → Controller buttons (PS2 tab): face buttons by position (Android A = ✕), triggers, stick clicks (`BUTTON_THUMBL/R`), D-pad hat, both sticks. The core is started with **"Start in analog mode"** on, so the touch sticks work in games that boot the pad in digital mode.

Save states are hidden for PS2 (a PS2 state is tens of MB and this has not been verified); games save to the emulated memory card, which lives in `filesDir/ps2/system/pcsx2/memcards/`.

## Native frontend change

`hwfront.c` gained a small core-option table (`HwCore.nativeSetOption` / `nativeClearOptions`, answered through libretro `GET_VARIABLE`). PPSSPP and Dolphin never read options, so their behaviour is unchanged. Everything else the PS2 core needs was already there (GL ES context, FBO blit, RetroPad + analog + analog-button input, 48 kHz audio).

## Interaction with other open work

- **PR #29 (controls)** is already merged into `main`; PS2 is built on its overlay. No conflict.
- **PR #31 (graphics / Auto Best)** replaces `GET_VARIABLE` in `hwfront.c` with its own `gfx_get_var` store and adds per-emulator graphics settings. After both land, the PS2 options should be fed through its `GraphicsSession` so the resolution lives in one place; until then the two stores must be chained (look up this table first, then `gfx_get_var`).
- **PR #32 (universal import)** has its own console detection (`GameFormats`/`HwSystem` edits). Its sniffing says "PlayStation 1 or 2 disc … not supported yet" and treats `.bin/.cue` as "CD image"; after both land those cases should defer to `DiscDetect`/`Ps2Disc`. `.chd` is treated as PSP there.

## What was tested

See the PR description for the exact list. In short: JVM unit tests (disc/BIOS/option/layout logic on synthetic data), the Python verifier tests, and the CI debug build + APK payload check. The core has **not** been run: the CI and development machines are x86_64 and cannot run an arm64 core.
