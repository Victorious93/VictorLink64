# VictorOps Zombies

![VictorOps Zombies logo](assets/victorops-zombies-logo.jpg)

An **original** top-down, round-based wave-survival zombie shooter for Android.
Hold the line across ever-harder rounds, board up the windows, spend your points
at the Mystery Crate, the Iron Hide Tonic perk machine and the Overcharge Forge.

## Concept (v0 slice)
- **Rounds** – zombie count, health and speed scale each round (`core/.../logic/Rounds.kt`).
- **Points** – +10 per hit, +60 per kill, +10 per barricade plank repaired; start with 500.
- **Barricades** – four windows with 6 planks each; zombies must tear them down to get in.
- **Mystery Crate** (950 pts) – weighted random weapon (Scatterbolt, Buzzsaw SMG, Pulse Rifle, Rail Lancer, Nova Disruptor).
- **Perk machine** – *Iron Hide Tonic* (2500 pts) doubles max health.
- **Upgrade machine** – *Overcharge Forge*, three tiers: Mk.II (5000 pts) doubles damage and speeds fire; Mk.III (10000 pts) adds +50% damage, faster fire and +1 pierce. Multi-pellet guns gain 2 pellets per tier, the Nova's blast grows, and magazines get bigger with faster reloads.
- **Armor packs** – three racks: Light Plating (500, +50), Reinforced Vest (1000, +100), Bulwark Suit (1800, +150). Armor soaks damage before health, caps at 200 and carries between rounds. Zombies also drop free Light packs (6%).
- **Ammo** – each weapon has a magazine and reload time. Reserve ammo is unlimited; an empty magazine triggers a reload.
- **Characters** – pick one on the start screen (names are display data in `Characters.kt`):
  - *Snoop Dogg*: faster move and fire rate, starts with the Buzzsaw SMG, ability **Bass Drop** (knockback + damage blast).
  - *Martha Stewart*: more health, repairs 2 planks per use for double points, starts with the Scatterbolt, ability **Fortify** (rebuilds every barricade).
- **Callouts** – text-only shouts over the player: low health, reload, game over.
- **Offline play** – *Solo* needs no internet, no Wi-Fi and opens no sockets. The Android `INTERNET` permission is declared only for Wi-Fi/LAN co-op.
- **Co-op multiplayer (1-4 players, drop-in/drop-out)** – host-authoritative: the host runs the whole simulation (`core/.../sim/World.kt`) and streams ~15 Hz snapshots (~1 KB); clients send ~20 Hz inputs. Each player has their own points, weapon, armor, perk and ammo; barricades, rounds and zombies are shared. A downed player is revived by a teammate standing next to them for 3 s; the game ends only when everyone is down.
  - **Wi-Fi/LAN (built in)**: pick *Host* on one device and *Join* on the others (enter the host's LAN address, port 47464). Works without internet.
  - **Bluetooth**: the game talks to a small `NetLink` interface (`core/.../net/NetLink.kt`), so any transport can carry it. A Bluetooth/online adapter is meant to live in VictorLink64, which already has an RFCOMM transport (2 players, since that transport accepts one peer). **Not built or device-tested yet.**
- **Controls** – Android: virtual joystick (left half), USE button (right). Desktop: WASD + E, Q for the character ability (SKILL button on touch). Aiming/firing is automatic at the nearest zombie.
- Graphics are procedural placeholder shapes for now.

## Why libGDX + Kotlin
- Full control over Gradle/Android config: ABIs (arm64-v8a, armeabi-v7a), APK signing schemes, target SDK.
- Small APK (~3.6 MB debug), no heavy engine runtime.
- Code-first, diff-friendly, CI-friendly – no binary scene files.
- Excellent fit for 2D top-down games; desktop (LWJGL3) module for fast iteration.

## Project layout
| Module | Purpose |
|---|---|
| `core` | Game code + pure-logic rules with unit tests |
| `android` | Android launcher, `applicationId com.victorious.zombie`, minSdk 24, target/compile SDK 35 |
| `lwjgl3` | Desktop launcher for testing |
| `assets` | Shared assets (logo) |

## Build
Requires JDK 17 and the Android SDK (`ANDROID_HOME` or `local.properties` with `sdk.dir`).
```bash
./gradlew test                 # pure-logic unit tests
./gradlew :android:assembleDebug   # APK at android/build/outputs/apk/debug/
./gradlew :lwjgl3:run          # play on desktop
```
Debug builds are signed with a key supplied via env vars `VOZ_KEYSTORE_PATH`, `VOZ_KEYSTORE_PASSWORD`,
`VOZ_KEY_ALIAS`, `VOZ_KEY_PASSWORD` (or the same keys in git-ignored `keystore.properties`/`local.properties`).
If unset, the standard Android default debug key is used. No keystore is committed.
Keystores and signing secrets are never committed (see `.gitignore`).

CI (`.github/workflows/android.yml`) runs tests, builds the debug APK, verifies v2/v3 signatures
and both ABIs with `apksigner`, and uploads the APK as an artifact.

## Roadmap
- **v1 – single-player**: real art & audio, multiple maps/rooms with buyable doors, more perks, specials, save high scores, settings, controller support.
- **Later milestone – co-op multiplayer (scoped, not built)**: local split/shared-screen co-op, then online co-op (host-authoritative netcode, lobby, revive mechanic).

## Originality / IP statement
VictorOps Zombies is an original work. Code, rules, weapon names and (placeholder) graphics are
created for this project. It is inspired only by the general genre of round-based survival
modes and uses **no** names, assets, audio, maps or code from Activision, Call of Duty, or any
other third-party franchise.

**Exception: real people.** Two playable characters use the real names of Snoop Dogg and Martha Stewart.
They are drawn as plain placeholder shapes and use no photos, voices, music or likenesses, and the in-game
callouts are generic player text. Even so, using a real person's name on a game character can raise
right-of-publicity and endorsement issues, especially if the game is published or sold. Before any public
release (e.g. Google Play), either get permission or rename them in `core/.../logic/Characters.kt`.
