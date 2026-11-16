package com.tirthankar.ascent.feature.status

import com.tirthankar.ascent.designsystem.AvatarSlot
import com.tirthankar.ascent.designsystem.AvatarSpec
import com.tirthankar.ascent.engine.Rank
import com.tirthankar.ascent.engine.Stat

/** Everything the Status Window shows. Built by [StatusUiStateMapper]; no math in the UI. */
/** [daysAgo] since the last backup, or null when there has never been one. */
data class BackupWarning(val daysAgo: Int?)

data class StatusUiState(
    val name: String,
    val title: String,
    val rank: Rank,
    val level: Int,
    val xpIntoLevel: Long,
    val xpForNext: Long,
    val hp: Int,
    val maxHp: Int,
    val mp: Int,
    val maxMp: Int,
    val stats: List<StatLine>,
    val streakDays: Int,
    /** Free points not yet placed (after the pending ones). */
    val freePoints: Int = 0,
    /** Equipped gear on the avatar: slot → rarity (0 = Common … 4 = Mythic). */
    val gear: Map<AvatarSlot, Int> = emptyMap(),
    // ---- Phase 4 ----
    /** The layered avatar (section 5); null shows the Phase 3 figure. */
    val avatar: AvatarSpec? = null,
    /** The class name at this rank and its two stats (empty for Polymath). */
    val className: String? = null,
    val classStats: List<Stat> = emptyList(),
    /** Untargeted powers ready to cast now. */
    val powersReady: List<PowerChip> = emptyList(),
    val trial: TrialCard? = null,
    /** Record entries not yet opened (the unread dot). */
    val unreadRecords: Int = 0,
    /** Phase 5: shown when the last backup is over 7 days old, or there is none. */
    val backupWarning: BackupWarning? = null,
) {
    val mythicAura: Boolean get() = gear.values.any { it >= 4 }
    val pendingTotal: Int get() = stats.sumOf { it.pending }
    val canAddPoint: Boolean get() = freePoints > 0
}

/** A power on the "Powers ready" row. [oneTap] casts at once; the others open the Skills tab. */
data class PowerChip(val id: String, val name: String, val oneTap: Boolean)

/** The open rank trial's card. */
data class TrialCard(
    val rank: Rank,
    val daysLeft: Int,
    val done: Int,
    val total: Int,
    val objectives: List<TrialObjectiveLine>,
)

data class TrialObjectiveLine(val title: String, val progress: Double, val target: Double, val unit: String)

/** One attribute row. [pending] points are shown as "+n" until confirmed. */
data class StatLine(val stat: Stat, val value: Int, val pending: Int = 0)

/** Taps on the Status Window. */
sealed interface StatusEvent {
    data class AddPoint(val stat: Stat) : StatusEvent
    data object ConfirmPoints : StatusEvent
    data object ResetPoints : StatusEvent
    data object OpenInventory : StatusEvent
    data object OpenCodex : StatusEvent
    data object OpenSettings : StatusEvent
    data object OpenSkills : StatusEvent
    data class CastPower(val id: String) : StatusEvent
}
