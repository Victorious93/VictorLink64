"""Tests for the graphics-options store shared by the libretro frontends (app/src/main/cpp/gfxopts.c).

The file is plain C (pthreads only), so the test compiles a tiny program against it with the host C compiler.
Covers what the cores depend on: variable lookup, the "variables changed" flag behind
RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE, the GL-pass selection, and reset between games. No ROM data is used.
Victorious Framework.
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
#include <string.h>
#include "gfxopts.h"

static void show(const char *tag, const char *v) { printf("%s %s\n", tag, v ? v : "(null)"); }

int main(void) {
    show("unset", gfx_get_var("ppsspp_internal_resolution"));
    printf("update0 %d\n", gfx_take_var_update());

    gfx_set_var("ppsspp_internal_resolution", "960x544");
    gfx_set_var("ppsspp_texture_filtering", "Linear");
    show("res", gfx_get_var("ppsspp_internal_resolution"));
    show("filter", gfx_get_var("ppsspp_texture_filtering"));
    printf("update1 %d\n", gfx_take_var_update());
    printf("update2 %d\n", gfx_take_var_update());

    gfx_set_var("ppsspp_internal_resolution", "960x544"); /* same value: not a change */
    printf("same %d\n", gfx_take_var_update());
    gfx_set_var("ppsspp_internal_resolution", "480x272");
    show("res2", gfx_get_var("ppsspp_internal_resolution"));
    printf("changed %d\n", gfx_take_var_update());

    /* earlier lookups stay valid for a while */
    const char *a = gfx_get_var("ppsspp_internal_resolution");
    const char *b = gfx_get_var("ppsspp_texture_filtering");
    printf("keep %s %s\n", a, b);

    char longv[200]; memset(longv, 'x', sizeof longv - 1); longv[sizeof longv - 1] = 0;
    gfx_set_var("long", longv);
    printf("longlen %zu\n", strlen(gfx_get_var("long")));

    int mode = -1; bool nearest = true;
    gfx_get_post(&mode, &nearest);
    printf("post0 %d %d\n", mode, nearest);
    gfx_set_post(4, true);
    gfx_get_post(&mode, &nearest);
    printf("post1 %d %d\n", mode, nearest);
    gfx_get_post(&mode, NULL);

    gfx_reset();
    show("afterreset", gfx_get_var("ppsspp_internal_resolution"));
    gfx_get_post(&mode, &nearest);
    printf("post2 %d %d\n", mode, nearest);
    printf("update3 %d\n", gfx_take_var_update());
    return 0;
}
"""


@unittest.skipUnless(CC, "no host C compiler available")
class GfxOptsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with tempfile.TemporaryDirectory() as tmp:
            src = pathlib.Path(tmp) / "t.c"
            exe = pathlib.Path(tmp) / "t"
            src.write_text(PROGRAM)
            subprocess.run([CC, "-Wall", "-Werror", "-I", str(CPP_DIR), str(src), str(CPP_DIR / "gfxopts.c"), "-o", str(exe), "-lpthread"], check=True)
            out = subprocess.run([str(exe)], check=True, capture_output=True, text=True).stdout
        cls.rows = {line.split(" ", 1)[0]: line.split(" ", 1)[1] for line in out.splitlines()}

    def test_unset_variable_is_null_so_the_core_keeps_its_default(self):
        self.assertEqual(self.rows["unset"], "(null)")
        self.assertEqual(self.rows["afterreset"], "(null)")

    def test_values_round_trip(self):
        self.assertEqual(self.rows["res"], "960x544")
        self.assertEqual(self.rows["filter"], "Linear")

    def test_update_flag_fires_once_per_change(self):
        self.assertEqual(self.rows["update0"], "0")
        self.assertEqual(self.rows["update1"], "1")
        self.assertEqual(self.rows["update2"], "0")
        self.assertEqual(self.rows["same"], "0")
        self.assertEqual(self.rows["changed"], "1")
        self.assertEqual(self.rows["res2"], "480x272")

    def test_recent_lookups_stay_valid(self):
        self.assertEqual(self.rows["keep"], "480x272 Linear")

    def test_long_values_are_truncated_not_overrun(self):
        self.assertEqual(self.rows["longlen"], "63")

    def test_post_effect_selection_and_reset(self):
        self.assertEqual(self.rows["post0"], "0 0")
        self.assertEqual(self.rows["post1"], "4 1")
        self.assertEqual(self.rows["post2"], "0 0")
        self.assertEqual(self.rows["update3"], "0")


if __name__ == "__main__":
    unittest.main()
