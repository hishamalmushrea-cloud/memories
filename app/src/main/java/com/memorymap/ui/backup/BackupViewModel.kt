package com.memorymap.ui.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.memorymap.domain.repository.AuthRepository
import com.memorymap.domain.repository.BackupOutcome
import com.memorymap.domain.repository.BackupRepository
import com.memorymap.util.backup.BackupCounts
import com.memorymap.util.backup.BackupManifest
import com.memorymap.util.backup.BackupPlanner
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BackupUiState(
    val isBusy: Boolean = false,
    /** The folder picked for export, or whose manifest is waiting to be imported. */
    val pendingUri: String? = null,
    /** Non-null while the import confirmation is showing. */
    val pendingManifest: BackupManifest? = null,
    /** A string resource key: the reason has to be readable in the user's language. */
    val messageKey: String? = null,
    val lastExport: BackupCounts? = null,
    val lastImport: BackupCounts? = null,
    val mediaCopied: Int = 0,
    val mediaMissing: Int = 0,
    val mediaRestored: Int = 0,
    val skipped: Int = 0,
)

/**
 * Export and import of the local archive.
 *
 * An import is never run the moment a folder is picked. The manifest is read
 * first and its contents shown, because merging somebody's life into an existing
 * archive is a decision the user should make with the numbers in front of them.
 */
@HiltViewModel
class BackupViewModel @Inject constructor(
    private val backupRepository: BackupRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    fun export(treeUri: String) {
        val userId = authRepository.currentUserId.value ?: return
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, messageKey = null, pendingManifest = null) }
            val outcome = backupRepository.export(userId, treeUri)
            _state.update { current ->
                when {
                    outcome is BackupOutcome.Exported -> current.copy(
                        isBusy = false,
                        messageKey = "backup_exported",
                        lastExport = outcome.manifest.counts,
                        lastImport = null,
                        mediaCopied = outcome.mediaCopied,
                        mediaMissing = outcome.mediaMissing,
                    )

                    outcome is BackupOutcome.Failed -> current.copy(
                        isBusy = false,
                        messageKey = outcome.messageKey,
                    )

                    else -> current.copy(isBusy = false)
                }
            }
        }
    }

    /** Reads the manifest so the user can see what an import would merge. */
    fun inspect(treeUri: String) {
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, messageKey = null) }
            val manifest = backupRepository.inspect(treeUri)
            _state.update { current ->
                val problems = manifest?.let { BackupPlanner.validate(it) }.orEmpty()
                when {
                    manifest == null || problems.isNotEmpty() ->
                        current.copy(isBusy = false, messageKey = "backup_error_format")

                    else -> current.copy(
                        isBusy = false,
                        pendingUri = treeUri,
                        pendingManifest = manifest,
                    )
                }
            }
        }
    }

    fun confirmImport() {
        val treeUri = _state.value.pendingUri ?: return
        val userId = authRepository.currentUserId.value ?: return
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, pendingUri = null, pendingManifest = null) }
            val outcome = backupRepository.import(userId, treeUri)
            _state.update { current ->
                when {
                    outcome is BackupOutcome.Imported -> current.copy(
                        isBusy = false,
                        messageKey = "backup_imported",
                        lastImport = outcome.counts,
                        lastExport = null,
                        mediaRestored = outcome.mediaRestored,
                        skipped = outcome.skipped,
                    )

                    outcome is BackupOutcome.Failed -> current.copy(
                        isBusy = false,
                        messageKey = outcome.messageKey,
                    )

                    else -> current.copy(isBusy = false)
                }
            }
        }
    }

    fun cancelImport() {
        _state.update { it.copy(pendingUri = null, pendingManifest = null) }
    }

    fun dismissMessage() {
        _state.update { it.copy(messageKey = null) }
    }
}
