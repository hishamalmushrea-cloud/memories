package com.memorymap.ui.memories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.memorymap.domain.model.MediaOwner
import com.memorymap.domain.model.MediaSummary
import com.memorymap.domain.model.Memory
import com.memorymap.domain.repository.AuthRepository
import com.memorymap.domain.repository.MediaRepository
import com.memorymap.domain.repository.MemoryRepository
import com.memorymap.util.MmLog
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** UI state of the memory list. */
data class MemoriesUiState(
    val memories: List<Memory> = emptyList(),
    val summary: Map<String, MediaSummary> = emptyMap(),
    val query: String = "",
    val isLoading: Boolean = true,
)

/**
 * Backs the memory list: create, read, update and delete, plus a live filter.
 *
 * Everything is read from Room and scoped to the signed-in account, so the list
 * restarts when the account changes. Deleting is a soft delete: the tombstone is
 * what keeps an offline delete from coming back after a reconnect.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MemoriesViewModel @Inject constructor(
    private val memoryRepository: MemoryRepository,
    private val mediaRepository: MediaRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val query = MutableStateFlow("")

    val state: StateFlow<MemoriesUiState> = authRepository.currentUserId
        .flatMapLatest { userId ->
            if (userId == null) {
                flowOf(MemoriesUiState())
            } else {
                combine(
                    memoryRepository.watchAll(userId),
                    mediaRepository.watchSummary(MediaOwner.MEMORY),
                    query,
                ) { memories, summary, term ->
                    MemoriesUiState(
                        memories = memories.filter { it.matches(term) },
                        summary = summary,
                        query = term,
                        isLoading = false,
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MemoriesUiState())

    fun onQueryChange(value: String) {
        query.value = value
    }

    /** Soft-deletes a memory and its attachment files. */
    fun delete(memoryId: String) {
        viewModelScope.launch {
            runCatching {
                memoryRepository.delete(memoryId)
                mediaRepository.removeAllFor(MediaOwner.MEMORY, memoryId)
            }.onFailure { MmLog.e("Unable to delete the memory", it) }
        }
    }

    /** Case-insensitive match over title, body and place, mirroring the DAO. */
    private fun Memory.matches(term: String): Boolean {
        val needle = term.trim().lowercase()
        if (needle.isEmpty()) return true
        return title.lowercase().contains(needle) ||
            text.lowercase().contains(needle) ||
            placeName.orEmpty().lowercase().contains(needle)
    }
}
