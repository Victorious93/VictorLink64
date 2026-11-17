"""Tests for the PlayStation 2 additions to the APK payload verifier (Victorious Framework).

All fixtures are synthetic. The "BIOS" bytes only imitate the ROM-directory layout; no real BIOS is used.
"""

import hashlib
import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

import verify_apk_payload as v  # noqa: E402
from test_verify_apk_payload import ABIS, DIAG, LIBS, ApkBuilder, elf  # noqa: E402


def fake_bios(size=4 * 1024 * 1024, table_at=0x200) -> bytes:
    img = bytearray(size)
    pos = table_at
    for name, sz in ((b"RESET", 0x100), (b"ROMDIR", 0x40), (b"ROMVER", 0x10)):
        img[pos:pos + len(name)] = name
        img[pos + 12:pos + 16] = sz.to_bytes(4, "little")
        pos += 16
    img[0x140:0x14E] = b"0160AC20040614"
    return bytes(img)


class Ps2VerifierTests(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.dir = self._tmp.name
        self.addCleanup(self._tmp.cleanup)

    def run_verify(self, builder, **kw):
        kw.setdefault("expected_abis", ABIS)
        kw.setdefault("required_libraries", LIBS)
        kw.setdefault("allowed_diagnostics", DIAG)
        return v.verify_apk(builder.write(self.dir), **kw)

    def test_bios_detector_matches_the_structure_not_the_name(self):
        self.assertTrue(v.looks_like_ps2_bios(fake_bios()))
        self.assertTrue(v.looks_like_ps2_bios(fake_bios(size=8 * 1024 * 1024, table_at=0x1F0010)))
        self.assertFalse(v.looks_like_ps2_bios(bytes(4 * 1024 * 1024)))
        self.assertFalse(v.looks_like_ps2_bios(b"RESET\0\0\0\0\0 and ROMVER\0\0\0\0 but not aligned".rjust(64, b"x")))

    def test_bios_bundled_under_any_name_is_rejected(self):
        for name in ("assets/scph10000.bin", "assets/data/blob.dat", "res/raw/x"):
            with self.subTest(name=name):
                b = ApkBuilder().valid().add(name, fake_bios())
                self.assertTrue(any("BIOS image bundled" in m for m in self.run_verify(b)), name)

    def test_bios_addon_files_are_rejected(self):
        for name in ("assets/scph39001.nvm", "assets/x.mec", "assets/y.rom1", "assets/y.ROM2"):
            with self.subTest(name=name):
                b = ApkBuilder().valid().add(name, b"x")
                self.assertTrue(any("BIOS add-on" in m for m in self.run_verify(b)), name)

    def test_ps2_disc_images_are_rejected(self):
        for ext in ("chd", "cue", "iso", "cso", "zso"):
            with self.subTest(ext=ext):
                b = ApkBuilder().valid().add(f"assets/game.{ext}", b"x")
                self.assertTrue(any("ROM-like" in m for m in self.run_verify(b)), ext)

    def test_ordinary_large_assets_pass(self):
        b = ApkBuilder().valid().add("assets/music.dat", bytes(range(256)) * 20000)
        self.assertEqual(self.run_verify(b), [])

    def test_native_libraries_are_not_scanned_as_bios(self):
        # Cores are big binaries under lib/; only their ELF headers are checked.
        b = ApkBuilder().valid().add("lib/arm64-v8a/libcore.so", elf("arm64-v8a") + fake_bios())
        self.assertEqual(self.run_verify(b), [])

    def test_pinned_core_digest(self):
        core = elf("arm64-v8a") + b"lrps2-test-core"
        good = hashlib.sha256(core).hexdigest()
        entry = "lib/arm64-v8a/liblrps2_libretro.so"
        b = ApkBuilder().valid().add(entry, core)
        self.assertEqual(self.run_verify(b, expected_sha256={entry: good}), [])
        bad = self.run_verify(ApkBuilder().valid().add(entry, core + b"!"), expected_sha256={entry: good})
        self.assertTrue(any("does not match pinned" in m for m in bad))
        missing = self.run_verify(ApkBuilder().valid(), expected_sha256={entry: good})
        self.assertTrue(any("pinned entry missing" in m for m in missing))

    def test_cli_accepts_pins_and_checks_format(self):
        core = elf("arm64-v8a") + b"core"
        entry = "lib/arm64-v8a/liblrps2_libretro.so"
        apk = ApkBuilder().valid().add(entry, core).write(self.dir)
        base = [str(apk), "--abis", ",".join(sorted(ABIS)), "--libs", "libfoo.so"]
        self.assertEqual(v.main(base + ["--expect-sha256", f"{entry}={hashlib.sha256(core).hexdigest()}"]), 0)
        self.assertEqual(v.main(base + ["--expect-sha256", f"{entry}={'0' * 64}"]), 1)
        with self.assertRaises(SystemExit):
            v.main(base + ["--expect-sha256", "not-a-pin"])


if __name__ == "__main__":
    unittest.main()
