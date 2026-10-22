<p align="center">
  <img src="assets/logo.png" alt="VictorLink64 logo" width="200">
</p>

<p align="center">
  <img src="assets/logo.png" width="200" alt="VictorLink64 logo">
</p>

<h1 align="center">VictorLink64</h1>

<p align="center"><b>N64 and Super Nintendo games, built-in chess, and multiplayer with friends — in one Android app.</b></p>

<p align="center">
  <a href="https://github.com/Victorious93/VictorLink64/releases"><img alt="Latest release" src="https://img.shields.io/github/v/release/Victorious93/VictorLink64?include_prereleases&style=flat-square&color=1EA0FF"></a>
  <a href="gpl-license"><img alt="License: GPL-3.0" src="https://img.shields.io/badge/license-GPL--3.0-FF3B3B?style=flat-square"></a>
  <img alt="Android 9+" src="https://img.shields.io/badge/Android-9%2B-3DDC84?style=flat-square&logo=android&logoColor=white">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?style=flat-square&logo=kotlin&logoColor=white">
</p>

## Overview

VictorLink64 plays Nintendo 64 games (Mupen64Plus) and Super Nintendo games (bsnes) from game files you provide,
includes a chess game and the VictorOps Zombies co-op shooter, and lets you connect with other players online,
over Wi-Fi, or over Bluetooth.

> **Early beta.** Releases are currently debug builds, and most features have not yet been tested on many real
> devices, so expect rough edges. Casting to a TV and online play for N64/SNES games are not implemented yet;
> online play currently covers chess and VictorOps Zombies (Zombies co-op has not been tested on real devices).

Screenshots coming soon.

## Features

- **N64 and SNES emulation** — open your own game files and play.
- **Built-in chess** — play the phone (Easy to Expert) or pass the phone to a friend.
- **VictorOps Zombies** — built-in top-down wave shooter: solo offline from the Library tab, or co-op with a connected friend from the Connect tab (2 players over Bluetooth). Co-op is new and **not yet device-tested**; see [`docs/ZOMBIES.md`](docs/ZOMBIES.md).
- **Online rooms** — create a room and share its 6-character code; friends enter it to join.
- **Nearby play** — over the same Wi-Fi, or over Bluetooth with no internet.
- **Bluetooth controllers and touch controls** — pair a gamepad or use the on-screen buttons.
- **Save states** — save anywhere and resume later.

## Install

1. On your Android phone (Android 9 or newer), download the latest `.apk` from the
   [Releases page](https://github.com/Victorious93/VictorLink64/releases).
2. Allow installing from unknown apps when Android asks.
3. Open the downloaded file to install.

Only install builds from this repository's Releases page.

## Usage

1. Open VictorLink64, tap **Pick folder**, and choose the folder that holds your game files.
2. Tap a game to play, or tap **Chess** for a quick match.
3. To play with a friend, open the **Connect** tab.

VictorLink64 does not include any games. Use game files you legally own, such as backups of your own cartridges.

## Build from source

Requirements:

- JDK 21
- Android SDK with platform `android-36` and build-tools `36.0.0`
- NDK `26.1.10909125` and CMake `3.22.1`
- `gawk` and `file` on the host (the native build fails without `gawk`)

```bash
sdkmanager "platforms;android-36" "build-tools;36.0.0" "ndk;26.1.10909125" "cmake;3.22.1"

git clone https://github.com/Victorious93/VictorLink64.git
cd VictorLink64
echo "sdk.dir=/path/to/android-sdk" > local.properties

./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/VictorLink64-debug.apk`. The first native build covers four ABIs
and takes several minutes. Run only one Gradle build per checkout at a time.

Run the tests:

```bash
./gradlew :app:testDebugUnitTest                      # chess engine, connect and Zombies adapter tests
./gradlew :zombies:test                                # VictorOps Zombies game tests (67 original + 2)
python3 -I -m unittest discover -s scripts/tests -v   # APK payload verifier tests
```

Project layout:

| Path | Contents |
| --- | --- |
| `app/src/main/java/org/victoriousframework/victorlink64/` | Kotlin and Compose UI: `ui/`, `chess/`, `connect/`, `snes/`, `zombies/` |
| `zombies/` | VictorOps Zombies game core (Kotlin + libGDX, JVM module) — see [`zombies/README.md`](zombies/README.md) |
| `mupen64plus-*` | N64 emulator core and plugins (native modules) |
| `app/src/main/jniLibs` | Prebuilt bsnes libretro core; rebuild with `scripts/build-bsnes.sh` |
| `server/relay` | Cloudflare Worker and Durable Object for online rooms (`npx wrangler deploy`) |

More detail: [`BUILDING.md`](BUILDING.md), [`ARCHITECTURE.md`](ARCHITECTURE.md), [`TESTING.md`](TESTING.md),
[`docs/NETPLAY.md`](docs/NETPLAY.md).

## FAQ

**Is it free?** Yes. It is free and open source, with no ads or subscriptions.

**Something doesn't work?** Please [open an issue](https://github.com/Victorious93/VictorLink64/issues) with your
phone model and what happened.

## Credits and license

VictorLink64 is made by Don Victor ([Victorious93](https://github.com/Victorious93)) and builds on these
open-source projects:

- [Mupen64Plus](https://github.com/mupen64plus) and [Mupen64Plus-AE](https://github.com/mupen64plus-ae/mupen64plus-ae) — N64 emulation (GPL v3). Upstream's original README is kept in [`UPSTREAM-README.md`](UPSTREAM-README.md).
- [bsnes](https://github.com/libretro/bsnes) by byuu and contributors — Super Nintendo emulation (GPL v3)
- [libGDX](https://github.com/libgdx/libgdx) — game framework for VictorOps Zombies (Apache-2.0)

The chess engine, multiplayer, VictorOps Zombies and app screens are original VictorLink64 / Victorious Framework code.

Licensed under the **GNU General Public License v3** — see [`gpl-license`](gpl-license).

<sub>Nintendo 64 and Super Nintendo are trademarks of Nintendo. VictorLink64 is not affiliated with or endorsed by Nintendo.</sub>
