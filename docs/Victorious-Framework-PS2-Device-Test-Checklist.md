# Victorious Framework — PlayStation 2 Device Test Checklist

Covers the PS2 support described in [`PS2.md`](PS2.md). Written 2026-11-17 · Author: Victorious

**Nothing below has been run on an arm64 device.** The LRPS2 core is arm64-only and cannot run on an x86_64 emulator. What *was* run is listed in the PR and in `PS2.md` ("What was tested"). Record real results in the table at the end; "could not reproduce" is not "fixed".

**Rules.** Never commit or share a ROM, BIOS or save. Use a BIOS dumped from your own PlayStation 2 and games you own. Test on an arm64-v8a phone (the owner's Dimensity 6300 phone is the target to learn about; also try one faster phone if available).

## 0. Before you start

1. Install the CI debug APK. Note the phone model, chip, RAM and Android version.
2. Put a few PS2 games in the library folder: one `.iso`, one `.chd` (CD and, if you have one, DVD), one `.cso`, one `.bin` + `.cue`. Also put a PSP `.iso`, a GameCube `.iso` and a PS1 `.bin`/`.cue` in the same folder to check they are **not** mixed up.

## 1. BIOS (no game yet)

1. Settings → **PlayStation 2**. The BIOS card says "No BIOS added yet" and explains what a BIOS is.
2. **Add BIOS file(s)** → pick your real BIOS. A line "Added scph….bin: <region> v<version> (<date>)" appears and the file is listed. Does the region/version match your console?
3. Pick a non-BIOS file (a photo, a PS1 BIOS if you have one): "Skipped …" with a plain reason, nothing listed.
4. Add a second BIOS: both listed, tap to choose, the chosen one stays after leaving and coming back.
5. **Remove** deletes the file; the list updates.

## 2. Missing BIOS message

1. With no BIOS added, tap a PS2 game in the Library. The PlayStation 2 screen opens with "PlayStation 2 games need a BIOS", the Play button says "Add a BIOS to play" and is disabled.
2. Add a BIOS, go back, tap the game again: setup shows the speed note; **Play** starts the game. Second time: the game starts straight away.

## 3. Library recognition

1. Library lists the PS2 games with the label "PlayStation 2". The PSP, GameCube and PS1 files are not listed as PS2 (a PS1 CD `.chd` *will* be listed as PS2 — known; confirm the core refuses it with the "could not start" message and does not crash the app).
2. Multi-track `.cue` (data + audio `.bin` files) launches.
3. A BIOS file left in the same folder is not listed as a game.
4. Rescan time with a few large files in the folder: acceptable?

## 4. Does it run (the unknown)

For each of 2–3 games, including one light/2D-era title and one demanding 3D title:

1. Native picture size. Does it start (core loads, picture appears, audio plays)? Note time to first picture.
2. Frame rate: full speed, slow, or unplayable? In-game and in FMV. (The app has no FPS counter yet; judge by eye or use a screen recording.)
3. Audio: crackling, drifting, silent?
4. Picture: correct aspect (4:3), no flicker, no black frame, no upside-down image.
5. Quit and relaunch the same game, then a different game, three times: no crash, no growing slowness.
6. Home button and return (app in background and back): picture and audio recover?
7. Rotate / lock orientation: nothing breaks.

If it fails to start, capture `adb logcat -s VL64Hw VL64HwCore` — the GL context request and the core's own messages are logged there.

## 5. Controls

1. The DualShock 2 layout shows: D-pad, two sticks, ✕ ○ □ △, L1 L2 R1 R2, L3 R3 small buttons, Select and Start. Brand cyan/steel, **no red**.
2. Menus: D-pad moves; ✕ confirms, ○ cancels (Western menus) — correct symbols?
3. Analog: left stick walks, right stick moves the camera in a 3D game. Games that boot the pad in digital mode still respond to the sticks (the app turns analog mode on).
4. L2/R2 and L1/R1 work; L3/R3 click (e.g. sprint or camera reset in games that use them).
5. Start and Select pause and map as in the game.
6. EDIT: drag, resize, opacity; reopen the game: layout kept; rotate: the other orientation keeps its own.
7. Bluetooth controller: Settings → Controller buttons → PS2 tab shows all 16 buttons, rebinding works; in game the pad works, both sticks, analog triggers (racing game), stick clicks. Touch controls hide when the controller connects (if that setting is on).

## 6. Picture size and speed note

1. Setup screen shows the speed note. On a phone with less than about 6 GB it also says to expect slowness.
2. Choosing 2× or 3× on a weaker phone shows a warning.
3. Launch at 2×: picture visibly sharper than Native? Is it still playable? Note frame rate.
4. Launch at **3×** — this value is *not* in the core's own list. Does it start, and is the picture about 1080p? If not, note what happens (black, crash, falls back to native).
5. Switch back to Native: next launch is Native again.

## 7. Saving

1. Save in a game to the memory card, quit, relaunch: the save is there.
2. SAVE/LOAD chips are not shown for PS2 (by design).

## 8. After PR #31 / #32 merge

Re-run sections 3 and 6 (library detection and resolution) to confirm the merged code paths agree.

## Results

| Date | Phone / chip / RAM | Section | Game | Result (works / slow / fails) | Notes |
| --- | --- | --- | --- | --- | --- |
|  |  |  |  |  |  |

— Victorious
