package com.tirthankar.ascent.engine

import kotlin.random.Random

/**
 * What a quest is, as far as rewards care. [stats] lets stat-scoped effects apply,
 * [tags] lets tagged effects apply ([QuestTags]), and [kindMultiplier] carries the
 * quest type's own factor (side 0.75, emergency 1.5).
 */
data class QuestRewardInput(
    val realm: Realm,
    val tier: Tier,
    val stats: Set<Stat> = emptySet(),
    val tags: Set<String> = emptySet(),
    val kindMultiplier: Double = 1.0,
    /** Powers on this quest or day (Limit Break ×3, Overload ×2, …): on top of the cap (A10, A16). */
    val powerMultiplier: Double = 1.0,
    /** The grind guard's m for a self-added quest ([GrindGuard]); 1 for everything else. Scales the base. */
    val guardMultiplier: Double = 1.0,
)

/** The player's state at the moment a quest completes. */
data class RewardContext(
    val level: Int,
    val streakDays: Int,
    /** The quest realm's current difficulty multiplier d. */
    val difficulty: Double,
    /** Base critical chance from AGI ([DerivedStats.critChance]). */
    val baseCritChance: Double,
    /** Skill and class effects (Phase 4). Uncapped. */
    val effects: List<Effect> = emptyList(),
    /** Effects from equipped gear, held under [gearCaps]. */
    val gearEffects: List<Effect> = emptyList(),
    val gearCaps: GearCaps = GearCaps(),
    /** Current chain lengths in days, by chain id (Momentum "body", Focus Chain "mind_focus"). */
    val chains: Map<String, Int> = emptyMap(),
    /** Realms with a completed quest today, for Resonance. */
    val realmsCompletedToday: Int = 0,
)

data class QuestReward(
    val xp: Long,
    val isCritical: Boolean,
)

/**
 * Folds every active [Effect] into the quest XP formula. Bonuses are data,
 * never special-cased: a new item or skill needs only new JSON.
 *
 * Stacking (decided 2026-11-11): multipliers add, so ×1.10 and ×1.15 give ×1.25.
 * Gear is capped (SPEC Phase 3): its XP bonus per realm at +30%, and total crit
 * chance with gear at 25%.
 * [random] is injected so crit rolls are reproducible in tests.
 */
class RewardCalculator(private val random: Random = Random.Default) {

    fun questReward(quest: QuestRewardInput, context: RewardContext): QuestReward {
        val isCritical = random.nextDouble() < critChance(context)
        val xp = QuestXp.xp(
            tier = quest.tier,
            difficulty = context.difficulty,
            level = context.level,
            streakDays = context.streakDays,
            isCritical = isCritical,
            streakCap = streakCap(context.effects + context.gearEffects),
            bonusMultiplier = xpMultiplier(quest, context.effects, context.gearEffects, context.gearCaps, context.chains, context.realmsCompletedToday) *
                quest.kindMultiplier * quest.powerMultiplier * quest.guardMultiplier.coerceIn(0.0, 1.0),
        )
        return QuestReward(xp = xp, isCritical = isCritical)
    }

    /** A crit roll on its own, for boss tasks (a crit doubles XP, never damage). */
    fun rollCritical(context: RewardContext): Boolean = random.nextDouble() < critChance(context)

    companion object {

        /** Passive bonuses (gear + skills + class) add up to at most +75% on a quest (A10, A11). */
        const val PASSIVE_CAP = 0.75

        /**
         * 1 + the passive bonus on [quest]: skill and class xp_mult, chain and Resonance
         * bonuses, and gear (itself capped at +30% per realm), together at most +75%.
         * Never below 0. The streak term, quest-kind factors, powers and crits sit outside.
         */
        fun xpMultiplier(
            quest: QuestRewardInput,
            effects: List<Effect>,
            gearEffects: List<Effect> = emptyList(),
            caps: GearCaps = GearCaps(),
            chains: Map<String, Int> = emptyMap(),
            realmsCompletedToday: Int = 0,
        ): Double {
            val skills = applicableBonus(quest, effects) +
                PassiveRules.chainBonus(quest.realm, effects, chains) +
                PassiveRules.resonanceBonus(effects, realmsCompletedToday)
            val gear = applicableBonus(quest, gearEffects).coerceAtMost(caps.xpPerRealm)
            return (1.0 + (skills + gear).coerceAtMost(PASSIVE_CAP)).coerceAtLeast(0.0)
        }

        /** 1 + the sum of mastery_mult bonuses for [stat] (and [tags]), never below 0. */
        fun masteryMultiplier(stat: Stat, effects: List<Effect>, tags: Set<String> = emptySet()): Double {
            val bonus = effects.filterIsInstance<Effect.MasteryMult>()
                .filter { it.stat == stat && (it.tag == null || it.tag in tags) }
                .sumOf { it.value - 1.0 }
            return (1.0 + bonus).coerceAtLeast(0.0)
        }

        /** The highest streak_cap, or the SPEC default of 1.5. */
        fun streakCap(effects: List<Effect>): Double =
            effects.filterIsInstance<Effect.StreakCap>()
                .maxOfOrNull { it.value }
                ?.coerceAtLeast(QuestXp.DEFAULT_STREAK_CAP)
                ?: QuestXp.DEFAULT_STREAK_CAP

        /** Base + gear crit (together at most [GearCaps.critTotal]), plus other bonuses, in 0..1. */
        fun critChance(context: RewardContext): Double {
            val gear = context.gearEffects.filterIsInstance<Effect.CritBonus>().sumOf { it.value }
            val withGear = if (gear > 0) minOf(context.gearCaps.critTotal, context.baseCritChance + gear) else context.baseCritChance
            val other = context.effects.filterIsInstance<Effect.CritBonus>().sumOf { it.value }
            return (withGear + other).coerceIn(0.0, 1.0)
        }

        private fun applicableBonus(quest: QuestRewardInput, effects: List<Effect>): Double =
            effects.filterIsInstance<Effect.XpMult>()
                .filter { it.appliesTo(quest) }
                .sumOf { it.value - 1.0 }

        private fun Effect.XpMult.appliesTo(quest: QuestRewardInput): Boolean =
            (realm == null || realm == quest.realm) &&
                (stat == null || stat in quest.stats) &&
                (tag == null || tag in quest.tags)
    }
}
