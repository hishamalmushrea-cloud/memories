package com.memorymap.domain.model

/**
 * What the UI is allowed to know about synchronisation.
 *
 * No table names, no row contents, no server responses — only enough to tell the
 * user whether their writing has reached the cloud and when that last happened.
 */
data class SyncState(
    /** Records waiting to travel, across every synced table. */
    val pending: Int = 0,
    /** Naive local ISO text of the last completed run, or null if never. */
    val lastRunAt: String? = null,
    /** One of [SyncOutcome], or null if sync has never run. */
    val lastOutcome: String? = null,
    val isRunning: Boolean = false,
    /** False on an offline-only install, where this is a supported state. */
    val cloudConfigured: Boolean = false,
)

/**
 * Stable keys describing the outcome of a run.
 *
 * These are stored in the database, so they must never be user-facing text: the
 * UI maps them onto the current language at display time.
 */
object SyncOutcome {
    const val OK = "ok"
    const val NOTHING_TO_DO = "nothing_to_do"
    const val ERROR = "error"
}
