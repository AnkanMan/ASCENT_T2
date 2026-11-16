package com.tirthankar.ascent.feature.status

import com.tirthankar.ascent.engine.Rank
import com.tirthankar.ascent.engine.Stat
import org.junit.Test
import kotlin.test.assertEquals

class StatusUiStateMapperTest {

    @Test
    fun `sample player maps to the expected display state`() {
        val state = StatusUiStateMapper.map(SamplePlayer.snapshot)
        assertEquals(10, state.level)
        assertEquals(1_309L, state.xpIntoLevel) // 9,000 − 7,691
        assertEquals(1_995L, state.xpForNext)
        assertEquals(250, state.maxHp) // 100 + 5·12 + 10·9
        assertEquals(164, state.maxMp) // 50 + 5·15 + 3·13
        assertEquals(180, state.hp)
        assertEquals(120, state.mp)
        assertEquals(Rank.E, state.rank)
        assertEquals(Stat.entries, state.stats.map { it.stat })
        assertEquals(listOf(12, 11, 15, 13, 10, 9), state.stats.map { it.value })
        assertEquals(12, state.streakDays)
    }

    @Test
    fun `hp and mp never exceed their maximums`() {
        val state = StatusUiStateMapper.map(SamplePlayer.snapshot.copy(hp = 999, mp = -5))
        assertEquals(state.maxHp, state.hp)
        assertEquals(0, state.mp)
    }
}
