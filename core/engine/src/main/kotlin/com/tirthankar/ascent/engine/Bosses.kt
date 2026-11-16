package com.tirthankar.ascent.engine

import kotlin.math.floor
import kotlin.math.roundToInt

/** A boss task as the rules see it. [phase] is 0-based. */
data class BossTaskState(val phase: Int, val hours: Double, val done: Boolean)

/**
 * Bosses and Trials (SPEC "Bosses", D13). HP is honest: it moves only with finished
 * tasks, never regenerates, and a crit doubles a task's XP, never its damage.
 */
object BossRules {

    const val DAMAGE_PER_HOUR = 100
    const val MIN_HOURS = 0.25
    const val MAX_HOURS = 40.0
    const val MAX_PHASES = 5
    const val MAX_ACTIVE = 2
    const val STIR_AFTER_DAYS = 5L
    const val ENRAGE_FACTOR = 0.75
    const val DEFEAT_FACTOR = 1.5

    /**
     * Each task pays half its damage as XP, × (1 + 0.05 L). SPEC gives tasks XP (a crit
     * doubles it) but no amount; this keeps a 1-hour task near a quest's pay (new rule).
     */
    const val TASK_XP_FACTOR = 0.5

    const val TRIAL_BASE_XP = 300
    const val TRIAL_REOPEN_DAYS = 28L

    fun clampHours(hours: Double): Double = hours.coerceIn(MIN_HOURS, MAX_HOURS)

    /** damage = 100 × estimated hours. */
    fun damage(hours: Double): Int = (DAMAGE_PER_HOUR * clampHours(hours)).roundToInt()

    fun maxHp(tasks: List<BossTaskState>): Int = tasks.sumOf { damage(it.hours) }

    fun hp(tasks: List<BossTaskState>): Int = maxHp(tasks) - tasks.filter { it.done }.sumOf { damage(it.hours) }

    /** The open phase: the first with an unfinished task, or [phaseCount] once all are done. */
    fun currentPhase(tasks: List<BossTaskState>, phaseCount: Int): Int =
        (0 until phaseCount).firstOrNull { phase -> tasks.any { it.phase == phase && !it.done } } ?: phaseCount

    /** Tasks in later phases stay locked until the phases before them are finished. */
    fun isUnlocked(task: BossTaskState, tasks: List<BossTaskState>, phaseCount: Int): Boolean =
        task.phase <= currentPhase(tasks, phaseCount)

    fun isDefeated(tasks: List<BossTaskState>): Boolean = tasks.isNotEmpty() && tasks.all { it.done }

    /** Past the deadline, rewards fall 25% (tasks still count). */
    fun isEnraged(today: Long, deadline: Long?): Boolean = deadline != null && today > deadline

    private fun rewardFactor(enraged: Boolean) = if (enraged) ENRAGE_FACTOR else 1.0

    fun taskXp(hours: Double, level: Int, critical: Boolean, enraged: Boolean): Long {
        val raw = TASK_XP_FACTOR * damage(hours) * QuestXp.levelMultiplier(level) *
            (if (critical) QuestXp.CRIT_MULTIPLIER else 1.0) * rewardFactor(enraged)
        return floor(raw + 0.5).toLong()
    }

    /** XP = 1.5 × total damage × (1 + 0.05 L), less 25% when enraged. */
    fun defeatXp(totalDamage: Int, level: Int, enraged: Boolean): Long =
        floor(DEFEAT_FACTOR * totalDamage * QuestXp.levelMultiplier(level) * rewardFactor(enraged) + 0.5).toLong()

    /** After 5 idle days the boss stirs: one notification per idle stretch. */
    fun stirs(today: Long, lastActivityDay: Long, lastStirDay: Long?): Boolean =
        today - lastActivityDay >= STIR_AFTER_DAYS && (lastStirDay == null || lastStirDay < lastActivityDay + STIR_AFTER_DAYS)

    /** Two active bosses plus one raid. */
    fun canAdd(activeNonRaid: Int, raidActive: Boolean, isRaid: Boolean): Boolean =
        if (isRaid) !raidActive else activeNonRaid < MAX_ACTIVE

    // ---- Trials ----

    /** At most one Trial attempt every 4 weeks. */
    fun canAttemptTrial(today: Long, lastAttemptDay: Long?): Boolean =
        lastAttemptDay == null || today - lastAttemptDay >= TRIAL_REOPEN_DAYS

    fun trialWon(result: Double, record: Double): Boolean = result > record + 1e-9

    fun trialXp(level: Int): Long = floor(TRIAL_BASE_XP * QuestXp.levelMultiplier(level) + 0.5).toLong()
}
