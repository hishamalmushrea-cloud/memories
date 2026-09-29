package com.memorymap.ui.lock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.memorymap.domain.model.LockState
import com.memorymap.domain.repository.AppLockRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Drives the lock gate and the setting that turns it on.
 *
 * Thin on purpose: the state machine lives in [AppLockRepository] so it can be
 * tested without a dispatcher, and this only forwards. The credential check is
 * launched by the screen, because only a composable can start an activity for
 * result; the view model is told the outcome and nothing about the credential.
 */
@HiltViewModel
class LockViewModel @Inject constructor(
    private val appLockRepository: AppLockRepository,
) : ViewModel() {

    val lockState: StateFlow<LockState> = appLockRepository.lockState

    /** The platform said the person passed; let them in. */
    fun onUnlockConfirmed() = appLockRepository.onUnlockConfirmed()

    /** The app left the foreground; ask again next time it is looked at. */
    fun lock() = appLockRepository.lock()

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch { appLockRepository.setEnabled(enabled) }
    }
}
