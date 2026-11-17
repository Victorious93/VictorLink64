#!/usr/bin/env python3
"""Victorious Framework: write tiny synthetic game-import test files for VictorLink64.

Everything here is made-up filler with just enough header for the Library to recognise the console. None of it is a
real game and none of it will boot in an emulator; it exists to test importing, detecting and archive handling
without ever touching real ROMs. Usage:  python3 scripts/make_import_fixtures.py OUT_DIR
Then copy OUT_DIR to the phone and use Library > add files / pick folder.
"""
import gzip
import io
import struct
import sys
import tarfile
import zipfile
import zlib
from pathlib import Path


def n64(order="z64", size=4096):
    magic = {"z64": b"\x80\x37\x12\x40", "v64": b"\x37\x80\x40\x12", "n64": b"\x40\x12\x37\x80"}[order]
    return magic + bytes((i * 31) & 0xFF for i in range(4, size))


def snes():
    rom = bytearray(0x8000)
    hdr = 0x7FC0
    rom[hdr:hdr + 21] = b"VL64 TEST CART       "
    rom[hdr + 0x15] = 0x20
    checksum, complement = 0x1234, 0x1234 ^ 0xFFFF
    struct.pack_into("<HH", rom, hdr + 0x1C, complement, checksum)
    return bytes(rom)


def gamecube_iso():
    rom = bytearray(0x400)
    rom[0:6] = b"GVLE01"
    rom[0x1C:0x20] = b"\xC2\x33\x9F\x3D"
    return bytes(rom)


def psp_iso():
    rom = bytearray(0x8800)
    rom[0x8000] = 1
    rom[0x8001:0x8006] = b"CD001"
    rom[0x8008:0x8028] = b"PSP GAME".ljust(32)
    return bytes(rom)


def psp_cso():
    return b"CISO" + struct.pack("<I", 0x18) + bytes(56)


def rvz():
    return b"RVZ\x01" + bytes(60)


def vint(n):
    out = bytearray()
    while n >= 0x80:
        out.append((n & 0x7F) | 0x80)
        n >>= 7
    out.append(n)
    return bytes(out)


def _block(btype, flags, body):
    after = vint(btype) + vint(flags) + body
    rest = vint(len(after)) + after
    return struct.pack("<I", zlib.crc32(rest) & 0xFFFFFFFF) + rest


def rar5_stored(files):
    """A RAR 5 archive using the 'store' method (no compression), written by hand so no RAR tool is needed."""
    out = bytearray(b"Rar!\x1a\x07\x01\x00")
    out += _block(1, 0, vint(0))
    for name, data in files:
        nb = name.encode()
        body = (vint(len(data)) + vint(0x4) + vint(len(data)) + vint(0x81A4) + struct.pack("<I", zlib.crc32(data) & 0xFFFFFFFF)
                + vint(0) + vint(1) + vint(len(nb)) + nb)
        out += _block(2, 0x2, body) + data
    out += _block(5, 0, vint(0))
    return bytes(out)


def zip_bytes(files):
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        for name, data in files:
            z.writestr(name, data)
    return buf.getvalue()


def targz_bytes(files):
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w:gz") as t:
        for name, data in files:
            info = tarfile.TarInfo(name)
            info.size = len(data)
            t.addfile(info, io.BytesIO(data))
    return buf.getvalue()


def all_fixtures():
    notes = b"synthetic test file - not a game\n"
    pack = [("Fake N64 Game.z64", n64()), ("Fake SNES Game.sfc", snes()), ("readme.txt", notes)]
    return {
        "Fake N64 Game.z64": n64(),
        "Fake N64 Game (byte-swapped).v64": n64("v64"),
        "Fake SNES Game.sfc": snes(),
        "Fake GameCube Disc.iso": gamecube_iso(),
        "Fake PSP Disc.iso": psp_iso(),
        "Fake PSP Compressed.cso": psp_cso(),
        "Fake GameCube Compressed.rvz": rvz(),
        "zipped-single.zip": zip_bytes([("Zipped N64 Game.z64", n64()), ("cover.png", b"\x89PNG")]),
        "zipped-several-games.zip": zip_bytes(pack),
        "rar-several-games.rar": rar5_stored(pack),
        "tarball.tar.gz": targz_bytes(pack),
        "single-gz.iso.gz": gzip.compress(gamecube_iso()),
    }


def main(argv):
    if len(argv) != 2:
        print(__doc__)
        return 2
    out = Path(argv[1])
    out.mkdir(parents=True, exist_ok=True)
    for name, data in all_fixtures().items():
        (out / name).write_bytes(data)
        print(f"{name}  ({len(data)} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
