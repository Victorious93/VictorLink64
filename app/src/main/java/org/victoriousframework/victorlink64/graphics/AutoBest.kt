package org.victoriousframework.victorlink64.graphics

/** PowerManager thermal status numbers (THERMAL_STATUS_*). */
object Thermal {
    const val NONE = 0
    const val LIGHT = 1
    const val MODERATE = 2
    const val SEVERE = 3
}

/**
 * Where Auto Best Settings starts for a game. The ladder is in [Presets]; tiers a phone class should not attempt are
 * cut off with [maxTier]. A tier that previously held full speed for this game ([learned]) wins over the class guess,
 * and a tier that failed ([knownBad]) is never started at or above.
 */
object AutoPolicy {
    private data class Row(val low: Int, val mid: Int, val high: Int) {
        fun of(c: DeviceClass) = when (c) { DeviceClass.LOW -> low; DeviceClass.MID -> mid; DeviceClass.HIGH -> high }
    }

    private val start = mapOf(
        GfxSystem.N64 to Row(1, 2, 3), GfxSystem.PSP to Row(1, 2, 3),
        GfxSystem.GAMECUBE to Row(0, 1, 2), GfxSystem.SNES to Row(2, 2, 2),
    )
    private val ceiling = mapOf(
        GfxSystem.N64 to Row(2, 3, 4), GfxSystem.PSP to Row(2, 3, 4),
        GfxSystem.GAMECUBE to Row(1, 2, 4), GfxSystem.SNES to Row(2, 2, 2),
    )

    fun maxTier(system: GfxSystem, device: DeviceClass): Int =
        minOf(ceiling.getValue(system).of(device), Presets.maxTier(system))

    fun startTier(system: GfxSystem, device: DeviceClass, thermal: Int, learned: Int?, knownBad: Int?): Int {
        val max = maxTier(system, device)
        var t = (learned ?: start.getValue(system).of(device)).coerceIn(0, max)
        if (knownBad != null) t = minOf(t, knownBad - 1)
        if (thermal >= Thermal.MODERATE) t -= 1
        return t.coerceIn(0, max)
    }
}

/**
 * Turns a stream of frame timestamps into speed windows. [speed] is 1.0 when the emulator produced frames at the
 * core's target rate. Gaps longer than [pauseGapMs] (app paused, state loading) restart the window instead of
 * counting as slowness.
 */
class FrameMonitor(private val targetFps: Double, private val windowMs: Long = 2000, private val pauseGapMs: Long = 500) {
    data class Window(val fps: Float, val speed: Float)

    private var lastNs = 0L
    private var windowStartNs = 0L
    private var frames = 0

    fun onFrame(nowNs: Long): Window? {
        if (lastNs == 0L || nowNs - lastNs > pauseGapMs * 1_000_000) {
            lastNs = nowNs; windowStartNs = nowNs; frames = 0
            return null
        }
        lastNs = nowNs
        frames++
        val elapsed = nowNs - windowStartNs
        if (elapsed < windowMs * 1_000_000) return null
        val fps = (frames * 1e9 / elapsed).toFloat()
        windowStartNs = nowNs; frames = 0
        return Window(fps, speedOf(fps.toDouble(), targetFps))
    }

    companion object {
        fun speedOf(fps: Double, target: Double): Float =
            if (target <= 0.0) 1f else (fps / target).coerceIn(0.0, 1.0).toFloat()
    }
}

/**
 * Picks the highest preset that holds full speed. It steps down when speed or temperature demands, and after a long
 * clean run it probes one tier up; a tier that fails a probe is remembered ([knownBadTier]) and not retried.
 * Thermal step-downs are temporary: they never mark a tier bad.
 */
class AutoTuner(
    private val maxTier: Int,
    startTier: Int,
    knownBadTier: Int? = null,
    private val p: Params = Params(),
) {
    data class Params(
        val warmupMs: Long = 8_000,
        val cooldownMs: Long = 8_000,
        val fullSpeed: Float = 0.97f,
        val dropSpeed: Float = 0.93f,
        val severeSpeed: Float = 0.70f,
        val dropStreak: Int = 2,
        val probeAfterMs: Long = 60_000,
        val thermalHoldMs: Long = 600_000,
        val stableAfterMs: Long = 30_000,
    )

    enum class Reason { SLOW, THERMAL, PROBE }

    sealed class Decision {
        data object Hold : Decision()
        data class Change(val from: Int, val to: Int, val reason: Reason) : Decision()
    }

    var tier: Int = startTier.coerceIn(0, maxTier); private set
    var knownBadTier: Int? = knownBadTier; private set

    /** Last tier that ran at full speed for [Params.stableAfterMs] without thermal pressure; save this per game. */
    var learnedTier: Int? = null; private set

    private var now = 0L
    private var sinceChange = 0L
    private var lowStreak = 0
    private var fullMs = 0L
    private var thermalHoldUntil = 0L

    fun onWindow(speed: Float, thermal: Int, windowMs: Long): Decision {
        now += windowMs
        sinceChange += windowMs

        if (thermal >= Thermal.MODERATE) {
            lowStreak = 0; fullMs = 0
            if (tier > 0 && sinceChange >= p.cooldownMs) {
                thermalHoldUntil = now + p.thermalHoldMs
                return change(tier - if (thermal >= Thermal.SEVERE) 2 else 1, Reason.THERMAL)
            }
            return Decision.Hold
        }
        if (sinceChange < p.warmupMs) return Decision.Hold

        if (speed < p.dropSpeed) lowStreak++ else lowStreak = 0
        if (speed < p.severeSpeed || lowStreak >= p.dropStreak) {
            if (tier == 0) { lowStreak = 0; return Decision.Hold }
            knownBadTier = minOf(knownBadTier ?: tier, tier)
            return change(tier - 1, Reason.SLOW)
        }

        if (speed >= p.fullSpeed && thermal <= Thermal.LIGHT) fullMs += windowMs
        else if (speed < p.dropSpeed) fullMs = 0
        if (fullMs >= p.stableAfterMs) learnedTier = tier

        val bad = knownBadTier
        if (fullMs >= p.probeAfterMs && tier < maxTier && (bad == null || tier + 1 < bad) &&
            thermal == Thermal.NONE && now >= thermalHoldUntil
        ) return change(tier + 1, Reason.PROBE)
        return Decision.Hold
    }

    private fun change(to: Int, reason: Reason): Decision.Change {
        val from = tier
        tier = to.coerceIn(0, maxTier)
        sinceChange = 0; lowStreak = 0; fullMs = 0
        return Decision.Change(from, tier, reason)
    }
}
