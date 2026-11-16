package com.tirthankar.ascent.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.DayOfWeek
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

/** Rhythm and Dossier settings (SPEC "Awakening → Rhythm"). */
data class AscentSettings(
    val resetTime: LocalTime = DEFAULT_RESET,
    val restDays: Set<DayOfWeek> = setOf(DayOfWeek.SATURDAY),
    /** When your day starts; lights-out quests are set 7 h before it. */
    val wakeTime: LocalTime = LocalTime.of(6, 30),
    val quietStart: LocalTime = LocalTime.of(22, 30),
    val quietEnd: LocalTime = LocalTime.of(6, 30),
    val dossierNotes: String = "",
    /** System sounds (Phase 3). Always silent when the phone is on silent. */
    val soundOn: Boolean = true,
    /** Haptic pulses paired with sounds (Phase 3). */
    val hapticsOn: Boolean = true,
) {
    /** True when [time] falls inside quiet hours (which may wrap past midnight). */
    fun isQuiet(time: LocalTime): Boolean =
        if (quietStart <= quietEnd) time >= quietStart && time < quietEnd
        else time >= quietStart || time < quietEnd

    companion object {
        val DEFAULT_RESET: LocalTime = LocalTime.of(4, 0)
    }
}

@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val settings: Flow<AscentSettings> = dataStore.data.map { it.toSettings() }

    suspend fun current(): AscentSettings = settings.first()

    suspend fun update(transform: (AscentSettings) -> AscentSettings) {
        dataStore.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs[RESET] = next.resetTime.toMinutes()
            prefs[REST_DAYS] = next.restDays.map { it.value }.sorted().joinToString(",")
            prefs[WAKE] = next.wakeTime.toMinutes()
            prefs[QUIET_START] = next.quietStart.toMinutes()
            prefs[QUIET_END] = next.quietEnd.toMinutes()
            prefs[DOSSIER] = next.dossierNotes
            prefs[SOUND] = next.soundOn
            prefs[HAPTICS] = next.hapticsOn
        }
    }

    /** Whether we've already asked for the notification permission. */
    val notificationPermissionAsked: Flow<Boolean> = dataStore.data.map { it[PERMISSION_ASKED] ?: false }

    suspend fun setNotificationPermissionAsked() {
        dataStore.edit { it[PERMISSION_ASKED] = true }
    }

    /** Whether Do Not Disturb access was asked for at the first Hyperfocus session (Task 21). */
    val focusDndAsked: Flow<Boolean> = dataStore.data.map { it[DND_ASKED] ?: false }

    suspend fun setFocusDndAsked() {
        dataStore.edit { it[DND_ASKED] = true }
    }

    /** The interruption filter before a session turned Do Not Disturb on, to restore after it. */
    suspend fun focusDndPrevious(): Int? = dataStore.data.first()[DND_PREVIOUS]

    suspend fun setFocusDndPrevious(filter: Int?) {
        dataStore.edit { if (filter == null) it.remove(DND_PREVIOUS) else it[DND_PREVIOUS] = filter }
    }

    /** The last game day a reminder was sent, so it fires at most once a day. */
    suspend fun lastReminderDay(): Long? = dataStore.data.first()[LAST_REMINDER]

    suspend fun setLastReminderDay(day: Long) {
        dataStore.edit { it[LAST_REMINDER] = day }
    }

    private fun Preferences.toSettings(): AscentSettings {
        val defaults = AscentSettings()
        return AscentSettings(
            resetTime = this[RESET]?.toLocalTime() ?: defaults.resetTime,
            restDays = this[REST_DAYS]?.let(::parseDays) ?: defaults.restDays,
            wakeTime = this[WAKE]?.toLocalTime() ?: defaults.wakeTime,
            quietStart = this[QUIET_START]?.toLocalTime() ?: defaults.quietStart,
            quietEnd = this[QUIET_END]?.toLocalTime() ?: defaults.quietEnd,
            dossierNotes = this[DOSSIER] ?: defaults.dossierNotes,
            soundOn = this[SOUND] ?: defaults.soundOn,
            hapticsOn = this[HAPTICS] ?: defaults.hapticsOn,
        )
    }

    private fun parseDays(raw: String): Set<DayOfWeek> =
        raw.split(',').mapNotNull { it.trim().toIntOrNull()?.takeIf { v -> v in 1..7 }?.let(DayOfWeek::of) }.toSet()

    private fun LocalTime.toMinutes(): Int = hour * 60 + minute

    private fun Int.toLocalTime(): LocalTime = LocalTime.of((this / 60).coerceIn(0, 23), (this % 60).coerceIn(0, 59))

    private companion object {
        val RESET = intPreferencesKey("reset_minutes")
        val REST_DAYS = stringPreferencesKey("rest_days")
        val WAKE = intPreferencesKey("wake_minutes")
        val QUIET_START = intPreferencesKey("quiet_start_minutes")
        val QUIET_END = intPreferencesKey("quiet_end_minutes")
        val DOSSIER = stringPreferencesKey("dossier_notes")
        val PERMISSION_ASKED = booleanPreferencesKey("notification_permission_asked")
        val LAST_REMINDER = longPreferencesKey("last_reminder_day")
        val SOUND = booleanPreferencesKey("sound_on")
        val HAPTICS = booleanPreferencesKey("haptics_on")
        val DND_ASKED = booleanPreferencesKey("focus_dnd_asked")
        val DND_PREVIOUS = intPreferencesKey("focus_dnd_previous")
    }
}
