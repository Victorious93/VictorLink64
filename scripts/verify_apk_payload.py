#!/usr/bin/env python3
"""Victorious Framework APK payload verifier.

Checks structural properties of a built APK. A pass means only that the
payload is structurally sound; it says nothing about runtime behaviour.

Checks:
  * file is a readable ZIP with AndroidManifest.xml and at least one classes*.dex
  * no unsafe (absolute / traversal) or duplicate entry names
  * for every expected ABI, each required library exists under lib/<abi>/
    and is an ELF image whose class and machine match that ABI
  * no game ROM entries (.z64/.n64/.v64), except diagnostic assets that match
    both an exact entry path and an exact SHA-256 digest
  * no disc images (.iso/.cso/.chd/.cue ...) and no PlayStation 2 BIOS dumps or BIOS add-on files
    (.rom1/.rom2/.mec/.nvm): users import their own BIOS, it is never bundled. Besides the file
    names, any non-library entry of 1-16 MiB whose bytes carry a PS2 ROM directory (a "RESET"
    entry and a "ROMVER" entry on 16-byte boundaries) is rejected, whatever it is called
  * optionally (--expect-sha256 ENTRY=DIGEST) that a prebuilt libretro core in the APK is exactly the pinned
    binary recorded in NOTICE / scripts/fetch-disc-cores.sh
"""

from __future__ import annotations

import argparse
import hashlib
import pathlib
import struct
import sys
import zipfile
from collections import Counter

ROM_EXTENSIONS = (
    ".z64", ".n64", ".v64", ".sfc", ".smc", ".iso", ".cso", ".pbp", ".gcm", ".rvz", ".ciso",
    ".chd", ".cue", ".zso",
)

# PlayStation 2 BIOS add-on files (the core reads/writes these next to a BIOS image). Never bundled.
PS2_BIOS_EXTENSIONS = (".rom1", ".rom2", ".mec", ".nvm")
PS2_BIOS_MIN_BYTES = 1 * 1024 * 1024
PS2_BIOS_MAX_BYTES = 16 * 1024 * 1024

# Upstream Mupen64Plus-AE ships this diagnostic asset; CoreService uses it for
# disk-only games. It is allowed only at this exact path with this exact digest.
KNOWN_DIAGNOSTICS = {
    "assets/mupen64plus_data/m64p_test_rom.v64": (
        "b5fe9d650a67091c97838386f5102ad94c79232240f9c5bcc72334097d76224c"
    ),
}

# ABI -> (EI_CLASS, e_machine)
ELF_EXPECTATIONS = {
    "arm64-v8a": (2, 183),
    "armeabi-v7a": (1, 40),
    "x86": (1, 3),
    "x86_64": (2, 62),
}


def _elf_problem(abi: str, data: bytes) -> str | None:
    if abi not in ELF_EXPECTATIONS:
        return f"no ELF expectation known for ABI {abi}"
    if len(data) < 20 or data[:4] != b"\x7fELF":
        return "not an ELF image"
    expected_class, expected_machine = ELF_EXPECTATIONS[abi]
    if data[5] != 1:
        return "ELF is not little-endian"
    machine = struct.unpack_from("<H", data, 18)[0]
    if data[4] != expected_class or machine != expected_machine:
        return (
            f"ELF class/machine {data[4]}/{machine} does not match "
            f"expected {expected_class}/{expected_machine}"
        )
    return None


def looks_like_ps2_bios(data: bytes) -> bool:
    """True if the bytes carry a PS2 ROM directory: 16-byte-aligned "RESET" and "ROMVER" entries.

    This is the same structure the LRPS2 core looks for when it scans for a BIOS, so it catches a BIOS dump
    under any file name. Ordinary binaries do not contain both 10-byte NUL-padded names on 16-byte boundaries.
    """
    reset = b"RESET" + b"\x00" * 5
    romver = b"ROMVER" + b"\x00" * 4
    for needle in (reset, romver):
        start = 0
        found = False
        while True:
            i = data.find(needle, start)
            if i < 0:
                break
            if i % 16 == 0:
                found = True
                break
            start = i + 1
        if not found:
            return False
    return True


def _unsafe(name: str) -> bool:
    if name.startswith(("/", "\\")) or "\\" in name:
        return True
    return ".." in name.split("/")


