package org.victoriousframework.victorlink64.snes

import org.junit.Assert.assertEquals
import org.junit.Test

class SnesGeometryTest {
    @Test fun landscapeFitsFullHeightAt4by3() {
        // The window from the bug report: 1604 x 720. The picture must use the full 720 px height, not 720 x 540.
        assertEquals(960 to 720, fitPicture(1604, 720, Aspect.NORMAL))
    }

    @Test fun portraitFitsWidth() {
        assertEquals(720 to 540, fitPicture(720, 1604, Aspect.NORMAL))
    }

    @Test fun sameWindowGivesDifferentSizeAfterRotation() {
        // Regression: the size used to be computed once in portrait and kept after rotating to landscape.
        val portrait = fitPicture(720, 1604, Aspect.NORMAL)
        val landscape = fitPicture(1604, 720, Aspect.NORMAL)
        assertEquals(720 to 540, portrait)
        assertEquals(960 to 720, landscape)
    }

    @Test fun portraitIgnoresWideAndFill() {
        assertEquals(720 to 540, fitPicture(720, 1604, Aspect.WIDE))
        assertEquals(720 to 540, fitPicture(720, 1604, Aspect.FILL))
    }

    @Test fun wideAndFillInLandscape() {
        assertEquals(1280 to 720, fitPicture(1604, 720, Aspect.WIDE))
        assertEquals(1604 to 720, fitPicture(1604, 720, Aspect.FILL))
    }

    @Test fun nearSquareLandscapeFitsWidth() {
        assertEquals(800 to 600, fitPicture(800, 700, Aspect.NORMAL))
        assertEquals(800 to 600, fitPicture(1000, 600, Aspect.NORMAL))
    }

    @Test fun emptyWindowGivesEmptyPicture() {
        assertEquals(0 to 0, fitPicture(0, 0, Aspect.NORMAL))
        assertEquals(0 to 0, fitPicture(-5, 100, Aspect.FILL))
    }

    @Test fun aspectCyclesAndRestoresFromName() {
        assertEquals(Aspect.WIDE, Aspect.NORMAL.next())
        assertEquals(Aspect.FILL, Aspect.WIDE.next())
        assertEquals(Aspect.NORMAL, Aspect.FILL.next())
        assertEquals(Aspect.WIDE, Aspect.fromName("WIDE"))
        assertEquals(Aspect.NORMAL, Aspect.fromName(null))
        assertEquals(Aspect.NORMAL, Aspect.fromName("garbage"))
    }

    @Test fun dpadDeadZoneIsNeutral() {
        assertEquals(0, dpadDirection(0f, 0f, 168f))
        assertEquals(0, dpadDirection(10f, -10f, 168f)) // ~14 px from centre, dead zone is ~20 px
    }

    @Test fun dpadStraightDirections() {
        assertEquals(DIR_RIGHT, dpadDirection(60f, 0f, 168f))
        assertEquals(DIR_LEFT, dpadDirection(-60f, 0f, 168f))
        assertEquals(DIR_UP, dpadDirection(0f, -60f, 168f))
        assertEquals(DIR_DOWN, dpadDirection(0f, 60f, 168f))
    }

    @Test fun dpadDiagonalsPressTwoDirections() {
        assertEquals(DIR_UP or DIR_RIGHT, dpadDirection(40f, -40f, 168f))
        assertEquals(DIR_UP or DIR_LEFT, dpadDirection(-40f, -40f, 168f))
        assertEquals(DIR_DOWN or DIR_RIGHT, dpadDirection(40f, 40f, 168f))
        assertEquals(DIR_DOWN or DIR_LEFT, dpadDirection(-40f, 40f, 168f))
    }

    @Test fun dpadSectorsAreEvenlySplit() {
        // 10 degrees off an axis is still straight; 35 degrees is a diagonal.
        fun at(deg: Double): Int {
            val r = Math.toRadians(deg); return dpadDirection((60 * Math.cos(r)).toFloat(), (-60 * Math.sin(r)).toFloat(), 168f)
        }
        assertEquals(DIR_RIGHT, at(10.0)); assertEquals(DIR_UP or DIR_RIGHT, at(35.0)); assertEquals(DIR_UP or DIR_RIGHT, at(55.0))
        assertEquals(DIR_UP, at(80.0)); assertEquals(DIR_UP or DIR_LEFT, at(125.0)); assertEquals(DIR_LEFT, at(175.0))
        assertEquals(DIR_DOWN or DIR_LEFT, at(215.0)); assertEquals(DIR_DOWN, at(270.0)); assertEquals(DIR_DOWN or DIR_RIGHT, at(325.0))
    }
}
