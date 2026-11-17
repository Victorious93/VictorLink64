package org.victoriousframework.victorlink64.graphics

import org.json.JSONObject
import java.io.File

/** Remembered graphics choices for one game. A null field means "inherit". */
data class GameRecord(
    val key: String,
    val system: GfxSystem,
    val name: String,
    val auto: Boolean? = null,
    val manual: GraphicsSettings? = null,
    /** Highest tier that held full speed for this game; Auto starts here. */
    val learnedTier: Int? = null,
    /** Lowest tier that failed to hold full speed; Auto never starts at or above it. */
    val knownBadTier: Int? = null,
    val lastPlayedMs: Long = 0,
) {
    val hasOverride: Boolean get() = auto != null || manual != null

    fun toJson(): JSONObject = JSONObject().put("sys", system.key).put("name", name).put("played", lastPlayedMs).apply {
        auto?.let { put("auto", it) }
        manual?.let { put("manual", it.toJson()) }
        learnedTier?.let { put("learned", it) }
        knownBadTier?.let { put("bad", it) }
    }

    companion object {
        fun fromJson(key: String, o: JSONObject): GameRecord? {
            val system = GfxSystem.fromKey(o.optString("sys")) ?: return null
            return GameRecord(
                key = key, system = system, name = o.optString("name", key),
                auto = if (o.has("auto")) o.optBoolean("auto") else null,
                manual = GraphicsSettings.fromJson(o.optJSONObject("manual")),
                learnedTier = if (o.has("learned")) o.optInt("learned") else null,
                knownBadTier = if (o.has("bad")) o.optInt("bad") else null,
                lastPlayedMs = o.optLong("played", 0),
            )
        }
    }
}

/** App-wide choices: whether Auto Best Settings is on, and the manual picture per emulator. */
data class GlobalGraphics(
    val auto: Boolean = true,
    val perSystem: Map<GfxSystem, GraphicsSettings> = emptyMap(),
) {
    fun manualFor(system: GfxSystem): GraphicsSettings = perSystem[system] ?: Presets.manualDefault(system)
}

/** Stable per-game keys. N64 uses the ROM's MD5, disc and SNES games their file name. */
object GameKeys {
    fun n64(md5: String) = "n64:" + md5.trim().lowercase()
    fun byFile(system: GfxSystem, fileName: String) = system.key + ":" + fileName.trim().lowercase()
}

interface TextBackend {
    fun read(): String?
    fun write(text: String)
}

class FileBackend(private val file: File) : TextBackend {
    override fun read(): String? = runCatching { if (file.exists()) file.readText() else null }.getOrNull()
    override fun write(text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) { file.writeText(text); tmp.delete() }
    }
}

class MemoryBackend(var text: String? = null) : TextBackend {
    override fun read() = text
    override fun write(text: String) { this.text = text }
}

/**
 * Small JSON document. Every call re-reads the backing text, because the N64 emulation runs in its own process and
 * writes learned tiers there while this process may already have the settings screen open.
 */
class GraphicsStore(private val backend: TextBackend) {
    private data class Doc(val global: GlobalGraphics, val games: Map<String, GameRecord>)

    private val lock = Any()

    private fun read(): Doc {
        val o = runCatching { JSONObject(backend.read() ?: return@runCatching null) }.getOrNull() ?: return Doc(GlobalGraphics(), emptyMap())
        val sys = o.optJSONObject("sys")
        val per = HashMap<GfxSystem, GraphicsSettings>()
        sys?.keys()?.forEach { k ->
            val system = GfxSystem.fromKey(k)
            val s = GraphicsSettings.fromJson(sys.optJSONObject(k))
            if (system != null && s != null) per[system] = Presets.capabilities(system).sanitize(s)
        }
        val games = LinkedHashMap<String, GameRecord>()
        o.optJSONObject("games")?.let { g -> g.keys().forEach { k -> GameRecord.fromJson(k, g.getJSONObject(k))?.let { games[k] = it } } }
        return Doc(GlobalGraphics(o.optBoolean("auto", true), per), games)
    }

    private fun write(d: Doc) {
        val sys = JSONObject()
        d.global.perSystem.forEach { (k, v) -> sys.put(k.key, v.toJson()) }
        val games = JSONObject()
        d.games.forEach { (k, v) -> games.put(k, v.toJson()) }
        backend.write(JSONObject().put("v", 1).put("auto", d.global.auto).put("sys", sys).put("games", games).toString())
    }

    private fun <T> edit(f: (Doc) -> Pair<Doc, T>): T = synchronized(lock) { val (d, r) = f(read()); write(d); r }

    fun global(): GlobalGraphics = synchronized(lock) { read().global }
    fun game(key: String): GameRecord? = synchronized(lock) { read().games[key] }
    fun games(): List<GameRecord> = synchronized(lock) { read().games.values.sortedByDescending { it.lastPlayedMs } }

    fun setAuto(auto: Boolean) = edit { d -> d.copy(global = d.global.copy(auto = auto)) to Unit }

    fun setSystemManual(system: GfxSystem, s: GraphicsSettings?) = edit { d ->
        val m = d.global.perSystem.toMutableMap()
        if (s == null) m.remove(system) else m[system] = Presets.capabilities(system).sanitize(s)
        d.copy(global = d.global.copy(perSystem = m)) to Unit
    }

    /** Creates or refreshes the game's record (name, last played) without touching its choices. */
    fun touchGame(key: String, system: GfxSystem, name: String, nowMs: Long) = edit { d ->
        val cur = d.games[key]
        val rec = (cur ?: GameRecord(key, system, name)).copy(name = name, lastPlayedMs = nowMs)
        d.copy(games = d.games + (key to rec)) to Unit
    }

    fun updateGame(key: String, f: (GameRecord) -> GameRecord) = edit { d ->
        val cur = d.games[key] ?: return@edit d to Unit
        d.copy(games = d.games + (key to f(cur))) to Unit
    }

    fun removeGame(key: String) = edit { d -> d.copy(games = d.games - key) to Unit }
}
