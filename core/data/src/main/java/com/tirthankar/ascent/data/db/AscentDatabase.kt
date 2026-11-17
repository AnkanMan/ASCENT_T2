package com.tirthankar.ascent.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * The single source of truth for player data.
 *
 * Every schema change bumps [VERSION], adds a Migration and a MigrationTest case.
 * `fallbackToDestructiveMigration` is banned (CLAUDE.md invariants).
 */
@Database(
    entities = [
        PlayerEntity::class,
        AttributeEntity::class,
        RealmDifficultyEntity::class,
        BaselineEntity::class,
        BodyTargetEntity::class,
        QuestEntity::class,
        GoalEntity::class,
        DailySnapshotEntity::class,
        // v2 (Phase 3)
        ArcEntity::class,
        ChapterEntity::class,
        ChapterObjectiveEntity::class,
        GateEntity::class,
        GateObjectiveEntity::class,
        BossEntity::class,
        BossPhaseEntity::class,
        BossTaskEntity::class,
        InventoryItemEntity::class,
        PlayerAchievementEntity::class,
        EarnedTitleEntity::class,
        LoreUnlockEntity::class,
        CustomLoreEntity::class,
        // v3 (Phase 4)
        PlayerSkillEntity::class,
        PowerCastEntity::class,
        // v4 (Phase 5)
        HabitObservationEntity::class,
        HabitSeriesCacheEntity::class,
        XpEventEntity::class,
        DayPlanEntity::class, PlanItemEntity::class, TaskSessionEntity::class,
        StudySessionEntity::class,
    ],
    version = AscentDatabase.VERSION,
    exportSchema = true,
)
abstract class AscentDatabase : RoomDatabase() {
    abstract fun playerDao(): PlayerDao
    abstract fun questDao(): QuestDao
    abstract fun goalDao(): GoalDao
    abstract fun snapshotDao(): SnapshotDao
    abstract fun arcDao(): ArcDao
    abstract fun gateDao(): GateDao
    abstract fun bossDao(): BossDao
    abstract fun inventoryDao(): InventoryDao
    abstract fun progressDao(): ProgressDao
    abstract fun skillDao(): SkillDao
    abstract fun habitDao(): HabitDao

    abstract fun studyDao(): StudyDao
    abstract fun plannerDao(): PlannerDao

    companion object {
        const val VERSION = 6
        const val NAME = "ascent.db"

        /** All migrations, in order. Each is additive: player data is never dropped or rewritten. */
        val MIGRATIONS = arrayOf(Migrations.MIGRATION_1_2, Migrations.MIGRATION_2_3, Migrations.MIGRATION_3_4, Migrations.MIGRATION_4_5, Migrations.MIGRATION_5_6)
    }
}
