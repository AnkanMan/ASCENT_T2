package com.tirthankar.ascent.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.tirthankar.ascent.engine.BaselineKey
import com.tirthankar.ascent.engine.Rank
import com.tirthankar.ascent.engine.Realm
import com.tirthankar.ascent.engine.Stat
import com.tirthankar.ascent.engine.TargetUnit
import com.tirthankar.ascent.engine.Tier

/** The single player row (id 0). Days are epoch days of the game day (see GameClock). */
@Entity(tableName = "player")
data class PlayerEntity(
    @PrimaryKey val id: Int = SINGLE_PLAYER_ID,
    val name: String,
    val title: String,
    val rank: Rank,
    /** Highest level reached. Never decreases. */
    val level: Int,
    val totalXp: Long,
    val hp: Int,
    val mp: Int,
    val freePoints: Int,
    val skillPoints: Int,
    val streakDays: Int,
    val bestStreak: Int,
    val consecutiveMisses: Int,
    /** The game day that is open: everything before it has been resolved. */
    val currentDay: Long,
    /** The last game day a Daily Quest was issued for. */
    val lastIssuedDay: Long?,
    /** A day the player must recover on after HP reached 0. */
    val recoveryDay: Long?,
    /** Drives the Body rotation in TemplatePlanner. */
    val trainingDayIndex: Int,
    val createdAtMillis: Long,
    /** Dismantled gear, spent on upgrades (v2). */
    @ColumnInfo(defaultValue = "0") val essence: Int = 0,
    /** Per-install seed for loot rolls: the player id is always 0 (D13, v2). */
    @ColumnInfo(defaultValue = "0") val rollSeed: Long = 0,
    /** Week start (epoch day) of the last Gate board spawned (v2). */
    val lastBoardWeek: Long? = null,
    // ---- v3 (Phase 4) ----
    val classId: String? = null,
    val classChosenAt: Long? = null,
    /** Free respecs, one per rank-up. */
    @ColumnInfo(defaultValue = "0") val respecTokens: Int = 0,
    /** A rank-up offers the class choice again (A14) until it is made. */
    @ColumnInfo(defaultValue = "0") val classRechoice: Boolean = false,
    /** Extra skill points (debug panel only); earned points are computed from level and bosses. */
    @ColumnInfo(defaultValue = "0") val bonusSkillPoints: Int = 0,
    /** Streak Shield use this game week and month (A9, A12). */
    val shieldWeekKey: String? = null,
    @ColumnInfo(defaultValue = "0") val shieldsUsedWeek: Int = 0,
    val shieldMonthKey: String? = null,
    @ColumnInfo(defaultValue = "0") val shieldsUsedMonth: Int = 0,
    /** The last missed Daily, and whether the Shield covered it (for Unbroken). */
    val lastMissDay: Long? = null,
    @ColumnInfo(defaultValue = "0") val lastMissShielded: Boolean = false,
    /** Equilibrium's +10% XP day, after a completed rest-day Daily. */
    val equilibriumDay: Long? = null,
) {
    companion object {
        const val SINGLE_PLAYER_ID = 0
    }
}

@Entity(tableName = "attribute")
data class AttributeEntity(
    @PrimaryKey val stat: Stat,
    val value: Int,
    val mastery: Int,
)

/** Controller state per realm. The Body row also tracks deload weeks. */
@Entity(tableName = "realm_difficulty")
data class RealmDifficultyEntity(
    @PrimaryKey val realm: Realm,
    val multiplier: Double,
    val completionEma: Double,
    val lastWeekMultiplier: Double,
    val risingWeeks: Int,
    val deload: Boolean,
)

@Entity(tableName = "baseline")
data class BaselineEntity(
    @PrimaryKey val key: BaselineKey,
    val value: Double,
)

/** Per Body template: this week's cap and last week's target (the +10% rule). */
@Entity(tableName = "body_target")
data class BodyTargetEntity(
    @PrimaryKey val templateKey: String,
    val weekStartDay: Long,
    val weekCap: Double,
    val lastTarget: Double,
)

enum class QuestScope { DAILY, WEEKLY }

/** Phase 3 quest types. Side quests live 1–3 days; penalty and emergency quests one day. */
enum class QuestKind { NORMAL, SIDE, PENALTY, EMERGENCY }

enum class QuestSource { TEMPLATE, CLAUDE, MANUAL }

enum class QuestStatus { OPEN, DONE, EXPIRED, REMOVED }

