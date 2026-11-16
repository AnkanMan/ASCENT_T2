package com.tirthankar.ascent.debug

import android.content.Context
import androidx.core.content.edit
import com.tirthankar.ascent.data.TimeSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Debug builds only: the real clock plus an offset the debug panel moves forward.
 * The offset is saved, because the game state it produced lives in the future; it only
 * ever grows (going back would strand the player's days). Reinstall to start over.
 */
@Singleton
class DebugClock @Inject constructor(@ApplicationContext context: Context) : TimeSource {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Volatile
    private var offsetMillis: Long = prefs.getLong(KEY_OFFSET, 0L)

    override fun now(): Instant = Instant.now().plusMillis(offsetMillis)

    override fun zone(): ZoneId = ZoneId.systemDefault()

    val offset: Duration get() = Duration.ofMillis(offsetMillis)

    fun advance(by: Duration) {
        require(!by.isNegative) { "the debug clock only moves forward" }
        offsetMillis += by.toMillis()
        prefs.edit { putLong(KEY_OFFSET, offsetMillis) }
    }

    private companion object {
        const val PREFS = "ascent_debug"
        const val KEY_OFFSET = "clock_offset_millis"
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class TimeModule {
    @Binds
    abstract fun timeSource(impl: DebugClock): TimeSource
}
