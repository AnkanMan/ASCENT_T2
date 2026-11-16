package com.tirthankar.ascent.engine

import java.time.Instant
import java.time.LocalDate
import kotlin.math.floor
import kotlin.math.roundToInt

/** Why a power can't be cast (or [Ok]). The UI says which check failed, in one line. */
sealed interface CastCheck {
    data object Ok : CastCheck
    data object NotUnlocked : CastCheck
    data object NotCastable : CastCheck
    data class NotEnoughMp(val needed: Int, val have: Int) : CastCheck
    data class LimitReached(val period: LimitPeriod) : CastCheck
    data class OnCooldown(val minutesLeft: Long) : CastCheck
    data object NeedsTarget : CastCheck
    data object RestDay : CastCheck
    data object NoHeadroom : CastCheck
    data object NoMissToRestore : CastCheck
}

/** What a power's target looks like right now; the data layer fills it in. */
data class CastTarget(
    /** A valid open target is chosen (or none is needed). */
    val chosen: Boolean = true,
    val isRestDay: Boolean = false,
    /** Limit Break: the target the cap allows, or null when there is no headroom. */
    val limitBreakTarget: Double? = null,
    /** Unbroken: yesterday's Daily was missed and the Shield didn't cover it. */
    val unshieldedMissYesterday: Boolean = false,
)

/**
 * Active powers (PHASE4.md sections 1–3). Each handler's rules live here; the data layer
 * applies them. Powers multiply on top of the passive cap (A10, A16), crit last.
 */
object PowerRules {

    const val LIMIT_BREAK_XP = 3.0
    const val EIGENSTATE_XP = 3.0
    const val HYPERFOCUS_XP = 2.0
    const val HYPERFOCUS_MASTERY = 2.0
    const val OVERLOAD_XP = 2.0
    const val UNBROKEN_XP_SHARE = 0.5
    const val MENTORS_EYE_DAYS = 28
    const val STILLNESS_MP_SHARE = 0.25
    const val LONG_WALK_MIN_MINUTES = 30.0

    /** Second Wind's replacement: 15 minutes of mobility, Easy. */
    const val SECOND_WIND_TEMPLATE = "body_mobility"
    const val SECOND_WIND_MINUTES = 15.0

    /** Handlers that need a chosen target. */
    private val TARGETED = setOf(
        PowerHandlers.SECOND_WIND, PowerHandlers.LIMIT_BREAK, PowerHandlers.HYPERFOCUS, PowerHandlers.EIGENSTATE_COLLAPSE,
        PowerHandlers.FIRST_PRINCIPLES, PowerHandlers.PARALLEL_RUNS,
    )

    /** Class skills that trigger by themselves on a matching quest, once a day; never cast. */
    val AUTOMATIC: Set<String> = setOf(PowerHandlers.STILLNESS, PowerHandlers.LONG_WALK)

    fun needsTarget(handler: String?): Boolean = handler in TARGETED

    /** MP for the next cast. Insight's free charges (Hamiltonian Blade) are spent first. */
    fun mpCost(skill: SkillDef, use: SkillUse?, gameDay: LocalDate, effects: List<Effect>): Int {
        if (skill.handler != PowerHandlers.INSIGHT) return skill.mp
        val usedToday = if (use?.periodKey == Periods.dayKey(gameDay)) use.usesInPeriod else 0
        return if (usedToday < PassiveRules.freeInsightCharges(effects)) 0 else skill.mp
    }

    /** Uses allowed in the current period: Insight scales with WIS and gear (A12). */
    fun allowedUses(skill: SkillDef, stats: StatBlock, effects: List<Effect>): Int {
        val limit = skill.limit ?: return Int.MAX_VALUE
        return if (skill.handler == PowerHandlers.INSIGHT) PassiveRules.insightCharges(limit, stats.wis, effects) else limit.uses
    }

    fun check(
        skill: SkillDef,
        unlocked: Boolean,
        mp: Int,
        use: SkillUse?,
        gameDay: LocalDate,
        now: Instant,
        stats: StatBlock,
        effects: List<Effect>,
        target: CastTarget,
    ): CastCheck {
        if (!unlocked) return CastCheck.NotUnlocked
        if (skill.kind != SkillKind.ACTIVE || skill.locked || skill.handler in AUTOMATIC) return CastCheck.NotCastable
        val limit = skill.limit
        if (limit != null) {
            if (limit.period == LimitPeriod.HOURS) {
                val left = Periods.cooldownLeft(limit.hours ?: 0, Periods.cooldownReduction(stats, effects, skill.id), use?.lastCastAt, now)
                if (!left.isZero) return CastCheck.OnCooldown((left.toMinutes() + 1).coerceAtLeast(1))
            } else if (Periods.usesLeft(limit.period, allowedUses(skill, stats, effects), use, gameDay) <= 0) {
                return CastCheck.LimitReached(limit.period)
            }
        }
        val cost = mpCost(skill, use, gameDay, effects)
        if (mp < cost) return CastCheck.NotEnoughMp(cost, mp)
        when (skill.handler) {
            PowerHandlers.SECOND_WIND -> if (target.isRestDay) return CastCheck.RestDay
            PowerHandlers.LIMIT_BREAK -> if (target.chosen && target.limitBreakTarget == null) return CastCheck.NoHeadroom
            PowerHandlers.UNBROKEN -> if (!target.unshieldedMissYesterday) return CastCheck.NoMissToRestore
        }
        if (needsTarget(skill.handler) && !target.chosen) return CastCheck.NeedsTarget
        return CastCheck.Ok
    }

