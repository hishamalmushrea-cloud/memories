package com.memorymap.data.sync

import com.memorymap.domain.usecase.ConflictResolver
import com.memorymap.domain.usecase.ConflictResolver.Winner
import com.memorymap.util.MmLog
import java.time.LocalDateTime

/** The outcome of one run: what happened, and where the next run should start. */
data class SyncResult(
    val report: SyncReport,
    /** The newest server timestamp seen, or the previous watermark if none was. */
    val watermark: String?,
)

/**
 * Drives one synchronisation run: push what the user changed while offline, then
 * pull what changed elsewhere.
 *
 * The order matters. Pushing first means the server already holds this device's
 * edits when we ask it what changed, so a row we just sent cannot come back as
 * somebody else's change and overwrite itself.
 *
 * Failures are contained per table and per direction: a table that cannot be
 * reached is marked `SYNC_ERROR` so the next run retries it, while the other
 * tables still sync. Nothing is ever deleted locally because a request failed.
 */
class SyncEngine {

    suspend fun sync(
        tables: List<SyncTable<*>>,
        userId: String,
        since: String?,
        now: String = LocalDateTime.now().toString(),
    ): SyncResult {
        var report = SyncReport()
        var watermark = since

        for (table in tables) {
            report += push(table, userId, now)
            val pulled = pull(table, userId, since)
            report += pulled.report
            watermark = newerOf(watermark, pulled.newestSeen)
        }

        return SyncResult(report = report, watermark = watermark)
    }

    private suspend fun push(table: SyncTable<*>, userId: String, now: String): SyncReport {
        val pending = try {
            table.pending(userId)
        } catch (error: Throwable) {
            MmLog.e("Could not read the queue for ${table.name}", error)
            return failure(table, error)
        }
        if (pending.isEmpty()) return SyncReport()

        // A tombstone is a delete, not an update: sending it as a living row
        // would put the record back on the server instead of removing it.
        val deletes = pending.filter { it.deleted }
        val upserts = pending.filterNot { it.deleted }

        var report = SyncReport()

        if (upserts.isNotEmpty()) {
            val ids = upserts.map { it.id }
            report += try {
                table.pushUpserts(upserts)
                table.markSynced(ids, now)
                SyncReport(uploaded = upserts.size)
            } catch (error: Throwable) {
                MmLog.e("Uploading ${table.name} failed", error)
                recordFailure(table, ids)
                SyncReport(failed = upserts.size, errors = listOf("${table.name}: ${error.message}"))
            }
        }

        if (deletes.isNotEmpty()) {
            val ids = deletes.map { it.id }
            report += try {
                table.pushDeletes(deletes)
                table.markSynced(ids, now)
                SyncReport(deleted = deletes.size)
            } catch (error: Throwable) {
                MmLog.e("Sending ${table.name} deletions failed", error)
                recordFailure(table, ids)
                SyncReport(failed = deletes.size, errors = listOf("${table.name}: ${error.message}"))
            }
        }

        return report
    }

    /**
     * Generic in the row type so a table can hand back its own server records;
     * the engine only ever looks at them through [SyncTable.remoteInfo].
     */
    private suspend fun <R> pull(
        table: SyncTable<R>,
        userId: String,
        since: String?,
    ): PullOutcome {
        val remote = try {
            table.fetchChanged(userId, since)
        } catch (error: Throwable) {
            MmLog.e("Could not read ${table.name} from the server", error)
            return PullOutcome(failure(table, error))
        }
        if (remote.isEmpty()) return PullOutcome(SyncReport())

        val incoming = remote.map { row -> table.remoteInfo(row) to row }
        val newestSeen = incoming.maxOf { it.first.updatedAt }

        val local = try {
            table.localSnapshot(incoming.map { it.first.id })
        } catch (error: Throwable) {
            MmLog.e("Could not read the local copy of ${table.name}", error)
            return PullOutcome(failure(table, error), newestSeen)
        }

        val winners = incoming.filter { (info, _) ->
            val mine = local[info.id]
            // A row this device has never seen is simply new; there is no
            // conflict to resolve.
            mine == null ||
                ConflictResolver.resolve(
                    localUpdatedAt = mine.updatedAt,
                    localDeleted = mine.deleted,
                    remoteUpdatedAt = info.updatedAt,
                    remoteDeleted = info.deleted,
                ) == Winner.REMOTE
        }.map { it.second }

        try {
            table.storeRemote(winners)
        } catch (error: Throwable) {
            MmLog.e("Could not store the downloaded ${table.name}", error)
            return PullOutcome(failure(table, error), newestSeen)
        }

        return PullOutcome(SyncReport(downloaded = winners.size), newestSeen)
    }

    /**
     * Notes that sending these rows failed, so the next run retries them.
     *
     * Recording the failure is itself IO and can fail; that must not turn a
     * reported sync error into a crash inside the worker.
     */
    private suspend fun recordFailure(table: SyncTable<*>, ids: List<String>) {
        try {
            table.markError(ids)
        } catch (error: Throwable) {
            MmLog.e("Could not mark ${table.name} as failed", error)
        }
    }

    private fun failure(table: SyncTable<*>, error: Throwable) =
        SyncReport(failed = 1, errors = listOf("${table.name}: ${error.message}"))

    /**
     * Keeps the later of two watermarks.
     *
     * Compared as instants rather than as text, because the same moment can be
     * rendered with a `Z` or with an offset depending on where it came from.
     */
    private fun newerOf(current: String?, candidate: String?): String? {
        if (candidate == null) return current
        if (current == null) return candidate
        val parsedCurrent = ConflictResolver.parse(current) ?: return candidate
        val parsedCandidate = ConflictResolver.parse(candidate) ?: return current
        return if (parsedCandidate.isAfter(parsedCurrent)) candidate else current
    }

    private data class PullOutcome(
        val report: SyncReport,
        val newestSeen: String? = null,
    )
}
