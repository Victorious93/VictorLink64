# :zombies — VictorOps Zombies game core (Victorious Framework)

Pure Kotlin + libGDX 1.13.1 (no Android classes), compiled as a JVM module. The Android side (libGDX backend, natives, launcher
activity, Connect-tab adapter) lives in `app/` under `org.victoriousframework.victorlink64.zombies`.

## Provenance
- Source: <https://github.com/Victorious93/victorops-zombies>, `core` module, **copied (one-time snapshot) at commit `a87b8dd`**
  (the commit that added its GPL-3.0 `LICENSE`; game code is otherwise identical to `ed540dc`). The owner of both repositories
  confirmed GPL-3.0 licensing for the game.
- Why a copy and not a git subtree: the module needs a few local changes (below), the game repo keeps its own Android/desktop
  launchers and release flow, and a ~1.4k-line module does not justify subtree history. The game repo stays the place for
  gameplay work; port wanted changes here by hand.
- Third-party: libGDX is Apache-2.0 (<https://github.com/libgdx/libgdx>), GPL-3-compatible; its notice is kept by the Gradle
  dependency, nothing from libGDX is vendored here.
- `UPSTREAM-README.md` is the game repo's README as copied.

## Local changes vs. the game repo
1. `VictorOpsGame(entry: Entry)` — `Entry.Menu` (original flow), `Solo`, `Host(onSession, onClose)`, `Client(link)`. The host
   app skips the Wi-Fi mode screen and hands the game an already-connected link.
2. `Msg.BYE` (`zb`): a client leaving the game removes its player on the host while the shared link stays up.
3. `ClientSession` repeats `JOIN` once per second until welcomed (a shared link has no handshake; the host may not have been
   listening yet).
4. Tests: `SharedLinkTest` for 2 and 3 (the original 67 tests are unchanged and pass).

The game's own Wi-Fi/LAN mode (`Tcp.kt`, `ModeSelectScreen`) is still in the module but is not reachable from VictorLink64's launcher.

Run the tests: `./gradlew :zombies:test`

— Victorious · Victorious Framework
