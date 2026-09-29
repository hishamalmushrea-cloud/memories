package com.memorymap.domain.repository

import kotlinx.coroutines.flow.StateFlow

/**
 * The daily reminder: a local nudge, on the user's terms.
 *
 * It is opt-in and it never leaves the device. The content is computed on the
 * phone at the moment it fires - what this day held in earlier years, or an
 * invitation to write today - so there is no server round trip, no scheduling
 * service and nothing for anyone else to see. That is what keeps a notification
 * consistent with an app whose whole promise is that it does nothing behind the
 * user's back: the only thing that runs is the thing the user turned on.
 */
interface ReminderRepository {

    /** Whether the daily reminder is currently on. */
    val isEnabled: StateFlow<Boolean>

    /** Turns the reminder on or off, and schedules or cancels the daily work to match. */
    suspend fun setEnabled(enabled: Boolean)
}
