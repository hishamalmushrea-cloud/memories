package com.memorymap.data.repository

import com.memorymap.data.local.LockSettings
import com.memorymap.domain.model.LockState
import com.memorymap.domain.repository.AppLockRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The lock state machine.
 *
 * Two inputs, one output. The saved setting decides whether the lock exists at
 * all; the in-memory [unlocked] flag decides whether it has been passed this
 * time the app is open. Every transition recomputes the state from both, so
 * there is no path that leaves them disagreeing - the failure this guards
 * against is a lock that says "unlocked" after the setting was turned off, or
 * "locked" after the user turned it off to let someone in.
 *
 * There is no coroutine scope here on purpose: the state is a single
 * [MutableStateFlow] updated synchronously on each transition, so the machine is
 * ordinary code a plain JVM test can drive without a dispatcher.
 */
@Singleton
class AppLockRepositoryImpl @Inject constructor(
    private val settings: LockSettings,
) : AppLockRepository {

    /** True only between [onUnlockConfirmed] and the next [lock]. Never persisted. */
    private var unlocked = false

    private val _lockState = MutableStateFlow(compute(settings.enabled, unlocked))
    override val lockState: StateFlow<LockState> = _lockState.asStateFlow()

    override suspend fun setEnabled(enabled: Boolean) {
        settings.enabled = enabled
        // Turning the lock off also clears the unlock, so turning it back on
        // later starts locked rather than inheriting a pass from before.
        if (!enabled) unlocked = false
        _lockState.value = compute(settings.enabled, unlocked)
    }

    override fun onUnlockConfirmed() {
        unlocked = true
        _lockState.value = compute(settings.enabled, unlocked)
    }

    override fun lock() {
        unlocked = false
        _lockState.value = compute(settings.enabled, unlocked)
    }

    private fun compute(enabled: Boolean, unlocked: Boolean): LockState = when {
        !enabled -> LockState.Disabled
        unlocked -> LockState.Unlocked
        else -> LockState.Locked
    }
}
