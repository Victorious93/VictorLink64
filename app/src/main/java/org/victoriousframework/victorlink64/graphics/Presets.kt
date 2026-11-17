package org.victoriousframework.victorlink64.graphics

import org.victoriousframework.victorlink64.graphics.PostFx.*
import org.victoriousframework.victorlink64.graphics.Resolution.*

data class Preset(val name: String, val settings: GraphicsSettings)

/**
 * Quality ladders, ordered by cost: index 0 is the cheapest. Auto Best Settings walks these.
 * Every preset only uses controls the emulator's [Capabilities] allow (checked by tests).
 */
object Presets {
    private val n64Notes = mapOf(
        "postFx" to "N64 uses the existing shader chain: FXAA, CRT and ScaleFX are available; Sharpen is not.",
        "msaa" to "MSAA only takes effect on GLES 3.1+ devices.",
    )

    fun capabilities(system: GfxSystem): Capabilities = when (system) {
        GfxSystem.N64 -> Capabilities(
            resolutions = Resolution.entries, textureFilter = true, anisotropy = true, msaa = true,
            widescreen = true, textureEnhance = true, postFx = listOf(NONE, FXAA, CRT, SMOOTH_PIXELS), notes = n64Notes,
        )
        GfxSystem.PSP -> Capabilities(
            resolutions = Resolution.entries, textureFilter = true, anisotropy = true, msaa = true,
            widescreen = false, textureEnhance = true, postFx = PostFx.entries,
            notes = mapOf(
                "widescreen" to "The PSP screen is already 16:9, so there is no widescreen hack.",
                "resolution" to "PPSSPP renders in 272-line steps; 720p uses its 3x step and 1080p its 4x step.",
            ),
        )
        GfxSystem.GAMECUBE -> Capabilities(
            resolutions = Resolution.entries, textureFilter = false, anisotropy = true, msaa = true,
            widescreen = true, textureEnhance = false, postFx = PostFx.entries,
            notes = mapOf(
                "textureFilter" to "The Dolphin core does not document its texture filter values, so this is not offered.",
                "textureEnhance" to "Texture upscaling is not available in the Dolphin core.",
                "resolution" to "Dolphin renders in whole multiples of 640x528; 720p uses 2x and 1080p uses 3x.",
            ),
        )
        GfxSystem.SNES -> Capabilities(
            resolutions = listOf(ORIGINAL), textureFilter = true, anisotropy = false, msaa = false,
            widescreen = false, textureEnhance = false, postFx = PostFx.entries,
            notes = mapOf(
                "resolution" to "SNES pictures are drawn at the screen's own size; only the scaling effect matters.",
                "textureFilter" to "Applies to the scaling pass: sharp keeps hard pixel edges, smooth blends them.",
            ),
        )
    }

    private fun p(name: String, s: GraphicsSettings) = Preset(name, s)

    private val ladders: Map<GfxSystem, List<Preset>> = mapOf(
        GfxSystem.N64 to listOf(
            p("Battery saver", GraphicsSettings(ORIGINAL, TexFilter.SHARP)),
            p("Smooth", GraphicsSettings(X2, TexFilter.SMOOTH)),
            p("Balanced", GraphicsSettings(X2, TexFilter.SMOOTH, anisotropy = 4)),
            p("High", GraphicsSettings(X3, TexFilter.SMOOTH, anisotropy = 8, postFx = FXAA)),
            p("Ultra", GraphicsSettings(X3, TexFilter.SMOOTH, anisotropy = 16, msaa = 4, textureEnhance = TexEnhance.X2, postFx = FXAA)),
        ),
        GfxSystem.PSP to listOf(
            p("Battery saver", GraphicsSettings(ORIGINAL)),
            p("Smooth", GraphicsSettings(X2)),
            p("Balanced", GraphicsSettings(X2, TexFilter.SMOOTH, anisotropy = 4)),
            p("High", GraphicsSettings(X3, TexFilter.SMOOTH, anisotropy = 8, postFx = SHARPEN)),
            p("Ultra", GraphicsSettings(P1080, TexFilter.SMOOTH, anisotropy = 16, msaa = 4, textureEnhance = TexEnhance.X2, postFx = FXAA)),
        ),
        GfxSystem.GAMECUBE to listOf(
            p("Battery saver", GraphicsSettings(ORIGINAL)),
            p("Smooth", GraphicsSettings(ORIGINAL, anisotropy = 2)),
            p("Balanced", GraphicsSettings(P720, anisotropy = 4)),
            p("High", GraphicsSettings(P1080, anisotropy = 8, msaa = 2)),
            p("Ultra", GraphicsSettings(P1080, anisotropy = 16, msaa = 4, postFx = FXAA)),
        ),
        GfxSystem.SNES to listOf(
            p("Plain", GraphicsSettings()),
            p("Sharp", GraphicsSettings(postFx = SHARPEN)),
            p("Smooth pixel art", GraphicsSettings(postFx = SMOOTH_PIXELS)),
        ),
    )

    fun ladder(system: GfxSystem): List<Preset> = ladders.getValue(system)

    fun maxTier(system: GfxSystem): Int = ladder(system).size - 1

    fun preset(system: GfxSystem, tier: Int): Preset = ladder(system)[tier.coerceIn(0, maxTier(system))]

    /** What the manual editor starts from for an emulator. */
    fun manualDefault(system: GfxSystem): GraphicsSettings = preset(system, minOf(2, maxTier(system))).settings
}
