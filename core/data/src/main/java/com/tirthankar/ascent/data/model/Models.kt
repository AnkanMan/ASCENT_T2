package com.tirthankar.ascent.data.model

import com.tirthankar.ascent.data.db.GoalEntity
import com.tirthankar.ascent.data.db.GoalHorizon
import com.tirthankar.ascent.data.db.GoalStatus
import com.tirthankar.ascent.data.db.QuestEntity
import com.tirthankar.ascent.data.db.QuestKind
import com.tirthankar.ascent.data.db.QuestScope
import com.tirthankar.ascent.data.db.QuestSource
import com.tirthankar.ascent.data.db.QuestStatus
import com.tirthankar.ascent.data.repo.guardOrigin
import com.tirthankar.ascent.data.repo.mode
import com.tirthankar.ascent.engine.MeasurementMode
import com.tirthankar.ascent.engine.QuestOrigin
import com.tirthankar.ascent.engine.Rank
import com.tirthankar.ascent.engine.Realm
import com.tirthankar.ascent.engine.Stat
import com.tirthankar.ascent.engine.StatBlock
import com.tirthankar.ascent.engine.TargetUnit
import com.tirthankar.ascent.engine.Tier

/** The stored player, as screens see it. */
data class PlayerProfile(
    val name: String,
    val title: String,
    val rank: Rank,
    val level: Int,
    val totalXp: Long,
    val hp: Int,
    val mp: Int,
    val freePoints: Int,
    val skillPoints: Int,
    val streakDays: Int,
    val bestStreak: Int,
    val stats: StatBlock,
    val mastery: Map<Stat, Int>,
    val difficulty: Map<Realm, Double>,
    /** Phase 4: the class id (content/classes.json), once chosen. */
    val classId: String? = null,
)

data class Quest(
    val id: Long,
    val publicId: String,
    val title: String,
    val realm: Realm,
    val stat: Stat,
    val tier: Tier,
    val target: Double,
    val unit: TargetUnit,
    val unitLabel: String,
    val scope: QuestScope,
    val isDaily: Boolean,
    val recurring: Boolean,
    val source: QuestSource,
    val goalId: Long?,
    val day: Long,
    val status: QuestStatus,
    val xpAwarded: Long,
    val critical: Boolean,
    /** Done, still the same day, and no level gained since: tapping again undoes it. */
    val canUndo: Boolean,
    val kind: QuestKind = QuestKind.NORMAL,
    /** Side quests: the last game day they're open. */
    val expiresDay: Long? = null,
    /** Emergency quests: when the window closes. */
    val expiresAtMillis: Long? = null,
    /** The habit series (Phase 5), or null for a one-off. */
    val seriesId: String? = null,
    /** The grind guard's multiplier paid at completion; 1 otherwise. */
    val guardMultiplier: Double = 1.0,
    /** Added by you (your own quests, bounties, Clarity's extras): the grind guard applies. */
    val selfAdded: Boolean = false,
    val actualProgress: Double = 0.0,
    val requiresResult: Boolean = false,
) {
    val isDone: Boolean get() = status == QuestStatus.DONE
}

data class Goal(
    val id: Long,
    val publicId: String,
    val horizon: GoalHorizon,
    val realm: Realm,
    val title: String,
    val target: Double?,
    val unitLabel: String?,
    /** From completed linked quests: the sum of their targets when units match, else their count. */
    val progress: Double,
    val status: GoalStatus,
    val weekStart: Long?,
    /** The chapter objective this goal feeds, if any. */
    val objectiveId: Long? = null,
)

internal fun QuestEntity.toModel(canUndo: Boolean) = Quest(
    id = id,
    publicId = publicId,
    title = title,
    realm = realm,
    stat = stat,
    tier = tier,
    target = target,
    unit = unit,
    unitLabel = unitLabel,
    scope = scope,
    isDaily = isDaily,
    recurring = recurring,
    source = source,
    goalId = goalId,
    day = day,
    status = status,
    xpAwarded = xpAwarded,
    critical = critical,
    canUndo = canUndo,
    kind = kind,
    expiresDay = expiresDay,
    expiresAtMillis = expiresAtMillis,
    seriesId = seriesId,
    guardMultiplier = guardMultiplier,
    selfAdded = guardOrigin() == QuestOrigin.SELF_ADDED,
    actualProgress = actualProgress,
    requiresResult = kind == QuestKind.NORMAL && mode() != MeasurementMode.CHECKBOX,
)

internal fun GoalEntity.toModel(progress: Double) = Goal(
    id = id,
    publicId = publicId,
    horizon = horizon,
    realm = realm,
    title = title,
    target = target,
    unitLabel = unitLabel,
    progress = progress,
    status = status,
    weekStart = weekStart,
    objectiveId = objectiveId,
)
