package com.tirthankar.ascent.engine

import kotlinx.serialization.Serializable

/** Condition kinds (SPEC "Achievements and titles"); hidden is a flag on any of them. */
enum class AchievementKind { COUNTER, STREAK, THRESHOLD, EVENT }

/** What a tier pays: any of a title, a chest, a named item and Essence. */
@Serializable
data class AchievementReward(
    val title: String? = null,
    val chest: DropSource? = null,
    val item: String? = null,
    val essence: Int = 0,
)

/**
 * One achievement (content/achievements.json). [metric] names a number the app
 * computes ([AchievementMetrics]); tier n is earned when it reaches thresholds[n-1].
 * [rewards] has one entry per threshold.
 */
@Serializable
data class AchievementDef(
    val id: String,
    val name: String,
    val description: String,
    val kind: AchievementKind,
    val metric: String,
    val thresholds: List<Double>,
    val rewards: List<AchievementReward>,
    val hidden: Boolean = false,
)

@Serializable
data class AchievementContent(
    val v: Int = 1,
    val achievements: List<AchievementDef>,
)

/** Metric names the app computes from history. Counters backfill from Phase 2 (D8). */
object AchievementMetrics {
    const val DAILIES_COMPLETED = "dailies_completed"
    const val BEST_STREAK = "best_streak"
    const val ERGODIC_STREAK = "ergodic_streak"
    const val EQUIPARTITION = "equipartition"
    const val RANK = "rank"
    const val DEEP_WORK_HOURS = "deep_work_hours"
    const val GATES_CLEARED = "gates_cleared"
    const val STEPS_TOTAL = "steps_total"
    const val BOSSES_DEFEATED = "bosses_defeated"
    const val PAPERS_SUBMITTED = "papers_submitted"
    const val STILL_NIGHT_STREAK = "still_night_streak"
    const val RED_GATES_CLEARED = "red_gates_cleared"

    // Phase 5: habit strength
    /** Repeating quests whose strength shows as Bound (85% or more) right now. */
    const val BOUND_HABITS = "bound_habits"
    /** Realms with at least one Bound repeating quest right now (3 = one in each). */
    const val BOUND_REALMS = "bound_realms"

    val ALL: Set<String> = setOf(
        DAILIES_COMPLETED, BEST_STREAK, ERGODIC_STREAK, EQUIPARTITION, RANK, DEEP_WORK_HOURS,
        GATES_CLEARED, STEPS_TOTAL, BOSSES_DEFEATED, PAPERS_SUBMITTED, STILL_NIGHT_STREAK, RED_GATES_CLEARED,
        BOUND_HABITS, BOUND_REALMS,
    )

    /**
     * The Bound series among [strengths] (series → strength) and the realms they cover
     * ([realmOf] gives each series' realm). Bound follows the shown percent (≥ 85%).
     */
    fun bound(strengths: Map<String, Double>, realmOf: (String) -> Realm?): Pair<Int, Int> {
        val bound = strengths.filterValues { HabitStrength.label(HabitStrength.percent(it)) == StrengthLabel.BOUND }.keys
        return bound.size to bound.mapNotNull(realmOf).toSet().size
    }

    /** Equipartition: the highest stat is at most 1.1 × the lowest (D13). */
    fun equipartition(stats: StatBlock): Boolean {
        val values = Stat.entries.map { stats[it] }
        return values.max() <= 1.1 * values.min() + 1e-9
    }

    /** The longest run of consecutive days in [days]. */
    fun longestRun(days: Collection<Long>): Int {
        val sorted = days.toSortedSet()
        var best = 0
        var run = 0
        var previous: Long? = null
        for (day in sorted) {
            run = if (previous != null && day == previous + 1) run + 1 else 1
            best = maxOf(best, run)
            previous = day
        }
        return best
    }
}

/** A tier newly reached. */
data class EarnedTier(val def: AchievementDef, val tier: Int, val reward: AchievementReward?)

/** Checks every achievement against the current metrics. Data, not code. */
object AchievementEvaluator {

    /** Tiers reached: how many thresholds [value] meets. */
    fun tierFor(def: AchievementDef, value: Double): Int = def.thresholds.count { value >= it - 1e-9 }

    /**
     * Tiers earned since [current] (achievement id → tier held). Every tier passed is
     * listed, so a backfill that jumps from 0 to III pays I, II and III.
     */
    fun newlyEarned(
        defs: List<AchievementDef>,
        metrics: Map<String, Double>,
        current: Map<String, Int>,
    ): List<EarnedTier> = defs.flatMap { def ->
        val value = metrics[def.metric] ?: return@flatMap emptyList()
        val held = current[def.id] ?: 0
        val reached = tierFor(def, value)
        (held + 1..reached).map { tier -> EarnedTier(def, tier, def.rewards.getOrNull(tier - 1)) }
    }
}
