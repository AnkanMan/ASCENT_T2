package com.tirthankar.ascent.feature.gates

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tirthankar.ascent.data.GameDay
import com.tirthankar.ascent.data.db.GateStatus
import com.tirthankar.ascent.data.model.Boss
import com.tirthankar.ascent.data.model.Gate
import com.tirthankar.ascent.data.repo.BossException
import com.tirthankar.ascent.data.repo.BossRefusal
import com.tirthankar.ascent.data.repo.BossRepository
import com.tirthankar.ascent.data.repo.GateRepository
import com.tirthankar.ascent.data.repo.NewBoss
import com.tirthankar.ascent.data.repo.NewBossPhase
import com.tirthankar.ascent.data.repo.RankTrialRepository
import com.tirthankar.ascent.engine.GateRules
import com.tirthankar.ascent.engine.Rank
import com.tirthankar.ascent.engine.Realm
import com.tirthankar.ascent.engine.SafetyRules
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class GateObjectiveUi(val id: Long, val title: String, val progress: Double, val target: Double, val unitLabel: String, val auto: Boolean)

data class GateUi(
    val id: Long,
    val publicId: String,
    val title: String,
    val lore: String,
    val realm: Realm,
    val rank: Rank,
    val days: Int,
    val isRed: Boolean,
    val status: GateStatus,
    val daysLeft: Int?,
    val percent: Int,
    val xp: Long,
    val objectives: List<GateObjectiveUi>,
)

data class BossSummaryUi(
    val id: Long,
    val publicId: String,
    val title: String,
    val hp: Int,
    val maxHp: Int,
    val phase: String?,
    val isTrial: Boolean,
    val isRaid: Boolean,
    val record: Double?,
    val trialOpen: Boolean,
    val daysLeft: Long?,
    val enraged: Boolean,
)

data class BossForm(
    val title: String = "",
    /** ISO date, optional. */
    val deadline: String = "",
    val firstPhase: String = "",
    val raid: Boolean = false,
    val refusal: BossRefusal? = null,
) {
    val deadlineDate: LocalDate? get() = deadline.trim().takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val valid: Boolean get() = title.isNotBlank() && (deadline.isBlank() || deadlineDate != null) && !SafetyRules.isForbidden(title)
}

data class GatesUiState(
    val offers: List<GateUi> = emptyList(),
    val open: List<GateUi> = emptyList(),
    val recent: List<GateUi> = emptyList(),
    val bosses: List<BossSummaryUi> = emptyList(),
    val bossForm: BossForm? = null,
    /** The open rank trial (Phase 4): outside the two-open limit, never Claude's to edit. */
    val trial: GateUi? = null,
) {
    val canAccept: Boolean get() = open.size < GateRules.MAX_OPEN
}

sealed interface GatesEvent {
    data class Accept(val gateId: Long) : GatesEvent
    data class Decline(val gateId: Long) : GatesEvent
    data class Log(val objectiveId: Long, val amount: Double) : GatesEvent
    data class OpenBoss(val bossId: Long) : GatesEvent
    data object OpenBossForm : GatesEvent
    data class EditBossForm(val form: BossForm) : GatesEvent
    data object SaveBoss : GatesEvent
    data object CloseBossForm : GatesEvent
    data class StartTrial(val templateKey: String) : GatesEvent
    data class LogTrial(val objectiveId: Long, val amount: Double) : GatesEvent
}

@HiltViewModel
class GatesViewModel @Inject constructor(
    private val gates: GateRepository,
    private val bosses: BossRepository,
    private val trials: RankTrialRepository,
    gameDay: GameDay,
) : ViewModel() {

    private val form = MutableStateFlow<BossForm?>(null)

    val uiState: StateFlow<GatesUiState> = combine(
        combine(gates.current, gates.trial) { current, trial -> current to trial },
        gates.recent,
        bosses.active,
        gameDay.observeToday(),
        form,
    ) { (current, trial), recent, bossList, today, f ->
        val t = today.toEpochDay()
        GatesUiState(
            offers = current.filter { it.status == GateStatus.OFFERED }.map { it.toUi(t) },
            open = current.filter { it.status == GateStatus.OPEN }.map { it.toUi(t) },
            recent = recent.map { it.toUi(t) },
            bosses = bossList.map { it.toSummary(t) },
            bossForm = f,
            trial = trial?.toUi(t),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GatesUiState())

    fun onEvent(event: GatesEvent) {
        when (event) {
            is GatesEvent.Accept -> viewModelScope.launch { gates.accept(event.gateId) }
            is GatesEvent.Decline -> viewModelScope.launch { gates.decline(event.gateId) }
            is GatesEvent.Log -> viewModelScope.launch { gates.log(event.objectiveId, event.amount) }
            is GatesEvent.OpenBoss -> Unit // handled by the route
            GatesEvent.OpenBossForm -> form.value = BossForm()
            is GatesEvent.EditBossForm -> form.value = event.form.copy(refusal = null)
            GatesEvent.CloseBossForm -> form.value = null
            GatesEvent.SaveBoss -> saveBoss()
            is GatesEvent.StartTrial -> viewModelScope.launch { bosses.createTrial(event.templateKey) }
            is GatesEvent.LogTrial -> viewModelScope.launch {
                gates.log(event.objectiveId, event.amount)
                trials.closeCleared() // all six done passes the trial at once
            }
        }
    }

    private fun saveBoss() = viewModelScope.launch {
        val f = form.value ?: return@launch
        if (!f.valid) return@launch
        val result = bosses.create(
            NewBoss(
                title = f.title,
                phases = listOf(NewBossPhase(f.firstPhase.ifBlank { DEFAULT_PHASE }, emptyList())),
                deadlineDay = f.deadlineDate?.toEpochDay(),
                isRaid = f.raid,
            ),
        )
        form.update { if (result.isSuccess) null else it?.copy(refusal = (result.exceptionOrNull() as? BossException)?.reason) }
    }

    private companion object {
        const val DEFAULT_PHASE = "I"
    }
}

internal fun Gate.toUi(today: Long) = GateUi(
    id = id,
    publicId = publicId,
    title = title,
    lore = lore,
    realm = realm,
    rank = rank,
    days = days,
    isRed = isRed,
    status = status,
    daysLeft = closesDay?.let { (it - today + 1).toInt().coerceAtLeast(0) },
    percent = (fraction * 100).toInt().coerceIn(0, 100),
    xp = xpPreview,
    objectives = objectives.map { GateObjectiveUi(it.id, it.title, it.progress, it.target, it.unitLabel, it.auto) },
)

internal fun Boss.toSummary(today: Long) = BossSummaryUi(
    id = id,
    publicId = publicId,
    title = title,
    hp = hp,
    maxHp = maxHp,
    phase = phases.getOrNull(currentPhase),
    isTrial = isTrial,
    isRaid = isRaid,
    record = recordToBeat,
    trialOpen = trialOpen,
    daysLeft = deadlineDay?.let { it - today },
    enraged = enraged,
)
