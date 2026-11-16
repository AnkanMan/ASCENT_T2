package com.tirthankar.ascent.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class MasteryGrowthTest {

    @Test
    fun `mastery per tier doubles`() {
        assertEquals(listOf(2, 5, 10, 20, 40), Tier.entries.map(MasteryGrowth::baseMastery))
    }

    @Test
    fun `mastery effects multiply the stat they name`() {
        val effects = listOf(Effect.MasteryMult(Stat.INT, 1.1))
        assertEquals(11, MasteryGrowth.masteryFor(Tier.NORMAL, Stat.INT, effects))
        assertEquals(5, MasteryGrowth.masteryFor(Tier.EASY, Stat.STR, effects))
    }

    @Test
    fun `stat rises at fifty plus ten times value`() {
        assertEquals(StatGrowth(value = 10, mastery = 149, statGain = 0), MasteryGrowth.apply(10, 0, 149))
        assertEquals(StatGrowth(value = 11, mastery = 0, statGain = 1), MasteryGrowth.apply(10, 0, 150))
    }

    @Test
    fun `overflow carries over`() {
        assertEquals(StatGrowth(value = 11, mastery = 20, statGain = 1), MasteryGrowth.apply(10, 140, 30))
        // 150 to reach 11, then 160 to reach 12, 5 left over.
        assertEquals(StatGrowth(value = 12, mastery = 5, statGain = 2), MasteryGrowth.apply(10, 0, 315))
    }

    @Test
    fun `remove is the exact inverse of apply`() {
        listOf(Triple(10, 0, 149), Triple(10, 140, 30), Triple(10, 0, 315), Triple(12, 7, 20)).forEach { (v, m, g) ->
            val grown = MasteryGrowth.apply(v, m, g)
            val back = MasteryGrowth.remove(grown.value, grown.mastery, g)
            assertEquals(v to m, back.value to back.mastery, "$v/$m + $g")
            assertEquals(-grown.statGain, back.statGain)
        }
    }

    @Test
    fun `remove never goes below zero`() {
        assertEquals(StatGrowth(0, 0, 0), MasteryGrowth.remove(0, 3, 10))
    }

    @Test
    fun `fifteen normal quests raise a stat of ten`() {
        var growth = StatGrowth(10, 0, 0)
        repeat(15) { growth = MasteryGrowth.apply(growth.value, growth.mastery, MasteryGrowth.baseMastery(Tier.NORMAL)) }
        assertEquals(11, growth.value)
        assertEquals(0, growth.mastery)
    }
}
