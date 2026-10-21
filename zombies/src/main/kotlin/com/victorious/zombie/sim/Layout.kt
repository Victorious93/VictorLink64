package com.victorious.zombie.sim

import com.victorious.zombie.logic.ArmorPack
import com.victorious.zombie.logic.ArmorPacks

/** Fixed arena geometry shared by the simulation and the renderer. */
object Layout {
    const val W = 1280f
    const val H = 720f
    const val MIN_X = 80f
    const val MIN_Y = 60f
    const val MAX_X = W - 80f
    const val MAX_Y = H - 60f
    const val INTERACT_RANGE = 70f

    class Point(val x: Float, val y: Float)

    /** Window i: where the barricade stands (zombies must reach it) and where zombies spawn outside. */
    class Window(val inside: Point, val spawn: Point)

    val windows = listOf(
        Window(Point(W / 2, MAX_Y), Point(W / 2, H + 30)),
        Window(Point(W / 2, MIN_Y), Point(W / 2, -30f)),
        Window(Point(MIN_X, H / 2), Point(-30f, H / 2)),
        Window(Point(MAX_X, H / 2), Point(W + 30, H / 2)),
    )
    val box = Point(200f, 150f)
    val perk = Point(W - 200f, 150f)
    val forge = Point(W - 200f, H - 150f)
    val armorStations: List<Pair<Point, ArmorPack>> = ArmorPacks.ALL.mapIndexed { i, p -> Point(160f + i * 150f, H - 150f) to p }
}
