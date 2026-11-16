package com.tirthankar.ascent.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EffectCodecTest {

    @Test
    fun `decodes the SPEC example`() {
        assertEquals(
            Effect.XpMult(realm = Realm.MIND, value = 1.15),
            EffectCodec.decode("""{"kind":"xp_mult","realm":"MIND","value":1.15}"""),
        )
    }

    @Test
    fun `encodes in the SPEC shape`() {
        assertEquals(
            """{"kind":"xp_mult","realm":"MIND","value":1.15}""",
            EffectCodec.encode(Effect.XpMult(realm = Realm.MIND, value = 1.15)),
        )
    }

    @Test
    fun `every kind round-trips`() {
        val effects = listOf(
            Effect.XpMult(value = 1.1),
            Effect.XpMult(stat = Stat.INT, value = 1.1),
            Effect.MasteryMult(stat = Stat.STR, value = 1.1),
            Effect.StreakCap(value = 1.6),
            Effect.CritBonus(value = 0.05),
        )
        effects.forEach { assertEquals(it, EffectCodec.decode(EffectCodec.encode(it))) }
        assertEquals(EffectDecodeResult(effects, emptyList()), EffectCodec.decodeList(EffectCodec.encodeList(effects)))
    }

    @Test
    fun `unknown kind is skipped and reported`() {
        val result = EffectCodec.decodeList(
            """[{"kind":"teleport","value":1},{"kind":"streak_cap","value":1.6}]""",
        )
        assertEquals(listOf<Effect>(Effect.StreakCap(1.6)), result.effects)
        assertEquals(1, result.rejected.size)
        assertTrue(result.rejected.single().contains("teleport"))
        assertNull(EffectCodec.decode("""{"kind":"teleport","value":1}"""))
    }

    @Test
    fun `invalid values are rejected`() {
        assertNull(EffectCodec.decode("""{"kind":"xp_mult","value":0}"""))
        assertNull(EffectCodec.decode("""{"kind":"streak_cap","value":0.9}"""))
        assertNull(EffectCodec.decode("""{"kind":"crit_bonus","value":2}"""))
        assertNull(EffectCodec.decode("""{"kind":"xp_mult","realm":"SPACE","value":1.1}"""))
    }

    @Test
    fun `malformed JSON never throws`() {
        assertNull(EffectCodec.decode("""{"kind":"""))
        val result = EffectCodec.decodeList("not json")
        assertEquals(emptyList(), result.effects)
        assertEquals(listOf("not json"), result.rejected)
        assertEquals(listOf("""{"kind":"xp_mult"}"""), EffectCodec.decodeList("""{"kind":"xp_mult"}""").rejected)
    }

    @Test
    fun `unknown keys are ignored`() {
        assertEquals(
            Effect.StreakCap(1.6),
            EffectCodec.decode("""{"kind":"streak_cap","value":1.6,"note":"Toda Chain"}"""),
        )
    }
}
