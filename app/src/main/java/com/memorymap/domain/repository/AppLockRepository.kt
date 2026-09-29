package com.memorymap.domain.repository

import com.memorymap.domain.model.LockState
import kotlinx.coroutines.flow.StateFlow

/**
 * The app's front-door lock.
 *
 * Whether the lock is *on* is a choice the user makes once and it survives a
 * restart; whether it is *passed* is true only for the time the app is open, and
 * is given up when the app goes to the background. Keeping the two apart is the
 * whole design: a setting that decided both would either unlock forever or ask
 * on every screen.
 *
 * The credential check itself is the platform's, not this app's - the repository
 * never sees a fingerprint or a PIN, it is only told "the person passed". That is
 * deliberate: a diary that could read the device code could also leak it.
 */
interface AppLockRepository {

    /** The live lock state, derived from the saved setting and this session. */
    val lockState: StateFlow<LockState>

    /** Turns the lock on or off and remembers the choice across restarts. */
    suspend fun setEnabled(enabled: Boolean)

    /** Records that the platform credential check succeeded. */
    fun onUnlockConfirmed()

    /**
     * Gives up the unlock, so the next time the app is looked at it asks again.
     * Called when the app leaves the foreground; a no-op while the lock is off.
     */
    fun lock()
}
