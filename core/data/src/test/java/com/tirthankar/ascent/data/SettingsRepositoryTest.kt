package com.tirthankar.ascent.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tirthankar.ascent.data.settings.AscentSettings
import com.tirthankar.ascent.data.settings.SettingsRepository
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.time.DayOfWeek
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class SettingsRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `defaults before anything is written`() = runTest {
        val repo = SettingsRepository(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, "a.preferences_pb") })
        assertEquals(AscentSettings(), repo.current())
        assertNull(repo.lastReminderDay())
    }

    @Test
    fun `settings round-trip`() = runTest {
        val repo = SettingsRepository(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, "b.preferences_pb") })
        val wanted = AscentSettings(
            resetTime = LocalTime.of(5, 30),
            restDays = setOf(DayOfWeek.SUNDAY, DayOfWeek.WEDNESDAY),
            wakeTime = LocalTime.of(5, 30),
            quietStart = LocalTime.of(23, 0),
            quietEnd = LocalTime.of(5, 0),
            dossierNotes = "Thesis draft due March.",
        )
        repo.update { wanted }
        assertEquals(wanted, repo.current())
        repo.setLastReminderDay(20_403)
        assertEquals(20_403, repo.lastReminderDay())
    }

    @Test
    fun `quiet hours can wrap past midnight`() {
        val s = AscentSettings(quietStart = LocalTime.of(22, 30), quietEnd = LocalTime.of(6, 30))
        assertTrue(s.isQuiet(LocalTime.of(23, 0)))
        assertTrue(s.isQuiet(LocalTime.of(3, 0)))
        assertFalse(s.isQuiet(LocalTime.of(6, 30)))
        assertFalse(s.isQuiet(LocalTime.of(12, 0)))
        val day = AscentSettings(quietStart = LocalTime.of(13, 0), quietEnd = LocalTime.of(14, 0))
        assertTrue(day.isQuiet(LocalTime.of(13, 30)))
        assertFalse(day.isQuiet(LocalTime.of(14, 0)))
    }
}
