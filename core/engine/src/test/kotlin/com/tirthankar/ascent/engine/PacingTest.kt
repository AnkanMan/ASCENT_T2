package com.tirthankar.ascent.engine

import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Checks the XP curve against SPEC's rank timeline, under SPEC's own assumption:
 * a full Daily Quest pays about 300 × (1 + 0.05 L) XP (d = 1, no streak bonus, no crit).
 * If this fails, report it. Don't tune SPEC numbers without approval.
 */
class PacingTest {

    private fun dayReached(): Map<Int, Int> {
        val reached = mutableMapOf<Int, Int>()
        var totalXp = 0L
        var day = 0
        while (XpCurve.levelProgress(totalXp).level < 100) {
            day++
            val level = XpCurve.levelProgress(totalXp).level
            totalXp += floor(300 * QuestXp.levelMultiplier(level) + 0.5).toLong()
            val newLevel = XpCurve.levelProgress(totalXp).level
            (level + 1..newLevel).forEach { reached.putIfAbsent(it, day) }
        }
        return reached
    }

    @Test
    fun `rank thresholds arrive when SPEC says`() {
        val reached = dayReached()
        // Rank, SPEC's "time at full pace" in days, allowed band.
        val expectations = listOf(
            Triple(Rank.D, 21, 17..25), // ~3 weeks
            Triple(Rank.C, 122, 105..140), // ~4 months
            Triple(Rank.B, 365, 330..400), // ~1 year
            Triple(Rank.A, 767, 700..840), // ~2.1 years
            Triple(Rank.S, 1388, 1300..1480), // ~3.8 years
        )
        println("Rank  level  day   years   (SPEC)")
        expectations.forEach { (rank, specDays, band) ->
            val day = reached.getValue(rank.minLevel!!)
            println("%-4s  %5d  %4d  %5.2f   (~%d days)".format(rank, rank.minLevel, day, day / 365.0, specDays))
            assertTrue(day in band, "$rank reached on day $day, SPEC expects ~$specDays")
        }
    }
}
