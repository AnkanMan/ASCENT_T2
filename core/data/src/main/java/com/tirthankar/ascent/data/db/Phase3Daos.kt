package com.tirthankar.ascent.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ArcDao {
    @Insert suspend fun insertArc(arc: ArcEntity): Long
    @Update suspend fun updateArc(arc: ArcEntity)
    @Insert suspend fun insertChapter(chapter: ChapterEntity): Long
    @Update suspend fun updateChapter(chapter: ChapterEntity)
    @Insert suspend fun insertObjective(objective: ChapterObjectiveEntity): Long
    @Update suspend fun updateObjective(objective: ChapterObjectiveEntity)

    @Query("SELECT COALESCE(MAX(id), 0) FROM arc") suspend fun maxId(): Long
    @Query("SELECT * FROM arc WHERE id = :id") suspend fun getArc(id: Long): ArcEntity?
    @Query("SELECT * FROM arc WHERE publicId = :publicId") suspend fun getByPublicId(publicId: String): ArcEntity?
    @Query("SELECT * FROM arc WHERE status IN ('ACTIVE', 'BACKLOG') ORDER BY status, id") fun observeOpen(): Flow<List<ArcEntity>>
    @Query("SELECT * FROM arc WHERE status IN ('ACTIVE', 'BACKLOG') ORDER BY status, id") suspend fun getOpen(): List<ArcEntity>
    @Query("SELECT * FROM arc WHERE status = 'ACTIVE'") suspend fun getActive(): List<ArcEntity>
    @Query("SELECT * FROM chapter WHERE arcId = :arcId ORDER BY position") suspend fun chapters(arcId: Long): List<ChapterEntity>
    @Query("SELECT * FROM chapter ORDER BY arcId, position") fun observeChapters(): Flow<List<ChapterEntity>>
    @Query("SELECT * FROM chapter WHERE id = :id") suspend fun getChapter(id: Long): ChapterEntity?
    @Query("SELECT * FROM chapter_objective WHERE chapterId = :chapterId ORDER BY position") suspend fun objectives(chapterId: Long): List<ChapterObjectiveEntity>
    @Query("SELECT * FROM chapter_objective ORDER BY chapterId, position") fun observeObjectives(): Flow<List<ChapterObjectiveEntity>>
    @Query("SELECT * FROM chapter_objective WHERE id = :id") suspend fun getObjective(id: Long): ChapterObjectiveEntity?
}

@Dao
interface GateDao {
    @Insert suspend fun insertGate(gate: GateEntity): Long
    @Update suspend fun updateGate(gate: GateEntity)
    @Insert suspend fun insertObjective(objective: GateObjectiveEntity): Long
    @Update suspend fun updateObjective(objective: GateObjectiveEntity)

