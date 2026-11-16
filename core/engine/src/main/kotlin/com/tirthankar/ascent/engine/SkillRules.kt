package com.tirthankar.ascent.engine

/** Why a skill can't be unlocked (or [Ok]). The UI shows one line for each. */
sealed interface UnlockCheck {
    data object Ok : UnlockCheck
    data object AlreadyUnlocked : UnlockCheck
    data object NoPoints : UnlockCheck
    data object Locked : UnlockCheck
    data class MissingPrerequisite(val skillId: String) : UnlockCheck
    data class GateStat(val needed: Int, val have: Int) : UnlockCheck
    data object NeedsBoss : UnlockCheck
}

/** What a respec does, or null when it can't be paid for. */
data class Respec(val pointsReturned: Int, val usesToken: Boolean, val essenceCost: Int)

/**
 * Skill points and unlocks (PHASE4.md section 1, A4). Points: one at every even level
 * plus one per boss defeated. Class skills come with the class and cost nothing.
 */
object SkillRules {

    const val LEVELS_PER_POINT = 2

    fun pointsEarned(level: Int, bossesDefeated: Int): Int = level / LEVELS_PER_POINT + bossesDefeated

    /** Points spent: one per unlocked tree skill (class skills are free). */
    fun pointsSpent(unlocked: Set<String>, content: SkillContent): Int =
        unlocked.count { id -> content.skill(id)?.let { it.tree != SkillTree.CLASS } ?: false }

    fun unspent(level: Int, bossesDefeated: Int, unlocked: Set<String>, content: SkillContent): Int =
        (pointsEarned(level, bossesDefeated) - pointsSpent(unlocked, content)).coerceAtLeast(0)

    /** The higher of a tree's two stats (A4). */
    fun gateStat(tree: SkillTree, stats: StatBlock, content: SkillContent): Int =
        content.treeStats[tree]?.maxOf { stats[it] } ?: 0

    fun canUnlock(
        skill: SkillDef,
        unlocked: Set<String>,
        unspentPoints: Int,
        stats: StatBlock,
        bossesDefeated: Int,
        content: SkillContent,
    ): UnlockCheck {
        if (skill.id in unlocked) return UnlockCheck.AlreadyUnlocked
        if (skill.locked || skill.tree == SkillTree.CLASS || skill.tree == SkillTree.OBSERVER) return UnlockCheck.Locked
        skill.prerequisites.firstOrNull { it !in unlocked }?.let { return UnlockCheck.MissingPrerequisite(it) }
        val needed = content.tierGates[skill.tier]
        if (needed != null) {
            val have = gateStat(skill.tree, stats, content)
            if (have < needed) return UnlockCheck.GateStat(needed, have)
        }
        if (skill.tier == ULTIMATE_TIER && bossesDefeated < 1) return UnlockCheck.NeedsBoss
        if (unspentPoints < 1) return UnlockCheck.NoPoints
        return UnlockCheck.Ok
    }

    /** A respec returns every point spent: free with a token, otherwise [SkillContent.respecEssence]. */
    fun respec(unlocked: Set<String>, tokens: Int, essence: Int, content: SkillContent): Respec? {
        val points = pointsSpent(unlocked, content)
        return when {
            tokens > 0 -> Respec(points, usesToken = true, essenceCost = 0)
            essence >= content.respecEssence -> Respec(points, usesToken = false, essenceCost = content.respecEssence)
            else -> null
        }
    }

    /** Effects of every unlocked passive (folded by RewardCalculator). */
    fun passiveEffects(unlocked: Set<String>, content: SkillContent): List<Effect> =
        unlocked.mapNotNull { content.skill(it) }.filter { it.kind == SkillKind.PASSIVE && !it.locked }.flatMap { it.effects }

    const val ULTIMATE_TIER = 4
}
