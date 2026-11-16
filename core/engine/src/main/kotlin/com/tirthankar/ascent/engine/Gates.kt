package com.tirthankar.ascent.engine

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * One Gate objective in content/gates.json. With [sessions], the normal week is the
 * engine's target for the first of [templates] × sessions (an amount, e.g. reps).
 * Otherwise the normal week is [perWeek] completions (a count, e.g. papers).
 */
@Serializable
data class GateObjectiveTemplate(
    val title: String,
    val unit: String,
    /** Completed quests with these template keys count toward the objective. */
    val templates: List<String> = emptyList(),
    val sessions: Int? = null,
    val perWeek: Double? = null,
)

@Serializable
data class GateTemplate(
    val id: String,
    val realm: Realm,
    val title: String,
    val lore: String,
    val days: Int,
    val objectives: List<GateObjectiveTemplate>,
)

@Serializable
data class GateContent(
    val v: Int = 1,
    val gates: List<GateTemplate>,
)

enum class GateOutcome {
    /** 100%: XP and the chest. */
    CLEARED,

    /** 50–99%: XP for what was cleared, no chest, no lore. */
    PARTIAL,

    /** Below 50%: XP for what was cleared, no chest, a lore line (SPEC "Gate break"). */
    BROKEN,

    /** A Red Gate below 100%: all-or-nothing, so nothing. */
    RED_FAILED,
}

/** The Gate board and Gate payouts (SPEC "Gate board", D3–D5, D13). */
object GateRules {

    const val MAX_OPEN = 2
    const val BREAK_BELOW = 0.5
    const val BOARD_SIZE = 3

    /** Extra effort over a normal week, by rank. */
    fun extra(rank: Rank): Double = when (rank) {
        Rank.E -> 0.10
        Rank.D -> 0.20
        Rank.C -> 0.35
        Rank.B -> 0.50
        Rank.A -> 0.70
        Rank.S, Rank.SIGMA -> 1.00
    }

    fun baseXp(rank: Rank): Int = when (rank) {
        Rank.E -> 400
        Rank.D -> 800
        Rank.C -> 1_600
        Rank.B -> 3_200
        Rank.A -> 6_400
        Rank.S, Rank.SIGMA -> 12_800
    }

    fun chest(rank: Rank): DropSource = when (rank) {
        Rank.E, Rank.D -> DropSource.CHEST_ED
        Rank.C, Rank.B -> DropSource.CHEST_CB
        Rank.A, Rank.S, Rank.SIGMA -> DropSource.CHEST_AS
    }

    /** Ranks a Gate can have (Σ is a player rank, not a Gate rank). */
    val GATE_RANKS: List<Rank> = listOf(Rank.E, Rank.D, Rank.C, Rank.B, Rank.A, Rank.S)

    /** True for objectives measured by completions rather than an amount. */
    fun isCount(objective: GateObjectiveTemplate): Boolean = objective.sessions == null

    /** A normal week of [objective]; [engineTarget] is today's target for its first template. */
    fun normalWeek(objective: GateObjectiveTemplate, engineTarget: Double?): Double =
        when (val sessions = objective.sessions) {
            null -> objective.perWeek ?: 1.0
            else -> (engineTarget ?: 1.0) * sessions
        }

    /** The objective's target: a normal week, scaled to [days], plus the rank's extra effort. */
    fun objectiveTarget(normalWeek: Double, days: Int, rank: Rank, count: Boolean, unit: TargetUnit): Double {
        val scaled = normalWeek * days / 7.0 * (1.0 + extra(rank))
        return if (count) max(1.0, ceil(scaled - 1e-9)) else TargetScaler.scaledTarget(scaled, 1.0, unit)
    }

    /**
     * How much Body growth the +10% weekly cap leaves for a template in the week starting
     * [weekStartDay]: that week's cap over the current target, minus one. The board spawns
     * at the Sunday reset, before the week's first Daily, so [memory] is usually last
     * week's and the cap is 10% above it. Unknown history leaves the full 10%.
     */
    fun bodyHeadroom(memory: BodyTargetMemory?, currentTarget: Double?, weekStartDay: Long, unit: TargetUnit): Double {
        if (memory == null || currentTarget == null || currentTarget <= 0) return SafetyRules.BODY_WEEKLY_RISE - 1.0
        val cap = if (memory.weekStartDay == weekStartDay) memory.weekCap else SafetyRules.weeklyCap(memory.lastTarget, unit)
        return max(0.0, cap / currentTarget - 1.0)
    }

    /** A Body objective fits only when the rank's extra effort stays within the headroom. */
    fun bodyFits(rank: Rank, headroom: Double): Boolean = extra(rank) <= headroom + 1e-9