    @Query("SELECT COALESCE(MAX(id), 0) FROM gate") suspend fun maxId(): Long
    @Query("SELECT * FROM gate WHERE id = :id") suspend fun getGate(id: Long): GateEntity?
    @Query("SELECT * FROM gate WHERE publicId = :publicId") suspend fun getByPublicId(publicId: String): GateEntity?
    @Query("SELECT * FROM gate WHERE status IN ('OFFERED', 'OPEN') AND kind != 'RANK_TRIAL' ORDER BY status DESC, id") fun observeCurrent(): Flow<List<GateEntity>>
    @Query("SELECT * FROM gate WHERE status IN ('OFFERED', 'OPEN') AND kind != 'RANK_TRIAL' ORDER BY status DESC, id") suspend fun getCurrent(): List<GateEntity>
    /** Open Gates that count toward the two-open limit (rank trials don't). */
    @Query("SELECT * FROM gate WHERE status = 'OPEN' AND kind != 'RANK_TRIAL'") suspend fun getOpen(): List<GateEntity>
    @Query("SELECT * FROM gate WHERE status = 'OPEN' AND kind = 'RANK_TRIAL'") suspend fun openTrials(): List<GateEntity>
    @Query("SELECT * FROM gate WHERE kind = 'RANK_TRIAL' ORDER BY id DESC LIMIT 1") suspend fun lastTrial(): GateEntity?
    @Query("SELECT * FROM gate WHERE kind = 'RANK_TRIAL' AND status = 'OPEN'") fun observeOpenTrial(): Flow<List<GateEntity>>
    @Query("SELECT * FROM gate WHERE status = 'OFFERED'") suspend fun getOffered(): List<GateEntity>
    @Query("SELECT * FROM gate WHERE status NOT IN ('OFFERED', 'OPEN', 'DECLINED', 'LAPSED') AND kind != 'RANK_TRIAL' ORDER BY closesDay DESC LIMIT :limit")
    fun observeClosed(limit: Int): Flow<List<GateEntity>>
    @Query("SELECT * FROM gate_objective WHERE gateId = :gateId ORDER BY position") suspend fun objectives(gateId: Long): List<GateObjectiveEntity>
    @Query("SELECT * FROM gate_objective ORDER BY gateId, position") fun observeObjectives(): Flow<List<GateObjectiveEntity>>
    @Query("SELECT * FROM gate_objective WHERE id = :id") suspend fun getObjective(id: Long): GateObjectiveEntity?
    @Query("SELECT COUNT(*) FROM gate WHERE status = 'CLEARED' AND kind != 'RANK_TRIAL'") suspend fun clearedCount(): Int
    @Query("SELECT COUNT(*) FROM gate WHERE status = 'CLEARED' AND isRed = 1") suspend fun redClearedCount(): Int
}

@Dao
interface BossDao {
    @Insert suspend fun insertBoss(boss: BossEntity): Long
    @Update suspend fun updateBoss(boss: BossEntity)
    @Insert suspend fun insertPhase(phase: BossPhaseEntity): Long
    @Insert suspend fun insertTask(task: BossTaskEntity): Long
    @Update suspend fun updateTask(task: BossTaskEntity)

    @Query("SELECT COALESCE(MAX(id), 0) FROM boss") suspend fun maxId(): Long
    @Query("SELECT COALESCE(MAX(id), 0) FROM boss_task") suspend fun maxTaskId(): Long
    @Query("SELECT * FROM boss WHERE id = :id") suspend fun getBoss(id: Long): BossEntity?
    @Query("SELECT * FROM boss WHERE publicId = :publicId") suspend fun getByPublicId(publicId: String): BossEntity?
    @Query("SELECT * FROM boss WHERE status = 'ACTIVE' ORDER BY isTrial, id") fun observeActive(): Flow<List<BossEntity>>
    @Query("SELECT * FROM boss WHERE status = 'ACTIVE' ORDER BY isTrial, id") suspend fun getActive(): List<BossEntity>
    @Query("SELECT * FROM boss WHERE status != 'ACTIVE' ORDER BY defeatedDay DESC LIMIT :limit") fun observeFinished(limit: Int): Flow<List<BossEntity>>
    @Query("SELECT * FROM boss WHERE id = :id") fun observeBoss(id: Long): Flow<BossEntity?>
    @Query("SELECT * FROM boss_phase WHERE bossId = :bossId ORDER BY position") suspend fun phases(bossId: Long): List<BossPhaseEntity>
    @Query("SELECT * FROM boss_phase WHERE bossId = :bossId ORDER BY position") fun observePhases(bossId: Long): Flow<List<BossPhaseEntity>>
    @Query("SELECT * FROM boss_phase ORDER BY bossId, position") fun observeAllPhases(): Flow<List<BossPhaseEntity>>
    @Query("SELECT * FROM boss WHERE arcId = :arcId") suspend fun forArc(arcId: Long): List<BossEntity>
    @Query("SELECT * FROM boss WHERE isTrial = 1 AND trialTemplate = :key") suspend fun trialsFor(key: String): List<BossEntity>
    @Query("SELECT * FROM boss_task WHERE bossId = :bossId ORDER BY phase, id") suspend fun tasks(bossId: Long): List<BossTaskEntity>
    @Query("SELECT * FROM boss_task WHERE bossId = :bossId ORDER BY phase, id") fun observeTasks(bossId: Long): Flow<List<BossTaskEntity>>
    @Query("SELECT * FROM boss_task ORDER BY bossId, phase, id") fun observeAllTasks(): Flow<List<BossTaskEntity>>
    @Query("SELECT * FROM boss_task WHERE id = :id") suspend fun getTask(id: Long): BossTaskEntity?
    @Query("SELECT * FROM boss_task WHERE publicId = :publicId") suspend fun getTaskByPublicId(publicId: String): BossTaskEntity?
    @Query("SELECT MAX(lastAttemptDay) FROM boss WHERE isTrial = 1") suspend fun lastTrialAttempt(): Long?
    @Query("SELECT COUNT(*) FROM boss WHERE status = 'DEFEATED' AND isTrial = 0") suspend fun defeatedCount(): Int
    @Query("SELECT COUNT(*) FROM boss WHERE paperSubmitted = 1") suspend fun papersSubmitted(): Int
}

