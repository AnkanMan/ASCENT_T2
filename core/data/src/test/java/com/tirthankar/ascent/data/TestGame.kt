package com.tirthankar.ascent.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tirthankar.ascent.data.backup.BackupPaths
import com.tirthankar.ascent.data.backup.BackupService
import com.tirthankar.ascent.data.backup.RestoreFinisher
import com.tirthankar.ascent.data.db.AscentDatabase
import com.tirthankar.ascent.data.settings.BackupPrefs
import com.tirthankar.ascent.data.repo.AchievementRepository
import com.tirthankar.ascent.data.repo.ArcRepository
import com.tirthankar.ascent.data.repo.AwakeningInput
import com.tirthankar.ascent.data.repo.BossRepository
import com.tirthankar.ascent.data.repo.ClassRepository
import com.tirthankar.ascent.data.repo.DailyCycle
import com.tirthankar.ascent.data.repo.GateRepository
import com.tirthankar.ascent.data.repo.GoalRepository
import com.tirthankar.ascent.data.repo.HabitRepository
import com.tirthankar.ascent.data.repo.InventoryRepository
import com.tirthankar.ascent.data.repo.LoreRepository
import com.tirthankar.ascent.data.repo.PlayerRepository
import com.tirthankar.ascent.data.repo.PowerEffects
import com.tirthankar.ascent.data.repo.PowerRepository
import com.tirthankar.ascent.data.repo.QuestRepository
import com.tirthankar.ascent.data.repo.RankTrialRepository
import com.tirthankar.ascent.data.repo.SideQuestService
import com.tirthankar.ascent.data.repo.SkillRepository
import com.tirthankar.ascent.data.settings.AscentSettings
import com.tirthankar.ascent.data.settings.SettingsRepository
import com.tirthankar.ascent.engine.AwakeningMeasurements
import com.tirthankar.ascent.engine.BaselineInputs
import com.tirthankar.ascent.engine.RewardCalculator
import kotlinx.coroutines.CoroutineScope
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.random.Random

class FakeTime(
    var instant: Instant,
    var zoneId: ZoneId = ZoneOffset.UTC,
) : TimeSource {
    override fun now(): Instant = instant
    override fun zone(): ZoneId = zoneId

    fun advance(duration: Duration) {
        instant = instant.plus(duration)
    }
}

/**
 * A whole data layer with a fake clock. Starts on Wed 11 Nov 2026. In memory, or in a
 * database file named [dbName] (the backup tests need real files).
 */
class TestGame(dir: File, scope: CoroutineScope, dbName: String? = null) {
    val context: Context = ApplicationProvider.getApplicationContext()
    val db: AscentDatabase = if (dbName == null) {
        Room.inMemoryDatabaseBuilder(context, AscentDatabase::class.java).build()
    } else {
        Room.databaseBuilder(context, AscentDatabase::class.java, dbName).addMigrations(*AscentDatabase.MIGRATIONS).build()
    }
    val time = FakeTime(Instant.parse("2026-11-11T10:00:00Z"))
    val settings = SettingsRepository(
        PreferenceDataStoreFactory.create(scope = scope) { File(dir, "test.preferences_pb") },
    )
    val events = GameEvents()
    val gameDay = GameDay(settings, time)
    val titles = TemplateTitles(context)
    val random = Random(42)
    val rewards = RewardCalculator(random)
    val content = ContentRepository(context)
    val inventory = InventoryRepository(db, content, time, events)
    val lore = LoreRepository(db, content, time)
    val achievements = AchievementRepository(db, content, inventory, lore, titles, time, events)
    val skills = SkillRepository(db, content, time, events, lore)
    val classes = ClassRepository(db, content, time, events, lore)
    val powers = PowerRepository(db, skills, inventory, settings, gameDay, time, events, lore)
    val bosses = BossRepository(db, gameDay, time, events, rewards, inventory, lore, achievements, titles, powers)
    val arcs = ArcRepository(db, content, gameDay, time, events, lore, achievements, bosses)
    val gates = GateRepository(db, content, gameDay, time, events, inventory, lore, achievements)
    val trials = RankTrialRepository(db, content, gameDay, time, events, inventory, lore, achievements)
    val habits = HabitRepository(db)
    val backupPrefs = BackupPrefs(PreferenceDataStoreFactory.create(scope = scope) { File(dir, "backup.preferences_pb") })
    val paths = BackupPaths(
        database = context.getDatabasePath(dbName ?: "in-memory.db"),
        datastore = File(dir, "test.preferences_pb"),
        workDir = File(dir, "backup"),
    )
    val restoreFinisher = RestoreFinisher(db, backupPrefs, paths, gameDay, time, lore, events)
    val backups = BackupService(context, db, backupPrefs, paths, gameDay, time, content)
    val quests = QuestRepository(db, gameDay, time, events, rewards, random, settings, inventory, gates, arcs, achievements, skills, powers, trials, content, habits)
    val sessionClock = FakeSessionClock(context)
    val activities = com.tirthankar.ascent.data.repo.ActivityRepository(db, gameDay, sessionClock, com.tirthankar.ascent.data.repo.PlannerCoordinator(db, gameDay), quests, events, skills)
    val effects = PowerEffects(db, powers, quests, skills, titles, settings, gameDay, events, time)
    val goals = GoalRepository(db, gameDay, time)
    val players = PlayerRepository(db, settings, titles, time, gameDay, random)
    val sideQuests = SideQuestService(db, quests, content, settings, gameDay, time, titles, events)
    val cycle = DailyCycle(
        db, settings, gameDay, titles, quests, events, sideQuests, gates, bosses, arcs, achievements, inventory, lore,
        skills, powers, trials, habits, restoreFinisher,
    )

    suspend fun awaken(settings: AscentSettings = AscentSettings()) {
        players.createPlayer(
            AwakeningInput(
                name = "Tester",
                measurements = AwakeningMeasurements(),
                baselines = BaselineInputs(maxPushUps = 15, maxSquats = 20, averageSteps = 4_000),
                settings = settings,
            ),
        )
        cycle.ensureUpToDate()
    }

    fun close() = db.close()
}

class FakeSessionClock(context: Context) : com.tirthankar.ascent.data.repo.SessionClock(context) {
    var millis = 1000L
    var bootNumber = 1
    override fun elapsed() = millis
    override fun boot() = bootNumber
}
