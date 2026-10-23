"""Tests for the SNES frontend pixel conversion (app/src/main/cpp/snes_color.h).

The header is plain C, so the test compiles a tiny program against it with the host C compiler and checks
what it prints. Regression: a shift-only conversion maxed out at 248, so pure white rendered as light grey.
No ROM data is used.
"""

import pathlib
import shutil
import subprocess
import tempfile
import unittest

CPP_DIR = pathlib.Path(__file__).resolve().parents[2] / "app" / "src" / "main" / "cpp"
CC = shutil.which("cc") or shutil.which("gcc") or shutil.which("clang")

PROGRAM = r"""
#include <stdio.h>
#include "snes_color.h"
int main(void) {
    printf("e5"); for (uint32_t v = 0; v < 32; v++) printf(" %u", vl_expand5(v)); printf("\n");
    printf("e6"); for (uint32_t v = 0; v < 64; v++) printf(" %u", vl_expand6(v)); printf("\n");
    printf("565 %08x %08x %08x %08x %08x\n", vl_from_rgb565(0xFFFF), vl_from_rgb565(0x0000), vl_from_rgb565(0xF800), vl_from_rgb565(0x07E0), vl_from_rgb565(0x001F));
    printf("1555 %08x %08x %08x %08x %08x\n", vl_from_0rgb1555(0x7FFF), vl_from_0rgb1555(0x0000), vl_from_0rgb1555(0x7C00), vl_from_0rgb1555(0x03E0), vl_from_0rgb1555(0x001F));
    printf("8888 %08x %08x\n", vl_from_xrgb8888(0x00FF8040u), vl_from_xrgb8888(0x00000000u));
    return 0;
}
"""


@unittest.skipUnless(CC, "no host C compiler available")
class SnesColorTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with tempfile.TemporaryDirectory() as tmp:
            src = pathlib.Path(tmp) / "t.c"
            exe = pathlib.Path(tmp) / "t"
            src.write_text(PROGRAM)
            subprocess.run([CC, "-Wall", "-Werror", "-I", str(CPP_DIR), str(src), "-o", str(exe)], check=True)
            out = subprocess.run([str(exe)], check=True, capture_output=True, text=True).stdout
        cls.rows = {line.split()[0]: line.split()[1:] for line in out.splitlines()}

    def test_expand_reaches_full_range(self):
        e5 = [int(x) for x in self.rows["e5"]]
        e6 = [int(x) for x in self.rows["e6"]]
        self.assertEqual((e5[0], e5[31]), (0, 255))
        self.assertEqual((e6[0], e6[63]), (0, 255))
        self.assertEqual(e5, sorted(set(e5)), "5-bit expansion must be strictly increasing")
        self.assertEqual(e6, sorted(set(e6)), "6-bit expansion must be strictly increasing")

    def test_rgb565_primaries(self):
        white, black, red, green, blue = self.rows["565"]
        self.assertEqual(white, "ffffffff")  # alpha ff, B/G/R all 255 (shift-only gave fff8f8f8)
        self.assertEqual(black, "ff000000")
        self.assertEqual(red, "ff0000ff")    # packed 0xAABBGGRR: R in the low byte
        self.assertEqual(green, "ff00ff00")
        self.assertEqual(blue, "ffff0000")

    def test_0rgb1555_primaries(self):
        white, black, red, green, blue = self.rows["1555"]
        self.assertEqual(white, "ffffffff")
        self.assertEqual(black, "ff000000")
        self.assertEqual(red, "ff0000ff")
        self.assertEqual(green, "ff00ff00")
        self.assertEqual(blue, "ffff0000")

    def test_xrgb8888_is_passed_through_exactly(self):
        mixed, black = self.rows["8888"]
        self.assertEqual(mixed, "ff4080ff")  # R=ff G=80 B=40
        self.assertEqual(black, "ff000000")


if __name__ == "__main__":
    unittest.main()
