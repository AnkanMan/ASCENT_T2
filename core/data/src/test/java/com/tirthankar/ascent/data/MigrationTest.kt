package com.tirthankar.ascent.data

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tirthankar.ascent.data.db.AscentDatabase
import com.tirthankar.ascent.data.db.GateKind
import com.tirthankar.ascent.data.db.Migrations
import com.tirthankar.ascent.data.db.QuestKind
import com.tirthankar.ascent.data.db.QuestStatus
import com.tirthankar.ascent.data.repo.HabitRepository
import com.tirthankar.ascent.engine.HabitStrength
import com.tirthankar.ascent.engine.SeriesPeriod
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every schema version must open from its exported JSON and migrate to the latest
 * without losing player data (CLAUDE.md invariants).
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AscentDatabase::class.java,
    )

    @Test
    fun `v1 schema is exported and validates`() {
        helper.createDatabase(DB, 1).close()
        helper.runMigrationsAndValidate(DB, AscentDatabase.VERSION, true, *AscentDatabase.MIGRATIONS).close()
    }

    @Test
    fun `migration 1 to 2 matches the v2 schema exactly`() {
        helper.createDatabase(DB, 1).use { insertPhase2Data(it) }
        helper.runMigrationsAndValidate(DB, 2, true, Migrations.MIGRATION_1_2).close()
    }

    @Test
    fun `every Phase 2 row survives migration 1 to 2`() {
        helper.createDatabase(DB, 1).use { insertPhase2Data(it) }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val room = Room.databaseBuilder(context, AscentDatabase::class.java, DB)
            .addMigrations(*AscentDatabase.MIGRATIONS)
            .build()
        try {
            runBlocking {
                val player = room.playerDao().getPlayer()!!
                assertEquals("Kept", player.name)
                assertEquals(9_000, player.totalXp)
                assertEquals(10, player.level)
                assertEquals(12, player.streakDays)
                assertEquals(0, player.essence)
                assertNotEquals(0L, player.rollSeed) // a random per-install seed
                assertNull(player.lastBoardWeek)

                assertEquals(6, room.playerDao().getAttributes().size)
                assertEquals(3, room.playerDao().getDifficulties().size)

                val done = room.questDao().getByPublicId("qdone")!!
                assertEquals(QuestStatus.DONE, done.status)
                assertEquals(QuestKind.NORMAL, done.kind)
                assertEquals(1.0, done.xpFactor)
                assertEquals(20_403L, done.completedDay) // backfilled for achievements (D8)
                assertEquals(126L, done.xpAwarded)
                val open = room.questDao().getByPublicId("qopen")!!
                assertNull(open.completedDay)

                val goal = room.goalDao().getByPublicId("g1")!!
                assertEquals("Finish KPZ scaling figures", goal.title)
                assertNull(goal.objectiveId)

                assertEquals(1, room.snapshotDao().dailiesCompleted())
                assertTrue(room.inventoryDao().equipped().isEmpty())
            }
        } finally {
            room.close()
        }
    }

    @Test
    fun `migration 2 to 3 matches the v3 schema exactly`() {
        helper.createDatabase(DB, 2).use { insertPhase3Data(it) }
        helper.runMigrationsAndValidate(DB, 3, true, Migrations.MIGRATION_2_3).close()
    }

    @Test
    fun `every Phase 3 row survives migration 2 to 3`() {
        helper.createDatabase(DB, 1).use { insertPhase2Data(it) }
        helper.runMigrationsAndValidate(DB, 2, true, Migrations.MIGRATION_1_2).use { insertPhase3Data(it) }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val room = Room.databaseBuilder(context, AscentDatabase::class.java, DB)
            .addMigrations(*AscentDatabase.MIGRATIONS)
            .build()
        try {
            runBlocking {
                val player = room.playerDao().getPlayer()!!
                assertEquals("Kept", player.name)
                assertEquals(9_000, player.totalXp)
                assertEquals(10, player.level)
                assertEquals(77, player.essence)
                assertEquals(1234L, player.rollSeed)
                assertNull(player.classId)
                assertEquals(0, player.respecTokens)
                assertEquals(0, player.bonusSkillPoints)
                assertEquals(0, player.shieldsUsedWeek)
                assertNull(player.lastMissDay)

                val done = room.questDao().getByPublicId("qdone")!!
                assertEquals(126L, done.xpAwarded)
                assertEquals("", done.tags)

                val red = room.gateDao().getByPublicId("gred")!!
                assertEquals(GateKind.RED, red.kind)
                assertTrue(red.isRed)
                assertEquals(0, red.attempt)
                assertNull(red.targetRank)
                val normal = room.gateDao().getByPublicId("gnormal")!!
                assertEquals(GateKind.NORMAL, normal.kind)
                assertEquals(2, room.gateDao().clearedCount())
                assertEquals(1, room.gateDao().redClearedCount())

                val lore = room.progressDao().observeLore().first().single()
                assertEquals("lore_awakening", lore.id)
                assertEquals(5_000L, lore.readAt) // marked read: no unread dots for old lines
                assertNull(lore.category)
                assertEquals(0, room.progressDao().observeUnread().first())

                assertTrue(room.skillDao().skills().isEmpty())
                assertTrue(room.skillDao().activeCasts().isEmpty())
            }
        } finally {
            room.close()
        }
    }

    @Test
    fun `migration 3 to 4 matches the v4 schema exactly`() {
        helper.createDatabase(DB, 1).use { insertPhase2Data(it) }
        helper.runMigrationsAndValidate(DB, 2, true, Migrations.MIGRATION_1_2).use { insertPhase3Data(it) }
        helper.runMigrationsAndValidate(DB, 3, true, Migrations.MIGRATION_2_3).use { insertPhase4Data(it) }
        helper.runMigrationsAndValidate(DB, 4, true, Migrations.MIGRATION_3_4).close()
    }

    @Test
    fun `every Phase 4 row survives migration 3 to 4, and habit history is backfilled`() {
        helper.createDatabase(DB, 1).use { insertPhase2Data(it) }
        helper.runMigrationsAndValidate(DB, 2, true, Migrations.MIGRATION_1_2).use { insertPhase3Data(it) }
        helper.runMigrationsAndValidate(DB, 3, true, Migrations.MIGRATION_2_3).use { insertPhase4Data(it) }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val room = Room.databaseBuilder(context, AscentDatabase::class.java, DB)
            .addMigrations(*AscentDatabase.MIGRATIONS)
            .build()
        try {
            runBlocking {
                // Player data untouched.
                val player = room.playerDao().getPlayer()!!
                assertEquals("Kept", player.name)
                assertEquals(9_000, player.totalXp)
                assertEquals("theorist", player.classId)
                assertEquals(2, player.respecTokens)
                val done = room.questDao().getByPublicId("qdone")!!
                assertEquals(126L, done.xpAwarded)
                assertEquals(10, done.masteryAwarded)
                assertEquals(1.0, done.guardMultiplier)
                assertEquals("paper", room.questDao().getByPublicId("qtagged")!!.tags)
                assertEquals(1, room.skillDao().skills().size)
                assertEquals("journal line", room.skillDao().recentCasts("clarity", 5).single().note)
                assertTrue(room.snapshotDao().getRange(0, 30_000).none { it.neutral })
                assertTrue(room.habitDao().xpEvents().isEmpty())

                // Series: template dailies by key, recurring chains by title, realm, scope and source.
                assertEquals("t:body_push_ups", done.seriesId)
                assertEquals("t:body_push_ups", room.questDao().getByPublicId("qpush1")!!.seriesId)
                val stretch = room.questDao().getByPublicId("qstr1")!!.seriesId
                assertEquals("r:qstr1", stretch)
                listOf("qstr2", "qstr3", "qstr4").forEach { assertEquals(stretch, room.questDao().getByPublicId(it)!!.seriesId) }
                assertEquals("r:qstrm", room.questDao().getByPublicId("qstrm")!!.seriesId) // another realm: its own chain
                assertEquals("r:qwk1", room.questDao().getByPublicId("qwk2")!!.seriesId)
                listOf("qoneoff", "qclaude", "qside", "qtagged").forEach { assertNull(room.questDao().getByPublicId(it)!!.seriesId, it) }

                // Observations: closed periods only; done 1, expired 0; nothing for removed
                // quests, rest days or the open day and week.
                val push = room.habitDao().observations("t:body_push_ups").associate { it.periodKey to it.x }
                assertEquals(mapOf(20_400L to 0.0, 20_401L to 1.0, 20_403L to 1.0), push)
                val str = room.habitDao().observations(stretch!!).associate { it.periodKey to it.x }
                assertEquals(mapOf(20_401L to 1.0, 20_403L to 0.0), str)
                val weekly = room.habitDao().observations("r:qwk1")
                assertEquals(listOf(20_394L), weekly.map { it.periodKey })
                assertEquals(3.0, weekly.single().progress)

                // Strength from the backfill, computed on a cache miss and then cached.
                val habits = HabitRepository(room)
                val summary = habits.summary("t:body_push_ups")
                val a = HabitStrength.ALPHA_DAY
                // x = 0, 1, 1: S = 0, then a, then (1 − a)·a + a.
                assertEquals((1 - a) * a + a, summary.strength, 1e-12)
                assertEquals(3, summary.observations)
                assertEquals(2, summary.streak)
                assertEquals(20_400L, summary.sinceKey)
                assertTrue(summary.measuring)
                assertEquals(summary.strength, room.habitDao().cache("t:body_push_ups")!!.strength)
                assertEquals(SeriesPeriod.WEEK, habits.period("r:qwk1"))
                assertEquals(HabitStrength.ALPHA_WEEK, habits.summary("r:qwk1").strength, 1e-12)
            }
        } finally {
            room.close()
        }
    }

    /**
     * A v3 database with Phase 4 data: a class and skills, a Clarity journal, and habit
     * history. The player's open day is 20404 (a Thursday); 20401 is that week's Sunday,
     * and 20402 was a rest day.
     */
    private fun insertPhase4Data(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("UPDATE player SET classId = 'theorist', respecTokens = 2")
        db.execSQL("INSERT INTO player_skill (skillId, unlockedAt, periodKey, usesInPeriod, lastCastAt, unlocked) VALUES ('clarity', 1, NULL, 0, NULL, 1)")
        db.execSQL("INSERT INTO power_cast (skillId, castAt, day, targetKind, targetId, mpSpent, status, expiresAt, note) VALUES ('clarity', 2, 20403, 'NONE', NULL, 10, 'COMPLETED', NULL, 'journal line')")
        db.execSQL(
            """
            INSERT INTO daily_snapshot (day, level, totalXp, str, agi, int, foc, wis, vit, hp, mp, streakDays, objectives,
                completed, bodyDone, bodyTotal, mindDone, mindTotal, selfDone, selfTotal, dailyComplete, restDay)
            VALUES (20402, 10, 9000, 11, 11, 11, 11, 11, 11, 180, 120, 12, 1, 1, 1, 1, 0, 0, 0, 0, 1, 1)
            """.trimIndent(),
        )
        // (publicId, title, realm, scope, recurring, source, templateKey, kind, day, status)
        listOf(
            q("qpush0", "Push-ups", "BODY", "DAILY", 0, "TEMPLATE", "'body_push_ups'", "NORMAL", 20_399, "REMOVED"),
            q("qpush1", "Push-ups", "BODY", "DAILY", 0, "TEMPLATE", "'body_push_ups'", "NORMAL", 20_400, "EXPIRED"),
            q("qpush2", "Push-ups", "BODY", "DAILY", 0, "TEMPLATE", "'body_push_ups'", "NORMAL", 20_401, "DONE"),
            q("qpush3", "Push-ups", "BODY", "DAILY", 0, "TEMPLATE", "'body_push_ups'", "NORMAL", 20_402, "DONE"), // rest day
            q("qpush5", "Push-ups", "BODY", "DAILY", 0, "TEMPLATE", "'body_push_ups'", "NORMAL", 20_404, "DONE"), // open day
            q("qstr1", "Stretch", "BODY", "DAILY", 1, "MANUAL", "NULL", "NORMAL", 20_401, "DONE"),
            q("qstr2", "Stretch", "BODY", "DAILY", 1, "MANUAL", "NULL", "NORMAL", 20_402, "EXPIRED"), // rest day
            q("qstr3", "Stretch", "BODY", "DAILY", 1, "MANUAL", "NULL", "NORMAL", 20_403, "EXPIRED"),
            q("qstr4", "Stretch", "BODY", "DAILY", 1, "MANUAL", "NULL", "NORMAL", 20_404, "OPEN"),
            q("qstrm", "Stretch", "MIND", "DAILY", 1, "MANUAL", "NULL", "NORMAL", 20_403, "DONE"),
            q("qwk1", "Three strength sessions", "BODY", "WEEKLY", 1, "MANUAL", "NULL", "NORMAL", 20_394, "DONE"),
            q("qwk2", "Three strength sessions", "BODY", "WEEKLY", 1, "MANUAL", "NULL", "NORMAL", 20_401, "OPEN"), // open week
            q("qoneoff", "Email the group", "SELF", "DAILY", 0, "MANUAL", "NULL", "NORMAL", 20_403, "DONE"),
            q("qclaude", "Read a paper", "MIND", "DAILY", 0, "CLAUDE", "'mind_paper_summary'", "NORMAL", 20_403, "DONE"),
            q("qside", "A walk", "BODY", "DAILY", 0, "TEMPLATE", "'body_easy_walk'", "SIDE", 20_403, "DONE"),
        ).forEach(db::execSQL)
        db.execSQL(
            """
            INSERT INTO quest (publicId, title, realm, stat, tier, target, unit, unitLabel, scope, isDaily, recurring,
                source, templateKey, goalId, day, status, completedAtMillis, xpAwarded, critical, masteryAwarded,
                levelAtCompletion, createdAtMillis, tags)
            VALUES ('qtagged', 'Summarise a paper', 'MIND', 'INT', 'NORMAL', 1, 'OTHER', 'done', 'DAILY', 0, 0,
                'CLAUDE', NULL, NULL, 20403, 'OPEN', NULL, 0, 0, 0, NULL, 0, 'paper')
            """.trimIndent(),
        )
    }

    private fun q(
        id: String, title: String, realm: String, scope: String, recurring: Int, source: String,
        templateKey: String, kind: String, day: Int, status: String,
    ): String {
        val done = status == "DONE"
        return """
            INSERT INTO quest (publicId, title, realm, stat, tier, target, unit, unitLabel, scope, isDaily, recurring,
                source, templateKey, goalId, day, status, completedAtMillis, xpAwarded, critical, masteryAwarded,
                levelAtCompletion, createdAtMillis, kind)
            VALUES ('$id', '$title', '$realm', 'STR', 'NORMAL', 3, 'OTHER', 'sessions', '$scope', 0, $recurring,
                '$source', $templateKey, NULL, $day, '$status', ${if (done) 10 else "NULL"}, ${if (done) 50 else 0}, 0, 0,
                NULL, 1, '$kind')
        """.trimIndent()
    }

    /** A v2 database with Phase 3 data in it. */
    private fun insertPhase3Data(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        if (db.query("SELECT COUNT(*) FROM player").use { it.moveToFirst(); it.getInt(0) } == 0) {
            db.execSQL(
                """
                INSERT INTO player (id, name, title, rank, level, totalXp, hp, mp, freePoints, skillPoints,
                    streakDays, bestStreak, consecutiveMisses, currentDay, lastIssuedDay, recoveryDay,
                    trainingDayIndex, createdAtMillis, essence, rollSeed, lastBoardWeek)
                VALUES (0, 'Kept', 'The Unmeasured', 'E', 10, 9000, 180, 120, 3, 5, 12, 12, 0, 20404, 20404, NULL, 4, 0, 77, 1234, NULL)
                """.trimIndent(),
            )
        } else {
            db.execSQL("UPDATE player SET essence = 77, rollSeed = 1234")
        }
        listOf("gred" to 1, "gnormal" to 0).forEach { (id, red) ->
            db.execSQL(
                """
                INSERT INTO gate (publicId, templateId, title, lore, realm, rank, days, isRed, status, source, boardWeek,
                    opensDay, closesDay, fraction, xpAwarded, createdAtMillis)
                VALUES ('$id', NULL, 'A Gate', 'Lore', 'BODY', 'E', 3, $red, 'CLEARED', 'BOARD', 20399, 20399, 20402, 1.0, 300, 0)
                """.trimIndent(),
            )
        }
        db.execSQL(
            "INSERT INTO lore_unlock (id, title, text, context, unlockedAtMillis) VALUES ('lore_awakening', 'Awakening', 'Text', NULL, 5000)",
        )
    }

    private fun insertPhase2Data(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(
            """
            INSERT INTO player (id, name, title, rank, level, totalXp, hp, mp, freePoints, skillPoints,
                streakDays, bestStreak, consecutiveMisses, currentDay, lastIssuedDay, recoveryDay,
                trainingDayIndex, createdAtMillis)
            VALUES (0, 'Kept', 'The Unmeasured', 'E', 10, 9000, 180, 120, 3, 5, 12, 12, 0, 20404, 20404, NULL, 4, 0)
            """.trimIndent(),
        )
        listOf("STR", "AGI", "INT", "FOC", "WIS", "VIT").forEach {
            db.execSQL("INSERT INTO attribute (stat, value, mastery) VALUES ('$it', 11, 20)")
        }
        listOf("BODY", "MIND", "SELF").forEach {
            db.execSQL(
                "INSERT INTO realm_difficulty (realm, multiplier, completionEma, lastWeekMultiplier, risingWeeks, deload) VALUES ('$it', 1.05, 0.8, 1.0, 1, 0)",
            )
        }
        db.execSQL(
            """
            INSERT INTO quest (publicId, title, realm, stat, tier, target, unit, unitLabel, scope, isDaily, recurring,
                source, templateKey, goalId, day, status, completedAtMillis, xpAwarded, critical, masteryAwarded,
                levelAtCompletion, createdAtMillis)
            VALUES ('qdone', 'Push-ups', 'BODY', 'STR', 'NORMAL', 40, 'REPS', 'reps', 'DAILY', 1, 0,
                'TEMPLATE', 'body_push_ups', NULL, 20403, 'DONE', 1, 126, 0, 10, 9, 0)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO quest (publicId, title, realm, stat, tier, target, unit, unitLabel, scope, isDaily, recurring,
                source, templateKey, goalId, day, status, completedAtMillis, xpAwarded, critical, masteryAwarded,
                levelAtCompletion, createdAtMillis)
            VALUES ('qopen', 'Read', 'SELF', 'WIS', 'NORMAL', 10, 'PAGES', 'pages', 'DAILY', 1, 0,
                'TEMPLATE', 'self_reading', NULL, 20404, 'OPEN', NULL, 0, 0, 0, NULL, 0)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO goal (publicId, horizon, realm, title, target, unitLabel, weekStart, deadline, parentId, status, source, createdAtMillis)
            VALUES ('g1', 'WEEK', 'MIND', 'Finish KPZ scaling figures', 3, 'figures', 20399, NULL, NULL, 'ACTIVE', 'CLAUDE', 0)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO daily_snapshot (day, level, totalXp, str, agi, int, foc, wis, vit, hp, mp, streakDays, objectives,
                completed, bodyDone, bodyTotal, mindDone, mindTotal, selfDone, selfTotal, dailyComplete, restDay)
            VALUES (20403, 10, 9000, 11, 11, 11, 11, 11, 11, 180, 120, 12, 4, 4, 1, 1, 2, 2, 1, 1, 1, 0)
            """.trimIndent(),
        )
    }

    @Test
    fun `migration 4 to 5 preserves quest XP and initializes legacy progress`() {
        helper.createDatabase(DB, 1).use { insertPhase2Data(it) }
        helper.runMigrationsAndValidate(DB, 4, true, Migrations.MIGRATION_1_2, Migrations.MIGRATION_2_3, Migrations.MIGRATION_3_4).close()
        helper.runMigrationsAndValidate(DB, 5, true, Migrations.MIGRATION_4_5).use { db ->
            db.query("SELECT actualProgress, legacyProgress, xpAwarded FROM quest WHERE publicId = 'qdone'").use { c ->
                assertTrue(c.moveToFirst()); assertEquals(40.0, c.getDouble(0)); assertEquals(1, c.getInt(1)); assertEquals(126L, c.getLong(2))
            }
            db.query("SELECT COUNT(*) FROM day_plan").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
            db.query("SELECT COUNT(*) FROM task_session").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
        }
    }

    @Test
    fun `migration 5 to 6 preserves populated planner and task history`() {
        helper.createDatabase(DB, 1).use { insertPhase2Data(it) }
        helper.runMigrationsAndValidate(DB, 5, true, Migrations.MIGRATION_1_2, Migrations.MIGRATION_2_3, Migrations.MIGRATION_3_4, Migrations.MIGRATION_4_5).use { db ->
            db.execSQL("INSERT INTO day_plan (id,targetDay,state,createdAt,writtenAt,completedAt) VALUES (1,20403,'COMPLETED',1,1,2)")
            db.execSQL("INSERT INTO plan_item (id,planId,position,title,questId,mode,target,unit,progress,done) VALUES (1,1,0,'Keep me',NULL,'COUNT',40,'reps',40,1)")
            db.execSQL("INSERT INTO task_session (id,questId,itemId,state,startedAt,endsAt,activeSeconds,anchor,boot,endedAt,quantity,origin,note) VALUES ('kept',NULL,1,'SAVED',1,100,90,NULL,1,91,40,'MANUAL','kept note')")
        }
        helper.runMigrationsAndValidate(DB, 6, true, Migrations.MIGRATION_5_6).use { db ->
            db.query("SELECT title,done FROM plan_item WHERE id=1").use { c -> assertTrue(c.moveToFirst()); assertEquals("Keep me",c.getString(0)); assertEquals(1,c.getInt(1)) }
            db.query("SELECT activeSeconds,note FROM task_session WHERE id='kept'").use { c -> assertTrue(c.moveToFirst()); assertEquals(90L,c.getLong(0)); assertEquals("kept note",c.getString(1)) }
            db.query("SELECT xpAwarded FROM quest WHERE publicId='qdone'").use { c -> assertTrue(c.moveToFirst()); assertEquals(126L,c.getLong(0)) }
            db.query("SELECT COUNT(*) FROM study_session").use { c -> c.moveToFirst(); assertEquals(0,c.getInt(0)) }
        }
    }

    private companion object {
        const val DB = "migration-test"
    }
}
