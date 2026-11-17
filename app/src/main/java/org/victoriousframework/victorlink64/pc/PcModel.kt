package org.victoriousframework.victorlink64.pc

import org.json.JSONArray
import org.json.JSONObject

/*
 * Victorious Framework: VictorLink64 "PC Games" data model.
 * Plain Kotlin (no Android classes) so it can be unit tested on the JVM.
 * Streaming itself is done by the vendored Moonlight core (:moonlight, GPL-3.0).
 */

/** A gaming PC running Sunshine (or NVIDIA GameStream) that this phone knows about. */
data class PcHost(
    val uuid: String,
    val name: String,
    /** Address used to reach the PC, e.g. "192.168.1.20". */
    val address: String,
    val httpPort: Int = DEFAULT_HTTP_PORT,
    val httpsPort: Int = DEFAULT_HTTPS_PORT,
    /** PEM/Base64 DER of the PC's certificate once paired; null until paired. */
    val serverCertB64: String? = null,
    val manual: Boolean = false,
) {
    val paired get() = serverCertB64 != null

    fun toJson(): JSONObject = JSONObject()
        .put("uuid", uuid).put("name", name).put("address", address)
        .put("httpPort", httpPort).put("httpsPort", httpsPort)
        .put("cert", serverCertB64 ?: JSONObject.NULL).put("manual", manual)

    companion object {
        const val DEFAULT_HTTP_PORT = 47989
        const val DEFAULT_HTTPS_PORT = 47984

        fun fromJson(o: JSONObject) = PcHost(
            uuid = o.getString("uuid"), name = o.optString("name", "PC"),
            address = o.getString("address"),
            httpPort = o.optInt("httpPort", DEFAULT_HTTP_PORT),
            httpsPort = o.optInt("httpsPort", DEFAULT_HTTPS_PORT),
            serverCertB64 = if (o.isNull("cert")) null else o.optString("cert").ifEmpty { null },
            manual = o.optBoolean("manual", false),
        )

        fun listToJson(hosts: List<PcHost>): String = JSONArray().also { a -> hosts.forEach { a.put(it.toJson()) } }.toString()

        fun listFromJson(s: String?): List<PcHost> {
            if (s.isNullOrBlank()) return emptyList()
            return runCatching {
                val a = JSONArray(s)
                (0 until a.length()).mapNotNull { i -> runCatching { fromJson(a.getJSONObject(i)) }.getOrNull() }
            }.getOrDefault(emptyList())
        }

        /** Insert or replace by uuid, keeping a known certificate if the update has none. */
        fun upsert(hosts: List<PcHost>, h: PcHost): List<PcHost> {
            val old = hosts.firstOrNull { it.uuid == h.uuid }
            val merged = if (old != null && h.serverCertB64 == null) h.copy(serverCertB64 = old.serverCertB64, manual = old.manual || h.manual) else h
            return if (old == null) hosts + merged else hosts.map { if (it.uuid == h.uuid) merged else it }
        }
    }
}

/**
 * Parses what the user typed in "Add a PC by address": an IPv4/hostname with an optional
 * ":port". IPv6 literals may be written in [brackets]. Returns null when it can't be used.
 */
fun parseHostInput(raw: String): Pair<String, Int>? {
    val s = raw.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
    if (s.isEmpty() || s.any { it.isWhitespace() }) return null
    if (s.startsWith("[")) {
        val end = s.indexOf(']'); if (end < 2) return null
        val host = s.substring(1, end)
        val rest = s.substring(end + 1)
        val port = if (rest.isEmpty()) PcHost.DEFAULT_HTTP_PORT else rest.removePrefix(":").toIntOrNull() ?: return null
        return if (port in 1..65535) host to port else null
    }
    val colons = s.count { it == ':' }
    if (colons > 1) return s to PcHost.DEFAULT_HTTP_PORT // bare IPv6
    val host = s.substringBefore(':')
    val port = if (colons == 1) s.substringAfter(':').toIntOrNull() ?: return null else PcHost.DEFAULT_HTTP_PORT
    if (host.isEmpty() || port !in 1..65535) return null
    if (!host.all { it.isLetterOrDigit() || it == '.' || it == '-' }) return null
    return host to port
}

