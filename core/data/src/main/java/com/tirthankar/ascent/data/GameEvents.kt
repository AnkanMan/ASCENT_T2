package com.tirthankar.ascent.data

import com.tirthankar.ascent.engine.ItemSlot
import com.tirthankar.ascent.engine.Rarity
import com.tirthankar.ascent.engine.Stat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/** One-off moments the UI animates and sounds: a quest's XP, a level-up, a chest. */
sealed interface GameEvent {
    data class QuestCompleted(
        val questId: Long,
        val xp: Long,
        val critical: Boolean,
        val stat: Stat,
        val statGain: Int,
    ) : GameEvent

    data class LevelUp(val fromLevel: Int, val toLevel: Int, val freePoints: Int) : GameEvent

    data class DailyIssued(val day: Long, val objectives: Int) : GameEvent

    /** Items gained at once, revealed by the chest animation. */
    data class LootDropped(val items: List<DroppedItem>) : GameEvent

    /** A power was cast: the sigil ring and its sound play (Phase 4). */
    data class PowerCast(val skillId: String, val name: String) : GameEvent

    /** A boss task landed: damage on the HP bar, XP to the player. */
    data class BossHit(val bossId: Long, val damage: Int, val xp: Long, val critical: Boolean) : GameEvent

    /**
     * A System notice: [kind] picks the header (from strings.xml in the UI), [subject]
     * and [detail] are names or lore text from content.
     */
    data class Notice(val kind: NoticeKind, val subject: String, val detail: String? = null) : GameEvent
}

data class DroppedItem(val name: String, val rarity: Rarity, val slot: ItemSlot)

enum class NoticeKind {
    ACHIEVEMENT,
    HIDDEN_ACHIEVEMENT,
    TITLE_EARNED,
    LORE,
    CHAPTER_COMPLETE,
    ARC_COMPLETE,
    GATE_CLEARED,
    GATE_PARTIAL,
    GATE_BROKEN,
    GATE_FAILED,
    BOSS_PHASE,
    BOSS_DEFEATED,
    BOSS_STIRS,
    BOSS_ENRAGED,
    TRIAL_WON,
    TRIAL_FAILED,
    PENALTY_ISSUED,
    EMERGENCY_ISSUED,

    // Phase 4
    SKILL_UNLOCKED,
    CLASS_AWAKENED,
    RANK_TRIAL_OPEN,
    RANK_UP,
    RANK_TRIAL_FAILED,
    STREAK_SHIELDED,
}

@Singleton
class GameEvents @Inject constructor() {
    private val _events = MutableSharedFlow<GameEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<GameEvent> = _events.asSharedFlow()

    fun emit(event: GameEvent) {
        _events.tryEmit(event)
    }
}
