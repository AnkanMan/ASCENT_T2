package com.tirthankar.ascent.feature.skills

import com.tirthankar.ascent.engine.SkillTree
import org.junit.Test
import kotlin.test.assertEquals

class SkillGraphLayoutTest {

    private val skills = SampleSkills.defs
    private val slots = SkillGraphLayout.slots(skills)

    @Test
    fun `every skill has a slot and no two share one`() {
        assertEquals(skills.size, slots.size)
        assertEquals(slots.size, slots.values.toSet().size)
    }

    @Test
    fun `chains keep their lane and ultimates sit in the centre`() {
        assertEquals(slots.getValue("iron_body").lane, slots.getValue("limit_break").lane)
        assertEquals(slots.getValue("light_feet").lane, slots.getValue("recovery_aura").lane)
        assertEquals(SkillGraphLayout.CENTRE, slots.getValue("unbroken").lane)
        assertEquals(3, slots.getValue("unbroken").row)
    }

    @Test
    fun `trees are columns in order, the Observer tree last`() {
        assertEquals(0, slots.getValue("iron_body").treeIndex)
        assertEquals(1, slots.getValue("deep_work").treeIndex)
        assertEquals(2, slots.getValue("restful").treeIndex)
        assertEquals(3, slots.getValue("superposition").treeIndex)
        assertEquals(SkillTree.OBSERVER, SkillGraphLayout.TREES.last())
    }
}