@Entity(
    tableName = "quest",
    indices = [Index(value = ["publicId"], unique = true), Index("day"), Index("goalId"), Index("seriesId")],
)
data class QuestEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Short id shown to Claude, e.g. "q7f2a". */
    val publicId: String,
    val title: String,
    val realm: Realm,
    val stat: Stat,
    val tier: Tier,
    val target: Double,
    val unit: TargetUnit,
    /** The unit as written, e.g. "figures"; shown in the UI. */
    val unitLabel: String,
    val scope: QuestScope,
    /** Part of the Daily Quest (templates and Claude's daily quests); counts for the streak. */
    val isDaily: Boolean,
    val recurring: Boolean,
    val source: QuestSource,
    val templateKey: String?,
    val goalId: Long?,
    /** Epoch day: the game day for daily quests, the week's Sunday for weekly ones. */
    val day: Long,
    val status: QuestStatus,
    val completedAtMillis: Long? = null,
    val xpAwarded: Long = 0,
    val critical: Boolean = false,
    val masteryAwarded: Int = 0,
    /** Level just before completion: undo is only offered while the level is still this. */
    val levelAtCompletion: Int? = null,
    val createdAtMillis: Long,
    @ColumnInfo(defaultValue = "NORMAL") val kind: QuestKind = QuestKind.NORMAL,
    /** Side quests: the last game day they are open. */
    val expiresDay: Long? = null,
    /** Emergency quests: the end of the 2-hour window. */
    val expiresAtMillis: Long? = null,
    /** Side 0.75, emergency 1.5, otherwise 1. */
    @ColumnInfo(defaultValue = "1.0") val xpFactor: Double = 1.0,
    /** The game day it was completed. */
    val completedDay: Long? = null,
    /** An item this completion dropped; such completions can't be undone. */
    val lootItemId: Long? = null,
    /** HP this completion restored (penalty quests), taken back on undo. */
    @ColumnInfo(defaultValue = "0") val hpAwarded: Int = 0,
    /** Comma-separated tags (v3): social, paper, meditation, walk, … */
    @ColumnInfo(defaultValue = "") val tags: String = "",
    /**
     * The habit series (v4): "t:<templateKey>" for template dailies, "r:<first publicId>"
     * for a recurring chain. Null for one-off quests.
     */
    val seriesId: String? = null,
    /** The grind guard's m paid at completion (v4); [xpAwarded] is the XP actually paid. */
    @ColumnInfo(defaultValue = "1.0") val guardMultiplier: Double = 1.0,
    @ColumnInfo(defaultValue = "0.0") val actualProgress: Double = 0.0,
    @ColumnInfo(defaultValue = "'AUTO'") val measurementMode: String = "AUTO",
    @ColumnInfo(defaultValue = "0") val legacyProgress: Boolean = false,
)

enum class GoalHorizon { WEEK, LONG }

enum class GoalStatus { ACTIVE, DONE, CLOSED }

@Entity(tableName = "goal", indices = [Index(value = ["publicId"], unique = true)])
data class GoalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Short id shown to Claude, e.g. "g12". */
    val publicId: String,
    val horizon: GoalHorizon,
    val realm: Realm,
    val title: String,
    val target: Double?,
    val unitLabel: String?,
    /** Epoch day of the week's Sunday, for weekly goals. */
    val weekStart: Long?,
    val deadline: Long?,
    val parentId: Long?,
    val status: GoalStatus,
    val source: QuestSource,
    val createdAtMillis: Long,
    /** A chapter objective this goal feeds (v2). */
    val objectiveId: Long? = null,
)

/** The day's results, saved at each reset for history and the ASCENT STATUS. */
@Entity(tableName = "daily_snapshot")
data class DailySnapshotEntity(
    @PrimaryKey val day: Long,
    val level: Int,
    val totalXp: Long,
    val str: Int,
    val agi: Int,
    val int: Int,
    val foc: Int,
    val wis: Int,
    val vit: Int,
    val hp: Int,
    val mp: Int,
    val streakDays: Int,
    val objectives: Int,
    val completed: Int,
    val bodyDone: Int,
    val bodyTotal: Int,
    val mindDone: Int,
    val mindTotal: Int,
    val selfDone: Int,
    val selfTotal: Int,
    val dailyComplete: Boolean,
    val restDay: Boolean,
    /** A day between a backup and its restore (v4, D7): unknown, not missed. */
    @ColumnInfo(defaultValue = "0") val neutral: Boolean = false,
)
