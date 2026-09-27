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
    /**
     * Whether the server still has this account's records after a wipe.
     *
     * Null when the question was never asked - no project is connected - so the
     * summary can stay silent instead of claiming something it does not know.
     */
    val serverRecordsRemoved: Boolean? = null,
    /** True while the account-deletion confirmation is on screen. */
    val accountConfirmationVisible: Boolean = false,
    val isDeletingAccount: Boolean = false,
    /** Set once the server has answered about deleting the account. */
    val accountDeletion: AuthRepository.Deletion? = null,
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
     *
     * The order is forced by what each step needs. The uploaded copies go first,
     * while the rows that hold their bucket keys still exist. The server's copy
     * of the records goes next, for the same reason: the request names the rows
     * by account, but there is no point leaving files in a bucket whose rows are
     * about to be deleted everywhere. The local wipe runs last and runs either
     * way, because a network that cannot be reached must not be able to keep the
     * user's archive on their own device.
     */
    fun confirmDeleteLocalData(
        deleteCloudCopies: Boolean = false,
        deleteServerRecords: Boolean = false,
    ) {
        val userId = authRepository.currentUserId.value ?: return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    wipeConfirmationVisible = false,
                    isWiping = true,
                    uploadedCount = 0,
                    cloudRemoval = null,
                    serverRecordsRemoved = null,
                )
            }
            // Deleting the records on the server while keeping the uploaded
            // files would leave them unreachable from anywhere, so the files go
            // with them whether or not that box was ticked. The screen says so
            // next to the box.
            val cloud = if (deleteCloudCopies || deleteServerRecords) {
                userRepository.deleteCloudCopies(userId)
            } else {
                null
            }
            val server = if (deleteServerRecords) userRepository.deleteServerRecords() else null
            val summary = userRepository.deleteLocalData(userId)
            authRepository.signOut()
            _state.update {
                it.copy(
                    isWiping = false,
                    wipeSummary = summary,
                    cloudRemoval = cloud,
                    serverRecordsRemoved = server,
                    stats = null,
                    peopleCount = 0,
                )
            }
        }
    }

    fun dismissWipeSummary() {
        _state.update {
            it.copy(wipeSummary = null, cloudRemoval = null, serverRecordsRemoved = null)
        }
    }

    /**
     * Opens the confirmation for deleting the account itself.
     *
     * Only reachable when a project is connected: an offline install has no
     * account anywhere but this device, and deleting local data already is that.
     */
    fun requestDeleteAccount() {
        _state.update { it.copy(accountConfirmationVisible = true, accountDeletion = null) }
    }

    fun cancelDeleteAccount() {
        _state.update { it.copy(accountConfirmationVisible = false) }
    }

    fun dismissAccountResult() {
        _state.update { it.copy(accountDeletion = null) }
    }

    /**
     * Deletes the account on the server, then everything on this device.
     *
     * The device is wiped only after the server confirms, and that order is the
     * whole point: if the request fails, the user keeps both their archive and a
     * truthful error. Wiping first and reporting a failure afterwards would leave
     * them with nothing on the device and an account still holding their records
     * - the one outcome nobody asked for.
     */
    fun confirmDeleteAccount() {
        val userId = authRepository.currentUserId.value ?: return
        viewModelScope.launch {
            _state.update { it.copy(accountConfirmationVisible = false, isDeletingAccount = true) }
            // The uploads go first, while the rows that hold their keys still
            // exist and the session is still valid: after the account is gone
            // there is nothing left to name them by and no token to remove them
            // with, which would leave the files in the bucket for good.
            val cloud = if (authRepository.isCloudConfigured) {
                userRepository.deleteCloudCopies(userId)
            } else {
                null
            }
            val deletion = authRepository.deleteAccount()

            if (deletion != AuthRepository.Deletion.DELETED) {
                _state.update {
                    it.copy(isDeletingAccount = false, accountDeletion = deletion, cloudRemoval = cloud)
                }
                return@launch
            }

            val summary = userRepository.deleteLocalData(userId)
            _state.update {
                it.copy(
                    isDeletingAccount = false,
                    accountDeletion = deletion,
                    cloudRemoval = cloud,
                    wipeSummary = summary,
                    stats = null,
                    peopleCount = 0,
                )
            }
        }
    }
}
