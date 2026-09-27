package com.memorymap.ui.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.memorymap.domain.model.Emotion
import com.memorymap.domain.model.SearchFilter
import com.memorymap.domain.model.SearchResults
import com.memorymap.domain.repository.AuthRepository
import com.memorymap.domain.repository.SearchRepository
import com.memorymap.domain.usecase.SearchQueryParser
import com.memorymap.util.MmLog
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchUiState(
    val text: String = "",
    val results: SearchResults? = null,
    val filter: SearchFilter = SearchFilter(),
    val isSearching: Boolean = false,
    /** False until the first search has run, so an empty screen is not an empty result. */
    val hasSearched: Boolean = false,
)

/**
 * Local search over the whole archive.
 *
 * Typing schedules a search rather than running one per keystroke: the query is
 * several `LIKE` scans per word, and running them all while the user is still
 * typing would only slow the keyboard down. Cancelling the pending run is what
 * makes that safe — a result from a half-typed word can never land after the
 * finished one.
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val searchRepository: SearchRepository,
    private val authRepository: AuthRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val _state = MutableStateFlow(
        SearchUiState(text = savedStateHandle.get<String>(ARG_QUERY).orEmpty()),
    )
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    init {
        // Arriving from a person or a place, the query is already written.
        if (_state.value.text.isNotBlank()) scheduleSearch()
    }

    fun onTextChanged(text: String) {
        _state.update { it.copy(text = text) }
        if (text.isBlank()) {
            searchJob?.cancel()
            _state.update { it.copy(results = null, hasSearched = false, isSearching = false) }
        } else {
            scheduleSearch()
        }
    }

    fun setEmotion(emotion: Emotion?) {
        _state.update { it.copy(filter = it.filter.copy(emotion = emotion)) }
        scheduleSearchIfWorthIt()
    }

    fun clearFilter() {
        _state.update { it.copy(filter = SearchFilter()) }
        scheduleSearchIfWorthIt()
    }

    private fun scheduleSearchIfWorthIt() {
        // A filter on its own is a valid search, but only once something is on
        // screen to narrow; with an empty box it would list the whole archive.
        if (_state.value.text.isNotBlank()) scheduleSearch()
    }

    private fun scheduleSearch() {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            runSearch()
        }
    }

    private suspend fun runSearch() {
        val snapshot = _state.value
        val query = SearchQueryParser.parse(snapshot.text)
        val userId = authRepository.currentUserId.value

        _state.update { it.copy(isSearching = true) }
        val results = try {
            if (userId == null) {
                SearchResults(query)
            } else {
                searchRepository.search(userId, query, snapshot.filter)
            }
        } catch (error: Throwable) {
            MmLog.e("Search failed", error)
            SearchResults(query)
        }
        _state.update {
            it.copy(results = results, isSearching = false, hasSearched = true)
        }
    }

    private companion object {
        const val ARG_QUERY = "query"

        /**
         * Long enough that a fast typist runs one search, short enough that the
         * results still feel like they answer the keystroke.
         */
        const val DEBOUNCE_MS = 250L
    }
}
