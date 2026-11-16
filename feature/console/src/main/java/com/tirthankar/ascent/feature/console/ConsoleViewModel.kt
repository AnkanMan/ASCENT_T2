package com.tirthankar.ascent.feature.console

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tirthankar.ascent.bridge.ApplyResult
import com.tirthankar.ascent.bridge.ParseProblem
import com.tirthankar.ascent.bridge.PendingImport
import com.tirthankar.ascent.bridge.ProposalCard
import com.tirthankar.ascent.bridge.ProposalService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Quick starters that ride along with the status (SPEC "Input and voice"). */
enum class Starter { PLAN_DAY, SET_WEEK, REPORT, LOW_ENERGY }

enum class ConsoleMessage { CLIPBOARD_EMPTY, NO_BLOCK, COPIED }

data class CardUi(val card: ProposalCard, val selected: Boolean)

data class ReviewUi(
    val cards: List<CardUi>,
    val problems: List<ParseProblem>,
) {
    val selectedCount: Int get() = cards.count { it.selected && it.card.applicable }
}

data class ConsoleUiState(
    val statusText: String = "",
    val starter: Starter? = null,
    val review: ReviewUi? = null,
    val result: ApplyResult? = null,
    val message: ConsoleMessage? = null,
    val busy: Boolean = false,
)

sealed interface ConsoleEvent {
    /** [requestText] is the starter's text from strings.xml, added to the status. */
    data class SelectStarter(val starter: Starter?, val requestText: String?) : ConsoleEvent
    data object Copied : ConsoleEvent
    data class Import(val text: String?) : ConsoleEvent
    data class ToggleCard(val index: Int) : ConsoleEvent
    data object ApplySelected : ConsoleEvent
    data object Dismiss : ConsoleEvent
    data object ClearMessage : ConsoleEvent
    data object Refresh : ConsoleEvent
}

@HiltViewModel
class ConsoleViewModel @Inject constructor(
    private val service: ProposalService,
    pending: PendingImport,
) : ViewModel() {

    private val _state = MutableStateFlow(ConsoleUiState())
    val state: StateFlow<ConsoleUiState> = _state.asStateFlow()

    private var requestText: String? = null

    init {
        refresh()
        // Text shared into ASCENT from the Claude app lands here.
        pending.text.filterNotNull().onEach { pending.consume()?.let(::review) }.launchIn(viewModelScope)
    }

    fun onEvent(event: ConsoleEvent) {
        when (event) {
            is ConsoleEvent.SelectStarter -> {
                requestText = event.requestText
                _state.update { it.copy(starter = event.starter) }
                refresh()
            }
            ConsoleEvent.Copied -> _state.update { it.copy(message = ConsoleMessage.COPIED) }
            is ConsoleEvent.Import -> {
                if (event.text.isNullOrBlank()) {
                    _state.update { it.copy(message = ConsoleMessage.CLIPBOARD_EMPTY) }
                } else {
                    review(event.text)
                }
            }
            is ConsoleEvent.ToggleCard -> _state.update { s ->
                val review = s.review ?: return@update s
                s.copy(
                    review = review.copy(
                        cards = review.cards.map {
                            if (it.card.index == event.index && it.card.applicable) it.copy(selected = !it.selected) else it
                        },
                    ),
                )
            }
            ConsoleEvent.ApplySelected -> apply()
            ConsoleEvent.Dismiss -> _state.update { it.copy(review = null) }
            ConsoleEvent.ClearMessage -> _state.update { it.copy(message = null, result = null) }
            ConsoleEvent.Refresh -> refresh()
        }
    }

    private fun refresh() = viewModelScope.launch {
        val text = service.statusText(requestText).orEmpty()
        _state.update { it.copy(statusText = text) }
    }

    private fun review(text: String) = viewModelScope.launch {
        _state.update { it.copy(busy = true, result = null) }
        val review = service.review(text)
        _state.update { s ->
            if (!review.foundBlock) {
                s.copy(busy = false, review = null, message = ConsoleMessage.NO_BLOCK)
            } else {
                s.copy(
                    busy = false,
                    review = ReviewUi(review.cards.map { CardUi(it, selected = it.applicable) }, review.problems),
                )
            }
        }
    }

    private fun apply() = viewModelScope.launch {
        val review = _state.value.review ?: return@launch
        _state.update { it.copy(busy = true) }
        val result = service.apply(review.cards.filter { it.selected }.map { it.card })
        _state.update { it.copy(busy = false, review = null, result = result) }
        refresh()
    }
}
