package com.tirthankar.ascent.engine

import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/** Passive skill rules that aren't plain multipliers (PHASE4.md section 1). */
object PassiveRules {

    /** How far back a chain is looked for. Longer than any cap needs. */
    const val LOOKBACK_DAYS = 60

    /** Chain ids used by `chain_xp` effects in skills.json. */
    const val CHAIN_BODY = "body"
    const val CHAIN_MIND_FOCUS = "mind_focus"

    /** Focus Chain: a day qualifies with at least this much completed Mind work. */
    const val FOCUS_CHAIN_MINUTES = 60.0

    /** Days with a completed Body quest (Momentum). */
    fun bodyDays(completions: List<Pair<Realm, Long>>): Set<Long> =
        completions.filter { it.first == Realm.BODY }.map { it.second }.toSet()

    /** Days with at least 60 minutes of completed Mind work (Focus Chain). [minutes] is per completion. */
    fun focusDays(completions: List<Triple<Realm, Long, Double>>): Set<Long> =
        completions.filter { it.first == Realm.MIND }
            .groupBy({ it.second }, { it.third })
            .filterValues { it.sum() >= FOCUS_CHAIN_MINUTES - 1e-9 }
            .keys

    /**
     * The length of a chain ending [today]: consecutive days in [qualifying], counting
     * back from today (or yesterday if today doesn't qualify yet). Rest days in
     * [restDays] are neutral: they neither extend nor break it (A3).
     */
    fun chainLength(qualifying: Set<Long>, restDays: Set<Long>, today: Long): Int {
        var day = if (today in qualifying) today else today - 1
        var length = 0
        var steps = 0
        while (steps < LOOKBACK_DAYS) {
            when {
                day in qualifying -> length++
                day in restDays -> Unit
                else -> return length
            }
            day--
            steps++
        }
        return length
    }

    /** Momentum and Focus Chain: perDay × chain length, up to each cap, for the quest's realm. */
    fun chainBonus(realm: Realm, effects: List<Effect>, chains: Map<String, Int>): Double =
        effects.filterIsInstance<Effect.ChainXp>()
            .filter { it.realm == realm }
            .sumOf { min(it.cap, it.perDay * (chains[it.chain] ?: 0)) }

    /** Resonance: perRealm × realms completed today, up to its cap. */
    fun resonanceBonus(effects: List<Effect>, realmsCompletedToday: Int): Double =
        effects.filterIsInstance<Effect.Resonance>().sumOf { min(it.cap, it.perRealm * realmsCompletedToday.coerceIn(0, 3)) }

    /** The stats a quest pays full mastery to: its own, plus Literature Sense's pair for paper quests. */
    fun masteryStats(primary: Stat, tags: Set<String>, effects: List<Effect>): List<Stat> {
        val extra = effects.filterIsInstance<Effect.DualMastery>().filter { it.tag in tags }.flatMap { it.stats }
        return (listOf(primary) + extra).distinct()
    }

    /** Bonds: HP restored by completing a quest with matching tags. */
    fun hpOnComplete(tags: Set<String>, effects: List<Effect>): Int =
        effects.filterIsInstance<Effect.TagHp>().filter { it.tag in tags }.sumOf { it.hp }

    /** Recovery Aura: the rest-day Daily's HP, ×1.5. */
    fun restDayHp(base: Int, effects: List<Effect>): Int {
        val mult = effects.filterIsInstance<Effect.RestHpMult>().maxOfOrNull { it.value } ?: 1.0
        return (base * mult).roundToInt()
    }

    /** Equilibrium: the next day's XP bonus after a completed rest-day Daily, or null without it. */
    fun restRestore(effects: List<Effect>): Effect.RestRestore? = effects.filterIsInstance<Effect.RestRestore>().firstOrNull()

    /** Insight charges per day: the skill's base + ⌊WIS / n⌋ + gear charges (A12). */
    fun insightCharges(limit: SkillLimit, wis: Int, effects: List<Effect>): Int {
        val fromWis = limit.wisPerCharge?.let { floor(wis.toDouble() / it).toInt() } ?: 0
        return limit.uses + fromWis + effects.filterIsInstance<Effect.InsightCharges>().sumOf { it.value }
    }

    /** Of those, the ones that cost no MP (Hamiltonian Blade). */
    fun freeInsightCharges(effects: List<Effect>): Int =
        effects.filterIsInstance<Effect.InsightCharges>().filter { it.free }.sumOf { it.value }
}
