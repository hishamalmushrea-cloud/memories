package com.memorymap.domain.usecase

/**
 * Decides what an import does with a single archived record.
 *
 * An archive deliberately holds no tombstones, so an archived row is always
 * alive and the decision collapses to three cases. It delegates to
 * [ConflictResolver] instead of restating those rules, so an import and a sync
 * can never disagree about which copy of a record wins.
 */
object BackupMerge {

    enum class Decision {
        /** This device has never seen the record. */
        INSERT,

        /** The archived copy is newer than the one here, so it replaces it. */
        UPDATE,

        /** The local copy is newer, or was deliberately deleted, so it stays. */
        SKIP,
    }

    /** The row this device already holds for an id, when it holds one. */
    data class Existing(val updatedAt: String, val deletedAt: String?)

    fun decide(existing: Existing?, archivedUpdatedAt: String): Decision = when {
        existing == null -> Decision.INSERT

        ConflictResolver.resolve(
            localUpdatedAt = existing.updatedAt,
            localDeleted = existing.deletedAt != null,
            remoteUpdatedAt = archivedUpdatedAt,
            // An archived row is alive by construction.
            remoteDeleted = false,
        ) == ConflictResolver.Winner.REMOTE -> Decision.UPDATE

        else -> Decision.SKIP
    }
}
