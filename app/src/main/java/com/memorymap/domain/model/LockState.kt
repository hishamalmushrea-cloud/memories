package com.memorymap.domain.model

/**
 * Whether the app is currently asking the person to prove it is them.
 *
 * The lock is a front door for the whole archive, not a per-record setting: the
 * per-record [Visibility] decides who a synced record is shared with, while this
 * decides whether the app opens at all on a device that is already unlocked. It
 * exists because every other privacy guarantee in the app - no tracking, no ads,
 * row-level security on the server - is undone the moment someone else picks up
 * the phone and taps the icon.
 */
sealed interface LockState {

    /** The user never turned the lock on; the app opens straight away. */
    data object Disabled : LockState

    /** The lock is on and has not been passed yet this time the app is open. */
    data object Locked : LockState

    /** The lock is on and was passed; the app is usable until it is locked again. */
    data object Unlocked : LockState
}
