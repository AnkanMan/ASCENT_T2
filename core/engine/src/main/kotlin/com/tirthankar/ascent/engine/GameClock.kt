package com.tirthankar.ascent.engine

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * Which game "day" it is. A day runs from one daily reset to the next, so with a
 * 04:00 reset, 02:00 on the 12th still belongs to the 11th.
 *
 * Works on wall-clock time, so daylight-saving shifts move the reset with the clock.
 * A reset inside a spring-forward gap happens at the end of the gap.
 */
class GameClock(
    val resetTime: LocalTime,
    val zone: ZoneId,
) {

    fun gameDay(instant: Instant): LocalDate {
        val local = instant.atZone(zone).toLocalDateTime()
        return local.minusHours(resetTime.hour.toLong())
            .minusMinutes(resetTime.minute.toLong())
            .toLocalDate()
    }

    /** The instant [day] begins: its date at the reset time. */
    fun dayStart(day: LocalDate): Instant {
        val local = day.atTime(resetTime)
        val transition = zone.rules.getTransition(local)
        return if (transition != null && transition.isGap) {
            transition.instant
        } else {
            local.atZone(zone).toInstant()
        }
    }

    /** The next reset strictly after [instant]. */
    fun nextReset(instant: Instant): Instant = dayStart(gameDay(instant).plusDays(1))

    companion object {
        /** The week runs Sunday to Saturday and turns over at the Sunday reset (D9). */
        val WEEK_START: DayOfWeek = DayOfWeek.SUNDAY

        fun weekStart(day: LocalDate): LocalDate = day.with(TemporalAdjusters.previousOrSame(WEEK_START))

        fun isWeekStart(day: LocalDate): Boolean = day.dayOfWeek == WEEK_START
    }
}
