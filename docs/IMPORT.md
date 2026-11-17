# Importing games (Library tab)

Victorious Framework · VictorLink64

You never need to unzip anything by hand. On the **Library** tab, use the folder button to pick a whole folder, or the
add-file button to pick single games. VictorLink64 works out the console from the file itself (its name and its first
bytes) and adds it to the Library.

## What it understands

| Console | Files (plain, or inside an archive) |
| --- | --- |
| N64 | `.z64` `.n64` `.v64` (byte order detected from the header) |
| SNES | `.sfc` `.smc` `.swc` `.fig` (copier headers handled) |
| PSP | `.iso` `.cso` `.zso` `.pbp` `.chd` |
| GameCube | `.iso` `.gcm` `.rvz` `.gcz` `.wia` `.ciso` |
| Archives | `.zip` `.7z` `.rar` `.tar` `.tar.gz`/`.tgz` `.tar.xz`/`.txz` `.gz` `.xz` |

- A plain `.iso` is told apart (GameCube or PSP) by its header, not its name.
- Compressed disc files (`.cso` `.rvz` `.gcz` `.ciso` `.chd` and so on) are handed to the emulator as they are. They are
  not unpacked.
- A game inside an archive is unpacked once, the first time you press Play, into the app's cache (with a progress bar and a
  Cancel button). Next time it starts straight away. An archive with several games shows one Library row per game, and
  rows say which archive they came from.
- **Settings → Game storage** shows what is unpacked, lets you delete items, and sets a size limit (2, 4, 8 or 16 GB, or
  none). When the limit is hit, the games played longest ago are removed first; they just unpack again when played.
- Files that look like games but can't be played (Wii discs, PlayStation or other CD images, password-protected or
  damaged archives, archives inside archives) are listed under "files couldn't be added", each with a plain reason.

## Known limits

- RAR: libarchive reads RAR 4 and RAR 5, including solid archives. Password-protected and multi-part RARs are refused with a message.
- `.chd` is assumed to be a PSP disc. A PlayStation 1 or Saturn `.chd` would be listed as PSP and fail to start.
- `.wbfs` and other Wii files are recognised but not playable (the app supports GameCube only).
- Cloud-drive files that cannot be opened with random access cannot be read inside 7z archives; copy them to the phone first.

## Licences of the libraries used

See `NOTICE` and the About screen. libarchive (BSD), libarchive-android (Apache-2.0), Apache Commons Compress
(Apache-2.0), XZ for Java (0BSD). All are compatible with GPL-3.0. The unRAR source is deliberately not used.

## Test status (what was and was not run)

- Automated JVM tests (`./gradlew :app:testDebugUnitTest`): format detection, archive listing/extraction for zip, 7z,
  tar, gz and xz built from synthetic data, cache eviction and size cap, error messages. RAR logic is covered with a fake
  reader only.
- On-device tests (`./gradlew connectedDebugAndroidTest`, needs a device or emulator): the real native libarchive RAR path
  with a synthetic RAR 5 archive. Ran on an Android 14 x86_64 emulator.
- Not tested: playing real games, PSP and GameCube cores (arm64 phones only), very large archives, cloud-drive files.

## Device test checklist

1. `python3 scripts/make_import_fixtures.py out/` makes tiny fake "games" (they only prove importing; they will not boot).
   Copy the `out` folder to the phone's Downloads. Library → add-file button → select all → the games appear with the
   right console labels (N64, SNES, GameCube, PSP) and blue play buttons.
2. Library → Pick folder → choose that folder → the same list appears, with zip/rar/tar.gz rows marked "in <archive name>".
3. With your own legal game in a `.zip` (N64 or SNES): press Play → "Unpacking" bar appears once → game starts. Close it
   and press Play again → starts at once.
4. Same with a `.7z` and a `.rar`.
5. Settings → Game storage → the unpacked game is listed; change the limit; Delete one; Clear all.
6. (arm64 phone) A GameCube `.rvz` and a PSP `.cso` or `.chd`: Play starts them directly with no unpacking bar.
7. A password-protected zip/rar shows up under "files couldn't be added" with a plain message, and nothing crashes.
8. About screen lists libarchive, libarchive-android, Commons Compress and XZ for Java.

— Victorious
