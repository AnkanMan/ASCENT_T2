package com.tirthankar.ascent.engine

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LootTest {

    private val content = TestContent.bundle

    @Test
    fun `splitmix64 matches the reference sequence`() {
        val rng = SplitMix64(0)
        assertEquals(-0x1ddf57c684e23251L, rng.nextLong()) // 0xE220A8397B1DCDAF
        assertEquals(0x6e789e6aa1b965f4L, rng.nextLong())
    }

    @Test
    fun `seeds are stable per event and differ between events`() {
        assertEquals(Seeds.forEvent(7, "quest:1"), Seeds.forEvent(7, "quest:1"))
        assertNotEquals(Seeds.forEvent(7, "quest:1"), Seeds.forEvent(7, "quest:2"))
        assertNotEquals(Seeds.forEvent(7, "quest:1"), Seeds.forEvent(8, "quest:1"))
    }

    @Test
    fun `the same event always gives the same drop`() {
        repeat(50) { i ->
            val seed = Seeds.forEvent(123, "gate:$i:chest:0")
            val a = LootRoller.roll(DropSource.CHEST_CB, seed, content.loot, content.items)
            val b = LootRoller.roll(DropSource.CHEST_CB, seed, content.loot, content.items)
            assertEquals(a, b)
        }
    }

    @Test
    fun `100,000 rolls match each table within 1 percent`() {
        for ((source, table) in content.loot.tables) {
            val counts = mutableMapOf<Rarity, Int>()
            var hits = 0
            repeat(N) { i ->
                val rarity = LootRoller.rollRarity(table, SplitMix64(Seeds.forEvent(99, "$source:$i")))
                if (rarity != null) {
                    hits++
                    counts.merge(rarity, 1, Int::plus)
                }
            }
            assertTrue(abs(hits.toDouble() / N - table.rollChance) < 0.01, "$source roll chance")
            Rarity.entries.forEach { rarity ->
                val expected = (table.weights[rarity] ?: 0) / 100.0
                val actual = (counts[rarity] ?: 0).toDouble() / hits
                assertTrue(abs(actual - expected) < 0.01, "$source $rarity: $actual vs $expected")
            }
        }
    }

    @Test
    fun `chest luck shifts rolls rarer but never into Mythic`() {
        val table = content.loot.tables.getValue(DropSource.CHEST_AS)
        val lucky = List(5_000) { LootRoller.rollRarity(table, SplitMix64(it.toLong()), chestLuck = 1.0)!! }
        assertTrue(lucky.none { it == Rarity.COMMON })
        assertTrue(lucky.none { it == Rarity.MYTHIC })
    }

    @Test
    fun `generated items respect their rarity's budget and never roll Phase 4 affixes`() {
        repeat(2_000) { i ->
            val item = LootRoller.roll(DropSource.CHEST_AS, Seeds.forEvent(5, "t:$i"), content.loot, content.items) ?: return@repeat
            if (item.templateId == null) {
                val rule = content.loot.rarities.getValue(item.rarity)
                assertTrue(item.affixes.size in rule.minAffixes..rule.maxAffixes)
                assertTrue(item.affixes.all { it.value <= rule.maxPerAffix + 1e-9 && it.value > 0 })
                assertTrue(item.affixes.none { it.type.phase4 })
                assertEquals(item.affixes.size, item.affixes.map { it.type }.toSet().size)
            }
        }
    }

    @Test
    fun `legendary and mythic drops are hand-written, raids give mythics`() {
        val raid = List(500) { LootRoller.roll(DropSource.RAID, Seeds.forEvent(1, "raid:$it"), content.loot, content.items)!! }
        assertTrue(raid.all { it.templateId != null && it.unique != null })
        assertTrue(raid.any { it.rarity == Rarity.MYTHIC })
        // Achievement-only items never come from a roll.
        assertTrue(raid.none { it.templateId == "boots_random_walker" })
    }

    @Test
    fun `side quest rolls usually miss`() {
        val drops = (0 until 1_000).count { LootRoller.roll(DropSource.SIDE_QUEST, it.toLong(), content.loot, content.items) != null }
        assertTrue(drops in 60..140, "$drops")
    }

    @Test
    fun `upgrades add 20 percent of base per level, costs and essence follow the table`() {
        assertEquals(0.05 * 2.0, GearRules.upgradedValue(0.05, 5), 1e-12)
        assertEquals(0.05 * 1.2, GearRules.upgradedValue(0.05, 1), 1e-12)
        assertEquals(5, GearRules.upgradeCost(0, content.loot.upgradeCosts))
        assertEquals(80, GearRules.upgradeCost(4, content.loot.upgradeCosts))
        assertNull(GearRules.upgradeCost(5, content.loot.upgradeCosts))
        assertEquals(30, GearRules.essenceFor(Rarity.LEGENDARY, content.loot.rarities))
        assertNull(GearRules.essenceFor(Rarity.MYTHIC, content.loot.rarities))
    }

    @Test
    fun `a fully geared plus-5 character hits the caps`() {
        // Seven pieces, each +8% Mind XP and +4% crit, upgraded to +5: 7 × 16% = 112% before the cap.
        val pieces = List(7) {
            GearPiece(
                affixes = listOf(Affix(AffixType.XP_REALM, 0.08, realm = Realm.MIND), Affix(AffixType.CRIT, 0.04)),
                upgradeLevel = 5,
            )
        }
        val gear = GearRules.fold(pieces, content.items.sets)
        val quest = QuestRewardInput(Realm.MIND, Tier.NORMAL, setOf(Stat.INT))
        assertEquals(1.30, RewardCalculator.xpMultiplier(quest, emptyList(), gear.effects), 1e-9)
        val context = RewardContext(level = 10, streakDays = 0, difficulty = 1.0, baseCritChance = 0.10, gearEffects = gear.effects)
        assertEquals(0.25, RewardCalculator.critChance(context), 1e-9)
        // Other realms are untouched by Mind gear.
        assertEquals(1.0, RewardCalculator.xpMultiplier(QuestRewardInput(Realm.BODY, Tier.NORMAL), emptyList(), gear.effects), 1e-9)
    }

    @Test
    fun `set bonuses need three pieces, dormant parts are listed not folded`() {
        val quiet = content.items.items.filter { it.setId == "quiet_hours" }.map { GearPiece(it.affixes, 0, it.unique, it.setId) }
        val sleep = QuestRewardInput(Realm.SELF, Tier.NORMAL, setOf(Stat.VIT), tags = setOf(QuestTags.SLEEP))
        val two = GearRules.fold(quiet.take(2), content.items.sets)
        val three = GearRules.fold(quiet, content.items.sets)
        assertTrue(RewardCalculator.xpMultiplier(sleep, emptyList(), three.effects) - RewardCalculator.xpMultiplier(sleep, emptyList(), two.effects) > 0.19)

        val blade = content.item("hamiltonian_blade")!!
        val bonus = GearRules.fold(listOf(GearPiece(blade.affixes, 0, blade.unique)), content.items.sets)
        // Phase 4 woke the free Insight (A12): nothing on the blade is dormant any more.
        assertTrue(bonus.dormant.isEmpty())
        assertEquals(1, PassiveRules.freeInsightCharges(bonus.effects))
        val boots = content.item("boots_random_walker")!!
        assertEquals(1, GearRules.fold(listOf(GearPiece(boots.affixes, 0, boots.unique)), emptyList()).dormant.size) // Phase 5 part
        val mind = QuestRewardInput(Realm.MIND, Tier.NORMAL, setOf(Stat.INT))
        assertEquals(1.15, RewardCalculator.xpMultiplier(mind, emptyList(), bonus.effects), 1e-9)
    }

    @Test
    fun `trial-tagged mastery applies only to trials`() {
        val gauntlets = content.item("hard_rod_gauntlets")!!
        val effects = GearRules.fold(listOf(GearPiece(gauntlets.affixes, 0, gauntlets.unique)), emptyList()).effects
        assertEquals(1.10, RewardCalculator.masteryMultiplier(Stat.STR, effects), 1e-9)
        assertEquals(2.10, RewardCalculator.masteryMultiplier(Stat.STR, effects, setOf(QuestTags.TRIAL)), 1e-9)
    }

    @Test
    fun `gear bonus sums regen, luck and gate reward`() {
        val bonus = GearRules.fold(
            listOf(
                GearPiece(listOf(Affix(AffixType.HP_REGEN, 0.04), Affix(AffixType.CHEST_LUCK, 0.03)), 0),
                GearPiece(listOf(Affix(AffixType.GATE_REWARD, 0.10, realm = Realm.MIND)), 0),
            ),
            emptyList(),
        )
        assertEquals(0.04, bonus.hpRegen, 1e-9)
        assertEquals(0.03, bonus.chestLuck, 1e-9)
        assertEquals(1.10, bonus.gateRewardMultiplier(Realm.MIND), 1e-9)
        assertEquals(1.0, bonus.gateRewardMultiplier(Realm.BODY), 1e-9)
    }

    private companion object {
        const val N = 100_000
    }
}
