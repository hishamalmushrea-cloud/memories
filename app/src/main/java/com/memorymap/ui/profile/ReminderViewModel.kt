package com.memorymap.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.memorymap.domain.repository.ReminderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Drives the daily-reminder switch.
 *
 * Thin, like the lock's: the decision and the scheduling live in
 * [ReminderRepository], and this only forwards. The notification permission is
 * asked for by the screen, because only a composable can launch the system
 * permission dialog; whether it was granted does not change the setting, it only
 * decides whether the system will let the notification through.
 */
@HiltViewModel
class ReminderViewModel @Inject constructor(
    private val reminderRepository: ReminderRepository,
) : ViewModel() {

    val isEnabled: StateFlow<Boolean> = reminderRepository.isEnabled

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch { reminderRepository.setEnabled(enabled) }
    }
}
