package com.resourcetracker.ui.cards

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.resourcetracker.data.repository.CardRepository
import com.resourcetracker.domain.model.Card
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Detail screen state.
 *
 * The card id arrives through the navigation route, which Hilt hands over as a
 * [SavedStateHandle] entry.
 */
@HiltViewModel
class CardDetailViewModel @Inject constructor(
    cardRepository: CardRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val cardId: String = checkNotNull(savedStateHandle["cardId"]) {
        "cardId missing from navigation arguments"
    }

    val card: StateFlow<CardState> = cardRepository.observeCard(cardId)
        .map { if (it == null) CardState.Missing else CardState.Loaded(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CardState.Loading)

    val id: String get() = cardId
}

sealed interface CardState {
    data object Loading : CardState
    data object Missing : CardState
    data class Loaded(val card: Card) : CardState
}
