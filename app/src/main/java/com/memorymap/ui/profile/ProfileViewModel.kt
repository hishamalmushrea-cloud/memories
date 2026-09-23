package com.memorymap.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.memorymap.data.repository.LifeStatsCalculator
import com.memorymap.domain.model.AuthState
import com.memorymap.domain.model.LifeStats
import com.memorymap.domain.repository.AuthRepository
import com.memorymap.util.MmLog
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
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
)

/**
 * Everything on this screen is counted from the local database, so the numbers
 * are correct with no connection and no server round trip.
 */
@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val statsCalculator: LifeStatsCalculator,
    private val authRepository: AuthRepository,
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
}
