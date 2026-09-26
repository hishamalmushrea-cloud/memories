package com.memorymap.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.memorymap.data.repository.LifeStatsCalculator
import com.memorymap.domain.model.AuthState
import com.memorymap.domain.model.CloudRemoval
import com.memorymap.domain.model.LifeStats
import com.memorymap.domain.model.SyncState
import com.memorymap.domain.model.WipeSummary
import com.memorymap.domain.repository.AuthRepository
import com.memorymap.domain.repository.SyncRepository
import com.memorymap.domain.repository.UserRepository
import com.memorymap.util.MmLog
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Account header, life statistics and the session actions. */
data class ProfileUiState(
    val authState: AuthState = AuthState.Unknown,
    val stats: LifeStats? = null,
    val cloudConfigured: Boolean = false,
    val peopleCount: Int = 0,
    val sync: SyncState = SyncState(),
    /** True while the destructive confirmation is on screen. */
    val wipeConfirmationVisible: Boolean = false,
    val isWiping: Boolean = false,
    /** Set once a wipe has finished, so the result can be reported. */
    val wipeSummary: WipeSummary? = null,
    /**
     * How many attachments this account uploaded, known only while the
     * confirmation is open - it is read before the rows that hold the answer.
     */
    val uploadedCount: Int = 0,
    /** Set when the user also asked for the uploaded copies to go. */
    val cloudRemoval: CloudRemoval? = null,
)

/**
 * Everything on this screen is counted from the local database, so the numbers
 * are correct with no connection and no server round trip.
 */
@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val statsCalculator: LifeStatsCalculator,
    private val authRepository: AuthRepository,
    private val syncRepository: SyncRepository,
    private val userRepository: UserRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(
        ProfileUiState(
            authState = authRepository.authState.value,
            cloudConfigured = authRepository.isCloudConfigured,
        ),
    )
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            authRepository.authState.collect { authState ->
                _state.update { it.copy(authState = authState) }
                refresh()
            }
        }
        // Restarted whenever the account changes, so the queue shown always
        // belongs to the person who is signed in.
        viewModelScope.launch {
            authRepository.currentUserId
                .flatMapLatest { userId -> syncRepository.watchState(userId) }
                .collect { sync -> _state.update { it.copy(sync = sync) } }
        }
    }

    /**
     * Runs one synchronisation immediately.
     *
     * This goes straight to the repository rather than through WorkManager, so
     * the button answers at once; the periodic worker keeps handling the
     * automatic case on its own schedule.
     */
    fun syncNow() {
        viewModelScope.launch {
            val userId = authRepository.currentUserId.value ?: return@launch
            _state.update { it.copy(sync = it.sync.copy(isRunning = true)) }
            val result = syncRepository.syncNow(userId)
            _state.update { it.copy(sync = result) }
            refresh()
        }
    }

    /** Recounts everything for the current account. */
    fun refresh() {
        viewModelScope.launch {
            val userId = authRepository.currentUserId.value
            if (userId == null) {
                _state.update { it.copy(stats = null, peopleCount = 0) }
                return@launch
            }
            runCatching {
                val stats = statsCalculator.calculate(userId)
                val people = statsCalculator.peopleCount(userId)
                _state.update { it.copy(stats = stats, peopleCount = people) }
            }.onFailure { MmLog.e("Unable to compute the life statistics", it) }
        }
    }

    /** Ends the session. The local archive is kept on purpose. */
    fun signOut() {
        viewModelScope.launch { authRepository.signOut() }
    }

    /**
     * Opens the confirmation, and works out what else is at stake.
     *
     * The count of uploaded copies is read here because it cannot be read
     * afterwards: the keys live on the rows the wipe is about to delete. Shown
     * now, it is a fact the user can act on; shown never, the objects would be
     * unreachable from the app for good.
     */
    fun requestDeleteLocalData() {
        val userId = authRepository.currentUserId.value
        _state.update { it.copy(wipeConfirmationVisible = true) }
        if (userId == null) return
        viewModelScope.launch {
            val uploaded = runCatching { userRepository.uploadedAttachmentPaths(userId).size }
                .getOrElse { error ->
                    MmLog.e("Could not count the uploaded attachments", error)
                    0
                }
            _state.update { it.copy(uploadedCount = uploaded) }
        }
    }

    fun cancelDeleteLocalData() {
        _state.update { it.copy(wipeConfirmationVisible = false, uploadedCount = 0) }
    }

    /**
     * Destroys the local archive and ends the session.
     *
     * Irreversible, so it only runs from the confirmation, and the summary of
     * what went is kept on screen afterwards: "everything is gone" is not the
     * same information as "412 records and 38 files are gone".
     */
    /**
     * Destroys the local archive and ends the session.
     *
     * [deleteCloudCopies] is the user's answer to the second question, and it is
     * carried out first, while the rows that hold the bucket keys still exist.
     * The local wipe runs either way: a bucket that cannot be reached must not
     * be able to keep the user's archive on their own device.
     */
    fun confirmDeleteLocalData(deleteCloudCopies: Boolean = false) {
        val userId = authRepository.currentUserId.value ?: return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    wipeConfirmationVisible = false,
                    isWiping = true,
                    uploadedCount = 0,
                    cloudRemoval = null,
                )
            }
            val cloud = if (deleteCloudCopies) {
                userRepository.deleteCloudCopies(userId)
            } else {
                null
            }
            val summary = userRepository.deleteLocalData(userId)
            authRepository.signOut()
            _state.update {
                it.copy(
                    isWiping = false,
                    wipeSummary = summary,
                    cloudRemoval = cloud,
                    stats = null,
                    peopleCount = 0,
                )
            }
        }
    }

    fun dismissWipeSummary() {
        _state.update { it.copy(wipeSummary = null, cloudRemoval = null) }
    }
}
