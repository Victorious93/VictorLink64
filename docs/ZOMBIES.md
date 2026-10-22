# VictorOps Zombies in VictorLink64

Victorious Framework · Updated 2026-10-21 · Author: Victorious

## What it is
VictorOps Zombies is a top-down, round-based co-op wave shooter (libGDX + Kotlin, procedural placeholder graphics).
VictorLink64 bundles it as a built-in game next to Chess:

- **Library tab → VictorOps Zombies**: solo, fully offline, no session needed.
- **Connect tab → Play Zombies** (shown once a friend is connected, over Bluetooth, Wi-Fi or online): the session host runs
  the simulation (`HostSession`), the guest joins (`ClientSession`). Both players tap **Play Zombies** and pick a survivor;
  the guest retries joining every second so tap order does not matter.
- Bluetooth is **2 players**: `BluetoothTransport` accepts one peer, and this PR does not change that. The game supports up to 4.

## How it fits together
```
GameScreen ── HostSession/ClientSession ── NetLink ── ConnectNetLink ── ConnectSession ── Transport (BT / LAN / relay)
 (:zombies)        (:zombies)            (:zombies)        (app)            (app)
```
- `ConnectNetLink` maps `send(type, data)` to `ConnectSession.send(type) { put("d", data) }` and forwards only the game's
  message types (`zj zi zw zs zf zb`) from `ConnectSession.messages`. Chess (`move`, `chess_invite`, ...) and `hello` are ignored.
- `PeerState.DISCONNECTED`, `ERROR` (and `IDLE`, i.e. the session was stopped) → `onClosed()`. `close()` detaches only; it never
  stops the shared session.
- The host attaches a fresh link each time the session becomes CONNECTED, so a friend who reconnects from the Connect tab can
  re-join the running game. A guest whose link dropped has to leave the game, reconnect on the Connect tab and tap Play Zombies again.
- `ZombiesActivity` runs in the app process (not a separate one) because `ConnectSession` is process-local.
- No new permissions: Bluetooth permissions and `INTERNET` are already in the app manifest.

## Build notes
- `:zombies` is a plain Kotlin/JVM module (JDK 17 bytecode, Kotlin 2.2.10 from the root build; the game repo used Kotlin 2.1.20 / AGP 8.9.1 /
  JDK 17 toolchain / SDK 35 / minSdk 24 — none of that carries over; this app uses AGP 9.3.1, JDK 21, SDK 36, minSdk 28).
- `app/build.gradle` adds `gdx-backend-android` and unpacks `gdx-platform` natives for `armeabi-v7a arm64-v8a x86 x86_64`
  (the app's ABI filters) into `app/build/gdx-natives` before each build.
- `gdxVersion=1.13.1` lives in the root `gradle.properties`.
- Tests: `./gradlew :zombies:test :app:testDebugUnitTest`

## Status
Legend: implemented / compiled / automated-tested / device-tested / unverified.

| Item | Implemented | Compiled | Automated tests | Device-tested |
| --- | --- | --- | --- | --- |
| Game core (sim, rules, snapshots) | yes (from game repo) | yes | yes — `:zombies:test` | no |
| `ConnectNetLink` adapter | yes | yes | yes — `ConnectNetLinkTest` (fake flows + real `ConnectSession` with a fake `Transport`) | no |
| Host/Client entry, BYE, JOIN retry | yes | yes | yes — `SharedLinkTest`, `SessionTest` | no |
| `ZombiesActivity` + libGDX Android backend | yes | yes — `assembleDebug` | no | no |
| Solo launch from Library | yes | yes — `assembleDebug` | no | no |
| Play Zombies button on Connect tab | yes | yes — `assembleDebug` | no | no |
| Bluetooth co-op (2 phones) | yes | yes — `assembleDebug` | no | **no — unverified** |
| Wi-Fi LAN / online relay co-op through ConnectSession | yes (same path) | yes — `assembleDebug` | no | **no — unverified** |
| Touch controls, rendering, frame rate | from game repo | — | no | **no — unverified** |
| Bandwidth over classic Bluetooth RFCOMM | — | — | size test only (< 2000 B snapshot) | **unmeasured** |
| Reconnect mid-game | implemented (host side) | yes — `assembleDebug` | adapter-level only (`twoLinksOnOneSessionBothSeeTraffic`) | **no — unverified** |
| libGDX natives packaged for 4 ABIs; arm64 `libgdx.so` LOAD alignment 0x4000 (16 KB) | yes | yes | `verify_apk_payload.py` passes (structure only) + `readelf` by hand | no (other ABIs' alignment not checked) |

**Bandwidth (not measured):** snapshots are about 1 KB at 15 Hz and inputs are a few bytes at 20 Hz, i.e. roughly 20 KB/s
(~160 kbit/s) host→guest after base64 and JSON wrapping. Classic Bluetooth RFCOMM normally carries more than that, but
this was not measured here, and the app's single ordered writer thread can queue up if the link stalls. Watch for rubber-banding.

**Known limits:** if the guest quits the game but stays connected, the host is told (BYE); if the guest's app is killed, the host only notices when
the transport drops. Solo/Host pause when the app goes to the background (libGDX lifecycle) while the link stays up.

Verified in the sandbox on 2026-10-21 (JDK 21, Gradle 9.5.0): `:zombies:test` 69/69 pass (67 from the game repo + 2 new), `:app:testDebugUnitTest` 25/25 pass
(12 new adapter tests), `assembleDebug` succeeds, APK payload verifier passes, verifier unit tests 17/17. The APK was never installed or run. CI (`build.yml`) does not run JVM unit tests.

## Device test checklist (2 phones, A = host, B = guest)
1. Install the same debug APK on both. Grant Bluetooth permissions when asked. Pair A and B in Android settings.
2. A: Connect tab → Bluetooth → Host. B: Connect tab → Bluetooth → pick A. Both show *Connected*.
3. Both tap **Play Zombies** (any order). Pick survivors. Expect: both see the arena; B gets a player id, colour differs.
4. Move on both phones with the joystick; each sees the other move without large lag. Note any rubber-banding.
5. Confirm auto-fire hits zombies on both; points rise on both; buy something (USE) on B.
6. Get one player downed, revive from the other (3 s adjacent). Confirm revive works.
7. Disconnect mid-game: switch off Bluetooth on B. Expect B shows "Disconnected from host"; A keeps playing, B's player disappears.
8. Reconnect: B re-enables Bluetooth, reconnects on the Connect tab; A's game is still running; B taps Play Zombies and re-joins.
9. Quit-and-return: B backs out of the game while still connected; A should drop B's player (BYE). B re-launches and re-joins.
10. Chess regression: with the same connection, play a few chess moves (Chess uses the same session).
11. Solo: with Bluetooth off and no session, Library → VictorOps Zombies starts and plays offline.
12. Record: device models, Android versions, any crash (logcat), perceived latency, battery.

— Victorious
