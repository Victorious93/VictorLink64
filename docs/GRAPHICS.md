# Graphics quality, Auto Best Settings and Shizuku boosts

Victorious Framework · branch `graphics-auto-best` · Settings → **Graphics & performance**

Everything below is labelled by evidence level: **implemented** (code exists), **compiled** (built), **automated-tested** (JVM/host tests), **host-rendered** (ran on a desktop GLES 3.2 implementation), **device-tested**, **unverified**. **Nothing here has been run on a phone yet.**

## What the player gets

- Picture quality choices per emulator: internal resolution (Original / 2x / 3x / 720p / 1080p), texture filtering, anisotropic filtering, MSAA, widescreen hack, texture upscaling, and a screen effect (sharpen / FXAA / CRT scanlines / smooth pixel art).
- **Auto Best Settings** (on by default): picks the best preset the phone can run at full speed, steps down on slowdowns or heat, remembers the result per game. Manual override per emulator or per game.
- Optional FPS counter (PSP, GameCube, SNES; N64 keeps its own counter).
- Optional **Shizuku boosts** (off by default, only usable when Shizuku is installed, running and granted). Everything works without Shizuku.

## How a game's settings are chosen

1. Per-game record says Auto, Manual, or "same as app" (default).
2. App-wide **Auto Best Settings** switch (default on).
3. Manual choices come from the game's own record, else the emulator's manual choices, else the "Balanced" preset.
4. Every manual value is cut down to what the emulator supports ([`Capabilities`](../app/src/main/java/org/victoriousframework/victorlink64/graphics/GraphicsModel.kt)), so a stored value can never claim something the core cannot do.

Stored in `files/vl64_graphics.json` (re-read on every access because N64 runs in its own `:EmulationProcess` and writes learned tiers there).

## Auto Best Settings

**Device profile** ([`DeviceProfile.kt`](../app/src/main/java/org/victoriousframework/victorlink64/graphics/DeviceProfile.kt)): SoC (`Build.SOC_MODEL`), GPU (`GL_RENDERER` from a 1x1 pbuffer context), RAM, current/peak refresh rate, GLES version, and `PowerManager.currentThermalStatus`. The class is decided by GPU first, then SoC name, then capped by RAM. The target phone (Dimensity 6300, Mali-G57, ~3.7 GB usable) is classed **Entry-level** — covered by a unit test.

**Ladders** ([`Presets.kt`](../app/src/main/java/org/victoriousframework/victorlink64/graphics/Presets.kt)), cheapest first:

| Tier | N64 (GLideN64) | PSP (PPSSPP) | GameCube (Dolphin) | SNES |
| --- | --- | --- | --- | --- |
| 0 | Battery saver: 1x, authentic filter | Battery saver: 1x | Battery saver: 1x | Plain (unchanged software path) |
| 1 | Smooth: 2x, bilinear | Smooth: 2x | Smooth: 1x, aniso 2x | Sharpen |
| 2 | Balanced: 2x, bilinear, aniso 4x | Balanced: 2x, linear, aniso 4x | Balanced: "720p" (2x), aniso 4x | Smooth pixel art |
| 3 | High: 3x, aniso 8x, FXAA | High: 3x step, aniso 8x, sharpen | High: "1080p" (3x), aniso 8x, MSAA 2x | |
| 4 | Ultra: 3x, aniso 16x, MSAA 4x, 2xBRZ textures, FXAA | Ultra: 4x step, aniso 16x, MSAA 4x, xBRZ textures, FXAA | Ultra: 3x, aniso 16x, MSAA 4x, FXAA | |

Start / highest tier Auto may try ([`AutoPolicy`](../app/src/main/java/org/victoriousframework/victorlink64/graphics/AutoBest.kt)):

| | Entry-level | Mid-range | High-end |
| --- | --- | --- | --- |
| N64, PSP | start 1, max 2 | start 2, max 3 | start 3, max 4 |
| GameCube | start 0, max 1 | start 1, max 2 | start 2, max 4 |
| SNES | 2 / 2 | 2 / 2 | 2 / 2 |

A tier that held full speed for the game last time wins over the class guess (within the ceiling). A tier that failed is never started at or above. A phone that is already warm starts one tier lower.

**Runtime tuner** ([`AutoTuner`](../app/src/main/java/org/victoriousframework/victorlink64/graphics/AutoBest.kt)), fed by [`FrameMonitor`](../app/src/main/java/org/victoriousframework/victorlink64/graphics/AutoBest.kt) windows of 2 s:

