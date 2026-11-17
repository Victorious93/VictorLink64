# Victorious Framework — Controls Device Test Checklist

Covers the controls overhaul in [`CONTROLS.md`](CONTROLS.md). Written 2026-11-17 · Author: Victorious

Nothing below has been run on a physical device. What *was* run: an Android 14 x86_64 emulator (no KVM-arm, so no arm64 cores): the overlay rendering, SNES with a locally generated stub ROM (not committed), the N64 test ROM, edit mode and layout persistence, the in-game menu entry and the Controller buttons screen (a synthetic gamepad key rebinding a row). Record real results in the table at the end; "could not reproduce" is not "fixed".

**Rules.** Never commit a ROM, save or APK. Use games you own.

## 1. Touch feel (each console, landscape then portrait)

1. Launch a game. Controls match the real pad (SNES diamond, PSP symbols, GameCube big A + B + kidney X/Y + Z + C-stick, N64 C cluster). No red anywhere.
2. Thumb on a face button, roll to the next one without lifting: the first releases, the second presses. At the seam between two buttons, both press.
3. D-pad: slide round the rim; diagonals register; keep the finger on it past the pad edge.
4. Two thumbs at once (stick + buttons) and a third finger on a shoulder: nothing drops.
5. Haptics: a tick on each fresh press, none while holding.
6. Notch/cut-out: controls stay clear of it in both landscape directions.

## 2. Analog

1. GameCube: main stick walks/runs smoothly (Super Mario Sunshine, Melee menus); C-stick moves the camera; analog L/R triggers: half-pull vs full-pull behave differently (Luigi's Mansion, Mario Kart Double Dash). Z works.
2. PSP: analog stick in a 3D game. 
3. N64: stick reaches full speed; centre is quiet (dead zone); C buttons work.
4. Edit panel (ALL): dead-zone slider and STICK: FIXED/FLOATING behave as labelled; floating base plants under the thumb and follows past the rim.
5. TRIGGERS: DIGITAL makes L/R a full press on touch.

## 3. Layout editor

1. EDIT (SNES/PSP/GC) or menu → Edit touch controls (N64): drag, corner-dot resize, size/opacity sliders, ALL tab, RESET, DONE.
2. Quit and relaunch: layout kept. Rotate: the other orientation has its own layout. RESET restores defaults for the current orientation only.

## 4. Hardware controller

1. Connect a Bluetooth/USB pad: touch controls fade out (HIDE WITH PAD on); PAD: HIDDEN chip / menu item brings them back; unplug restores them.
2. Settings → Controller buttons: bind a key, clear it, reset; the row lights when you press the matching button. Binding persists into the game.
3. In a game: buttons, sticks, analog triggers, hat D-pad. SNES: left stick acts as the D-pad.
4. N64: pad via the Mupen64Plus profile (unchanged); touch + pad together do not fight.

## 5. Fallback

Settings → N64 touch controls → turn off *VictorLink64 on-screen controls*: the legacy skins return and work.

## Results

| Date | Device / Android | Build SHA-256 | Tests run | Result | Tester |
| --- | --- | --- | --- | --- | --- |
| | | | | | |

— Victorious
