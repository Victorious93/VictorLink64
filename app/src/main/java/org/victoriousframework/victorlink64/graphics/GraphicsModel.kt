package org.victoriousframework.victorlink64.graphics

import org.json.JSONObject

/** Emulators that have graphics options. Victorious Framework. */
enum class GfxSystem(val key: String, val label: String) {
    N64("n64", "N64"),
    SNES("snes", "SNES"),
    PSP("psp", "PSP"),
    GAMECUBE("gamecube", "GameCube");

    companion object {
        fun fromKey(key: String?): GfxSystem? = entries.firstOrNull { it.key == key }
    }
}

/** Internal rendering size. Each core maps these to its own steps (see [CoreOptionMapping]). */
enum class Resolution(val label: String) {
    ORIGINAL("Original"), X2("2x"), X3("3x"), P720("720p"), P1080("1080p")
}

/** Texture sampling style. [DEFAULT] leaves the core's own choice untouched. */
enum class TexFilter(val label: String) {
    DEFAULT("Core default"), SHARP("Sharp / authentic"), SMOOTH("Smooth (bilinear)")
}

enum class TexEnhance(val label: String) {
    OFF("Off"), X2("2x upscale"), X4("4x upscale")
}

/**
 * Screen-space effect drawn by the shared GL pass (PSP, GameCube, SNES) or by the N64 shader chain.
 * [id] is the value handed to native code; keep in sync with glpass.h.
 */
enum class PostFx(val id: Int, val label: String, val hint: String) {
    NONE(0, "Off", "Plain scaling."),
    SHARPEN(1, "Sharpen", "Crisper edges after scaling."),
    FXAA(2, "FXAA", "Softens jagged edges (cheap anti-aliasing)."),
    CRT(3, "CRT scanlines", "Retro scanlines and a light phosphor mask."),
    SMOOTH_PIXELS(4, "Smooth pixel art", "xBRZ-style edge smoothing for pixel art (an approximation, not true xBRZ).");

    companion object {
        fun fromId(id: Int): PostFx = entries.firstOrNull { it.id == id } ?: NONE
    }
}

/** One complete set of picture choices for an emulator. */
data class GraphicsSettings(
    val resolution: Resolution = Resolution.ORIGINAL,
    val textureFilter: TexFilter = TexFilter.DEFAULT,
    /** 1 = off, otherwise 2, 4, 8 or 16. */
    val anisotropy: Int = 1,
    /** 0 = off, otherwise 2, 4 or 8. */
    val msaa: Int = 0,
    val widescreen: Boolean = false,
    val textureEnhance: TexEnhance = TexEnhance.OFF,
    val postFx: PostFx = PostFx.NONE,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("res", resolution.name).put("filter", textureFilter.name).put("aniso", anisotropy)
        .put("msaa", msaa).put("wide", widescreen).put("enh", textureEnhance.name).put("fx", postFx.name)

    companion object {
        val ANISO_STEPS = intArrayOf(1, 2, 4, 8, 16)
        val MSAA_STEPS = intArrayOf(0, 2, 4, 8)

        fun fromJson(o: JSONObject?): GraphicsSettings? {
            if (o == null) return null
            val d = GraphicsSettings()
            return GraphicsSettings(
                resolution = enumOr(o.optString("res"), d.resolution),
                textureFilter = enumOr(o.optString("filter"), d.textureFilter),
                anisotropy = o.optInt("aniso", d.anisotropy).takeIf { it in ANISO_STEPS } ?: 1,
                msaa = o.optInt("msaa", d.msaa).takeIf { it in MSAA_STEPS } ?: 0,
                widescreen = o.optBoolean("wide", false),
                textureEnhance = enumOr(o.optString("enh"), d.textureEnhance),
                postFx = enumOr(o.optString("fx"), d.postFx),
            )
        }

        private inline fun <reified E : Enum<E>> enumOr(name: String, fallback: E): E =
            enumValues<E>().firstOrNull { it.name == name } ?: fallback
    }
}

/** Which controls a given emulator can honour. Controls a core cannot honour are hidden or disabled in the UI. */
data class Capabilities(
    val resolutions: List<Resolution>,
    val textureFilter: Boolean,
    val anisotropy: Boolean,
    val msaa: Boolean,
    val widescreen: Boolean,
    val textureEnhance: Boolean,
    val postFx: List<PostFx>,
    /** Why a control is missing, shown under the control so nothing silently does nothing. */
    val notes: Map<String, String> = emptyMap(),
) {
    /** Drops everything this emulator cannot do so stored or preset values never claim more than the core supports. */
    fun sanitize(s: GraphicsSettings): GraphicsSettings = GraphicsSettings(
        resolution = if (s.resolution in resolutions) s.resolution else resolutions.firstOrNull() ?: Resolution.ORIGINAL,
        textureFilter = if (textureFilter) s.textureFilter else TexFilter.DEFAULT,
        anisotropy = if (anisotropy) s.anisotropy else 1,
        msaa = if (msaa) s.msaa else 0,
        widescreen = widescreen && s.widescreen,
        textureEnhance = if (textureEnhance) s.textureEnhance else TexEnhance.OFF,
        postFx = if (s.postFx in postFx) s.postFx else PostFx.NONE,
    )
}
