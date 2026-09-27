package com.memorymap.domain.model

/**
 * Synchronization state of a locally stored record.
 *
 * A single `isSynced: Boolean` is deliberately NOT used: a record that was
 * deleted while offline must stay in the database until the server confirms the
 * deletion, otherwise the server copy comes back on the next sync.
 */
enum class SyncStatus {
    /** Created locally, not yet sent to the server. */
    PENDING_CREATE,

    /** Edited locally after a successful upload. */
    PENDING_UPDATE,

    /** Deleted locally (tombstone), deletion not yet sent to the server. */
    PENDING_DELETE,

    /** Local and server copies agree. */
    SYNCED,

    /** The last upload attempt failed; WorkManager will retry. */
    SYNC_ERROR;

    /** True when the record still has to reach the server. */
    val isPending: Boolean
        get() = this != SYNCED

    companion object {
        fun fromName(value: String?): SyncStatus =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: PENDING_CREATE
    }
}
