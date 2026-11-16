package com.tirthankar.ascent.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class XpCurveTest {

    @Test
    fun `xpToNext matches floor(100 n^1_3) for the first ten levels`() {
        assertEquals(
            listOf(100L, 246L, 417L, 606L, 810L, 1027L, 1254L, 1492L, 1739L, 1995L),
            (1..10).map(XpCurve::xpToNext),
        )
    }

    @Test
    fun `xpToNext is exact where n^1_3 is an integer`() {
        // 1024^1.3 = 2^13 exactly; floating-point error must not floor it down.
        assertEquals(819_200L, XpCurve.xpToNext(1024))
    }

    @Test
    fun `xpToNext strictly increases`() {
        (1 until 200).forEach { n ->
            assertTrue(XpCurve.xpToNext(n + 1) > XpCurve.xpToNext(n), "at level $n")
        }
    }

    @Test
    fun `totalXpToReach sums the curve`() {
        assertEquals(0L, XpCurve.totalXpToReach(1))
        assertEquals(100L, XpCurve.totalXpToReach(2))
        assertEquals(7_691L, XpCurve.totalXpToReach(10))
        assertEquals(1_710_984L, XpCurve.totalXpToReach(100))
    }

    @Test
    fun `zero XP is level 1 with an empty bar`() {
        assertEquals(LevelProgress(level = 1, xpIntoLevel = 0, xpForNext = 100), XpCurve.levelProgress(0))
        assertEquals(0.0, XpCurve.levelProgress(0).fraction)
    }

    @Test
    fun `level boundaries are exact`() {
        assertEquals(LevelProgress(1, 99, 100), XpCurve.levelProgress(99))
        assertEquals(LevelProgress(2, 0, 246), XpCurve.levelProgress(100))
        assertEquals(LevelProgress(9, 1_738, 1_739), XpCurve.levelProgress(7_690))
        assertEquals(LevelProgress(10, 0, 1_995), XpCurve.levelProgress(7_691))
    }

    @Test
    fun `levelProgress round-trips with totalXpToReach`() {
        (1..200).forEach { level ->
            val start = XpCurve.totalXpToReach(level)
            assertEquals(level, XpCurve.levelProgress(start).level, "start of $level")
            assertEquals(level, XpCurve.levelProgress(start + XpCurve.xpToNext(level) - 1).level, "end of $level")
        }
    }

    @Test
    fun `fraction is the share of the current level earned`() {
        assertEquals(0.5, XpCurve.levelProgress(50).fraction)
    }

    @Test
    fun `invalid input is rejected`() {
        assertFailsWith<IllegalArgumentException> { XpCurve.xpToNext(0) }
        assertFailsWith<IllegalArgumentException> { XpCurve.totalXpToReach(0) }
        assertFailsWith<IllegalArgumentException> { XpCurve.levelProgress(-1) }
    }
}