def verify_apk(
    path: pathlib.Path,
    expected_abis: set[str],
    required_libraries: set[str],
    allowed_diagnostics: dict[str, str] | None = None,
    expected_sha256: dict[str, str] | None = None,
) -> list[str]:
    """Return a list of failure descriptions; an empty list means success."""
    path = pathlib.Path(path)
    if allowed_diagnostics is None:
        allowed_diagnostics = KNOWN_DIAGNOSTICS
    if not path.is_file():
        return [f"APK not found: {path}"]

    problems: list[str] = []
    try:
        zf = zipfile.ZipFile(path)
    except zipfile.BadZipFile:
        return [f"{path} is not a valid ZIP/APK"]

    with zf:
        names = [i.filename for i in zf.infolist()]

        for name, count in Counter(names).items():
            if count > 1:
                problems.append(f"duplicate ZIP entry: {name} ({count}x)")
        for name in sorted(set(names)):
            if _unsafe(name):
                problems.append(f"unsafe ZIP entry name: {name!r}")

        nameset = set(names)
        if "AndroidManifest.xml" not in nameset:
            problems.append("AndroidManifest.xml is missing")
        if not any(n.startswith("classes") and n.endswith(".dex") and "/" not in n for n in nameset):
            problems.append("no classes*.dex entry found")

        for abi in sorted(expected_abis):
            for lib in sorted(required_libraries):
                entry = f"lib/{abi}/{lib}"
                if entry not in nameset:
                    problems.append(f"missing native library for {abi}: {lib}")
                    continue
                problem = _elf_problem(abi, zf.read(entry)[:64])
                if problem:
                    problems.append(f"{entry}: {problem} (ELF check)")

        for entry, want in sorted((expected_sha256 or {}).items()):
            if entry not in nameset:
                problems.append(f"pinned entry missing: {entry}")
                continue
            got = hashlib.sha256(zf.read(entry)).hexdigest()
            if got != want.lower():
                problems.append(f"{entry}: SHA-256 {got} does not match pinned {want.lower()}")

        for info in zf.infolist():
            name = info.filename
            lower = name.lower()
            if lower.endswith(PS2_BIOS_EXTENSIONS):
                problems.append(f"PlayStation 2 BIOS add-on file bundled: {name}")
                continue
            if (
                not name.startswith("lib/")
                and not lower.endswith((".dex", ".arsc", ".xml", ".png", ".webp", ".jpg", ".ogg", ".mp3", ".ttf"))
                and PS2_BIOS_MIN_BYTES <= info.file_size <= PS2_BIOS_MAX_BYTES
                and looks_like_ps2_bios(zf.read(name))
            ):
                problems.append(f"PlayStation 2 BIOS image bundled: {name}")

        for name in sorted(nameset):
            if not name.lower().endswith(ROM_EXTENSIONS):
                continue
            if name not in allowed_diagnostics:
                problems.append(f"unexpected ROM-like file bundled: {name}")
                continue
            digest = hashlib.sha256(zf.read(name)).hexdigest()
            if digest != allowed_diagnostics[name]:
                problems.append(
                    f"ROM-like file {name} is on the diagnostic allow-list by path but "
                    f"its SHA-256 {digest} does not match {allowed_diagnostics[name]}"
                )

    return problems


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("apk", type=pathlib.Path)
    p.add_argument("--abis", required=True, help="comma-separated expected ABIs")
    p.add_argument("--libs", required=True, help="comma-separated required library file names")
    p.add_argument(
        "--allow-diagnostic",
        action="append",
        default=[],
        metavar="PATH=SHA256",
        help="extra exact-path/digest diagnostic allowance (default list is always included)",
    )
    p.add_argument(
        "--expect-sha256",
        action="append",
        default=[],
        metavar="ENTRY=SHA256",
        help="require this APK entry (e.g. lib/arm64-v8a/liblrps2_libretro.so) to have exactly this SHA-256",
    )
    args = p.parse_args(argv)

    pinned: dict[str, str] = {}
    for item in args.expect_sha256:
        entry, _, digest = item.partition("=")
        if not entry or len(digest) != 64:
            p.error(f"--expect-sha256 needs ENTRY=<64-hex SHA-256>, got {item!r}")
        pinned[entry] = digest.lower()

    allowed = dict(KNOWN_DIAGNOSTICS)
    for item in args.allow_diagnostic:
        entry, _, digest = item.partition("=")
        if not entry or len(digest) != 64:
            p.error(f"--allow-diagnostic needs PATH=<64-hex SHA-256>, got {item!r}")
        allowed[entry] = digest.lower()

    problems = verify_apk(
        args.apk,
        {a for a in args.abis.split(",") if a},
        {l for l in args.libs.split(",") if l},
        allowed,
        pinned,
    )
    if problems:
        print(f"FAIL: {len(problems)} problem(s) in {args.apk}")
        for m in problems:
            print(f"  - {m}")
        return 1
    print(f"PASS: payload checks passed for {args.apk} (structure only; not a runtime result)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
