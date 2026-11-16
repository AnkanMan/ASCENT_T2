package com.tirthankar.ascent.engine

/** One Daily Quest objective at the end of its day. */
data class ObjectiveResult(val realm: Realm, val completed: Boolean)

/** What the day-end resolution needs about the player and the day. */
data class DayInput(
    /** Only the Daily Quest's objectives; quests you add yourself never count. */
    val objectives: List<ObjectiveResult>,
    /** A rest day or a recovery day: completing its Daily restores HP. */
    val isRestDay: Boolean,
    val hp: Int,
    val maxHp: Int,
    val maxMp: Int,
    val streakDays: Int,
    val consecutiveMisses: Int,
)

data class DayOutcome(
    /** Completion fraction per realm, only for realms that had objectives. */
    val completionByRealm: Map<Realm, Double>,
    val dailyIssued: Boolean,
    val dailyComplete: Boolean,
    val streakDays: Int,
    val hpChange: Int,
    val hp: Int,
    /** MP refills at every reset (SPEC). */
    val mp: Int,
    /** HP reached 0: the next day is a mandatory recovery day. XP is never lost. */
    val nextDayIsRecovery: Boolean,
    val consecutiveMisses: Int,
    /** True on the day the miss streak reaches [ReEntry.MISSED_DAYS]. */
    val triggersReEntry: Boolean,
)

/**
 * End-of-day rules (SPEC "Quest system" and "HP", D3 and D7):
 * every objective done → streak +1 (and +30 HP on a rest day); otherwise the streak
 * resets and you lose 15 HP. A day with no Daily changes nothing but MP.
 */
object DayResolver {

    const val MISSED_DAILY_HP = -15
    const val REST_DAY_DAILY_HP = 30

    fun resolve(input: DayInput): DayOutcome {
        require(input.maxHp > 0) { "maxHp must be > 0" }
        val hp = input.hp.coerceIn(0, input.maxHp)
        if (input.objectives.isEmpty()) {
            return DayOutcome(
                completionByRealm = emptyMap(),
                dailyIssued = false,
                dailyComplete = false,
                streakDays = input.streakDays,
                hpChange = 0,
                hp = hp,
                mp = input.maxMp,
                nextDayIsRecovery = hp == 0,
                consecutiveMisses = input.consecutiveMisses,
                triggersReEntry = false,
            )
        }
        val completion = input.objectives.groupBy { it.realm }
            .mapValues { (_, list) -> list.count { it.completed }.toDouble() / list.size }
        val complete = input.objectives.all { it.completed }
        val hpChange = when {
            !complete -> MISSED_DAILY_HP
            input.isRestDay -> REST_DAY_DAILY_HP
            else -> 0
        }
        val newHp = (hp + hpChange).coerceIn(0, input.maxHp)
        val misses = if (complete) 0 else input.consecutiveMisses + 1
        return DayOutcome(
            completionByRealm = completion,
            dailyIssued = true,
            dailyComplete = complete,
            streakDays = if (complete) input.streakDays + 1 else 0,
            hpChange = newHp - hp,
            hp = newHp,
            mp = input.maxMp,
            nextDayIsRecovery = newHp == 0,
            consecutiveMisses = misses,
            triggersReEntry = !complete && misses == ReEntry.MISSED_DAYS,
        )
    }
}
