package com.tirthankar.ascent.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SkillDao {
    @Upsert suspend fun upsertSkill(skill: PlayerSkillEntity)
    @Query("SELECT * FROM player_skill") suspend fun skills(): List<PlayerSkillEntity>
    @Query("SELECT * FROM player_skill") fun observeSkills(): Flow<List<PlayerSkillEntity>>
    @Query("SELECT * FROM player_skill WHERE skillId = :id") suspend fun skill(id: String): PlayerSkillEntity?

    /** Respec: the rows stay, so cooldowns and period uses survive (PHASE4.md section 1). */
    @Query("UPDATE player_skill SET unlocked = 0 WHERE skillId IN (:ids)") suspend fun lock(ids: List<String>)

    @Insert suspend fun insertCast(cast: PowerCastEntity): Long
    @Update suspend fun updateCast(cast: PowerCastEntity)
    @Query("SELECT * FROM power_cast WHERE id = :id") suspend fun cast(id: Long): PowerCastEntity?
    @Query("SELECT * FROM power_cast WHERE skillId = :skillId ORDER BY castAt DESC LIMIT :limit")
    suspend fun recentCasts(skillId: String, limit: Int): List<PowerCastEntity>
    @Query("SELECT * FROM power_cast WHERE status = 'ACTIVE'") suspend fun activeCasts(): List<PowerCastEntity>
    @Query("SELECT * FROM power_cast WHERE status = 'ACTIVE'") fun observeActiveCasts(): Flow<List<PowerCastEntity>>
    @Query("SELECT * FROM power_cast WHERE targetKind = :kind AND targetId = :id AND day = :day")
    suspend fun castsOn(kind: String, id: Long, day: Long): List<PowerCastEntity>
    @Query("SELECT COUNT(*) FROM power_cast") suspend fun castCount(): Int
    @Query("UPDATE power_cast SET status = 'EXPIRED' WHERE status = 'ACTIVE' AND day < :today") suspend fun expireBefore(today: Long)
}
