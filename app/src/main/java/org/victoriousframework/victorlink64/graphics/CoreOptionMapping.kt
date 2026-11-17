package org.victoriousframework.victorlink64.graphics

/**
 * What a settings choice means for a libretro core: [vars] are libretro core variables, [postFx]/[nearest] drive the
 * shared GL pass in our frontends. Variable names and values were read out of the shipped PPSSPP and Dolphin libraries;
 * values the cores do not recognise are ignored by them, so a wrong value falls back to the core default.
 */
data class CoreOptions(val vars: Map<String, String>, val postFx: PostFx, val nearest: Boolean)

/** Keys written into the N64 emulation profile; read back by GamePrefs / NativeConfigFiles. */
data class N64Overrides(
    val profile: Map<String, String>,
    /** Surface height to render at, or null to keep the user's own choice. */
    val renderHeight: Int?,
    /** ShaderLoader enum names for the N64 post-processing chain, in order. */
    val shaders: List<String>,
)

object CoreOptionMapping {
    fun forSystem(system: GfxSystem, s: GraphicsSettings): CoreOptions = when (system) {
        GfxSystem.PSP -> psp(s)
        GfxSystem.GAMECUBE -> dolphin(s)
        GfxSystem.SNES -> snes(s)
        GfxSystem.N64 -> CoreOptions(emptyMap(), PostFx.NONE, false)
    }

    fun psp(s: GraphicsSettings): CoreOptions {
        val v = LinkedHashMap<String, String>()
        v["ppsspp_internal_resolution"] = when (s.resolution) {
            Resolution.ORIGINAL -> "480x272"
            Resolution.X2 -> "960x544"
            Resolution.X3, Resolution.P720 -> "1440x816"
            Resolution.P1080 -> "1920x1088"
        }
        v["ppsspp_texture_filtering"] = when (s.textureFilter) {
            TexFilter.DEFAULT -> "Auto"; TexFilter.SHARP -> "Nearest"; TexFilter.SMOOTH -> "Linear"
        }
        v["ppsspp_texture_anisotropic_filtering"] = if (s.anisotropy <= 1) "disabled" else "${s.anisotropy}x"
        v["ppsspp_mulitsample_level"] = if (s.msaa <= 0) "Disabled" else "x${s.msaa}"
        v["ppsspp_texture_scaling_type"] = "xbrz"
        v["ppsspp_texture_scaling_level"] = when (s.textureEnhance) {
            TexEnhance.OFF -> "disabled"; TexEnhance.X2 -> "2x"; TexEnhance.X4 -> "4x"
        }
        v["ppsspp_texture_deposterize"] = if (s.textureEnhance == TexEnhance.OFF) "disabled" else "enabled"
        return CoreOptions(v, s.postFx, false)
    }

    fun dolphin(s: GraphicsSettings): CoreOptions {
        val v = LinkedHashMap<String, String>()
        v["dolphin_efb_scale"] = when (s.resolution) {
            Resolution.ORIGINAL -> "x1"
            Resolution.X2, Resolution.P720 -> "x2"
            Resolution.X3, Resolution.P1080 -> "x3"
        }
        v["dolphin_max_anisotropy"] = "x${s.anisotropy.coerceAtLeast(1)}"
        v["dolphin_anti_aliasing"] = if (s.msaa <= 0) "None" else "${s.msaa}x MSAA"
        if (s.widescreen) {
            v["dolphin_widescreen_hack"] = "enabled"
            v["dolphin_aspect_ratio"] = "Force Wide"
        }
        return CoreOptions(v, s.postFx, false)
    }

    fun snes(s: GraphicsSettings): CoreOptions = CoreOptions(emptyMap(), s.postFx, s.textureFilter == TexFilter.SHARP)

    /** True when the SNES picture must go through the GL pass instead of the plain software blit. */
    fun snesNeedsGl(s: GraphicsSettings): Boolean = s.postFx != PostFx.NONE || s.textureFilter == TexFilter.SHARP

    fun n64(s: GraphicsSettings, supportsGles31: Boolean, explicitWidescreen: Boolean): N64Overrides {
        val p = LinkedHashMap<String, String>()
        var height: Int? = null
        when (s.resolution) {
            Resolution.ORIGINAL -> p["UseNativeResolutionFactor"] = "1"
            Resolution.X2 -> p["UseNativeResolutionFactor"] = "2"
            Resolution.X3 -> p["UseNativeResolutionFactor"] = "3"
            Resolution.P720 -> { p["UseNativeResolutionFactor"] = "0"; height = 720 }
            Resolution.P1080 -> { p["UseNativeResolutionFactor"] = "0"; height = 1080 }
        }
        when (s.textureFilter) {
            TexFilter.DEFAULT -> {}
            TexFilter.SHARP -> p["bilinearMode"] = "False"
            TexFilter.SMOOTH -> p["bilinearMode"] = "True"
        }
        p["MaxAnisotropy"] = if (s.anisotropy <= 1) "0" else s.anisotropy.toString()
        p[MSAA_KEY] = if (supportsGles31) s.msaa.toString() else "0"
        p["txEnhancementMode"] = when (s.textureEnhance) { TexEnhance.OFF -> "0"; TexEnhance.X2 -> "9"; TexEnhance.X4 -> "11" }
        if (s.widescreen) p["WidescreenHack"] = "True" else if (explicitWidescreen) p["WidescreenHack"] = "False"
        val shaders = when (s.postFx) {
            PostFx.FXAA -> listOf("FXAA")
            PostFx.CRT -> listOf("CTR_GEOM")
            PostFx.SMOOTH_PIXELS -> listOf("SCALEFX")
            PostFx.NONE, PostFx.SHARPEN -> emptyList()
        }
        return N64Overrides(p, height, shaders)
    }

    /** Profile key carrying the MSAA level to NativeConfigFiles. */
    const val MSAA_KEY = "VL64_MSAA"
    /** Profile key carrying the render surface height to GamePrefs. */
    const val RENDER_HEIGHT_KEY = "VL64_RENDER_HEIGHT"
}
