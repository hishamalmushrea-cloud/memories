package com.memorymap.data.sync

/** A row waiting to travel to the server. */
data class PendingRow(
    val id: String,
    /** Naive local ISO text, as Room stores it. */
    val updatedAt: String,
    /** A tombstone: deleted locally, and the deletion has not been sent yet. */
    val deleted: Boolean,
)

/** The local state of a row, read back so a conflict can be resolved. */
data class LocalRow(
    val id: String,
    val updatedAt: String,
    val deleted: Boolean,
)

/** The part of an incoming server row the engine needs in order to compare it. */
data class RemoteRow(
    val id: String,
    val updatedAt: String,
    val deleted: Boolean,
)

/** What one sync run did, so the UI can report it honestly. */
data class SyncReport(
    val uploaded: Int = 0,
    val deleted: Int = 0,
    val downloaded: Int = 0,
    val failed: Int = 0,
    val errors: List<String> = emptyList(),
) {
    val succeeded: Boolean get() = failed == 0
    val isEmpty: Boolean get() = uploaded == 0 && deleted == 0 && downloaded == 0

    operator fun plus(other: SyncReport): SyncReport = SyncReport(
        uploaded = uploaded + other.uploaded,
        deleted = deleted + other.deleted,
        downloaded = downloaded + other.downloaded,
        failed = failed + other.failed,
        errors = errors + other.errors,
    )
}

/**
 * One table that takes part in synchronisation, where [R] is its server row.
 *
 * Everything here is IO. The *policy* — which side wins a disagreement — lives in
 * [com.memorymap.domain.usecase.ConflictResolver] and is applied by [SyncEngine],
 * so it is identical for every table and is tested once.
 */
interface SyncTable<R> {

    /** The table name, used only in messages the user can read. */
    val name: String

    /** Rows whose `sync_status` is not `SYNCED`, oldest first. */
    suspend fun pending(userId: String): List<PendingRow>

    /** Sends living rows, creating or replacing them on the server. */
    suspend fun pushUpserts(rows: List<PendingRow>)

    /**
     * Sends deletions as tombstones rather than removing the server row.
     *
     * A hard delete would simply vanish from the next download, so another
     * device would keep its copy forever and the deletion would never spread.
     */
    suspend fun pushDeletes(rows: List<PendingRow>)

    /** Records that these rows now agree with the server. */
    suspend fun markSynced(ids: List<String>, at: String)

    /** Records that sending these rows failed, so the next run retries them. */
    suspend fun markError(ids: List<String>)

    /** Server rows changed since the watermark, tombstones included. */
    suspend fun fetchChanged(userId: String, since: String?): List<R>

    /** The comparable part of a server row. */
    fun remoteInfo(row: R): RemoteRow

    /** Local state for the given ids, so incoming rows can be compared. */
    suspend fun localSnapshot(ids: List<String>): Map<String, LocalRow>

    /** Stores the incoming rows that won their conflict. */
    suspend fun storeRemote(rows: List<R>)
}
