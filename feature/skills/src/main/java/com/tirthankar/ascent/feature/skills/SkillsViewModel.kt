package com.tirthankar.ascent.feature.skills

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tirthankar.ascent.bridge.PowerRequests
import com.tirthankar.ascent.bridge.ProposalService
import com.tirthankar.ascent.data.repo.CastResult
import com.tirthankar.ascent.data.repo.PowerEffects
import com.tirthankar.ascent.data.repo.PowerRepository
import com.tirthankar.ascent.data.repo.RespecCost
import com.tirthankar.ascent.data.repo.SkillOverview
import com.tirthankar.ascent.data.repo.SkillRepository
import com.tirthankar.ascent.data.repo.SkillStatus
import com.tirthankar.ascent.engine.CastCheck
import com.tirthankar.ascent.engine.PowerHandlers
import com.tirthankar.ascent.engine.PowerRules
import com.tirthankar.ascent.engine.SkillKind
import com.tirthankar.ascent.engine.SkillTree
import com.tirthankar.ascent.engine.Stat
import com.tirthankar.ascent.engine.UnlockCheck
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

/** One Skills screen: the trees, points, respec, and the sheet for the selected node. */
data class SkillsUiState(
    val loaded: Boolean = false,
    val skills: List<SkillStatus> = emptyList(),
    val classSkill: SkillStatus? = null,
    val points: Int = 0,
    val respec: RespecCost = RespecCost.NothingToReturn,
    val tierGates: Map<Int, Int> = emptyMap(),
    val treeStats: Map<SkillTree, List<Stat>> = emptyMap(),
    /** The cast check for each unlocked active, untargeted. */
    val casts: Map<String, CastCheck> = emptyMap(),
    val selectedId: String? = null,
    val message: SkillMessage? = null,
    /** A text-input power waiting for its text (Clarity, Insight). */
    val input: PowerInput? = null,
    /** An ASCENT STATUS with a request, waiting for the share sheet (A7, A8). */
    val share: String? = null,
) {
    val selected: SkillStatus? get() = selectedId?.let { id -> (skills + listOfNotNull(classSkill)).firstOrNull { it.def.id == id } }

    fun name(id: String): String = (skills + listOfNotNull(classSkill)).firstOrNull { it.def.id == id }?.def?.name ?: id
}

/** Powers that need text before they're cast. */
sealed interface PowerInput {
    val skillId: String
    data class Clarity(override val skillId: String) : PowerInput
    data class Insight(override val skillId: String) : PowerInput
}

/** One line of feedback after an action. */
sealed interface SkillMessage {
    data class Unlocked(val name: String) : SkillMessage
    data class NotUnlocked(val check: UnlockCheck) : SkillMessage
    data class Cast(val name: String, val mp: Int) : SkillMessage
    data class Refused(val check: CastCheck) : SkillMessage
    data class Respecced(val points: Int) : SkillMessage
    data object RespecFailed : SkillMessage
}

