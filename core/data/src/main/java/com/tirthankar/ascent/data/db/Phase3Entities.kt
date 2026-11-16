package com.tirthankar.ascent.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.tirthankar.ascent.engine.EquipSlot
import com.tirthankar.ascent.engine.FinaleKind
import com.tirthankar.ascent.engine.ItemSlot
import com.tirthankar.ascent.engine.LoreCategory
import com.tirthankar.ascent.engine.Rank
import com.tirthankar.ascent.engine.Rarity
import com.tirthankar.ascent.engine.Realm

// ---- Arcs (SPEC "Main quests: arcs") ----

enum class ArcStatus { ACTIVE, BACKLOG, DONE, ABANDONED }

enum class ChapterStatus { LOCKED, ACTIVE, DONE }

@Entity(tableName = "arc", indices = [Index(value = ["publicId"], unique = true)])
data class ArcEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Short id shown to Claude, e.g. "a2". */
    val publicId: String,
    val realm: Realm,
    val title: String,
    val goalId: Long?,
    val status: ArcStatus,
    /** The content template it came from, e.g. "iron_discipline". */
    val templateId: String?,
    val finale: FinaleKind,
    /** The finale boss's name, or the title granted at the end. */
    val finaleName: String?,
    val finaleDeadline: Long?,
    val trialTemplate: String?,
    val source: QuestSource,
    val completedDay: Long?,
    val createdAtMillis: Long,
)

@Entity(tableName = "chapter", indices = [Index("arcId")])
data class ChapterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val arcId: Long,
    val position: Int,
    val title: String,
    val status: ChapterStatus,
    val completedDay: Long?,
    val xpAwarded: Long,
)

@Entity(tableName = "chapter_objective", indices = [Index("chapterId")])
data class ChapterObjectiveEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chapterId: Long,
    val position: Int,
    val title: String,
    val target: Double,
    val unitLabel: String,
    /** Progress logged by hand. Linked weekly goals add to it. */
    val logged: Double,
)

// ---- Gates (SPEC "Gate board") ----

enum class GateStatus {
    /** On this week's board, not accepted. */
    OFFERED,
    OPEN,
    CLEARED,
    PARTIAL,
    BROKEN,

    /** A Red Gate below 100%. */
    FAILED,
    DECLINED,

    /** An offer the next board replaced. */
    LAPSED,
}

enum class GateSource { BOARD, CLAUDE }

/** v3: what kind of Gate. Rank trials sit outside the two-open limit. */
enum class GateKind { NORMAL, RED, RANK_TRIAL }

@Entity(tableName = "gate", indices = [Index(value = ["publicId"], unique = true)])
data class GateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Short id shown to Claude, e.g. "gt3". */
    val publicId: String,
    val templateId: String?,
    val title: String,
    val lore: String,
    val realm: Realm,
    val rank: Rank,
    val days: Int,
    val isRed: Boolean,
    val status: GateStatus,
    val source: GateSource,
    /** The board's week (epoch day of its Sunday). */
    val boardWeek: Long?,
    /** First and last game day it is open (inclusive), once accepted. */
    val opensDay: Long?,
    val closesDay: Long?,
    val fraction: Double?,
    val xpAwarded: Long,
    val createdAtMillis: Long,
    // ---- v3 (Phase 4) ----
    @ColumnInfo(defaultValue = "NORMAL") val kind: GateKind = GateKind.NORMAL,
    /** Rank trials: the rank they earn. */
    val targetRank: Rank? = null,
    @ColumnInfo(defaultValue = "0") val attempt: Int = 0,
    /** A failed rank trial can be retried from this game day. */
    val retryAt: Long? = null,
)

@Entity(tableName = "gate_objective", indices = [Index("gateId")])
data class GateObjectiveEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val gateId: Long,
    val position: Int,
    val title: String,
    val target: Double,
    val unitLabel: String,
    /** Comma-separated template keys whose completed quests count toward it. */
    val templateKeys: String,
    /** Counts completions (+1 each) rather than summing quest targets. */
    val isCount: Boolean,
    val logged: Double,
    /** Progress frozen when the Gate closed. */
    val finalProgress: Double?,
)

// ---- Bosses (SPEC "Bosses") ----

enum class BossStatus { ACTIVE, DEFEATED, WON, ABANDONED }

@Entity(tableName = "boss", indices = [Index(value = ["publicId"], unique = true)])
data class BossEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Short id shown to Claude, e.g. "b1". */
    val publicId: String,
    val title: String,
    val arcId: Long?,
    val deadlineDay: Long?,
    val isTrial: Boolean,
    val isRaid: Boolean,
    /** For a Trial: the template tested, its record and the last attempt. */
    val trialTemplate: String?,
    val recordToBeat: Double?,
    val lastAttemptDay: Long?,
    val lastResult: Double?,
    val status: BossStatus,
    val source: QuestSource,
    val createdDay: Long,
    val lastActivityDay: Long,
    val lastStirDay: Long?,
    val enrageNoticed: Boolean,
    val defeatedDay: Long?,
    val xpAwarded: Long,
    val paperSubmitted: Boolean,
    val createdAtMillis: Long,
)

