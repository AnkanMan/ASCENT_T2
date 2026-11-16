package com.tirthankar.ascent.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContentTest {

    private val content = TestContent.bundle

    @Test
    fun `shipped content passes validation`() {
        assertEquals(emptyList(), ContentValidator.validate(content))
    }

    @Test
    fun `every content file loads with the expected sizes`() {
        assertEquals(DropSource.entries.toSet(), content.loot.tables.keys)
        assertTrue(content.items.items.size >= 15)
        assertEquals(3, content.items.sets.size)
        assertTrue(Realm.entries.all { realm -> content.gates.gates.count { it.realm == realm } >= 2 })
        assertEquals(14, content.achievements.achievements.size) // + Bound State, Bound Triplet (Phase 5)
        assertEquals(4, content.arcs.arcs.size)
        assertEquals(70, content.lore.entries.size) // + Bound state, Conserved (Phase 5)
    }

    @Test
    fun `validation catches broken content`() {
        val broken = content.copy(
            loot = content.loot.copy(
                tables = content.loot.tables + (DropSource.BOSS to DropTable(weights = mapOf(Rarity.EPIC to 50))),
                rollingAffixes = content.loot.rollingAffixes + AffixType.INSIGHT,
            ),
            quests = content.quests.copy(
                penalty = content.quests.penalty + TaskTemplate("p_bad", Realm.SELF, Stat.VIT, Tier.EASY, "Skip dinner tonight"),
            ),
            lore = content.lore.copy(
                entries = content.lore.entries + LoreEntry("lore_bad", "Bad", LoreUnlock("awakening"), "The shadow army rises."),
            ),
        )
        val problems = ContentValidator.validate(broken)
        assertTrue(problems.any { "BOSS weights sum to 50" in it }, problems.toString())
        assertTrue(problems.any { "Phase 4 affix" in it }, problems.toString())
        assertTrue(problems.any { "forbidden" in it && "p_bad" in it }, problems.toString())
        assertTrue(problems.any { "lore_bad" in it }, problems.toString())
    }

    @Test
    fun `franchise and forbidden filters`() {
        assertTrue(LoreRules.hasFranchiseWords("Arise, my army"))
        assertTrue(!LoreRules.isValid("x".repeat(201)))
        assertTrue(LoreRules.isValid("A measurement is not a verdict."))
    }
}
