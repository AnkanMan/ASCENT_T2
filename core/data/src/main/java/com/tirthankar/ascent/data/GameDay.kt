package com.tirthankar.ascent.data

import com.tirthankar.ascent.data.settings.AscentSettings
import com.tirthankar.ascent.data.settings.SettingsRepository
import com.tirthankar.ascent.engine.GameClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Today's game day and the next reset, from the settings and the clock. */
@Singleton
class GameDay @Inject constructor(
    private val settings: SettingsRepository,
    private val time: TimeSource,
) {
    fun clock(settings: AscentSettings): GameClock = GameClock(settings.resetTime, time.zone())

    suspend fun clock(): GameClock = clock(settings.current())

    suspend fun today(): LocalDate = clock().gameDay(time.now())

    suspend fun nextReset(): Instant = clock().nextReset(time.now())

    fun now(): Instant = time.now()

    /** Today's game day, re-checked every [tickMillis] so the UI rolls over at the reset. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeToday(tickMillis: Long = 30_000): Flow<LocalDate> =
        settings.settings.flatMapLatest { s ->
            flow {
                while (true) {
                    emit(clock(s).gameDay(time.now()))
                    delay(tickMillis)
                }
            }
        }.distinctUntilChanged()
}
