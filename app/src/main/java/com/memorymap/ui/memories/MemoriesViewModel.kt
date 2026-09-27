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
import com.memorymap.R
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
    /** A message for a delete that failed; null when the list is healthy. */
    val errorRes: Int? = null,
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

    /**
     * The last thing that went wrong, if anything.
     *
     * A flow of its own rather than part of the derived state: the state is rebuilt
     * whenever the account or the list changes, and a message that lived inside it
     * would be wiped by the refresh a delete triggers - the person would see the
     * failure for a moment and then not.
     */
    private val message = MutableStateFlow<Int?>(null)

    val state: StateFlow<MemoriesUiState> = authRepository.currentUserId
        .flatMapLatest { userId ->
            if (userId == null) {
                flowOf(MemoriesUiState())
            } else {
                combine(
                    memoryRepository.watchAll(userId),
                    mediaRepository.watchSummary(MediaOwner.MEMORY),
                    query,
                    message,
                ) { memories, summary, term, errorRes ->
                    MemoriesUiState(
                        memories = memories.filter { it.matches(term) },
                        summary = summary,
                        query = term,
                        isLoading = false,
                        errorRes = errorRes,
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
            }.onFailure {
                MmLog.e("Unable to delete the memory", it)
                message.value = R.string.memory_error_delete
            }
        }
    }

    /** Called once a message has been shown. */
    fun clearError() {
        message.value = null
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
