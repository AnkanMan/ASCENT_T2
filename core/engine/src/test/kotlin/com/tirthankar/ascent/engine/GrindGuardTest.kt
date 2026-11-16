package com.tirthankar.ascent.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** PHASE5.md section 6, "Grind guard". */
class GrindGuardTest {

    private val content = GuardContent()

    @Test
    fun `m(n) runs 1, 1, 1, 0_5, 0_25, 0_125, 0_1, 0_1`() {
        assertEquals(
            listOf(1.0, 1.0, 1.0, 0.5, 0.25, 0.125, 0.1, 0.1),
            (1..8).map { GrindGuard.multiplier(it, content) },
        )
        assertEquals(0.1, GrindGuard.multiplier(50, content))
    }

    @Test
    fun `the shipped guard json matches the PHASE5 numbers`() {
        val g = TestContent.bundle.guard
        assertEquals(3, g.freeSlots)
        assertEquals(0.5, g.decay)
        assertEquals(0.1, g.floor)
        assertEquals(GuardContent.DEFAULT_SLOTS, g.slots)
        assertTrue(ContentValidator.validate(TestContent.bundle).none { it.startsWith("guard") })
    }

    @Test
    fun `a Hard quest at slots 3 to 4 pays 0_5, an Extreme quest at slots 2 to 4 pays 0_5`() {
        val hard = GrindGuard.pay(usedSlots = 2, Tier.HARD, QuestOrigin.SELF_ADDED, content)
        assertEquals(3, hard.firstSlot)
        assertEquals(4, hard.lastSlot)
        assertEquals(0.5, hard.multiplier)
        val extreme = GrindGuard.pay(usedSlots = 1, Tier.EXTREME, QuestOrigin.SELF_ADDED, content)
        assertEquals(2, extreme.firstSlot)
        assertEquals(4, extreme.lastSlot)
        assertEquals(0.5, extreme.multiplier)
        // A Hard quest that fits in the free slots pays full.
        assertEquals(1.0, GrindGuard.pay(usedSlots = 1, Tier.HARD, QuestOrigin.SELF_ADDED, content).multiplier)
        listOf(Tier.TRIVIAL, Tier.EASY, Tier.NORMAL).forEach { assertEquals(1, GrindGuard.slots(it, content)) }
    }

    @Test
    fun `excluded sources never use slots`() {
        var day = GuardDay(day = 10)
        QuestOrigin.entries.filter { it != QuestOrigin.SELF_ADDED }.forEachIndexed { i, origin ->
            val (next, pay) = day.complete(i.toLong(), Tier.EXTREME, origin, content)
            assertEquals(GuardPay.UNGUARDED, pay)
            assertFalse(pay.guarded)
            day = next
        }
        assertEquals(0, day.usedSlots)
        assertEquals(1.0, day.complete(99, Tier.NORMAL, QuestOrigin.SELF_ADDED, content).second.multiplier)
    }

    @Test
    fun `no loot roll past the 3rd slot`() {
        assertTrue(GrindGuard.pay(2, Tier.NORMAL, QuestOrigin.SELF_ADDED, content).lootAllowed)
        assertFalse(GrindGuard.pay(3, Tier.NORMAL, QuestOrigin.SELF_ADDED, content).lootAllowed)
        // A Hard quest reaching into slot 4 loses the roll too.
        assertFalse(GrindGuard.pay(2, Tier.HARD, QuestOrigin.SELF_ADDED, content).lootAllowed)
        assertTrue(GrindGuard.pay(10, Tier.NORMAL, QuestOrigin.TEMPLATE, content).lootAllowed)
    }

    @Test
    fun `the guard resets at the game-day boundary`() {
        var day = GuardDay(day = 20)
        repeat(5) { day = day.complete(it.toLong(), Tier.NORMAL, QuestOrigin.SELF_ADDED, content).first }
        assertEquals(5, day.usedSlots)
        assertEquals(0.125, GrindGuard.nextMultiplier(day.usedSlots, content))
        assertEquals(day, day.on(20))
        val tomorrow = day.on(21)
        assertEquals(0, tomorrow.usedSlots)
        assertEquals(1.0, tomorrow.complete(9, Tier.NORMAL, QuestOrigin.SELF_ADDED, content).second.multiplier)
    }

