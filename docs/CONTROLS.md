# Victorious Framework — On-screen and gamepad controls

Updated 2026-11-17 · Author: Victorious

One input system serves every console. Status labels follow the repo rule: **implemented**, **compiled**,
**automated-tested** (JVM tests in CI), **device-tested** (nothing here yet).

## What it does

Smoke-tested on an Android 14 **x86_64 emulator** only (overlay rendering, SNES with a locally generated stub ROM, the N64 test ROM, layout editing and persistence, the in-game menu entry, key rebinding). No physical device, no arm64, no PSP/GameCube core run, no real gamepad. Checklist: [`Victorious-Framework-Controls-Device-Test-Checklist.md`](Victorious-Framework-Controls-Device-Test-Checklist.md).

| Feature | Where | Status |
| --- | --- | --- |
| True analog stick, **fixed or floating** base, radial dead zone, response curve, springing knob | `controls/StickMath.kt`, `TouchEngine.kt`, `TouchOverlayView.kt` | automated-tested (math + engine + view), not device-tested |
| GameCube **main stick + C-stick**, PSP stick, N64 stick | `ConsoleLayouts.kt` | automated-tested layout and routing |
| **Analog GameCube L/R triggers** (slide down the control; full squeeze also sets the digital click) | `TriggerMath`, `PadConvert.toRetro` | automated-tested; switchable to digital |
| **Slide D-pad**, 8 directions, finger stays captured past the edge | `DPadMath` | automated-tested |
| Multi-touch, rolling between face buttons, A+B at the seam, direct hit beats a neighbour's slop | `TouchEngine` | automated-tested |
| Console-accurate layouts: SNES diamond; PSP symbols; GameCube big A, B, kidney X/Y, Z, START, C-stick; N64 C-button cluster, A/B, Z/L/R, START | `ConsoleLayouts.kt` | layouts checked by `DefaultLayoutQualityTest` (on screen, no overlaps, 5 landscape + 3 portrait aspect ratios) |
| **Edit layout**: drag, resize (corner dot or slider), per-control opacity, global size/opacity/dead zone, **saved per console and per orientation**, reset | `OverlayHost` + `OverlayEditPanel`, `LayoutStore` | automated-tested (Robolectric view test + persistence tests), not device-tested |
| Haptics on each fresh press (not while held) | `TouchEngine.freshPress` | logic automated-tested; feel not device-tested |
| **Auto-hide when a hardware controller is connected**, with a show/hide override | `HardwareControllers`, `PadSession` | compiled, not device-tested |
| Hardware gamepad: keys, sticks, analog triggers, hat D-pad, merged with touch | `HardwareInput`, `HardwarePad` | automated-tested (Robolectric events), not device-tested |
| **Controller button remapping** screen (SNES, PSP, GameCube; also a live controller test) | `ControllerMapActivity` (Settings → Controller buttons) | automated-tested (bindings + persistence), screen not device-tested |
| Analog values to the cores | `hwfront.c` `nativeSetPad` | compiled for arm64; not run against the cores on a device |

Brand rule: navy and cyan-blue only. There is no red anywhere in the controls (`DefaultLayoutQualityTest` pins the accent set).

## Libretro wiring (PSP, GameCube)

`hwfront.c` keeps one pad snapshot per frame (taken in `poll_cb`) and answers three queries:

- `RETRO_DEVICE_JOYPAD` — buttons, including the bitmask query.
- `RETRO_DEVICE_ANALOG` + `RETRO_DEVICE_INDEX_ANALOG_LEFT/RIGHT` — both sticks, ±0x7FFF, +Y down.
- `RETRO_DEVICE_ANALOG` + `RETRO_DEVICE_INDEX_ANALOG_BUTTON` + `L2`/`R2` — trigger pressure 0..0x7FFF (any other pressed button reads 0x7FFF).

**GameCube mapping, checked against `libretro/dolphin` `Source/Core/DolphinLibretro/Input.cpp`** (the earlier mapping had A/B and X/Y swapped, Z on R2 and L on L1):

| GameCube | RetroPad |
| --- | --- |
| A / B / X / Y | A / B / X / Y |
| Z | R (R1) |
| L / R trigger | L2 / R2 (analog via `INDEX_ANALOG_BUTTON`, digital L2/R2 as the click) |
| Main stick / C-stick | left / right analog |

PSP follows PPSSPP's RetroPad convention (cross = B, circle = A, square = Y, triangle = X).

SNES (bsnes) has no analog device; the overlay and hardware pads drive digital buttons (a hardware stick acts as the D-pad).

## N64 (Mupen64Plus)

The overlay replaces the legacy touch-skin system **by default**. `OverlayController` writes into the same per-player
state as `PeripheralController`, so a hardware pad and touch can be used together. Layout editing and show/hide are in the
in-game menu. The old skin system is kept behind **Settings → N64 touch controls → VictorLink64 on-screen controls**
(off = legacy) as a fallback until the new controls are proven on devices. Not carried over to the new overlay: the
accelerometer-as-stick option and per-button auto-hold. N64 hardware pads keep using the Mupen64Plus controller profiles
(opened from the Controller buttons screen).

## Layout model

Positions are stored as an anchor (left / centre / right, top / centre / bottom), an offset in "edge units" (1.0 = the
short side of the usable area, inside notches) and a scale/opacity, so a layout follows the screen at any aspect ratio.
Landscape and portrait have separate saved layouts. Controls missing from saved data fall back to their defaults.

`scripts/render_layout_preview.py` renders the defaults to PNG from a test dump (see its docstring).

## Credits

Layouts and behaviour are modelled on the official on-screen controls of Dolphin Android and PPSSPP (both GPL v2.0 or
later). No code was copied; this is original VictorLink64 code under GPL-3.0. About screen credits both projects.

— Victorious
