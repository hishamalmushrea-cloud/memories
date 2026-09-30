package com.memorymap.ui.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.memorymap.domain.repository.AuthRepository
import com.memorymap.domain.repository.LOCAL_USER_ID
import com.memorymap.domain.repository.ReadableExportOutcome
import com.memorymap.domain.repository.ReadableExportRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ReadableExportUiState(
    val isBusy: Boolean = false,
    /** A string resource key, so the result reads in the user's language. */
    val messageKey: String? = null,
)

/**
 * Writes a readable copy of the archive to a file the user picks.
 *
 * This is the human counterpart to the backup: not a machine archive to restore,
 * but the memories themselves as a document to read, print or send. It is kept
 * apart from [BackupViewModel] because it is a different action with a different
 * result, and because that screen's own tests construct its view model directly.
 */
@HiltViewModel
class ReadableExportViewModel @Inject constructor(
    private val repository: ReadableExportRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ReadableExportUiState())
    val state: StateFlow<ReadableExportUiState> = _state.asStateFlow()

    fun export(documentUri: String) {
        val userId = authRepository.currentUserId.value ?: LOCAL_USER_ID
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, messageKey = null) }
            val outcome = repository.export(userId, documentUri)
            _state.update {
                it.copy(
                    isBusy = false,
                    messageKey = when (outcome) {
                        ReadableExportOutcome.Written -> "export_written"
                        ReadableExportOutcome.Failed -> "export_error_write"
                    },
                )
            }
        }
    }

    fun dismissMessage() {
        _state.update { it.copy(messageKey = null) }
    }
}
