package com.tirthankar.ascent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tirthankar.ascent.data.DroppedItem
import com.tirthankar.ascent.data.GameEvent
import com.tirthankar.ascent.data.GameEvents
import com.tirthankar.ascent.data.NoticeKind
import com.tirthankar.ascent.data.model.AvatarLook
import com.tirthankar.ascent.data.repo.AvatarRepository
import com.tirthankar.ascent.data.repo.PlayerRepository
import com.tirthankar.ascent.data.settings.AscentSettings
import com.tirthankar.ascent.data.settings.SettingsRepository
import com.tirthankar.ascent.designsystem.Sfx
import com.tirthankar.ascent.designsystem.SystemSounds
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * App-level state: does a player exist, and which overlays wait to be shown (level-up,
 * chest, System notices). Every game event also plays its sound and haptic (Task 36).
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    players: PlayerRepository,
    events: GameEvents,
    private val settings: SettingsRepository,
    private val sounds: SystemSounds,
    avatarRepository: AvatarRepository,
) : ViewModel() {

    /** Null while loading; false until the Awakening creates the player. */
    val hasPlayer: StateFlow<Boolean?> =
        players.hasPlayer.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _levelUp = MutableStateFlow<GameEvent.LevelUp?>(null)
    val levelUp: StateFlow<GameEvent.LevelUp?> = _levelUp.asStateFlow()

    private val _loot = MutableStateFlow<List<DroppedItem>>(emptyList())
    val loot: StateFlow<List<DroppedItem>> = _loot.asStateFlow()

    private val _notices = MutableStateFlow<List<GameEvent.Notice>>(emptyList())
    val notices: StateFlow<List<GameEvent.Notice>> = _notices.asStateFlow()

    /** The power just cast, for the sigil ring (Phase 4). */
    private val _cast = MutableStateFlow<GameEvent.PowerCast?>(null)
    val cast: StateFlow<GameEvent.PowerCast?> = _cast.asStateFlow()

    /** A rank-up waiting for its sequence (Phase 4). */
    private val _rankUp = MutableStateFlow<GameEvent.Notice?>(null)
    val rankUp: StateFlow<GameEvent.Notice?> = _rankUp.asStateFlow()

    /** New Record lines for the toast, oldest first (Phase 4). */
    private val _records = MutableStateFlow<List<GameEvent.Notice>>(emptyList())
    val records: StateFlow<List<GameEvent.Notice>> = _records.asStateFlow()

    /** The layered avatar, for the rank-up sequence. */
    val avatar: StateFlow<AvatarLook?> = avatarRepository.look.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val notificationPermissionAsked: StateFlow<Boolean?> =
        settings.notificationPermissionAsked.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val current: StateFlow<AscentSettings> =
        settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AscentSettings())

    init {
        events.events.filterIsInstance<GameEvent.LevelUp>()
            .onEach { e ->
                // Two level-ups before the first is dismissed merge into one sequence.
                _levelUp.value = _levelUp.value?.let { GameEvent.LevelUp(it.fromLevel, e.toLevel, it.freePoints + e.freePoints) } ?: e
            }
            .launchIn(viewModelScope)
        events.events
            .onEach { e ->
                when (e) {
                    is GameEvent.LootDropped -> _loot.update { it + e.items }
                    is GameEvent.Notice -> when (e.kind) {
                        NoticeKind.LORE -> _records.update { (it + e).takeLast(MAX_QUEUED) }
                        NoticeKind.RANK_UP -> _rankUp.value = e
                        else -> _notices.update { (it + e).takeLast(MAX_QUEUED) }
                    }
                    is GameEvent.PowerCast -> _cast.value = e
                    else -> Unit
                }
                sfxFor(e)?.let { sounds.play(it, current.value.soundOn, current.value.hapticsOn) }
            }
            .launchIn(viewModelScope)
    }

    fun dismissLevelUp() {
        _levelUp.value = null
    }

    fun castShown() {
        _cast.value = null
    }

    fun dismissRankUp() {
        _rankUp.value = null
    }

    fun recordShown() {
        _records.update { it.drop(1) }
    }

    fun dismissLoot() {
        _loot.value = emptyList()
    }

    fun dismissNotice() {
        _notices.update { it.drop(1) }
    }

    fun onPermissionAsked() {
        viewModelScope.launch { settings.setNotificationPermissionAsked() }
    }

    private fun sfxFor(e: GameEvent): Sfx? = when (e) {
        is GameEvent.QuestCompleted -> Sfx.QUEST_COMPLETE
        is GameEvent.LevelUp -> Sfx.LEVEL_UP
        is GameEvent.LootDropped -> Sfx.CHEST_OPEN
        is GameEvent.BossHit -> Sfx.BOSS_HIT
        is GameEvent.PowerCast -> Sfx.CAST
        is GameEvent.DailyIssued -> null // announced by its notification's chime
        is GameEvent.Notice -> when (e.kind) {
            NoticeKind.PENALTY_ISSUED, NoticeKind.GATE_BROKEN, NoticeKind.GATE_FAILED,
            NoticeKind.BOSS_STIRS, NoticeKind.BOSS_ENRAGED, NoticeKind.TRIAL_FAILED, NoticeKind.RANK_TRIAL_FAILED -> Sfx.PENALTY
            NoticeKind.EMERGENCY_ISSUED -> Sfx.SYSTEM_CHIME
            NoticeKind.SKILL_UNLOCKED -> Sfx.SKILL_UNLOCK
            NoticeKind.CLASS_AWAKENED -> Sfx.CLASS
            NoticeKind.RANK_UP -> Sfx.RANK_UP
            NoticeKind.LORE -> null // follows another notice; one sound is enough
            else -> Sfx.UNLOCK
        }
    }

    private companion object {
        const val MAX_QUEUED = 8
    }
}
