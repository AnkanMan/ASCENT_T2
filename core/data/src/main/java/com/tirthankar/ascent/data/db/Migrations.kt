package com.tirthankar.ascent.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Schema migrations. Each one only adds: player data is never dropped or rewritten. */
object Migrations {
    /** v5 → v6: isolated, additive Study Mode attempt/history storage. */
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS study_session (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, day INTEGER NOT NULL, subject TEXT NOT NULL, objective TEXT NOT NULL, plannedSeconds INTEGER NOT NULL, quoteIndex INTEGER NOT NULL, questId INTEGER, taskSessionId TEXT, state TEXT NOT NULL, startedAt INTEGER NOT NULL, endsAt INTEGER NOT NULL, activeSeconds INTEGER NOT NULL, anchor INTEGER, boot INTEGER NOT NULL, endedAt INTEGER, learned TEXT NOT NULL, nextStep TEXT NOT NULL, xpAwarded INTEGER NOT NULL, questXpAwarded INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_study_session_day ON study_session(day)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_study_session_questId ON study_session(questId)")
        }
    }


    /** v4 → v5: additive planner/session storage and explicit legacy progress. */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE quest ADD COLUMN actualProgress REAL NOT NULL DEFAULT 0.0")
            db.execSQL("ALTER TABLE quest ADD COLUMN measurementMode TEXT NOT NULL DEFAULT 'AUTO'")
            db.execSQL("ALTER TABLE quest ADD COLUMN legacyProgress INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE quest SET actualProgress = CASE WHEN status = 'DONE' THEN target ELSE 0 END, legacyProgress = 1")
            db.execSQL("CREATE TABLE IF NOT EXISTS day_plan (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, targetDay INTEGER NOT NULL, state TEXT NOT NULL, createdAt INTEGER NOT NULL, writtenAt INTEGER NOT NULL, completedAt INTEGER)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_day_plan_targetDay ON day_plan(targetDay)")
            db.execSQL("CREATE TABLE IF NOT EXISTS plan_item (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, planId INTEGER NOT NULL, position INTEGER NOT NULL, title TEXT NOT NULL, questId INTEGER, mode TEXT NOT NULL, target REAL NOT NULL, unit TEXT NOT NULL, progress REAL NOT NULL, done INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_plan_item_planId ON plan_item(planId)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_plan_item_planId_questId ON plan_item(planId, questId)")
            db.execSQL("CREATE TABLE IF NOT EXISTS task_session (id TEXT NOT NULL PRIMARY KEY, questId INTEGER, itemId INTEGER, state TEXT NOT NULL, startedAt INTEGER NOT NULL, endsAt INTEGER NOT NULL, activeSeconds INTEGER NOT NULL, anchor INTEGER, boot INTEGER, endedAt INTEGER, quantity REAL NOT NULL, origin TEXT NOT NULL, note TEXT NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_task_session_questId ON task_session(questId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_task_session_itemId ON task_session(itemId)")
        }
    }

    /** v1 → v2 (Phase 3): new tables for arcs, Gates, bosses, gear, achievements and lore. */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            V2_STATEMENTS.forEach(db::execSQL)
        }
    }

    /** v2 → v3 (Phase 4): skills, power casts, classes, rank trials, quest tags, the Record. */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            V3_STATEMENTS.forEach(db::execSQL)
        }
    }

    /**
     * v3 → v4 (Phase 5): habit series and observations, the grind guard's multiplier,
     * the XP log and neutral days. Fills the new `seriesId` column and backfills
     * observations from exact matches only; no existing value changes.
     */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            V4_STATEMENTS.forEach(db::execSQL)
        }
    }

    /** Tables and columns copied from the exported schema (schemas/…/4.json), then the backfill. */
    private val V4_STATEMENTS = listOf(
        "ALTER TABLE `quest` ADD COLUMN `seriesId` TEXT",
        "ALTER TABLE `quest` ADD COLUMN `guardMultiplier` REAL NOT NULL DEFAULT 1.0",
        "CREATE INDEX IF NOT EXISTS `index_quest_seriesId` ON `quest` (`seriesId`)",
        "ALTER TABLE `daily_snapshot` ADD COLUMN `neutral` INTEGER NOT NULL DEFAULT 0",
        "CREATE TABLE IF NOT EXISTS `habit_observation` (`seriesId` TEXT NOT NULL, `periodKey` INTEGER NOT NULL, `x` REAL NOT NULL, `target` REAL NOT NULL, `progress` REAL NOT NULL, `recordedAt` INTEGER NOT NULL, PRIMARY KEY(`seriesId`, `periodKey`))",
        "CREATE TABLE IF NOT EXISTS `habit_series_cache` (`seriesId` TEXT NOT NULL, `period` TEXT NOT NULL, `strength` REAL NOT NULL, `lastPeriodKey` INTEGER NOT NULL, `streak` INTEGER NOT NULL, `best` INTEGER NOT NULL, `observations` INTEGER NOT NULL, `sinceKey` INTEGER, PRIMARY KEY(`seriesId`))",
        "CREATE TABLE IF NOT EXISTS `xp_event` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timestamp` INTEGER NOT NULL, `day` INTEGER NOT NULL, `source` TEXT NOT NULL, `amount` INTEGER NOT NULL, `multipliers` TEXT NOT NULL, `refId` INTEGER)",
        "CREATE INDEX IF NOT EXISTS `index_xp_event_day` ON `xp_event` (`day`)",

        // Series: template dailies by template key (Second Wind's swaps already carry the
        // Mobility key) …
        """
        UPDATE `quest` SET `seriesId` = 't:' || `templateKey`
        WHERE `recurring` = 0 AND `source` = 'TEMPLATE' AND `kind` = 'NORMAL' AND `templateKey` IS NOT NULL
        """.trimIndent(),
        // … and recurring chains by exact title, realm, scope and source, named after the
        // chain's first quest.
        """
        UPDATE `quest` SET `seriesId` = 'r:' || (
            SELECT q2.`publicId` FROM `quest` q2
            WHERE q2.`recurring` = 1 AND q2.`title` = `quest`.`title` AND q2.`realm` = `quest`.`realm`
                AND q2.`scope` = `quest`.`scope` AND q2.`source` = `quest`.`source`
            ORDER BY q2.`id` LIMIT 1)
        WHERE `recurring` = 1
        """.trimIndent(),
        // Observations for closed periods only: done is 1, expired is 0. Removed quests,
        // the current day or week, and rest days (for daily series) record nothing.
        // The week starts on Sunday: epoch day 3 (1970-01-04) was a Sunday.
        """
        INSERT OR REPLACE INTO `habit_observation` (`seriesId`, `periodKey`, `x`, `target`, `progress`, `recordedAt`)
        SELECT `seriesId`, `day`,
            MAX(CASE WHEN `status` = 'DONE' THEN 1.0 ELSE 0.0 END),
            MAX(`target`),
            MAX(CASE WHEN `status` = 'DONE' THEN `target` ELSE 0.0 END),
            MAX(COALESCE(`completedAtMillis`, `createdAtMillis`))
        FROM `quest`
        WHERE `seriesId` IS NOT NULL AND `status` IN ('DONE', 'EXPIRED')
            AND (
                (`scope` = 'DAILY' AND `day` < (SELECT `currentDay` FROM `player` LIMIT 1)
                    AND NOT EXISTS (SELECT 1 FROM `daily_snapshot` s WHERE s.`day` = `quest`.`day` AND s.`restDay` = 1))
                OR (`scope` = 'WEEKLY' AND `day` < (SELECT `currentDay` - ((`currentDay` + 4) % 7) FROM `player` LIMIT 1))
            )
        GROUP BY `seriesId`, `day`
        """.trimIndent(),
    )

    /** Copied from the exported schema (schemas/…/3.json). Additive only. */
    private val V3_STATEMENTS = listOf(
        "ALTER TABLE `player` ADD COLUMN `classId` TEXT",
        "ALTER TABLE `player` ADD COLUMN `classChosenAt` INTEGER",
        "ALTER TABLE `player` ADD COLUMN `respecTokens` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `player` ADD COLUMN `classRechoice` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `player` ADD COLUMN `bonusSkillPoints` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `player` ADD COLUMN `shieldWeekKey` TEXT",
        "ALTER TABLE `player` ADD COLUMN `shieldsUsedWeek` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `player` ADD COLUMN `shieldMonthKey` TEXT",
        "ALTER TABLE `player` ADD COLUMN `shieldsUsedMonth` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `player` ADD COLUMN `lastMissDay` INTEGER",
        "ALTER TABLE `player` ADD COLUMN `lastMissShielded` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `player` ADD COLUMN `equilibriumDay` INTEGER",
        "ALTER TABLE `quest` ADD COLUMN `tags` TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE `gate` ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'NORMAL'",
        "ALTER TABLE `gate` ADD COLUMN `targetRank` TEXT",
        "ALTER TABLE `gate` ADD COLUMN `attempt` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `gate` ADD COLUMN `retryAt` INTEGER",
        // The new column mirrors the existing isRed flag; isRed itself is untouched.
        "UPDATE `gate` SET `kind` = 'RED' WHERE `isRed` = 1",
        "ALTER TABLE `lore_unlock` ADD COLUMN `category` TEXT",
        "ALTER TABLE `lore_unlock` ADD COLUMN `readAt` INTEGER",
        // Lines unlocked before the Record existed count as read: no wall of unread dots.
        "UPDATE `lore_unlock` SET `readAt` = `unlockedAtMillis`",
        "CREATE TABLE IF NOT EXISTS `player_skill` (`skillId` TEXT NOT NULL, `unlockedAt` INTEGER NOT NULL, `periodKey` TEXT, `usesInPeriod` INTEGER NOT NULL, `lastCastAt` INTEGER, `unlocked` INTEGER NOT NULL DEFAULT 1, PRIMARY KEY(`skillId`))",
        "CREATE TABLE IF NOT EXISTS `power_cast` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `skillId` TEXT NOT NULL, `castAt` INTEGER NOT NULL, `day` INTEGER NOT NULL, `targetKind` TEXT NOT NULL, `targetId` INTEGER, `mpSpent` INTEGER NOT NULL, `status` TEXT NOT NULL, `expiresAt` INTEGER, `note` TEXT)",
        "CREATE INDEX IF NOT EXISTS `index_power_cast_status` ON `power_cast` (`status`)",
    )

    /** Copied from the exported schema (schemas/…/2.json) so the migration matches it exactly. */
    private val V2_STATEMENTS = listOf(
        // New columns on existing tables. Defaults keep every existing row valid.
        "ALTER TABLE `player` ADD COLUMN `essence` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `player` ADD COLUMN `rollSeed` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `player` ADD COLUMN `lastBoardWeek` INTEGER",
        // A random per-install seed for loot rolls (D13). random() is SQLite's 64-bit generator.
        "UPDATE `player` SET `rollSeed` = random()",
        "ALTER TABLE `quest` ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'NORMAL'",
        "ALTER TABLE `quest` ADD COLUMN `expiresDay` INTEGER",
        "ALTER TABLE `quest` ADD COLUMN `expiresAtMillis` INTEGER",
        "ALTER TABLE `quest` ADD COLUMN `xpFactor` REAL NOT NULL DEFAULT 1.0",
        "ALTER TABLE `quest` ADD COLUMN `completedDay` INTEGER",
        "ALTER TABLE `quest` ADD COLUMN `lootItemId` INTEGER",
        "ALTER TABLE `quest` ADD COLUMN `hpAwarded` INTEGER NOT NULL DEFAULT 0",
        // Phase 2 completions happened on their own day (or week): fill the new column so
        // achievement counters backfill from history (D8). Existing values are untouched.
        "UPDATE `quest` SET `completedDay` = `day` WHERE `status` = 'DONE'",
        "ALTER TABLE `goal` ADD COLUMN `objectiveId` INTEGER",

        "CREATE TABLE IF NOT EXISTS `arc` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `publicId` TEXT NOT NULL, `realm` TEXT NOT NULL, `title` TEXT NOT NULL, `goalId` INTEGER, `status` TEXT NOT NULL, `templateId` TEXT, `finale` TEXT NOT NULL, `finaleName` TEXT, `finaleDeadline` INTEGER, `trialTemplate` TEXT, `source` TEXT NOT NULL, `completedDay` INTEGER, `createdAtMillis` INTEGER NOT NULL)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_arc_publicId` ON `arc` (`publicId`)",
        "CREATE TABLE IF NOT EXISTS `chapter` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `arcId` INTEGER NOT NULL, `position` INTEGER NOT NULL, `title` TEXT NOT NULL, `status` TEXT NOT NULL, `completedDay` INTEGER, `xpAwarded` INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS `index_chapter_arcId` ON `chapter` (`arcId`)",
        "CREATE TABLE IF NOT EXISTS `chapter_objective` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `chapterId` INTEGER NOT NULL, `position` INTEGER NOT NULL, `title` TEXT NOT NULL, `target` REAL NOT NULL, `unitLabel` TEXT NOT NULL, `logged` REAL NOT NULL)",
        "CREATE INDEX IF NOT EXISTS `index_chapter_objective_chapterId` ON `chapter_objective` (`chapterId`)",
        "CREATE TABLE IF NOT EXISTS `gate` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `publicId` TEXT NOT NULL, `templateId` TEXT, `title` TEXT NOT NULL, `lore` TEXT NOT NULL, `realm` TEXT NOT NULL, `rank` TEXT NOT NULL, `days` INTEGER NOT NULL, `isRed` INTEGER NOT NULL, `status` TEXT NOT NULL, `source` TEXT NOT NULL, `boardWeek` INTEGER, `opensDay` INTEGER, `closesDay` INTEGER, `fraction` REAL, `xpAwarded` INTEGER NOT NULL, `createdAtMillis` INTEGER NOT NULL)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_gate_publicId` ON `gate` (`publicId`)",
        "CREATE TABLE IF NOT EXISTS `gate_objective` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `gateId` INTEGER NOT NULL, `position` INTEGER NOT NULL, `title` TEXT NOT NULL, `target` REAL NOT NULL, `unitLabel` TEXT NOT NULL, `templateKeys` TEXT NOT NULL, `isCount` INTEGER NOT NULL, `logged` REAL NOT NULL, `finalProgress` REAL)",
        "CREATE INDEX IF NOT EXISTS `index_gate_objective_gateId` ON `gate_objective` (`gateId`)",
        "CREATE TABLE IF NOT EXISTS `boss` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `publicId` TEXT NOT NULL, `title` TEXT NOT NULL, `arcId` INTEGER, `deadlineDay` INTEGER, `isTrial` INTEGER NOT NULL, `isRaid` INTEGER NOT NULL, `trialTemplate` TEXT, `recordToBeat` REAL, `lastAttemptDay` INTEGER, `lastResult` REAL, `status` TEXT NOT NULL, `source` TEXT NOT NULL, `createdDay` INTEGER NOT NULL, `lastActivityDay` INTEGER NOT NULL, `lastStirDay` INTEGER, `enrageNoticed` INTEGER NOT NULL, `defeatedDay` INTEGER, `xpAwarded` INTEGER NOT NULL, `paperSubmitted` INTEGER NOT NULL, `createdAtMillis` INTEGER NOT NULL)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_boss_publicId` ON `boss` (`publicId`)",
        "CREATE TABLE IF NOT EXISTS `boss_phase` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `bossId` INTEGER NOT NULL, `position` INTEGER NOT NULL, `name` TEXT NOT NULL)",
        "CREATE INDEX IF NOT EXISTS `index_boss_phase_bossId` ON `boss_phase` (`bossId`)",
        "CREATE TABLE IF NOT EXISTS `boss_task` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `publicId` TEXT NOT NULL, `bossId` INTEGER NOT NULL, `phase` INTEGER NOT NULL, `title` TEXT NOT NULL, `hours` REAL NOT NULL, `done` INTEGER NOT NULL, `doneDay` INTEGER, `damage` INTEGER NOT NULL, `xpAwarded` INTEGER NOT NULL, `critical` INTEGER NOT NULL, `levelAtCompletion` INTEGER)",
        "CREATE INDEX IF NOT EXISTS `index_boss_task_bossId` ON `boss_task` (`bossId`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_boss_task_publicId` ON `boss_task` (`publicId`)",
        "CREATE TABLE IF NOT EXISTS `inventory_item` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `templateId` TEXT, `name` TEXT NOT NULL, `slot` TEXT NOT NULL, `rarity` TEXT NOT NULL, `affixesJson` TEXT NOT NULL, `uniqueJson` TEXT, `setId` TEXT, `upgradeLevel` INTEGER NOT NULL, `equippedSlot` TEXT, `source` TEXT NOT NULL, `seed` INTEGER NOT NULL, `acquiredAtMillis` INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS `player_achievement` (`achievementId` TEXT NOT NULL, `tier` INTEGER NOT NULL, `earnedAtMillis` INTEGER NOT NULL, PRIMARY KEY(`achievementId`))",
        "CREATE TABLE IF NOT EXISTS `earned_title` (`title` TEXT NOT NULL, `source` TEXT NOT NULL, `earnedAtMillis` INTEGER NOT NULL, PRIMARY KEY(`title`))",
        "CREATE TABLE IF NOT EXISTS `lore_unlock` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `text` TEXT NOT NULL, `context` TEXT, `unlockedAtMillis` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `custom_lore` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `targetKind` TEXT NOT NULL, `targetId` INTEGER NOT NULL, `event` TEXT NOT NULL, `title` TEXT NOT NULL, `text` TEXT NOT NULL, `unlocked` INTEGER NOT NULL, `createdAtMillis` INTEGER NOT NULL)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_custom_lore_targetKind_targetId_event` ON `custom_lore` (`targetKind`, `targetId`, `event`)",
    )
}