sealed interface SkillsEvent {
    data class Select(val id: String) : SkillsEvent
    data object CloseSheet : SkillsEvent
    data class Unlock(val id: String) : SkillsEvent
    data class Cast(val id: String) : SkillsEvent
    data object Respec : SkillsEvent
    data class SubmitClarity(val moved: String, val stuck: String, val next: String) : SkillsEvent
    data class SubmitInsight(val problem: String) : SkillsEvent
    data object CloseInput : SkillsEvent
    /** Eigenstate Collapse's optional question (A8): no cast, just a share. */
    data object AskWhichOne : SkillsEvent
    data object ShareShown : SkillsEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SkillsViewModel @Inject constructor(
    private val skills: SkillRepository,
    private val powers: PowerRepository,
    private val effects: PowerEffects,
    private val bridge: ProposalService,
) : ViewModel() {

    private val local = MutableStateFlow(Local())

    private data class Local(
        val selectedId: String? = null,
        val message: SkillMessage? = null,
        val input: PowerInput? = null,
        val share: String? = null,
    )

    private val overview = combine(skills.overview, powers.activeCasts) { o, _ -> o }
        .mapLatest { o -> o?.let { it to castChecks(it) } }

    val uiState: StateFlow<SkillsUiState> = combine(overview, local) { o, l ->
        val (ov, casts) = o ?: return@combine SkillsUiState(selectedId = l.selectedId, message = l.message, input = l.input, share = l.share)
        SkillsUiState(
            loaded = true,
            skills = ov.skills,
            classSkill = ov.classSkill,
            points = ov.unspentPoints,
            respec = ov.respec,
            tierGates = ov.tierGates,
            treeStats = ov.treeStats,
            casts = casts,
            selectedId = l.selectedId,
            message = l.message,
            input = l.input,
            share = l.share,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SkillsUiState())

    fun onEvent(event: SkillsEvent) {
        when (event) {
            is SkillsEvent.Select -> local.update { it.copy(selectedId = event.id, message = null) }
            SkillsEvent.CloseSheet -> local.update { it.copy(selectedId = null) }
            is SkillsEvent.Unlock -> viewModelScope.launch {
                val name = uiState.value.name(event.id)
                val check = skills.unlock(event.id)
                message(if (check == UnlockCheck.Ok) SkillMessage.Unlocked(name) else SkillMessage.NotUnlocked(check))
            }
            is SkillsEvent.Cast -> viewModelScope.launch {
                when (skills.definition(event.id)?.handler) {
                    PowerHandlers.CLARITY -> local.update { it.copy(selectedId = null, input = PowerInput.Clarity(event.id)) }
                    PowerHandlers.INSIGHT -> local.update { it.copy(selectedId = null, input = PowerInput.Insight(event.id)) }
                    PowerHandlers.MENTORS_EYE -> castThen(event.id, { effects.cast(event.id) }) {
                        bridge.statusText(PowerRequests.REVIEW, historyDays = PowerRules.MENTORS_EYE_DAYS)
                    }
                    else -> castThen(event.id, { effects.cast(event.id) })
                }
            }
            is SkillsEvent.SubmitClarity -> viewModelScope.launch {
                val id = local.value.input?.skillId ?: return@launch
                local.update { it.copy(input = null) }
                castThen(id, { effects.clarity(id, event.moved, event.stuck, event.next) })
            }
            is SkillsEvent.SubmitInsight -> viewModelScope.launch {
                val id = local.value.input?.skillId ?: return@launch
                if (event.problem.isBlank()) return@launch
                local.update { it.copy(input = null) }
                castThen(id, { effects.insight(id) }) { bridge.statusText(PowerRequests.stuck(event.problem)) }
            }
            SkillsEvent.CloseInput -> local.update { it.copy(input = null) }
            SkillsEvent.AskWhichOne -> viewModelScope.launch {
                bridge.statusText(PowerRequests.COLLAPSE)?.let { text -> local.update { it.copy(share = text) } }
            }
            SkillsEvent.ShareShown -> local.update { it.copy(share = null) }
            SkillsEvent.Respec -> viewModelScope.launch {
                val respec = skills.respec()
                local.update { it.copy(selectedId = null) }
                message(respec?.let { SkillMessage.Respecced(it.pointsReturned) } ?: SkillMessage.RespecFailed)
            }
        }
    }

    private fun message(m: SkillMessage) = local.update { it.copy(message = m) }

    /**
     * Casts, closes the sheet so the sigil ring (drawn by the app shell) is seen, and
     * queues [share]'s text for the share sheet when the power sends a request.
     */
    private suspend fun castThen(id: String, cast: suspend () -> CastResult, share: (suspend () -> String?)? = null) {
        val name = uiState.value.name(id)
        when (val result = cast()) {
            is CastResult.Cast -> {
                val text = share?.invoke()
                local.update { it.copy(selectedId = null, message = SkillMessage.Cast(name, result.mpSpent), share = text ?: it.share) }
            }
            is CastResult.Refused -> message(SkillMessage.Refused(result.check))
        }
    }

    private suspend fun castChecks(o: SkillOverview): Map<String, CastCheck> =
        (o.skills + listOfNotNull(o.classSkill))
            .filter { it.unlocked && it.def.kind == SkillKind.ACTIVE && !it.def.locked }
            .associate { it.def.id to powers.check(it.def.id) }
}