enum class StreamRes(val label: String, val width: Int, val height: Int) {
    P480("480p", 854, 480), P720("720p", 1280, 720), P1080("1080p", 1920, 1080);
}

enum class StreamCodec(val label: String) { AUTO("Automatic"), H264("H.264 (works everywhere)"), HEVC("HEVC / H.265 (sharper, less data)") }

enum class FramePacing(val label: String, val detail: String) {
    LOW_LAG("Lowest lag", "Shows each frame as soon as it arrives. May tear a little."),
    BALANCED("Balanced", "Good for most games."),
    SMOOTH("Smoothest", "Evens out frames. Adds a little lag."),
}

data class StreamSettings(
    val res: StreamRes = StreamRes.P720,
    val fps: Int = 60,
    /** Kbps; 0 means "pick for me". */
    val bitrateKbps: Int = 0,
    val codec: StreamCodec = StreamCodec.AUTO,
    val pacing: FramePacing = FramePacing.BALANCED,
    val touchControls: Boolean = true,
    val showStats: Boolean = false,
    val playAudioOnPc: Boolean = false,
) {
    val effectiveBitrateKbps get() = if (bitrateKbps > 0) bitrateKbps else recommendedBitrateKbps(res, fps)

    companion object {
        val FPS_CHOICES = listOf(30, 60)
        const val MIN_KBPS = 2_000
        const val MAX_KBPS = 40_000

        /** Same scale Moonlight uses: ~10 Mbps for 720p60, ~20 Mbps for 1080p60. */
        fun recommendedBitrateKbps(res: StreamRes, fps: Int): Int {
            val base = when (res) { StreamRes.P480 -> 5_000; StreamRes.P720 -> 10_000; StreamRes.P1080 -> 20_000 }
            return if (fps <= 30) base * 2 / 3 else base
        }
    }
}

/**
 * Xbox-style button flags used by Moonlight's controller packets
 * (values match com.limelight.nvstream.input.ControllerPacket).
 */
object PadFlags {
    const val UP = 0x0001; const val DOWN = 0x0002; const val LEFT = 0x0004; const val RIGHT = 0x0008
    const val START = 0x0010; const val BACK = 0x0020; const val LS_CLICK = 0x0040; const val RS_CLICK = 0x0080
    const val LB = 0x0100; const val RB = 0x0200; const val GUIDE = 0x0400
    const val A = 0x1000; const val B = 0x2000; const val X = 0x4000; const val Y = 0x8000
}

/** Converts a -1..1 stick value to Moonlight's signed 16-bit range. Y is flipped (up = positive). */
fun stickToShort(v: Float, invert: Boolean = false): Short {
    val c = v.coerceIn(-1f, 1f) * (if (invert) -1f else 1f)
    return (c * 32767f).toInt().toShort()
}

/** Converts a 0..1 trigger to 0..255 (as an unsigned byte). */
fun triggerToByte(v: Float): Byte = (v.coerceIn(0f, 1f) * 255f).toInt().toByte()

/** Applies a radial dead zone so a resting stick reads as centred. */
fun deadZone(x: Float, y: Float, dz: Float = 0.1f): Pair<Float, Float> {
    val m = kotlin.math.sqrt(x * x + y * y)
    if (m < dz) return 0f to 0f
    val scaled = ((m - dz) / (1f - dz)).coerceAtMost(1f) / m
    return x * scaled to y * scaled
}

/** Converts a controller hat (D-pad reported as axes) to D-pad flags. */
fun hatFlags(x: Float, y: Float): Int {
    var f = 0
    if (x < -0.5f) f = f or PadFlags.LEFT
    if (x > 0.5f) f = f or PadFlags.RIGHT
    if (y < -0.5f) f = f or PadFlags.UP
    if (y > 0.5f) f = f or PadFlags.DOWN
    return f
}
