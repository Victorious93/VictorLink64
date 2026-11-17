package org.victoriousframework.victorlink64.controls

/** Minimal string key-value storage; SharedPreferences in the app, a map in unit tests. */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
}

/** Text form of a layout: one `id,ax,ay,ox,oy,scale,opacity` line per control. Tolerant of damaged or foreign data. */
object LayoutCodec {
    fun encode(placements: Collection<Placement>): String =
        placements.joinToString("\n") { "${it.id},${it.ax},${it.ay},${it.ox},${it.oy},${it.scale},${it.opacity}" }

    fun decode(text: String?): List<Placement> {
        if (text.isNullOrBlank()) return emptyList()
        return text.lineSequence().mapNotNull { line ->
            val f = line.trim().split(',')
            if (f.size != 7 || f[0].isBlank()) return@mapNotNull null
            val n = f.drop(1).map { it.toFloatOrNull()?.takeIf { v -> v.isFinite() } ?: return@mapNotNull null }
            sanitize(Placement(f[0], n[0], n[1], n[2], n[3], n[4], n[5]))
        }.toList()
    }

    fun sanitize(p: Placement) = Placement(
        p.id, snapAnchor(p.ax), snapAnchor(p.ay), p.ox.coerceIn(-3f, 3f), p.oy.coerceIn(-3f, 3f),
        p.scale.coerceIn(Placement.MIN_SCALE, Placement.MAX_SCALE), p.opacity.coerceIn(Placement.MIN_OPACITY, 1f),
    )

    private fun snapAnchor(a: Float) = if (a < 0.25f) 0f else if (a > 0.75f) 1f else 0.5f
}

/**
 * Saved on-screen layouts, one per console and orientation (the same screen offers very different room in landscape
 * and portrait). A control missing from the saved data uses its default, so controls added in future updates appear
 * without wiping a user's customised layout, and ids that no longer exist are dropped.
 */
class LayoutStore(private val kv: KeyValueStore) {
    private fun key(console: Console, landscape: Boolean) = "layout.${console.id}.${if (landscape) "land" else "port"}"

    fun defaultsFor(console: Console, landscape: Boolean): Map<String, Placement> =
        ConsoleLayouts.defaults(console, landscape).associateBy { it.id }

    fun load(console: Console, landscape: Boolean): Map<String, Placement> {
        val defaults = defaultsFor(console, landscape)
        val saved = LayoutCodec.decode(kv.getString(key(console, landscape))).associateBy { it.id }
        return defaults.mapValues { (id, d) -> saved[id] ?: d }
    }

    fun save(console: Console, landscape: Boolean, placements: Collection<Placement>) {
        val known = defaultsFor(console, landscape).keys
        val clean = placements.filter { it.id in known }.map(LayoutCodec::sanitize)
        kv.putString(key(console, landscape), LayoutCodec.encode(clean))
    }

    fun hasCustomLayout(console: Console, landscape: Boolean) = kv.getString(key(console, landscape)) != null

    /** Back to the built-in layout for this console and orientation. */
    fun reset(console: Console, landscape: Boolean) = kv.remove(key(console, landscape))
}

enum class StickMode { FIXED, FLOATING }

/** Global overlay settings shared by every console. Values are clamped on read so bad stored data cannot break play. */
class OverlaySettings(private val kv: KeyValueStore) {
    private fun float(key: String, def: Float, lo: Float, hi: Float) =
        (kv.getString(key)?.toFloatOrNull()?.takeIf { it.isFinite() } ?: def).coerceIn(lo, hi)

    private fun bool(key: String, def: Boolean) = kv.getString(key)?.let { it == "1" } ?: def
    private fun put(key: String, v: Boolean) = kv.putString(key, if (v) "1" else "0")

    var masterOpacity: Float
        get() = float("opacity", 0.8f, 0.2f, 1f)
        set(v) = kv.putString("opacity", v.coerceIn(0.2f, 1f).toString())

    var masterScale: Float
        get() = float("scale", 1f, 0.6f, 1.6f)
        set(v) = kv.putString("scale", v.coerceIn(0.6f, 1.6f).toString())

    var stickDeadzone: Float
        get() = float("deadzone", 0.10f, 0f, 0.4f)
        set(v) = kv.putString("deadzone", v.coerceIn(0f, 0.4f).toString())

    var stickMode: StickMode
        get() = if (kv.getString("stickmode") == "floating") StickMode.FLOATING else StickMode.FIXED
        set(v) = kv.putString("stickmode", if (v == StickMode.FLOATING) "floating" else "fixed")

    var haptics: Boolean
        get() = bool("haptics", true)
        set(v) = put("haptics", v)

    var analogTriggers: Boolean
        get() = bool("analogtriggers", true)
        set(v) = put("analogtriggers", v)

    var hideWithController: Boolean
        get() = bool("hidewithcontroller", true)
        set(v) = put("hidewithcontroller", v)

    fun stickConfig() = StickConfig(deadzone = stickDeadzone).sanitized()
}
