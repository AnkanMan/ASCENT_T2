package com.tirthankar.ascent.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DerivedStatsTest {

    private val tens = StatBlock.uniform(StatBlock.STARTING_VALUE)

    @Test
    fun `starting stats give the SPEC values`() {
        assertEquals(250, DerivedStats.maxHp(tens)) // 100 + 50 + 100
        assertEquals(130, DerivedStats.maxMp(tens)) // 50 + 50 + 30
        assertEquals(0.05, DerivedStats.critChance(tens), 1e-12)
        assertEquals(0.10, DerivedStats.cooldownReduction(tens), 1e-12)
    }

    @Test
    fun `max HP and MP use the right stats`() {
        val stats = StatBlock(str = 12, agi = 0, int = 15, foc = 13, wis = 99, vit = 9)
        assertEquals(100 + 60 + 90, DerivedStats.maxHp(stats))
        assertEquals(50 + 75 + 39, DerivedStats.maxMp(stats))
    }

    @Test
    fun `crit chance caps at 25 percent`() {
        assertEquals(0.245, DerivedStats.critChance(tens.copy(agi = 49)), 1e-12)
        assertEquals(0.25, DerivedStats.critChance(tens.copy(agi = 50)), 1e-12)
        assertEquals(0.25, DerivedStats.critChance(tens.copy(agi = 60)), 1e-12)
    }

    @Test
    fun `cooldown reduction caps at 40 percent`() {
        assertEquals(0.39, DerivedStats.cooldownReduction(tens.copy(foc = 39)), 1e-12)
        assertEquals(0.40, DerivedStats.cooldownReduction(tens.copy(foc = 40)), 1e-12)
        assertEquals(0.40, DerivedStats.cooldownReduction(tens.copy(foc = 50)), 1e-12)
    }

    @Test
    fun `mastery threshold is 50 plus 10 times the value`() {
        assertEquals(50, Mastery.masteryToNext(0))
        assertEquals(150, Mastery.masteryToNext(10))
        assertEquals(550, Mastery.masteryToNext(50))
    }

    @Test
    fun `stat block lookup by stat`() {
        val stats = StatBlock(1, 2, 3, 4, 5, 6)
        assertEquals(listOf(1, 2, 3, 4, 5, 6), Stat.entries.map { stats[it] })
    }

    @Test
    fun `negative values are rejected`() {
        assertFailsWith<IllegalArgumentException> { tens.copy(vit = -1) }
        assertFailsWith<IllegalArgumentException> { Mastery.masteryToNext(-1) }
    }
}
