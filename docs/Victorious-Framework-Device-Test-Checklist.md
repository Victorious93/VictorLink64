# Victorious Framework — VictorLink64 Device Test Checklist

Covers the fixes merged in PR #16 (v0.3.2 bug sweep). Written 2026-10-16 · Author: Victorious

**Why this exists.** Those fixes compile and CI is green, but none has been seen working on a device. Until this checklist is filled in, their status is *compiled, not device-tested*. Do not record anything below as working without a result in the table at the end.

**Rules.** Never commit a ROM, save or APK. Use ROMs you own. Record exactly what you saw, including "could not reproduce the original bug", which is *not* the same as "fixed" (see the note under each test).

## 0. Setup

| Item | Value |
| --- | --- |
| Build | `./gradlew assembleDebug` → `app/build/outputs/apk/debug/VictorLink64-debug.apk`, or the CI artifact (7-day retention; upload was previously blocked by storage quota) |
| Install | `adb install -r VictorLink64-debug.apk` (package `org.victoriousframework.victorlink64.debug`) |
| Record | device model · Android version · APK `sha256sum` · date · who tested |
| Devices | Tests 1 and 3 need one device. Tests 2a/2b need **two** devices (or two emulators for Wi-Fi only; Bluetooth needs real hardware) |

## 1. Chess: "Phone is thinking…" must not get stuck

*Fixes:* New game during an AI search used to leave the thinking state on, with Resign/Undo disabled. (Found by code reading, never reproduced. Reproduction needs a slow search window.)

1. Library tab → **Chess** → **New game** → *vs Phone*, difficulty **Expert**, you play **White** → Start.
2. Play a developing move (e.g. e2→e4). Status reads "Phone is thinking…". While it does, tap **New game** (top right) → pick *vs Phone*, **White** → Start.
3. **Expect:** board resets; status reads "White to move"; **Resign** is enabled; no "thinking" text. Make a move; the phone replies normally.
4. Repeat with *You play* **Black**. **Expect:** the phone opens, shows "Phone is thinking…", then plays a move and the status returns to "Black to move"; Resign/Undo enabled afterwards.
5. While "Phone is thinking…" is showing, **Undo** and **Resign** must be disabled; after the reply they must be enabled.
6. Play a full short game vs Phone (Easy) to the end; the result text appears and Resign is disabled.

*If Expert answers too fast to hit step 2:* try a slower phone, or repeat several times. A pass only means "did not recur", so note how many tries.

## 2. Ordered messaging over Wi-Fi and Bluetooth

*Fixes:* every `send()` used its own thread, so messages could be reordered; now one ordered writer thread per connection. Chess alternates turns, so reordering is rarely visible. This test is mainly a **regression check** on the rewritten send path.

### 2a. Wi-Fi (both devices on the same network; some guest/AP-isolated networks block mDNS: note it if so)

1. Device A: **Connect** → *Nearby · same Wi-Fi* → **Host**. Device B: **Join**.
2. **Expect:** both show Connected; A is Host (White).
3. On A tap **Play chess**; B's Chess opens automatically.
4. Play ≥ 20 moves each side **as fast as you can**. After each few moves compare the move list under the board on both devices.
5. **Expect:** move lists are identical on both; no "illegal"/stuck state; every move arrives.
6. Resign on one side → the other shows the resign result. **Rematch** → both reset, colors swap.
7. Tap **Leave** on B. **Expect:** A shows "Friend disconnected" (no crash). Host again from A and join from B: **Expect:** a fresh connection works (a new connection must get a new sender).

### 2b. Bluetooth (real hardware; grant the permission prompts)

Same steps as 2a using *Nearby · Bluetooth*: A **Host**, B **Join** → pick A in the list. Note the Android versions (the permission set differs below/above Android 12).

### 2c. Online (needs the relay, see test 4 for the new relay code)

Device A **Create room**, note the 6-character code; B enters it → **Join**. Repeat steps 3-6 of 2a.

## 3. Zipped ROMs: two zips, same inner filename

*Fixes:* the unpacked ROM was cached by inner filename only, so a second zip containing the same filename could launch the first zip's ROM.

You need two **different** ROMs. Put each in its own zip with the **same** inner name:

```
A.zip → game.z64   (ROM #1)
B.zip → game.z64   (ROM #2, different game)
```
Repeat with SNES (`game.sfc`) if you have two.

1. Copy both zips to a folder on the device. Library → pick that folder. **Expect:** both games listed with "· ZIP".
2. Launch **A**. Note the title/behavior. Back out of the game.
3. Launch **B**. **Expect:** B's game, **not** A's. (Wrong = same title as A.)
4. Launch **A** again. **Expect:** A's game again.
5. Debug build only: `adb shell run-as org.victoriousframework.victorlink64.debug ls cache/roms` **Expect:** one file named `<hex>_game.z64` (only the most recent is kept).
6. Plain, unzipped ROM in the same folder still launches.

## 4. Relay hardening (needs `npx wrangler deploy`, no device required)

*Fixes:* peers could forge `welcome`/`peer_join`/`peer_leave`; closes were not answered; leave count was wrong. **Not live until deployed.** The app reaches `wss://victorlink64-relay.victorlink64.workers.dev`.

1. Get a code: `curl https://victorlink64-relay.victorlink64.workers.dev/new`.
2. Two terminals: `npx wscat -c "wss://victorlink64-relay.victorlink64.workers.dev/room/<CODE>?name=a"` and the same with `name=b`.
3. In terminal b send `{"t":"move","uci":"e2e4"}`. **Expect:** it appears in terminal a.
4. In terminal b send `{"t":"peer_leave"}` and `{"t":"welcome","host":false}`. **Expect:** **nothing** appears in terminal a.
5. Close terminal b. **Expect:** terminal a receives `{"t":"peer_leave",...,"count":1}`.

## 5. Online room code error handling

1. Turn on airplane mode; **Connect** → **Create room**. **Expect:** a toast "Relay unreachable: …", no crash.
2. Wi-Fi back on: **Create room** shows a 6-character code (letters/digits, no 0/O/1/I).

## 6. Regression smoke (quick)

App launches; Library scans a folder; an N64 ROM and a SNES ROM each start and respond to input; Settings and About open. This only says the app is not obviously broken, **not** that gameplay or controllers are verified.

## Results (fill in)

Legend: ✔ pass · ✘ fail · — not run

| # | Test | Result | Device / Android | Notes (what you saw) |
| --- | --- | --- | --- | --- |
| 1 | Chess thinking lock |  |  |  |
| 2a | Wi-Fi chess + reconnect |  |  |  |
| 2b | Bluetooth chess + reconnect |  |  |  |
| 2c | Online chess |  |  |  |
| 3 | Same-name zips |  |  |  |
| 4 | Relay forgery dropped / leave count |  |  |  |
| 5 | Relay error toast |  |  |  |
| 6 | Smoke |  |  |  |

After testing, copy results into `docs/Victorious-Framework-Status.md` (bug sweep table) and change "reasoned from code; not device-tested" to what you observed.

— Victorious
