package com.resourcetracker.ui.cards

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

data class CardLists(
    val due: List<Card> = emptyList(),
    val inFlight: List<Card> = emptyList(),
    val reviewed: List<Card> = emptyList(),
    val isLoading: Boolean = true,
)

@HiltViewModel
class CardsViewModel @Inject constructor(
    cardRepository: CardRepository,
) : ViewModel() {

    val lists: StateFlow<CardLists> = kotlinx.coroutines.flow.combine(
        cardRepository.observeDue(),
        cardRepository.observeInFlight(),
        cardRepository.observeReviewed(),
    ) { due, inFlight, reviewed ->
        CardLists(due = due, inFlight = inFlight, reviewed = reviewed, isLoading = false)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CardLists())
}
