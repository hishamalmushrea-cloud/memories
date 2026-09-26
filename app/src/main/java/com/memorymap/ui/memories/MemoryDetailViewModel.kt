package com.memorymap.ui.memories

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.memorymap.domain.model.MediaItem
import com.memorymap.domain.model.MediaOwner
import com.memorymap.domain.model.Memory
import com.memorymap.domain.repository.AuthRepository
import com.memorymap.domain.repository.MediaRepository
import com.memorymap.domain.repository.MemoryRepository
import com.memorymap.util.MmLog
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** UI state of the memory detail screen. */
data class MemoryDetailUiState(
    val memory: Memory? = null,
    val attachments: List<MediaItem> = emptyList(),
    val isLoading: Boolean = true,
    val isDeleted: Boolean = false,
)

/**
 * Shows one memory with its attachments and deletes it on request.
 *
 * Both streams are reactive, so an edit made in the editor is visible here
 * without a manual refresh.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MemoryDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val memoryRepository: MemoryRepository,
    private val mediaRepository: MediaRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val memoryId: String = savedStateHandle.get<String>("memoryId").orEmpty()

    val state: StateFlow<MemoryDetailUiState> = authRepository.currentUserId
        .flatMapLatest { userId ->
            if (userId == null) {
                flowOf(MemoryDetailUiState())
            } else {
                combine(
                    memoryRepository.watchOne(memoryId),
                    mediaRepository.watchFor(MediaOwner.MEMORY, memoryId),
                ) { memory, attachments ->
                    // A tombstone is treated as gone: the detail screen must not
                    // keep showing a memory the user already deleted.
                    val live = memory?.takeIf { !it.isDeleted }
                    MemoryDetailUiState(
                        memory = live,
                        attachments = attachments,
                        isLoading = false,
                        isDeleted = memory != null && live == null,
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MemoryDetailUiState())

    /** Soft-deletes the memory and its attachment files. */
    fun delete() {
        viewModelScope.launch {
            runCatching {
                memoryRepository.delete(memoryId)
                mediaRepository.removeAllFor(MediaOwner.MEMORY, memoryId)
            }.onFailure { MmLog.e("Unable to delete the memory", it) }
        }
    }

    /** Removes a single attachment from this memory. */
    fun removeAttachment(item: MediaItem) {
        viewModelScope.launch {
            runCatching { mediaRepository.remove(item.id) }
                .onFailure { MmLog.e("Unable to remove the attachment", it) }
        }
    }

    /** Exposes the id so the UI can open the editor for this memory. */
    val id: String get() = memoryId
}
