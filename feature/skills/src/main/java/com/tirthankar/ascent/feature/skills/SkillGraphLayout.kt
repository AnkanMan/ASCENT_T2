package com.tirthankar.ascent.feature.skills

import com.tirthankar.ascent.engine.SkillDef
import com.tirthankar.ascent.engine.SkillTree

/** Where a node sits: its tree column, lane (0 left, 0.5 centre, 1 right) and row (tier − 1). */
data class NodeSlot(val treeIndex: Int, val lane: Float, val row: Int)

/**
 * The drawn tree from SPEC.md: Body, Mind and Self side by side, each with two chains
 * of three and the ultimate centred below, and the Observer tree at the right edge.
 */
object SkillGraphLayout {

    val TREES: List<SkillTree> = listOf(SkillTree.BODY, SkillTree.MIND, SkillTree.SELF, SkillTree.OBSERVER)

    const val CENTRE = 0.5f

    fun slots(skills: List<SkillDef>): Map<String, NodeSlot> {
        val result = mutableMapOf<String, NodeSlot>()
        TREES.forEachIndexed { treeIndex, tree ->
            val inTree = skills.filter { it.tree == tree }.sortedBy { it.tier }
            val firsts = inTree.filter { it.prerequisites.isEmpty() }
            inTree.forEach { skill ->
                val lane = when {
                    tree == SkillTree.OBSERVER -> CENTRE
                    skill.prerequisites.size > 1 -> CENTRE
                    skill.prerequisites.isEmpty() -> if (firsts.size > 1) firsts.indexOf(skill).coerceIn(0, 1).toFloat() else CENTRE
                    else -> result[skill.prerequisites.first()]?.lane ?: CENTRE
                }
                result[skill.id] = NodeSlot(treeIndex, lane, (skill.tier - 1).coerceAtLeast(0))
            }
        }
        return result
    }
}
