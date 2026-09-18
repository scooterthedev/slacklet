package com.scooter.slackwear.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.scooter.slackwear.core.model.SafeFailure
import com.scooter.slackwear.core.model.toSafeFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import com.scooter.slackwear.core.model.repository.SearchHit
import com.scooter.slackwear.core.model.repository.SearchRepository
import com.scooter.slackwear.core.model.repository.SearchSort
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SearchViewModel(
    private val repository: SearchRepository,
    private val failureMapper: (Throwable) -> SafeFailure = { it.toSafeFailure() },
) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var inFlight: Job? = null

    fun search(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        run(query = trimmed, sort = _state.value.sort)
    }

    fun cycleSort() {
        val current = _state.value
        val next = SearchSort.entries[(current.sort.ordinal + 1) % SearchSort.entries.size]

        if (current.query.isBlank()) {
            _state.value = current.copy(sort = next)
        } else {
            run(query = current.query, sort = next)
        }
    }

    private fun run(query: String, sort: SearchSort) {

        inFlight?.cancel()
        _state.value = SearchUiState(query = query, sort = sort, isSearching = true)

        inFlight = viewModelScope.launch {
            try {
                val hits = repository.search(query, sort).getOrThrow()
                coroutineContext.ensureActive()
                _state.value = SearchUiState(query = query, sort = sort, hits = hits)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                coroutineContext.ensureActive()
                _state.value = SearchUiState(query = query, sort = sort, failure = failureMapper(error))
            }
        }
    }

    fun clear() {
        inFlight?.cancel()
        _state.value = SearchUiState()
    }
}

data class SearchUiState(
    val query: String = "",
    val sort: SearchSort = SearchSort.NEWEST,
    val hits: List<SearchHit> = emptyList(),
    val isSearching: Boolean = false,
    val failure: SafeFailure? = null,
) {
    val error: String? get() = failure?.let { "Search failed - ${it.reason}" }
}