- Speed = frames delivered / the core's target rate, capped at 1. Pauses (gaps over 0.5 s) restart the window rather than counting as slowness.
- 8 s warm-up after launch and after every change (shader compiles hitch).
- **Step down** after 2 windows under 93 % speed, or at once under 70 %; the failed tier is remembered as too heavy.
- **Thermal**: status ≥ MODERATE steps down one tier, ≥ SEVERE two. Thermal steps are never remembered as "too heavy" and block probing for 10 minutes.
- **Probe up** one tier after 60 s of clean full speed, if the tier is under the phone's ceiling, not known to be too heavy, and the phone is cool. A failed probe steps back and records the tier as too heavy.
- A tier is saved as learned after 30 s at full speed.

**PSP, GameCube, SNES** apply tier changes live (core variables and the GL pass). **N64 cannot change resolution mid-game**, so settings apply at launch and Auto only learns for the *next* launch: the first change it wants is saved (a failed tier is recorded; after 5 clean minutes the next tier is tried next time) and watching stops. N64 speed is the core's VI rate against 60 Hz (50 Hz for PAL cartridges).

## What each emulator can and cannot do

| Control | N64 | PSP | GameCube | SNES |
| --- | --- | --- | --- | --- |
| Internal resolution | yes: GLideN64 `UseNativeResolutionFactor` 1/2/3; 720p/1080p render the surface at that height with factor 0 | yes: `ppsspp_internal_resolution` (480x272 ... 1920x1088); PPSSPP only has 272-line steps, so 720p uses the 3x step | yes: `dolphin_efb_scale` x1/x2/x3; 720p = x2, 1080p = x3 (Dolphin's own labels) | no: the picture is drawn at screen size |
| Texture filtering | yes: `bilinearMode` (authentic 3-point vs bilinear) | yes: `ppsspp_texture_filtering` Auto/Nearest/Linear | **no**: the Dolphin core's value names could not be confirmed, so nothing is sent | scaling filter in the GL pass (sharp = nearest) |
| Anisotropic filtering | yes: `MaxAnisotropy` | yes: `ppsspp_texture_anisotropic_filtering` | yes: `dolphin_max_anisotropy` | no |
| MSAA | yes: `MultiSampling`, only written on GLES 3.1+ devices; GLideN64 ignores it in some framebuffer modes | yes: `ppsspp_mulitsample_level` (core decides if the backend supports it) | yes: `dolphin_anti_aliasing` | no |
| Widescreen hack | yes: emulation-profile `WidescreenHack` (also stretches the display, as the existing option does) | **no**: PSP is natively 16:9 | yes: `dolphin_widescreen_hack` + `dolphin_aspect_ratio` (value names unverified) | no |
| Texture upscaling | yes: `txEnhancementMode` 2xBRZ / 4xBRZ | yes: `ppsspp_texture_scaling_type`/`_level` + deposterize | **no**: not in the Dolphin core | no |
| Screen effect | existing shader chain: FXAA, CRT (crt-geom), ScaleFX; **no sharpen** | shared GL pass: all | shared GL pass: all | shared GL pass: all |

Variable names above were read from the shipped `libppsspp_libretro.so` / `libdolphin_libretro.so`; **values** for PPSSPP follow its documented option list and for Dolphin were matched against strings in the binary where possible. A value a core does not recognise is ignored by it (it keeps its default), so a wrong value cannot crash — but it also would not take effect. Confirm on a device.

## Shared GL pass

`glpass.c` / `glpass_shaders.h` (GLSL ES 3.00), used by `hwfront.c` (PSP/GameCube: samples the core's FBO texture instead of a plain blit) and `snesfront.c`:

| Effect | What it does |
| --- | --- |
| Sharpen | 4-tap unsharp mask, clamped to the neighbourhood so it cannot ring |
| FXAA | luma-directed edge blur (the common 4-tap FXAA variant) |
| CRT | scanlines and a light RGB mask; fades out when the picture is not enlarged enough to show them |
| Smooth pixel art | **xBRZ-style approximation**: EPX corner rules plus resolution-aware sharp-bilinear blending. It is *not* true xBRZ |

If no program can be built the frontend falls back to the previous plain blit.

**SNES** previously only had a CPU conversion into an `ANativeWindow`. With an effect or sharp scaling selected it now uploads the picture as a texture and draws through the GL pass (EGL context created on the emulation thread, swap interval 0 because pacing comes from the audio clock). With no effect and default filtering the original software path is used unchanged. The choice is made once at the first frame; if EGL setup fails it falls back to the software path. Switching between software and GL mid-game is not done.

`hwfront.c` also gained: libretro core variables (`GET_VARIABLE`, `GET_VARIABLE_UPDATE`), and framebuffer growth when a core announces a larger geometry or a changed option enlarges the picture.

## Shizuku boosts (optional)

Shizuku API (RikkaApps, **Apache-2.0**) is credited in About. Off by default; each boost has its own switch, enabled only while Shizuku is installed, running and permission is granted. A tiny helper process ([`ShellService`](../app/src/main/java/org/victoriousframework/victorlink64/shizuku/ShizukuBridge.kt)) runs the commands through Shizuku's `UserService`.

| Boost | Command | Undone |
| --- | --- | --- |
| Game mode: performance | `cmd game mode performance <pkg>` (Android 12+) | `cmd game mode standard <pkg>` when the game ends |
| Battery optimization exemption | `cmd deviceidle whitelist +<pkg>` (skipped if already exempt) | removed when the switch is turned off (not if it was exempt before) |
| Fastest refresh rate | `settings put system min_refresh_rate / peak_refresh_rate <peak Hz>` (previous values saved) | restored (or deleted) when the game ends |
| Steady speed (sustained performance) | `cmd power set-fixed-performance-mode-enabled true` (Android 12+) | `... false` when the game ends |

Session boosts are applied when a game starts and undone when it ends; if the game process went away without telling us (N64 runs in its own process) they are undone the next time the library is shown. Command results are shown in Settings; a command that prints an error is reported as failed. Pure command logic is unit-tested with a fake shell.

## Evidence

| Item | Status |
| --- | --- |
| Kotlin + Java changes build (`:app:testDebugUnitTest` compiles them) | **compiled** |
| C changes compile with `-Wall -Wextra` for arm64-v8a, armeabi-v7a, x86_64 (NDK 26 clang) | **compiled** |
| Presets, device class, Auto policy, tuner, frame monitor, option mapping, store, Shizuku command plan: 73 JVM tests | **automated-tested** |
| `gfxopts.c` (6 host tests in `scripts/tests/test_gfxopts.py`) | **automated-tested** |
| Shader sources validate with `glslangValidator`; all five effects ran on Mesa llvmpipe (GLES 3.2) and produced sane images | **host-rendered** (not a Mali GPU) |
| Anything on the Moto G Play 2026 or any phone | **unverified** |

### Device checklist (Moto G Play 2026, Dimensity 6300, Mali-G57, Android 16)

1. Settings → Graphics & performance shows the right chip/GPU/RAM and "Entry-level".
2. PSP game: counter shows ~60 FPS; Auto starts at "Smooth" (2x); watch for a probe to "Balanced" after a minute, and a step back if it drops.
3. GameCube game: Auto starts at "Battery saver" (x1); check the picture and speed.
4. SNES game: smooth-pixel-art effect looks right, no black screen; rotate the phone and background/foreground the app (EGL surface rebuild); turn the effect off and confirm the old path still works.
5. N64 game: per-game record appears afterwards; picture follows the tier (2x, bilinear); FXAA/CRT choose a shader pass; MSAA actually does something (or not).
6. Manual: switch Auto off, change a PSP option, relaunch, confirm it stuck; override one game only.
7. Heat: play until the phone is warm and watch Auto step down with the toast; confirm the next launch starts lower only if speed (not heat) was the cause.
8. Shizuku (if installed): grant, switch each boost on, launch and exit a game, check `cmd game mode` / refresh rate before and after; turn everything off.
9. Confirm the PPSSPP/Dolphin option values really take effect (resolution visibly changes in game).

## Not done / known limits

- No device evidence for any of the above.
- Dolphin texture filtering and Dolphin widescreen value names are unverified; texture filtering is not offered for GameCube.
- N64: no live changes, no sharpen, MSAA depends on GLideN64 mode.
- The N64 shader choice is written to the shared preferences before launch (the existing shader preference, marked as ours so it is cleared again); the emulation process reads it at start.
- The old **"Show FPS (coming soon)"** toggle in Settings already drives the new counter (same preference); its label still says "coming soon" and should be updated once PR #29 (touching that screen) lands.
- Control overlays were deliberately not touched (PR #29).

— Victorious
