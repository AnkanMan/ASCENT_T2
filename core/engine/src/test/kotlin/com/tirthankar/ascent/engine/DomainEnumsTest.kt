package com.tirthankar.ascent.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class DomainEnumsTest {

    @Test
    fun `each stat belongs to its SPEC realm`() {
        val expected = mapOf(
            Stat.STR to Realm.BODY,
            Stat.AGI to Realm.BODY,
            Stat.INT to Realm.MIND,
            Stat.FOC to Realm.MIND,
            Stat.WIS to Realm.SELF,
            Stat.VIT to Realm.SELF,
        )
        assertEquals(expected, Stat.entries.associateWith { it.realm })
    }

    @Test
    fun `every realm feeds exactly two stats`() {
        Realm.entries.forEach { realm ->
            assertEquals(2, Stat.entries.count { it.realm == realm }, "realm $realm")
        }
    }

    @Test
    fun `tier base XP matches SPEC`() {
        assertEquals(
            listOf(20, 40, 80, 150, 300),
            Tier.entries.map { it.baseXp },
        )
        assertEquals(
            listOf(Tier.TRIVIAL, Tier.EASY, Tier.NORMAL, Tier.HARD, Tier.EXTREME),
            Tier.entries.toList(),
        )
    }
}
