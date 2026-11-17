package org.victoriousframework.victorlink64.graphics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.victoriousframework.victorlink64.graphics.AutoTuner.Decision
import org.victoriousframework.victorlink64.graphics.AutoTuner.Reason

class AutoTunerTest {
    private val w = 2000L

    /** Feeds [n] identical windows; returns the first change, if any. */
    private fun AutoTuner.feed(n: Int, speed: Float, thermal: Int = Thermal.NONE): Decision.Change? {
        repeat(n) { val d = onWindow(speed, thermal, w); if (d is Decision.Change) return d }
        return null
    }

    @Test fun holdsWhenFullSpeed() {
        val t = AutoTuner(maxTier = 2, startTier = 1)
        assertNull(t.feed(20, 1f))
        assertEquals(1, t.tier)
    }

    @Test fun ignoresSlownessDuringWarmup() {
        val t = AutoTuner(2, 1)
        assertNull(t.feed(3, 0.2f)) // 6 s < 8 s warmup
        assertEquals(1, t.tier)
    }

    @Test fun stepsDownAfterTwoSlowWindows() {
        val t = AutoTuner(2, 2)
        t.feed(4, 1f)
        assertTrue(t.onWindow(0.85f, 0, w) is Decision.Hold)
        val d = t.onWindow(0.85f, 0, w) as Decision.Change
        assertEquals(Decision.Change(2, 1, Reason.SLOW), d)
        assertEquals(2, t.knownBadTier)
    }

    @Test fun stepsDownAtOnceWhenFarTooSlow() {
        val t = AutoTuner(2, 2)
        t.feed(4, 1f)
        assertEquals(Decision.Change(2, 1, Reason.SLOW), t.onWindow(0.4f, 0, w))
    }

    @Test fun aSingleBlipDoesNotStepDown() {
        val t = AutoTuner(2, 2)
        t.feed(4, 1f)
        assertTrue(t.onWindow(0.9f, 0, w) is Decision.Hold)
        assertTrue(t.onWindow(1f, 0, w) is Decision.Hold)
        assertTrue(t.onWindow(0.9f, 0, w) is Decision.Hold)
        assertEquals(2, t.tier)
    }

    @Test fun neverGoesBelowTierZero() {
        val t = AutoTuner(2, 0)
        assertNull(t.feed(30, 0.3f))
        assertEquals(0, t.tier)
        assertNull(t.knownBadTier)
    }

    @Test fun keepsStepping_downOneTierPerCooldown() {
        val t = AutoTuner(4, 3)
        t.feed(4, 1f)
        assertEquals(2, t.feed(3, 0.3f)!!.to)
        assertNull(t.feed(3, 0.3f)) // inside the warmup after a change
        assertEquals(1, t.feed(3, 0.3f)!!.to)
    }

    @Test fun thermalPressureStepsDownWithoutBlamingTheTier() {
        val t = AutoTuner(3, 3)
        val d = t.feed(6, 1f, Thermal.MODERATE)
        assertEquals(Decision.Change(3, 2, Reason.THERMAL), d)
        assertNull(t.knownBadTier)
    }

    @Test fun severeThermalDropsTwoTiers() {
        val t = AutoTuner(3, 3)
        val d = t.feed(6, 1f, Thermal.SEVERE)
        assertEquals(1, d!!.to)
    }

    @Test fun probesUpAfterALongCleanRun() {
        val t = AutoTuner(maxTier = 3, startTier = 1)
        val d = t.feed(40, 1f)
        assertEquals(Decision.Change(1, 2, Reason.PROBE), d)
        assertEquals(1, t.learnedTier)
    }

    @Test fun neverProbesAboveTheClassCeiling() {
        val t = AutoTuner(maxTier = 1, startTier = 1)
        assertNull(t.feed(200, 1f))
        assertEquals(1, t.tier)
    }

    @Test fun aFailedProbeIsRememberedAndNotRetried() {
        val t = AutoTuner(3, 1)
        t.feed(40, 1f) // probes to 2
        assertEquals(2, t.tier)
        val back = t.feed(8, 0.5f)
        assertEquals(Decision.Change(2, 1, Reason.SLOW), back)
        assertEquals(2, t.knownBadTier)
        assertNull(t.feed(200, 1f)) // tier 2 is known bad; stays at 1
        assertEquals(1, t.tier)
    }

    @Test fun startsAlreadyAwareOfAKnownBadTier() {
        val t = AutoTuner(3, 1, knownBadTier = 2)
        assertNull(t.feed(200, 1f))
        assertEquals(1, t.tier)
    }

    @Test fun noProbingWhileThePhoneIsWarmOrJustCooledDown() {
        val t = AutoTuner(3, 3)
        t.feed(6, 1f, Thermal.MODERATE) // steps to 2, hold for 10 minutes
        assertNull(t.feed(100, 1f)) // 200 s of clean running: still held
        assertEquals(2, t.tier)
        assertEquals(Reason.PROBE, t.feed(300, 1f)!!.reason) // past the hold
    }

    @Test fun lightHeatBlocksProbingButNotRunning() {
        val t = AutoTuner(3, 1)
        assertNull(t.feed(100, 1f, Thermal.LIGHT))
        assertEquals(1, t.tier)
    }

    @Test fun learnsTheTierAfterThirtySecondsOfFullSpeed() {
        val t = AutoTuner(3, 2)
        t.feed(10, 1f)
        assertNull(t.learnedTier)
        t.feed(10, 1f)
        assertEquals(2, t.learnedTier)
    }

    @Test fun frameMonitorReportsSpeedPerWindow() {
        val m = FrameMonitor(targetFps = 60.0, windowMs = 2000)
        var t = 1_000_000_000L
        var w: FrameMonitor.Window? = null
        repeat(130) { t += 16_666_667; m.onFrame(t)?.let { w = it } }
        assertEquals(1f, w!!.speed, 0.001f)
        assertEquals(60f, w!!.fps, 0.5f)
    }

    @Test fun frameMonitorSeesSlowEmulation() {
        val m = FrameMonitor(60.0, 2000)
        var t = 1_000_000_000L
        var w: FrameMonitor.Window? = null
        repeat(80) { t += 33_333_333; m.onFrame(t)?.let { w = it } }
        assertEquals(0.5f, w!!.speed, 0.01f)
    }

    @Test fun frameMonitorTreatsALongGapAsPauseNotSlowness() {
        val m = FrameMonitor(60.0, 2000)
        var t = 1_000_000_000L
        repeat(60) { t += 16_666_667; m.onFrame(t) }
        t += 30_000_000_000L // app was paused for 30 s
        var w: FrameMonitor.Window? = null
        repeat(130) { t += 16_666_667; m.onFrame(t)?.let { w = it } }
        assertEquals(1f, w!!.speed, 0.001f)
    }

    @Test fun frameMonitorSpeedIsCappedAtFull() {
        assertEquals(1f, FrameMonitor.speedOf(75.0, 60.0), 0f)
        assertEquals(0.5f, FrameMonitor.speedOf(30.0, 60.0), 0.001f)
        assertEquals(1f, FrameMonitor.speedOf(10.0, 0.0), 0f)
    }

    @Test fun n64RegionPicksPalTarget() {
        assertEquals(60.0, N64Region.targetFps(0x45), 0.0) // USA
        assertEquals(60.0, N64Region.targetFps(0x4A), 0.0) // Japan
        assertEquals(50.0, N64Region.targetFps(0x50), 0.0) // Europe
        assertEquals(50.0, N64Region.targetFps(0x44), 0.0) // Germany
        assertEquals(50.0, N64Region.targetFps(0x70), 0.0)
    }
}
