package com.tirthankar.ascent.engine

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GameClockTest {

    private val utc4 = GameClock(LocalTime.of(4, 0), ZoneOffset.UTC)

    @Test
    fun `before the reset is still yesterday`() {
        assertEquals(LocalDate.of(2026, 11, 11), utc4.gameDay(Instant.parse("2026-11-12T02:00:00Z")))
        assertEquals(LocalDate.of(2026, 11, 11), utc4.gameDay(Instant.parse("2026-11-12T03:59:59Z")))
        assertEquals(LocalDate.of(2026, 11, 12), utc4.gameDay(Instant.parse("2026-11-12T04:00:00Z")))
    }

    @Test
    fun `a midnight reset follows the calendar`() {
        val clock = GameClock(LocalTime.MIDNIGHT, ZoneOffset.UTC)
        assertEquals(LocalDate.of(2026, 11, 12), clock.gameDay(Instant.parse("2026-11-12T00:00:00Z")))
        assertEquals(LocalDate.of(2026, 11, 11), clock.gameDay(Instant.parse("2026-11-11T23:59:59Z")))
    }

    @Test
    fun `next reset is the following reset time`() {
        assertEquals(Instant.parse("2026-11-12T04:00:00Z"), utc4.nextReset(Instant.parse("2026-11-11T10:00:00Z")))
        assertEquals(Instant.parse("2026-11-12T04:00:00Z"), utc4.nextReset(Instant.parse("2026-11-12T03:00:00Z")))
        assertEquals(Instant.parse("2026-11-13T04:00:00Z"), utc4.nextReset(Instant.parse("2026-11-12T04:00:00Z")))
    }

    @Test
    fun `fall-back day is 25 hours long`() {
        val ny = GameClock(LocalTime.of(4, 0), ZoneId.of("America/New_York"))
        val length = Duration.between(ny.dayStart(LocalDate.of(2026, 10, 31)), ny.dayStart(LocalDate.of(2026, 11, 1)))
        assertEquals(Duration.ofHours(25), length)
    }

    @Test
    fun `spring-forward day is 23 hours long`() {
        val ny = GameClock(LocalTime.of(4, 0), ZoneId.of("America/New_York"))
        val length = Duration.between(ny.dayStart(LocalDate.of(2026, 3, 7)), ny.dayStart(LocalDate.of(2026, 3, 8)))
        assertEquals(Duration.ofHours(23), length)
    }

    @Test
    fun `a reset inside the spring-forward gap happens when the gap ends`() {
        val ny = GameClock(LocalTime.of(2, 30), ZoneId.of("America/New_York"))
        val start = ny.dayStart(LocalDate.of(2026, 3, 8))
        assertEquals(Instant.parse("2026-03-08T07:00:00Z"), start) // 03:00 EDT
        assertEquals(LocalDate.of(2026, 3, 8), ny.gameDay(start))
        assertEquals(LocalDate.of(2026, 3, 7), ny.gameDay(start.minusSeconds(1)))
    }

    @Test
    fun `changing timezone can change the game day`() {
        val instant = Instant.parse("2026-11-11T22:00:00Z")
        val kolkata = GameClock(LocalTime.of(4, 0), ZoneId.of("Asia/Kolkata")) // 03:30 on the 12th
        val auckland = GameClock(LocalTime.of(4, 0), ZoneId.of("Pacific/Auckland")) // 11:00 on the 12th
        assertEquals(LocalDate.of(2026, 11, 11), kolkata.gameDay(instant))
        assertEquals(LocalDate.of(2026, 11, 12), auckland.gameDay(instant))
    }

    @Test
    fun `the week starts on Sunday`() {
        assertEquals(LocalDate.of(2026, 11, 8), GameClock.weekStart(LocalDate.of(2026, 11, 11))) // Wed
        assertEquals(LocalDate.of(2026, 11, 8), GameClock.weekStart(LocalDate.of(2026, 11, 14))) // Sat
        assertEquals(LocalDate.of(2026, 11, 15), GameClock.weekStart(LocalDate.of(2026, 11, 15))) // Sun
        assertTrue(GameClock.isWeekStart(LocalDate.of(2026, 11, 15)))
        assertFalse(GameClock.isWeekStart(LocalDate.of(2026, 11, 16)))
    }
}
