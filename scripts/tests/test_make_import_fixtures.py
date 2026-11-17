"""Victorious Framework: the fixture generator writes well-formed archives and nothing game-sized."""
import importlib.util
import io
import struct
import tarfile
import unittest
import zipfile
import zlib
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("make_import_fixtures", Path(__file__).resolve().parents[1] / "make_import_fixtures.py")
M = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(M)


def read_vint(b, i):
    v, shift = 0, 0
    while True:
        c = b[i]
        i += 1
        v |= (c & 0x7F) << shift
        shift += 7
        if not c & 0x80:
            return v, i


class FixtureTests(unittest.TestCase):
    def test_everything_is_tiny(self):
        for name, data in M.all_fixtures().items():
            self.assertLess(len(data), 64 * 1024, name)

    def test_zip_and_tar_open(self):
        fx = M.all_fixtures()
        with zipfile.ZipFile(io.BytesIO(fx["zipped-several-games.zip"])) as z:
            self.assertEqual(z.testzip(), None)
            self.assertIn("Fake N64 Game.z64", z.namelist())
        with tarfile.open(fileobj=io.BytesIO(fx["tarball.tar.gz"])) as t:
            self.assertIn("Fake SNES Game.sfc", t.getnames())

    def test_rar5_blocks_have_valid_crcs_and_data(self):
        rar = M.all_fixtures()["rar-several-games.rar"]
        self.assertTrue(rar.startswith(b"Rar!\x1a\x07\x01\x00"))
        i, names = 8, []
        while i < len(rar):
            crc = struct.unpack_from("<I", rar, i)[0]
            size, j = read_vint(rar, i + 4)
            self.assertEqual(crc, zlib.crc32(rar[i + 4:j + size]) & 0xFFFFFFFF)
            btype, k = read_vint(rar, j)
            flags, k = read_vint(rar, k)
            data_size = 0
            if btype == 2:
                data_size, k = read_vint(rar, k)
                _fflags, k = read_vint(rar, k)
                _usize, k = read_vint(rar, k)
                _attr, k = read_vint(rar, k)
                data_crc = struct.unpack_from("<I", rar, k)[0]
                k += 4
                _comp, k = read_vint(rar, k)
                _os, k = read_vint(rar, k)
                nlen, k = read_vint(rar, k)
                names.append(rar[k:k + nlen].decode())
                data_start = j + size
                self.assertEqual(data_crc, zlib.crc32(rar[data_start:data_start + data_size]) & 0xFFFFFFFF)
            i = j + size + data_size
        self.assertEqual(names, ["Fake N64 Game.z64", "Fake SNES Game.sfc", "readme.txt"])


if __name__ == "__main__":
    unittest.main()
