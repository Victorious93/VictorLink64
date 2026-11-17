package org.victoriousframework.victorlink64.controls

import kotlin.math.abs

/** The usable screen rectangle in pixels (inside notches and system bars). */
data class Area(val left: Float, val top: Float, val width: Float, val height: Float) {
    /** One "edge unit": the short side of the area. Control sizes and offsets are fractions of it. */
    val edge get() = minOf(width, height)
    val right get() = left + width
    val bottom get() = top + height
    val landscape get() = width > height
}

/** A control resolved to pixels. Centre ([cx], [cy]) and size ([w], [h]) are mutable so edit mode can drag/resize. */
class PlacedControl(val def: ControlDef, var cx: Float, var cy: Float, var w: Float, var h: Float, var opacity: Float) {
    val radius get() = minOf(w, h) / 2
    val left get() = cx - w / 2
    val top get() = cy - h / 2
    val right get() = cx + w / 2
    val bottom get() = cy + h / 2
}

object LayoutGeometry {
    private fun dir(anchor: Float) = if (anchor > 0.75f) -1f else 1f

    /** Pixel centre of [p] inside [area]. */
    fun centre(p: Placement, area: Area): Pair<Float, Float> {
        val e = area.edge
        return Pair(area.left + p.ax * area.width + dir(p.ax) * p.ox * e, area.top + p.ay * area.height + dir(p.ay) * p.oy * e)
    }

    /**
     * Resolves every control to pixels. [placements] maps control id to its saved (or default) placement; [masterScale]
     * is the user's global size setting. Controls are clamped inside [area] so a layout saved on a bigger window can
     * never push a button off a smaller one.
     */
    fun place(defs: List<ControlDef>, placements: Map<String, Placement>, area: Area, masterScale: Float): List<PlacedControl> =
        defs.mapNotNull { d ->
            val p = placements[d.id] ?: return@mapNotNull null
            val s = p.scale.coerceIn(Placement.MIN_SCALE, Placement.MAX_SCALE) * masterScale
            val w = d.size * area.edge * s
            val h = d.height * area.edge * s
            val (cx, cy) = centre(p, area)
            PlacedControl(d, cx, cy, w, h, p.opacity).also { clamp(it, area) }
        }

    /** Keeps [c] fully inside [area]; a control larger than the area is centred. */
    fun clamp(c: PlacedControl, area: Area) {
        c.cx = if (c.w >= area.width) area.left + area.width / 2 else c.cx.coerceIn(area.left + c.w / 2, area.right - c.w / 2)
        c.cy = if (c.h >= area.height) area.top + area.height / 2 else c.cy.coerceIn(area.top + c.h / 2, area.bottom - c.h / 2)
    }

    /**
     * Converts a dragged/resized control back to a saved [Placement], re-anchoring to the nearest edge on each axis
     * (centre anchor within 8% of the middle) so the layout keeps following the screen after rotation or resize.
     */
    fun toPlacement(c: PlacedControl, area: Area, old: Placement, masterScale: Float): Placement {
        val e = area.edge
        val fx = (c.cx - area.left) / area.width
        val fy = (c.cy - area.top) / area.height
        val ax = if (abs(fx - 0.5f) < 0.08f) 0.5f else if (fx < 0.5f) 0f else 1f
        val ay = if (abs(fy - 0.5f) < 0.08f) 0.5f else if (fy < 0.5f) 0f else 1f
        val ox = (c.cx - (area.left + ax * area.width)) / e * dir(ax)
        val oy = (c.cy - (area.top + ay * area.height)) / e * dir(ay)
        val scale = (c.w / (c.def.size * e * masterScale)).coerceIn(Placement.MIN_SCALE, Placement.MAX_SCALE)
        return old.copy(ax = ax, ay = ay, ox = ox, oy = oy, scale = scale, opacity = c.opacity)
    }
}
