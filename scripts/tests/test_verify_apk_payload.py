"""Tests for the Victorious Framework APK payload verifier.

All fixtures are synthetic ZIPs. No commercial ROM data is used or required.
"""

import hashlib
import io
import pathlib
import struct
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

import verify_apk_payload as v  # noqa: E402

ABIS = {"arm64-v8a", "armeabi-v7a", "x86", "x86_64"}
LIBS = {"libfoo.so"}
DIAG_PATH = "assets/mupen64plus_data/m64p_test_rom.v64"
DIAG_BYTES = b"\x80\x37\x12\x40" + b"diagnostic" * 8
DIAG_SHA256 = hashlib.sha256(DIAG_BYTES).hexdigest()
DIAG = {DIAG_PATH: DIAG_SHA256}


def elf(abi: str) -> bytes:
    """Minimal ELF header with the e_machine / EI_CLASS for the given ABI."""
    machine, cls = {
        "arm64-v8a": (183, 2),
        "armeabi-v7a": (40, 1),
        "x86": (3, 1),
        "x86_64": (62, 2),
    }[abi]
    ident = b"\x7fELF" + bytes([cls, 1, 1]) + b"\x00" * 9
    return ident + struct.pack("<HH", 3, machine) + b"\x00" * 32


class ApkBuilder:
    def __init__(self):
        self.entries = []

    def add(self, name, data=b"x"):
        self.entries.append((name, data))
        return self

    def valid(self):
        self.add("AndroidManifest.xml").add("classes.dex")
        for abi in ABIS:
            for lib in LIBS:
                self.add(f"lib/{abi}/{lib}", elf(abi))
        return self

    def write(self, directory) -> pathlib.Path:
        path = pathlib.Path(directory) / "test.apk"
        with zipfile.ZipFile(path, "w") as zf:
            for name, data in self.entries:
                zf.writestr(name, data)
        return path


class VerifyApkTests(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.dir = self._tmp.name
        self.addCleanup(self._tmp.cleanup)

    def run_verify(self, builder, **kw):
        kw.setdefault("expected_abis", ABIS)
        kw.setdefault("required_libraries", LIBS)
        kw.setdefault("allowed_diagnostics", DIAG)
        return v.verify_apk(builder.write(self.dir), **kw)

    def test_valid_apk_passes(self):
        self.assertEqual(self.run_verify(ApkBuilder().valid()), [])

    def test_missing_manifest(self):
        b = ApkBuilder().valid()
        b.entries = [e for e in b.entries if e[0] != "AndroidManifest.xml"]
        self.assertTrue(any("AndroidManifest.xml" in m for m in self.run_verify(b)))

    def test_missing_dex(self):
        b = ApkBuilder().valid()
        b.entries = [e for e in b.entries if e[0] != "classes.dex"]
        self.assertTrue(any("dex" in m.lower() for m in self.run_verify(b)))

    def test_missing_library_for_one_abi(self):
        b = ApkBuilder().valid()
        b.entries = [e for e in b.entries if e[0] != "lib/x86/libfoo.so"]
        msgs = self.run_verify(b)
        self.assertTrue(any("x86" in m and "libfoo.so" in m for m in msgs))

    def test_wrong_elf_architecture(self):
        b = ApkBuilder().valid()
        b.entries = [e for e in b.entries if e[0] != "lib/arm64-v8a/libfoo.so"]
        b.add("lib/arm64-v8a/libfoo.so", elf("x86"))
        self.assertTrue(any("arm64-v8a" in m and "ELF" in m for m in self.run_verify(b)))

    def test_non_elf_library(self):
        b = ApkBuilder().valid()
        b.entries = [e for e in b.entries if e[0] != "lib/x86/libfoo.so"]
        b.add("lib/x86/libfoo.so", b"not an elf file at all")
        self.assertTrue(any("x86" in m and "ELF" in m for m in self.run_verify(b)))

    def test_unexpected_rom_rejected(self):
        for ext in ("z64", "n64", "v64"):
            with self.subTest(ext=ext):
                b = ApkBuilder().valid().add(f"assets/game.{ext}", b"\x80\x37\x12\x40")
                self.assertTrue(any("ROM" in m for m in self.run_verify(b)))

    def test_rom_extension_check_is_case_insensitive(self):
        b = ApkBuilder().valid().add("assets/GAME.Z64", b"\x80\x37\x12\x40")
        self.assertTrue(any("ROM" in m for m in self.run_verify(b)))

    def test_known_diagnostic_asset_allowed(self):
        b = ApkBuilder().valid().add(DIAG_PATH, DIAG_BYTES)
        self.assertEqual(self.run_verify(b), [])

    def test_diagnostic_path_with_different_content_rejected(self):
        b = ApkBuilder().valid().add(DIAG_PATH, b"\x80\x37\x12\x40 swapped content")
        self.assertTrue(any("SHA-256" in m for m in self.run_verify(b)))

    def test_diagnostic_content_at_other_path_rejected(self):
        b = ApkBuilder().valid().add("assets/elsewhere/m64p_test_rom.v64", DIAG_BYTES)
        self.assertTrue(any("ROM" in m for m in self.run_verify(b)))

    def test_zip_traversal_entry_rejected(self):
        b = ApkBuilder().valid().add("../evil.txt")
        self.assertTrue(any("unsafe" in m.lower() for m in self.run_verify(b)))

    def test_absolute_entry_rejected(self):
        b = ApkBuilder().valid().add("/etc/passwd")
        self.assertTrue(any("unsafe" in m.lower() for m in self.run_verify(b)))

    def test_duplicate_entries_rejected(self):
        b = ApkBuilder().valid().add("assets/dup.txt", b"a").add("assets/dup.txt", b"b")
        import warnings

        with warnings.catch_warnings():
            warnings.simplefilter("ignore")
            msgs = self.run_verify(b)
        self.assertTrue(any("duplicate" in m.lower() for m in msgs))

    def test_not_a_zip(self):
        path = pathlib.Path(self.dir) / "bad.apk"
        path.write_bytes(b"this is not a zip")
        msgs = v.verify_apk(path, ABIS, LIBS)
        self.assertTrue(any("not a valid ZIP" in m for m in msgs))

    def test_missing_file(self):
        msgs = v.verify_apk(pathlib.Path(self.dir) / "nope.apk", ABIS, LIBS)
        self.assertTrue(any("not found" in m.lower() for m in msgs))

    def test_cli_exit_codes(self):
        good = ApkBuilder().valid().write(self.dir)
        argv = [str(good), "--abis", ",".join(sorted(ABIS)), "--libs", "libfoo.so"]
        self.assertEqual(v.main(argv), 0)
        bad_dir = tempfile.mkdtemp(dir=self.dir)
        bad = ApkBuilder().add("AndroidManifest.xml").write(bad_dir)
        argv = [str(bad), "--abis", ",".join(sorted(ABIS)), "--libs", "libfoo.so"]
        self.assertEqual(v.main(argv), 1)


if __name__ == "__main__":
    unittest.main()
