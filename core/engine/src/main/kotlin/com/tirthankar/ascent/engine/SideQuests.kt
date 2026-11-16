package com.tirthankar.ascent.engine

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Side quests (SPEC "Side quests", D13): optional, short-lived, never penalised. */
object SideQuestRules {

    const val XP_FACTOR = 0.75
    const val MAX_VISIBLE = 3
    const val BALANCE_EXPIRY_DAYS = 2
    const val BOUNTY_EXPIRY_DAYS = 3
    const val DEFAULT_CLAUDE_EXPIRY_DAYS = 2
    private val CLAUDE_EXPIRY = 1..3

    /** Claude's `expires_days`, clamped to 1–3. */
    fun claudeExpiryDays(requested: Int?): Int =
        (requested ?: DEFAULT_CLAUDE_EXPIRY_DAYS).coerceIn(CLAUDE_EXPIRY.first, CLAUDE_EXPIRY.last)

    /** The last game day a side quest issued on [issuedDay] is open (inclusive). */
    fun lastDay(issuedDay: Long, expiryDays: Int): Long = issuedDay + expiryDays - 1

    /** One balance quest per reset while fewer than three side quests are visible. */
    fun shouldIssueBalance(visibleSideQuests: Int): Boolean = visibleSideQuests < MAX_VISIBLE

    /**
     * The realm with the lowest completion over the last 7 days. A realm with no
     * objectives counts as 0%. Ties go to the realm that comes first (Body, Mind, Self).
     */
    fun balanceRealm(done: Map<Realm, Int>, total: Map<Realm, Int>): Realm =
        Realm.entries.minBy { realm ->
            val t = total[realm] ?: 0
            if (t == 0) 0.0 else (done[realm] ?: 0).toDouble() / t
        }

    /**
     * A template for a balance quest in [realm], never one already issued today
     * ([excludeKeys]). Body balance quests are never strength work (no muscle-group
     * repeats); on rest days they are a recovery walk or stretch.
     */
    fun balanceTemplate(realm: Realm, excludeKeys: Set<String>, isRestDay: Boolean, seed: Long): QuestTemplate? {
        val kinds = when (realm) {
            Realm.BODY -> if (isRestDay) listOf(TemplateKind.BODY_RECOVERY) else listOf(TemplateKind.CARDIO, TemplateKind.MOBILITY)
            Realm.MIND -> listOf(TemplateKind.MIND_OUTPUT)
            Realm.SELF -> listOf(TemplateKind.SELF_HABIT)
        }
        val options = TemplateLibrary.all.filter { it.kind in kinds && it.key !in excludeKeys }
        if (options.isEmpty()) return null
        return options[Math.floorMod(seed, options.size.toLong()).toInt()]
    }
}

/** A penalty or emergency task from content/quests.json. */
@Serializable
data class TaskTemplate(
    val id: String,
    val realm: Realm,
    val stat: Stat,
    val tier: Tier,
    val title: String,
    val target: Double = 1.0,
    val unit: String = "done",
)

@Serializable
data class QuestContent(
    val v: Int = 1,
    val penalty: List<TaskTemplate>,
    val emergency: List<TaskTemplate>,
)

/** Penalty quests (SPEC "Quest system"): one small corrective task after a missed Daily. */
object PenaltyRules {
    const val DONE_HP = 10
    const val MISSED_HP = -10

    fun pick(options: List<TaskTemplate>, day: Long): TaskTemplate? =
        options.takeIf { it.isNotEmpty() }?.let { it[Math.floorMod(day, it.size.toLong()).toInt()] }
}

/**
 * Emergency quests (D2): about two a week at random times between your wake time and
 * the start of quiet hours, each open for 2 hours, never on rest days.
 */
object EmergencyRules {

    const val PER_WEEK = 2
    const val WINDOW_HOURS = 2L
    const val XP_FACTOR = 1.5

    /** The emergency start times for the week starting [weekStart]. Same seed, same slots. */
    fun slots(
        weekStart: LocalDate,
        seed: Long,
        restDays: Set<DayOfWeek>,
        wake: LocalTime,
        quietStart: LocalTime,
    ): List<LocalDateTime> {
        val activeMinutes = Math.floorMod(quietStart.toSecondOfDay() / 60 - wake.toSecondOfDay() / 60, MINUTES_PER_DAY)
        val latestStart = activeMinutes - WINDOW_HOURS * 60
        if (latestStart <= 0) return emptyList()
        val days = (0L until 7L).map { weekStart.plusDays(it) }.filter { it.dayOfWeek !in restDays }.toMutableList()
        val rng = SplitMix64(Seeds.forEvent(seed, "emergency:${weekStart.toEpochDay()}"))
        return buildList {
            repeat(minOf(PER_WEEK, days.size)) {
                val day = days.removeAt(rng.nextInt(days.size))
                val offset = rng.nextInt(latestStart.toInt() + 1).toLong()
                add(day.atTime(wake).plusMinutes(offset))
            }
        }.sorted()
    }

    private const val MINUTES_PER_DAY = 24 * 60
}