@Dao
interface InventoryDao {
    @Insert suspend fun insert(item: InventoryItemEntity): Long
    @Update suspend fun update(item: InventoryItemEntity)
    @Query("DELETE FROM inventory_item WHERE id = :id") suspend fun delete(id: Long)
    @Query("SELECT * FROM inventory_item WHERE id = :id") suspend fun get(id: Long): InventoryItemEntity?
    @Query("SELECT * FROM inventory_item ORDER BY rarity DESC, acquiredAtMillis DESC") fun observeAll(): Flow<List<InventoryItemEntity>>
    @Query("SELECT * FROM inventory_item WHERE equippedSlot IS NOT NULL") suspend fun equipped(): List<InventoryItemEntity>
    @Query("SELECT * FROM inventory_item WHERE equippedSlot IS NOT NULL") fun observeEquipped(): Flow<List<InventoryItemEntity>>
    @Query("SELECT EXISTS(SELECT 1 FROM inventory_item WHERE source = :source)") suspend fun hasSource(source: String): Boolean
}

@Dao
interface ProgressDao {
    @Upsert suspend fun upsertAchievement(achievement: PlayerAchievementEntity)
    @Query("SELECT * FROM player_achievement") suspend fun achievements(): List<PlayerAchievementEntity>
    @Query("SELECT * FROM player_achievement") fun observeAchievements(): Flow<List<PlayerAchievementEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertTitle(title: EarnedTitleEntity)
    @Query("SELECT * FROM earned_title ORDER BY earnedAtMillis") fun observeTitles(): Flow<List<EarnedTitleEntity>>
    @Query("SELECT EXISTS(SELECT 1 FROM earned_title WHERE title = :title)") suspend fun hasTitle(title: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertLore(lore: LoreUnlockEntity): Long
    @Query("SELECT * FROM lore_unlock ORDER BY unlockedAtMillis") fun observeLore(): Flow<List<LoreUnlockEntity>>
    @Query("SELECT id FROM lore_unlock") suspend fun loreIds(): List<String>
    @Query("UPDATE lore_unlock SET readAt = :at WHERE id = :id AND readAt IS NULL") suspend fun markRead(id: String, at: Long)
    @Query("SELECT COUNT(*) FROM lore_unlock WHERE readAt IS NULL") fun observeUnread(): Flow<Int>

    @Upsert suspend fun upsertCustomLore(lore: CustomLoreEntity)
    @Query("SELECT * FROM custom_lore WHERE targetKind = :kind AND targetId = :targetId AND event = :event")
    suspend fun customLore(kind: LoreTarget, targetId: Long, event: String): CustomLoreEntity?
}