    /**
     * Limit Break (A1): the largest target the +10% weekly cap allows this week, on the
     * unit's friendly step (or its fine step), or null when that is no higher than now.
     */
    fun limitBreakTarget(current: Double, memory: BodyTargetMemory?, weekStartDay: Long, unit: TargetUnit): Double? {
        val cap = when {
            memory == null -> return null // no history: the cap is today's target
            memory.weekStartDay == weekStartDay -> memory.weekCap
            else -> SafetyRules.weeklyCap(memory.lastTarget, unit)
        }
        val friendly = TargetScaler.floorToStep(cap, unit.step)
        val target = if (friendly > current + 1e-9) friendly else TargetScaler.floorToStep(cap, unit.fineStep)
        return target.takeIf { it > current + 1e-9 }
    }

    /** Unbroken: the missed Daily pays half its XP. */
    fun unbrokenXp(fullXp: Long): Long = floor(fullXp * UNBROKEN_XP_SHARE + 0.5).toLong()

    /** Stillness: a meditation quest refills a quarter of max MP. */
    fun stillnessMp(maxMp: Int): Int = (maxMp * STILLNESS_MP_SHARE).roundToInt()

    /** Long Walk: a walk of 30 minutes or more also pays Normal INT mastery. */
    fun longWalkApplies(tags: Set<String>, unit: TargetUnit, target: Double): Boolean =
        "walk" in tags && unit == TargetUnit.MINUTES && target >= LONG_WALK_MIN_MINUTES
}

/** Whether a miss is shielded, and by which allowance. */
data class ShieldDecision(val shielded: Boolean, val fromMonthly: Boolean = false)

/**
 * Streak Shield before Unbroken (A9). The weekly shield covers the first miss of the
 * game week; Iron Lattice adds one more per game month (A12).
 */
object ShieldRules {

    fun resolveMiss(weeklyUsed: Int, monthlyUsed: Int, effects: List<Effect>): ShieldDecision {
        val shields = effects.filterIsInstance<Effect.StreakShield>()
        val perWeek = shields.sumOf { it.perWeek }
        val perMonth = shields.sumOf { it.perMonth }
        return when {
            weeklyUsed < perWeek -> ShieldDecision(shielded = true)
            monthlyUsed < perMonth -> ShieldDecision(shielded = true, fromMonthly = true)
            else -> ShieldDecision(shielded = false)
        }
    }

    /** Unbroken works only the day after an unshielded miss. */
    fun unbrokenEligible(missDay: Long?, today: Long, shielded: Boolean): Boolean =
        missDay != null && missDay == today - 1 && !shielded
}

/** The Focus Session (A6) and Hyperfocus. */
object HyperfocusRules {

    const val SESSION_MINUTES = 90

    data class Result(val progressMinutes: Int, val xpMultiplier: Double, val masteryMultiplier: Double, val full: Boolean)

    /** A full session pays the bonus; ended early, the minutes count without it. */
    fun result(minutesDone: Int): Result {
        val minutes = minutesDone.coerceIn(0, SESSION_MINUTES)
        return if (minutes >= SESSION_MINUTES) {
            Result(SESSION_MINUTES, PowerRules.HYPERFOCUS_XP, PowerRules.HYPERFOCUS_MASTERY, full = true)
        } else {
            Result(minutes, 1.0, 1.0, full = false)
        }
    }
}

/** Clarity: a 3-line journal; tomorrow's quests come from the third line. */
object ClarityRules {

    const val MAX_QUESTS = 3

    /** Up to three quest titles from the third line, split on ; , or "and". Forbidden ones are dropped. */
    fun questTitles(thirdLine: String): List<String> =
        thirdLine.split(';', ',', '\n')
            .flatMap { it.split(Regex("\\s+and\\s+", RegexOption.IGNORE_CASE)) }
            .map { it.trim().trimEnd('.') }
            .filter { it.isNotEmpty() && !SafetyRules.isForbidden(it) }
            .take(MAX_QUESTS)
}