    /** The mean of min(progress / target, 1) over the objectives (D13). */
    fun fractionCleared(objectives: List<Pair<Double, Double>>): Double {
        if (objectives.isEmpty()) return 0.0
        return objectives.sumOf { (progress, target) -> if (target <= 0) 1.0 else min(1.0, max(0.0, progress / target)) } /
            objectives.size
    }

    fun outcome(fraction: Double, isRed: Boolean): GateOutcome = when {
        fraction >= 1.0 - 1e-9 -> GateOutcome.CLEARED
        isRed -> GateOutcome.RED_FAILED
        fraction >= BREAK_BELOW -> GateOutcome.PARTIAL
        else -> GateOutcome.BROKEN
    }

    /** XP = base × (1 + 0.05 L) × fraction cleared × gear bonus; a failed Red Gate pays nothing. */
    fun payoutXp(rank: Rank, level: Int, fraction: Double, isRed: Boolean, rewardMultiplier: Double = 1.0): Long {
        if (outcome(fraction, isRed) == GateOutcome.RED_FAILED) return 0
        val raw = baseXp(rank) * QuestXp.levelMultiplier(level) * fraction.coerceIn(0.0, 1.0) * rewardMultiplier
        return floor(raw + 0.5).toLong()
    }

    /** Chests opened: one at 100%, two for a Red Gate. */
    fun chests(outcome: GateOutcome, isRed: Boolean): Int =
        if (outcome == GateOutcome.CLEARED) (if (isRed) 2 else 1) else 0

    /**
     * The board's ranks: one below, one at and one above [playerRank]. At E there is no
     * rank below, so the board offers E, E and D (D5).
     */
    fun boardRanks(playerRank: Rank): List<Rank> {
        val r = GATE_RANKS.indexOf(playerRank.coerceGateRank())
        val below = GATE_RANKS[max(0, r - 1)]
        val above = GATE_RANKS[min(GATE_RANKS.lastIndex, r + 1)]
        return listOf(below, GATE_RANKS[r], above)
    }

    /** The Red Gate: one rank above your highest. */
    fun redRank(playerRank: Rank): Rank =
        GATE_RANKS[min(GATE_RANKS.lastIndex, GATE_RANKS.indexOf(playerRank.coerceGateRank()) + 1)]

    /**
     * Pairs the board's ranks with three different realms. Body goes to the lowest rank
     * that fits its headroom; if none fits, the Body Gate is dropped and the board shows
     * only the other two (SPEC "Body cap").
     */
    fun board(playerRank: Rank, seed: Long, bodyHeadroom: Double): List<Pair<Rank, Realm>> {
        val ranks = boardRanks(playerRank).sortedBy { it.ordinal }
        val rng = SplitMix64(seed)
        val others = mutableListOf(Realm.MIND, Realm.SELF)
        if (rng.nextInt(2) == 1) others.reverse()
        val bodyRank = ranks.firstOrNull { bodyFits(it, bodyHeadroom) }
        val result = mutableListOf<Pair<Rank, Realm>>()
        val remaining = ranks.toMutableList()
        if (bodyRank != null) {
            remaining.remove(bodyRank)
            result += bodyRank to Realm.BODY
        }
        remaining.take(2).forEachIndexed { i, rank -> result += rank to others[i] }
        return result
    }

    /**
     * The rank whose extra effort is closest to [measuredExtra], for correcting a Gate
     * Claude proposes. Ties go to the lower rank.
     */
    fun correctedRank(measuredExtra: Double): Rank =
        GATE_RANKS.minBy { abs(extra(it) - measuredExtra) * 1000 + it.ordinal * 1e-6 }

    /**
     * How often a template of [kind] comes up in a normal week of Daily Quests, for
     * judging a Gate Claude proposes: strength alternates A/B over three days, deep work
     * and sleep are daily, Mind outputs and Self habits every other day.
     */
    fun normalFrequency(kind: TemplateKind): Double = when (kind) {
        TemplateKind.STRENGTH_A, TemplateKind.STRENGTH_B -> 1.5
        TemplateKind.CARDIO -> 2.0
        TemplateKind.MOBILITY, TemplateKind.BODY_RECOVERY -> 1.0
        TemplateKind.DEEP_WORK, TemplateKind.SLEEP -> 7.0
        TemplateKind.MIND_OUTPUT, TemplateKind.SELF_HABIT -> 3.5
    }

    /** Extra effort of a proposed objective over a normal week of [normalWeek] for [days]. */
    fun measuredExtra(target: Double, normalWeek: Double, days: Int): Double {
        val base = normalWeek * days / 7.0
        return if (base <= 0) 0.0 else target / base - 1.0
    }

    private fun Rank.coerceGateRank(): Rank = if (this == Rank.SIGMA) Rank.S else this
}
