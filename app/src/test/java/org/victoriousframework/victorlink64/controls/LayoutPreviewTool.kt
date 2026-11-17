package org.victoriousframework.victorlink64.controls

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Developer aid, not a check: with VL64_LAYOUT_PREVIEW_DIR set it dumps every console's default layout geometry as
 * text (render with scripts/render_layout_preview.py) so a layout can be eyeballed without a device. Skipped otherwise.
 */
class LayoutPreviewTool {
    @Test fun dumpDefaultLayouts() {
        val dir = System.getenv("VL64_LAYOUT_PREVIEW_DIR")
        assumeTrue(dir != null)
        File(dir!!).mkdirs()
        for (c in Console.entries) for ((name, w, h) in listOf(Triple("land", 1000, 460), Triple("port", 460, 1000))) {
            val area = Area(0f, 0f, w.toFloat(), h.toFloat())
            val placements = ConsoleLayouts.defaults(c, w > h).associateBy { it.id }
            val lines = LayoutGeometry.place(ConsoleLayouts.defs(c), placements, area, 1f).joinToString("\n") {
                "${it.def.id} ${it.def.shape} ${it.def.accent} ${it.cx} ${it.cy} ${it.w} ${it.h}"
            }
            File(dir, "${c.id}-$name.txt").writeText("$w $h\n$lines\n")
        }
    }
}
