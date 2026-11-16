package com.tirthankar.ascent.feature.status

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tirthankar.ascent.data.GameDay
import com.tirthankar.ascent.data.TimeSource
import com.tirthankar.ascent.data.settings.BackupPrefs
import java.time.Duration
import java.time.Instant
import com.tirthankar.ascent.data.model.AvatarLook
import com.tirthankar.ascent.data.model.Gate
import com.tirthankar.ascent.data.repo.AvatarRepository
import com.tirthankar.ascent.data.repo.ClassRepository
import com.tirthankar.ascent.data.repo.GateRepository
import com.tirthankar.ascent.data.repo.InventoryRepository
import com.tirthankar.ascent.data.repo.LoreRepository
import com.tirthankar.ascent.data.repo.PlayerRepository
import com.tirthankar.ascent.data.repo.PowerEffects
import com.tirthankar.ascent.data.repo.PowerRepository
import com.tirthankar.ascent.data.repo.SkillRepository
import com.tirthankar.ascent.designsystem.AvatarSlot
import com.tirthankar.ascent.designsystem.AvatarSpec
import com.tirthankar.ascent.designsystem.Materialize
import com.tirthankar.ascent.engine.CastCheck
import com.tirthankar.ascent.engine.ItemSlot
import com.tirthankar.ascent.engine.PowerHandlers
import com.tirthankar.ascent.engine.PowerRules
import com.tirthankar.ascent.engine.SkillKind
import com.tirthankar.ascent.engine.Stat
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The Phase 4 parts of the Status Window, gathered before they meet the profile. */
private data class Progression(
    val avatar: AvatarLook?,
    val powers: List<PowerChip>,
    val trial: Gate?,
    val unread: Int,
    val today: Long,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StatusViewModel @Inject constructor(
    private val players: PlayerRepository,
    private val effects: PowerEffects,
    inventory: InventoryRepository,
    avatar: AvatarRepository,
    skills: SkillRepository,
    powers: PowerRepository,
    gates: GateRepository,
    lore: LoreRepository,
    classes: ClassRepository,
    gameDay: GameDay,
    backups: BackupPrefs,
    time: TimeSource,
) : ViewModel() {

    private val pending = MutableStateFlow<Map<Stat, Int>>(emptyMap())

    /** Powers ready now and castable without a target (PHASE4.md section 9). */
    private val ready = combine(skills.overview, powers.activeCasts) { o, _ -> o }.mapLatest { o ->
        o ?: return@mapLatest emptyList()
        (o.skills + listOfNotNull(o.classSkill))
            .filter { it.unlocked && it.def.kind == SkillKind.ACTIVE && !it.def.locked && it.def.handler !in PowerRules.AUTOMATIC }
            .filter { powers.check(it.def.id) == CastCheck.Ok }
            .map { PowerChip(it.def.id, it.def.name, oneTap = it.def.handler in ONE_TAP) }
    }

    private val progression = combine(avatar.look, ready, gates.trial, lore.unreadCount, gameDay.observeToday()) { look, chips, trial, unread, today ->
        Progression(look, chips, trial, unread, today.toEpochDay())
    }

    /** Phase 5: a warning when the last backup is more than 7 days old, or there is none. */
    private val backupWarning = combine(backups.state, gameDay.observeToday()) { s, _ ->
        val last = s.lastBackupAt ?: return@combine BackupWarning(daysAgo = null)
        val days = Duration.between(Instant.ofEpochMilli(last), time.now()).toDays().toInt()
        BackupWarning(days).takeIf { days > STALE_DAYS }
    }

    /** Null until the stored player loads (or when there is none yet). */
    val uiState: StateFlow<StatusUiState?> =
        combine(players.profile, pending, inventory.equipped, progression, backupWarning) { profile, points, equipped, p, backup ->
            profile?.let {
                val cls = classes.definition(it.classId)
                StatusUiStateMapper.map(it.toSnapshot(), points).copy(
                    gear = equipped.associate { item -> item.slot.toAvatar() to item.rarity.ordinal },
                    avatar = p.avatar?.toSpec(),
                    className = p.avatar?.className,
                    classStats = cls?.stats.orEmpty(),
                    powersReady = p.powers,
                    trial = p.trial?.toCard(p.today),
                    unreadRecords = p.unread,
                    backupWarning = backup,
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun onEvent(event: StatusEvent) {
        when (event) {
            is StatusEvent.AddPoint -> {
                if (uiState.value?.canAddPoint == true) {
                    pending.update { it + (event.stat to (it[event.stat] ?: 0) + 1) }
                }
            }
            StatusEvent.ResetPoints -> pending.value = emptyMap()
            StatusEvent.ConfirmPoints -> viewModelScope.launch {
                if (players.allocatePoints(pending.value)) pending.value = emptyMap()
            }
            is StatusEvent.CastPower -> viewModelScope.launch { effects.cast(event.id) }
            // Navigation is handled by the route.
            StatusEvent.OpenInventory, StatusEvent.OpenCodex, StatusEvent.OpenSettings, StatusEvent.OpenSkills -> Unit
        }
    }

    private companion object {
        /** No target, no text, no share: these cast straight from the Status row. */
        val ONE_TAP = setOf(PowerHandlers.OVERLOAD, PowerHandlers.UNBROKEN)

        /** PHASE5.md section 3: warn when the last backup is more than this many days old. */
        const val STALE_DAYS = 7
    }
}

internal fun AvatarLook.toSpec() = AvatarSpec(aura.name, particles, rings, cloak, sigil, stage, gear)

private fun Gate.toCard(today: Long) = TrialCard(
    rank = rank,
    daysLeft = ((closesDay ?: today) - today + 1).toInt().coerceAtLeast(0),
    done = objectivesDone,
    total = objectives.size,
    objectives = objectives.map { TrialObjectiveLine(it.title, it.progress, it.target, it.unitLabel) },
)

internal fun ItemSlot.toAvatar(): AvatarSlot = when (this) {
    ItemSlot.WEAPON -> AvatarSlot.WEAPON
    ItemSlot.HEAD -> AvatarSlot.HEAD
    ItemSlot.BODY -> AvatarSlot.BODY
    ItemSlot.HANDS -> AvatarSlot.HANDS
    ItemSlot.FEET -> AvatarSlot.FEET
    ItemSlot.ACCESSORY -> AvatarSlot.ACCESSORY
}

/** Entry point for navigation: collects state and materializes the window on open. */
@Composable
fun StatusRoute(
    onOpenInventory: () -> Unit,
    onOpenCodex: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenSkills: () -> Unit = {},
    viewModel: StatusViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val current = state ?: return
    Materialize(modifier) {
        StatusScreen(current) { event ->
            when (event) {
                StatusEvent.OpenInventory -> onOpenInventory()
                StatusEvent.OpenCodex -> onOpenCodex()
                StatusEvent.OpenSettings -> onOpenSettings()
                StatusEvent.OpenSkills -> onOpenSkills()
                else -> viewModel.onEvent(event)
            }
        }
    }
}