    @Test
    fun `undo frees slots without repaying anyone`() {
        var day = GuardDay(day = 1)
        val paid = mutableMapOf<Long, Double>()
        (1L..4L).forEach { id -> day.complete(id, Tier.NORMAL, QuestOrigin.SELF_ADDED, content).let { (d, p) -> day = d; paid[id] = p.multiplier } }
        assertEquals(listOf(1.0, 1.0, 1.0, 0.5), paid.values.toList())
        day = day.undo(1)
        assertEquals(3, day.usedSlots)
        // Redoing quest 1 now takes slot 4: it pays less than before, never more.
        assertEquals(0.5, day.complete(1, Tier.NORMAL, QuestOrigin.SELF_ADDED, content).second.multiplier)
        // Completing the same quest twice doesn't take a second slot.
        assertEquals(day.usedSlots, day.complete(2, Tier.NORMAL, QuestOrigin.SELF_ADDED, content).first.usedSlots)
    }

    @Test
    fun `mastery scales with the guard, rounded half-up`() {
        assertEquals(10, GrindGuard.scaleMastery(10, 1.0))
        assertEquals(5, GrindGuard.scaleMastery(10, 0.5))
        assertEquals(1, GrindGuard.scaleMastery(5, 0.25))
        assertEquals(0, GrindGuard.scaleMastery(4, 0.1))
    }

    @Test
    fun `the guard scales quest XP before the rest of the formula`() {
        val calc = RewardCalculator(Random(1))
        val context = RewardContext(level = 10, streakDays = 5, difficulty = 1.0, baseCritChance = 0.0)
        val full = calc.questReward(QuestRewardInput(Realm.SELF, Tier.NORMAL), context).xp
        val quarter = calc.questReward(QuestRewardInput(Realm.SELF, Tier.NORMAL, guardMultiplier = 0.25), context).xp
        // 80 × 1.5 × 1.1 = 132; a quarter is 33.
        assertEquals(132, full)
        assertEquals(33, quarter)
    }

    private val calc = RewardCalculator(Random(0))
    private val context = RewardContext(level = 12, streakDays = 3, difficulty = 1.1, baseCritChance = 0.0)

    private fun xp(tier: Tier, m: Double) =
        calc.questReward(QuestRewardInput(Realm.MIND, tier, guardMultiplier = m), context).xp

    /** What [order] pays when completed one after another on a fresh day, with no undos. */
    private fun fresh(order: List<Int>, tiers: List<Tier>, origins: List<QuestOrigin>): Long {
        var day = GuardDay(day = 1)
        return order.sumOf { id ->
            val (next, pay) = day.complete(id.toLong(), tiers[id], origins[id], content)
            day = next
            xp(tiers[id], pay.multiplier)
        }
    }

    /**
     * Property: whatever the order of completions and undos, the XP held for the quests
     * completed right now never exceeds what those same quests pay when completed fresh,
     * in the order of their latest completion. Undo refunds exactly, so cycling can't
     * gain XP. (A quest's used slots at completion are at least the slots of every quest
     * ahead of it in that order, and m never rises with more slots.)
     */
    @Test
    fun `complete and undo in any order never increase total XP`() {
        repeat(300) { run ->
            val random = Random(run)
            val tiers = List(8) { Tier.entries[random.nextInt(Tier.entries.size)] }
            val origins = List(8) { if (random.nextDouble() < 0.75) QuestOrigin.SELF_ADDED else QuestOrigin.TEMPLATE }
            var day = GuardDay(day = 1)
            val paid = linkedMapOf<Int, Long>() // in order of latest completion
            repeat(60) {
                val id = random.nextInt(8)
                if (id in paid) {
                    day = day.undo(id.toLong())
                    paid.remove(id)
                } else {
                    val (next, pay) = day.complete(id.toLong(), tiers[id], origins[id], content)
                    day = next
                    paid[id] = xp(tiers[id], pay.multiplier)
                }
                val total = paid.values.sum()
                val honest = fresh(paid.keys.toList(), tiers, origins)
                assertTrue(total <= honest, "run $run: holding $total for ${paid.keys}, fresh pays $honest")
                assertEquals(
                    paid.keys.filter { origins[it] == QuestOrigin.SELF_ADDED }.sumOf { GrindGuard.slots(tiers[it], content) },
                    day.usedSlots,
                )
            }
        }
    }
}
