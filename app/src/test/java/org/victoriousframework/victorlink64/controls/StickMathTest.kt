package org.victoriousframework.victorlink64.controls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/** Victorious Framework: stick, trigger and D-pad math behind the on-screen controls. */
class StickMathTest {
    private val cfg = StickConfig(deadzone = 0.10f, outer = 0.95f, curve = 1f)

    @Test fun insideDeadzoneIsZero() {
        assertEquals(Vec2.ZERO, StickMath.shape(0.05f, -0.05f, cfg))
        assertEquals(Vec2.ZERO, StickMath.shape(0.10f, 0f, cfg))
        assertEquals(Vec2.ZERO, StickMath.shape(0f, 0f, cfg))
    }

    @Test fun deadzoneIsRadialNotPerAxis() {
        // A per-axis deadzone would zero x here (0.08 < 0.10); a radial one keeps the diagonal direction.
        val v = StickMath.shape(0.08f, 0.5f, cfg)
        assertTrue(v.x > 0f && v.y > 0f)
        assertEquals(0.08f / 0.5f, v.x / v.y, 1e-4f)
    }

    @Test fun outputRisesContinuouslyFromZeroAtTheDeadzoneEdge() {
        val just = StickMath.shape(0.1001f, 0f, cfg)
        assertTrue(just.x in 0f..0.01f)
        var prev = 0f
        for (i in 10..100) {
            val m = hypot(StickMath.shape(i / 100f, 0f, cfg).x, 0f)
            assertTrue("monotonic at $i", m >= prev)
            prev = m
        }
    }

    @Test fun fullDeflectionReachedBeforeTheRimAndNeverExceedsOne() {
        assertEquals(1f, StickMath.shape(0.95f, 0f, cfg).x, 1e-4f)
        assertEquals(1f, StickMath.shape(1f, 0f, cfg).x, 1e-4f)
        val corner = StickMath.shape(5f, 5f, cfg)
        assertEquals(1f, hypot(corner.x, corner.y), 1e-4f)
        assertEquals(corner.x, corner.y, 1e-5f)
    }

    @Test fun directionIsPreserved() {
        val v = StickMath.shape(-0.3f, 0.4f, cfg)
        assertTrue(v.x < 0f && v.y > 0f)
        assertEquals(-0.3f / 0.4f, v.x / v.y, 1e-4f)
    }

    @Test fun curveAboveOneGivesFinerControlNearCentre() {
        val linear = StickMath.shape(0.4f, 0f, StickConfig(0.1f, 0.95f, 1f)).x
        val curved = StickMath.shape(0.4f, 0f, StickConfig(0.1f, 0.95f, 1.5f)).x
        assertTrue(curved < linear)
    }

    @Test fun badConfigIsSanitised() {
        val v = StickMath.shape(0.9f, 0f, StickConfig(deadzone = 5f, outer = -1f, curve = 0f))
        assertTrue(v.x in 0f..1f)
        assertEquals(1f, StickMath.shape(1f, 0f, StickConfig(0.9f, 0.5f, 1f)).x, 1e-4f)
    }

    @Test fun axisConversionMatchesLibretroRange() {
        assertEquals(32767, StickMath.toAxis16(1f))
        assertEquals(-32767, StickMath.toAxis16(-1f))
        assertEquals(0, StickMath.toAxis16(0f))
        assertEquals(32767, StickMath.toAxis16(3f))
        assertEquals(16384, StickMath.toAxis16(0.5f))
        assertEquals(0, StickMath.toPressure16(-0.2f))
        assertEquals(32767, StickMath.toPressure16(1f))
    }

    @Test fun fixedStickMeasuresFromHomeAndClampsKnobToTheRim() {
        val s = StickTracker(cfg)
        s.begin(200f, 100f, 100f, 100f, 50f, floating = false)
        assertEquals(100f, s.baseX, 0f)
        assertEquals(150f, s.knobX, 1e-3f)
        assertEquals(1f, s.out.x, 1e-4f)
        s.move(100f, 130f)
        assertEquals(0f, s.out.x, 1e-4f)
        assertTrue(s.out.y > 0f && s.out.y < 1f)
        s.end()
        assertEquals(Vec2.ZERO, s.out)
        assertTrue(!s.active)
    }

    @Test fun floatingStickPlantsItsBaseWhereTheFingerLands() {
        val s = StickTracker(cfg)
        s.begin(300f, 200f, 100f, 100f, 50f, floating = true)
        assertEquals(300f, s.baseX, 0f); assertEquals(200f, s.baseY, 0f)
        assertEquals(Vec2.ZERO, s.out)
        s.move(330f, 200f)
        assertTrue(s.out.x > 0f)
    }

    @Test fun floatingBaseFollowsAFingerThatPassesTheRim() {
        val s = StickTracker(cfg)
        s.begin(300f, 200f, 0f, 0f, 50f, floating = true)
        s.move(500f, 200f)
        assertEquals(450f, s.baseX, 1e-3f)
        assertEquals(1f, s.out.x, 1e-4f)
        s.move(430f, 200f) // finger comes back 20 px: output drops straight away instead of staying pinned
        assertTrue(s.out.x < 1f)
    }

    @Test fun floatingBaseStaysOnScreen() {
        val s = StickTracker(cfg)
        s.begin(5f, 5f, 0f, 0f, 50f, floating = true, bounds = Bounds(0f, 0f, 800f, 400f))
        assertEquals(50f, s.baseX, 0f); assertEquals(50f, s.baseY, 0f)
    }

    @Test fun triggerTravel() {
        assertEquals(0f, TriggerMath.value(0f), 0f)
        assertEquals(0f, TriggerMath.value(TriggerMath.DEAD), 1e-6f)
        assertEquals(1f, TriggerMath.value(0.9f), 0f)
        val mid = TriggerMath.value(0.5f)
        assertTrue(mid > 0.4f && mid < 0.7f)
        assertTrue(TriggerMath.value(0.4f) < TriggerMath.value(0.6f))
        assertEquals(1f, TriggerMath.value(5f), 0f)
    }

    @Test fun dpadHasEightDirections() {
        val s = 100f
        assertEquals(0, DPadMath.direction(3f, -3f, s))
        assertEquals(DPadMath.RIGHT, DPadMath.direction(40f, 0f, s))
        assertEquals(DPadMath.LEFT, DPadMath.direction(-40f, 0f, s))
        assertEquals(DPadMath.UP, DPadMath.direction(0f, -40f, s))
        assertEquals(DPadMath.DOWN, DPadMath.direction(0f, 40f, s))
        assertEquals(DPadMath.UP or DPadMath.RIGHT, DPadMath.direction(30f, -30f, s))
        assertEquals(DPadMath.DOWN or DPadMath.LEFT, DPadMath.direction(-30f, 30f, s))
        assertEquals(DPadMath.DOWN or DPadMath.RIGHT, DPadMath.direction(30f, 30f, s))
        assertEquals(DPadMath.UP or DPadMath.LEFT, DPadMath.direction(-30f, -30f, s))
    }

    @Test fun dpadSlidingAroundTheRimPassesThroughDiagonals() {
        val seq = listOf(0, 45, 90, 135, 180).map {
            val a = Math.toRadians(it.toDouble())
            DPadMath.direction((40 * Math.cos(a)).toFloat(), (-40 * Math.sin(a)).toFloat(), 100f)
        }
        assertEquals(
            listOf(DPadMath.RIGHT, DPadMath.RIGHT or DPadMath.UP, DPadMath.UP, DPadMath.UP or DPadMath.LEFT, DPadMath.LEFT), seq,
        )
    }
}
