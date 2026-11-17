package org.victoriousframework.victorlink64.graphics

/** The picture settings a game will actually use, and how they were chosen. */
data class Resolved(
    val system: GfxSystem,
    val auto: Boolean,
    /** Ladder index when [auto]; null for a manual choice. */
    val tier: Int?,
    val settings: GraphicsSettings,
    val label: String,
    /** Highest tier Auto may try on this phone (0 when manual). */
    val maxTier: Int,
    val knownBadTier: Int?,
)

object GraphicsResolver {
    /**
     * Auto (the default) picks a preset from the phone class, what this game learned, and current heat. A per-game
     * choice beats the app-wide one; a manual choice beats Auto entirely.
     */
    fun resolve(system: GfxSystem, game: GameRecord?, global: GlobalGraphics, device: DeviceClass, thermal: Int): Resolved {
        val caps = Presets.capabilities(system)
        val auto = game?.auto ?: global.auto
        if (!auto) {
            val s = caps.sanitize(game?.manual ?: global.manualFor(system))
            return Resolved(system, false, null, s, "Manual", 0, null)
        }
        val max = AutoPolicy.maxTier(system, device)
        val tier = AutoPolicy.startTier(system, device, thermal, game?.learnedTier, game?.knownBadTier)
        val preset = Presets.preset(system, tier)
        return Resolved(system, true, tier, caps.sanitize(preset.settings), preset.name, max, game?.knownBadTier)
    }

    fun forTier(system: GfxSystem, tier: Int): Pair<GraphicsSettings, String> {
        val p = Presets.preset(system, tier)
        return Presets.capabilities(system).sanitize(p.settings) to p.name
    }
}
