package com.memorymap.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.memorymap.data.remote.SupabaseClientProvider
import com.memorymap.data.repository.LifeStatsCalculator
import com.memorymap.domain.model.LifeStats
import com.memorymap.domain.model.User
import com.memorymap.domain.repository.UserRepository
import com.memorymap.util.MmLog
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Account + life statistics shown on the profile screen. */
data class ProfileUiState(
    val user: User? = null,
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
    private val userRepository: UserRepository,
    private val statsCalculator: LifeStatsCalculator,
    supabase: SupabaseClientProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(ProfileUiState(cloudConfigured = supabase.isAvailable))
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            userRepository.watchCurrentUser().collect { user ->
                _state.update { it.copy(user = user) }
                refresh()
            }
        }
        refresh()
    }

    /** Recounts everything. Called after the local user becomes known. */
    fun refresh() {
        viewModelScope.launch {
            val userId = _state.value.user?.id ?: LOCAL_USER_ID
            runCatching {
                val stats = statsCalculator.calculate(userId)
                val people = statsCalculator.peopleCount(userId)
                _state.update { it.copy(stats = stats, peopleCount = people) }
            }.onFailure { MmLog.e("Unable to compute the life statistics", it) }
        }
    }

    private companion object {
        const val LOCAL_USER_ID = "local"
    }
}