@Entity(tableName = "boss_phase", indices = [Index("bossId")])
data class BossPhaseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bossId: Long,
    /** 0-based. */
    val position: Int,
    val name: String,
)

@Entity(tableName = "boss_task", indices = [Index("bossId"), Index(value = ["publicId"], unique = true)])
data class BossTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Short id shown to Claude, e.g. "t4". */
    val publicId: String,
    val bossId: Long,
    /** 0-based phase position. */
    val phase: Int,
    val title: String,
    val hours: Double,
    val done: Boolean,
    val doneDay: Long?,
    val damage: Int,
    val xpAwarded: Long,
    val critical: Boolean,
    /** Level before this hit, so undo is only offered while it holds. */
    val levelAtCompletion: Int?,
)

// ---- Gear (SPEC "Loot and equipment") ----

@Entity(tableName = "inventory_item")
data class InventoryItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The hand-written item it is, or null for a generated one. */
    val templateId: String?,
    val name: String,
    val slot: ItemSlot,
    val rarity: Rarity,
    /** JSON list of affixes (base values; upgrades scale them). */
    val affixesJson: String,
    /** JSON unique effect, for hand-written items. */
    val uniqueJson: String?,
    val setId: String?,
    val upgradeLevel: Int,
    val equippedSlot: EquipSlot?,
    /** Where it came from, e.g. "gate:gt3" or "achievement:ergodic". */
    val source: String,
    val seed: Long,
    val acquiredAtMillis: Long,
)

// ---- Achievements, titles, lore ----

@Entity(tableName = "player_achievement")
data class PlayerAchievementEntity(
    @PrimaryKey val achievementId: String,
    val tier: Int,
    val earnedAtMillis: Long,
)

@Entity(tableName = "earned_title")
data class EarnedTitleEntity(
    @PrimaryKey val title: String,
    val source: String,
    val earnedAtMillis: Long,
)

@Entity(tableName = "lore_unlock")
data class LoreUnlockEntity(
    /** The seed lore id, or "custom:<id>" for a line Claude wrote. */
    @PrimaryKey val id: String,
    val title: String,
    val text: String,
    /** What unlocked it, e.g. "boss b1". */
    val context: String?,
    val unlockedAtMillis: Long,
    /** v3: the Record filter, and when it was opened (null = unread). */
    val category: LoreCategory? = null,
    val readAt: Long? = null,
)

// ---- Phase 4: skills and powers ----

/**
 * A skill you hold (or held) and its use in the current period. A respec clears
 * [unlocked] but keeps the row, so cooldowns and period uses are not reset.
 * Class skills have rows too, for their limits.
 */
@Entity(tableName = "player_skill")
data class PlayerSkillEntity(
    @PrimaryKey val skillId: String,
    val unlockedAt: Long,
    val periodKey: String?,
    val usesInPeriod: Int,
    val lastCastAt: Long?,
    @ColumnInfo(defaultValue = "1") val unlocked: Boolean = true,
)

enum class CastStatus { ACTIVE, COMPLETED, EXPIRED, REFUNDED }

enum class CastTargetKind { NONE, QUEST, BOSS_TASK }

/** One cast, so effects survive restarts and the reset can expire them. */
@Entity(tableName = "power_cast", indices = [Index("status")])
data class PowerCastEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val skillId: String,
    val castAt: Long,
    /** The game day it was cast. */
    val day: Long,
    val targetKind: CastTargetKind,
    val targetId: Long?,
    val mpSpent: Int,
    val status: CastStatus,
    val expiresAt: Long?,
    /**
     * Handler data, e.g. Limit Break's previous target, Second Wind's original quest, or
     * Clarity's three journal lines. It stays on the phone: STATUS never reads it.
     */
    val note: String?,
)

enum class LoreTarget { ARC, BOSS, GATE }

/** A line Claude wrote for one of your arcs, bosses or Gates (bridge v2 `add_lore`). */
@Entity(tableName = "custom_lore", indices = [Index(value = ["targetKind", "targetId", "event"], unique = true)])
data class CustomLoreEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val targetKind: LoreTarget,
    val targetId: Long,
    /** chapter_done, arc_end, phase_2 … phase_5, defeat or break. */
    val event: String,
    val title: String,
    val text: String,
    val unlocked: Boolean,
    val createdAtMillis: Long,
)
