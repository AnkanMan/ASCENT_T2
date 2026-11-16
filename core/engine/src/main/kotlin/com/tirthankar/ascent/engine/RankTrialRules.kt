package com.tirthankar.ascent.engine

import kotlin.math.max
import kotlin.math.min

/** One generated rank-trial objective. */
data class TrialObjective(
    val realm: Realm,
    val title: String,
    val unit: String,
    val templates: List<String>,
    val isCount: Boolean,
    val target: Double,
)

enum class TrialOutcome { PASSED, FAILED }

/**
 * Rank trials (PHASE4.md section 4): a 7-day special Gate with 2 objectives per realm at
 * the target rank's Gate effort. Body objectives obey the weekly cap; the effort they
 * can't carry moves to Mind and Self.
 */
object RankTrialRules {

    /** The next rank, or null at S (Σ is out of scope). */
    fun nextRank(rank: Rank): Rank? = when (rank) {
        Rank.E -> Rank.D
        Rank.D -> Rank.C
        Rank.C -> Rank.B
        Rank.B -> Rank.A
        Rank.A -> Rank.S
        Rank.S, Rank.SIGMA -> null
    }

    /** The trial open to you, if any: level at the threshold, rank one below, and no wait. */
    fun available(rank: Rank, level: Int, retryDay: Long?, today: Long, content: RankTrialContent): RankTrialDef? {
        val next = nextRank(rank) ?: return null
        val def = content.forRank(next) ?: return null
        if (level < def.level) return null
        if (retryDay != null && today < retryDay) return null
        return def
    }

    /**
     * Effort per realm. Body takes as much as its headroom allows; the remainder of the
     * total (3 × effort) is split between Mind and Self.
     */
    fun realmEfforts(effort: Double, bodyHeadroom: Double): Map<Realm, Double> {
        val body = min(effort, max(0.0, bodyHeadroom))
        val others = (3 * effort - body) / 2
        return mapOf(Realm.BODY to body, Realm.MIND to others, Realm.SELF to others)
    }

    /**
     * Picks [RankTrialContent.objectivesPerRealm] objectives per realm (seeded) and sizes
     * each like a Gate objective at that realm's effort. [engineTarget] is today's target
     * for a template.
     */
    fun objectives(
        def: RankTrialDef,
        content: RankTrialContent,
        seed: Long,
        bodyHeadroom: Double,
        engineTarget: (QuestTemplate) -> Double?,
    ): List<TrialObjective> {
        val rng = SplitMix64(seed)
        val efforts = realmEfforts(def.effort, bodyHeadroom)
        return Realm.entries.flatMap { realm ->
            val pool = content.objectives[realm].orEmpty().toMutableList()
            List(min(content.objectivesPerRealm, pool.size)) { pool.removeAt(rng.nextInt(pool.size)) }.map { o ->
                val template = o.templates.firstOrNull()?.let { TemplateLibrary[it] }
                val count = GateRules.isCount(o)
                val unit = if (count) TargetUnit.OTHER else (template?.unit ?: TargetUnit.fromLabel(o.unit))
                val normal = GateRules.normalWeek(o, template?.let(engineTarget))
                TrialObjective(realm, o.title, o.unit, o.templates, count, target(normal, content.days, efforts.getValue(realm), count, unit))
            }
        }
    }

    /** Like a Gate objective's target, with an explicit extra effort. */
    fun target(normalWeek: Double, days: Int, extra: Double, count: Boolean, unit: TargetUnit): Double {
        val scaled = normalWeek * days / 7.0 * (1.0 + extra)
        return if (count) max(1.0, kotlin.math.ceil(scaled - 1e-9)) else TargetScaler.scaledTarget(scaled, 1.0, unit)
    }

    /** Passing needs every objective complete before the trial closes. */
    fun outcome(objectives: List<Pair<Double, Double>>): TrialOutcome =
        if (objectives.isNotEmpty() && objectives.all { (p, t) -> p >= t - 1e-9 }) TrialOutcome.PASSED else TrialOutcome.FAILED

    /** A failed trial pays the Gate payout for what was cleared, at the target rank's base XP. */
    fun failXp(def: RankTrialDef, level: Int, fraction: Double): Long = GateRules.payoutXp(def.rank, level, fraction, isRed = false)

    /** The day a failed trial can be retried. */
    fun retryDay(closedDay: Long, content: RankTrialContent): Long = closedDay + content.retryDays
}
