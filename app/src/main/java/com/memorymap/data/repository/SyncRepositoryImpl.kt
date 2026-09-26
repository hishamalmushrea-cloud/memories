package com.memorymap.data.repository

import com.memorymap.data.local.dao.DailyEntryDao
import com.memorymap.data.local.MediaFileStore
import com.memorymap.data.local.dao.MediaDao
import com.memorymap.data.local.dao.MemoryDao
import com.memorymap.data.local.dao.PersonDao
import com.memorymap.data.local.dao.PlaceDao
import com.memorymap.data.local.dao.SyncMetaDao
import com.memorymap.data.local.entities.SyncMetaEntity
import com.memorymap.data.remote.MediaStorage
import com.memorymap.data.remote.SupabaseClientProvider
import com.memorymap.data.remote.SyncApi
import com.memorymap.data.sync.EntrySyncTable
import com.memorymap.data.sync.MediaSyncTable
import com.memorymap.data.sync.MemorySyncTable
import com.memorymap.data.sync.PersonSyncTable
import com.memorymap.data.sync.PlaceSyncTable
import com.memorymap.data.sync.SyncEngine
import com.memorymap.data.sync.SyncReport
import com.memorymap.data.sync.SyncTable
import com.memorymap.domain.model.SyncOutcome
import com.memorymap.domain.model.SyncState
import com.memorymap.domain.repository.SyncRepository
import com.memorymap.util.ImageOptimizer
import com.memorymap.util.MmLog
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf

/**
 * Runs synchronisation and reports what it did.
 *
 * Two invariants hold here whatever the network does:
 *  - a run never throws to its caller, so a sync can never take the app down;
 *  - a run that fails leaves the queue exactly as it was, marked `SYNC_ERROR`,
 *    so the next run retries the same rows instead of silently dropping them.
 */
@Singleton
class SyncRepositoryImpl @Inject constructor(
    private val memoryDao: MemoryDao,
    private val entryDao: DailyEntryDao,
    private val mediaDao: MediaDao,
    private val personDao: PersonDao,
    private val placeDao: PlaceDao,
    private val metaDao: SyncMetaDao,
    private val api: SyncApi,
    private val storage: MediaStorage,
    private val files: MediaFileStore,
    private val images: ImageOptimizer,
    private val supabase: SupabaseClientProvider,
) : SyncRepository {

    private val engine = SyncEngine()
    private val running = MutableStateFlow(false)

    override fun watchState(userId: String?): Flow<SyncState> {
        if (userId.isNullOrBlank()) return flowOf(SyncState(cloudConfigured = supabase.isAvailable))

        return combine(
            memoryDao.watchPendingSyncCount(userId),
            entryDao.watchPendingSyncCount(userId),
            metaDao.watch(userId),
            running,
        ) { memories, entries, meta, isRunning ->
            SyncState(
                pending = memories + entries,
                lastRunAt = meta?.lastRunAt,
                lastOutcome = meta?.lastRunOutcome,
                isRunning = isRunning,
                cloudConfigured = supabase.isAvailable,
            )
        }
    }

    override suspend fun syncNow(userId: String?): SyncState {
        // No account, or an offline-only install: a supported state, not an error.
        if (userId.isNullOrBlank() || !supabase.isAvailable) return currentState(userId)

        running.value = true
        val since = try {
            metaDao.get(userId)?.lastDownloadAt
        } catch (error: Throwable) {
            MmLog.e("Could not read the sync bookmark", error)
            null
        }

        val outcome = try {
            val result = engine.sync(tables = tables(), userId = userId, since = since)
            writeMeta(userId, result.watermark, outcomeOf(result.report))
            outcomeOf(result.report)
        } catch (error: Throwable) {
            // The engine already contains failures per table; this catches
            // anything around it. The watermark is left where it was, so the
            // next run re-reads the same window rather than skipping past it.
            MmLog.e("Synchronisation failed", error)
            writeMeta(userId, since, SyncOutcome.ERROR)
            SyncOutcome.ERROR
        } finally {
            running.value = false
        }

        // The outcome is overlaid rather than re-read: the Room flow behind
        // watchState may not have observed the bookmark write yet, and the
        // caller — the worker deciding whether to retry — needs the truth.
        return currentState(userId).copy(lastOutcome = outcome)
    }

    /**
     * People and places go first.
     *
     * The server's link tables carry a foreign key to them, so a memory that
     * mentions a person can only be accepted once that person exists there.
     * Sending the referenced rows before the rows that reference them is what
     * keeps a first sync from failing on the very link it is trying to store.
     */
    private fun tables(): List<SyncTable<*>> = listOf(
        PersonSyncTable(personDao, api),
        PlaceSyncTable(placeDao, api),
        MemorySyncTable(memoryDao, api),
        EntrySyncTable(entryDao, api),
        // Last, and after the records it points at: an attachment names the
        // memory or event that owns it, so those rows have to exist first.
        MediaSyncTable(mediaDao, api, storage, files, images),
    )

    private suspend fun writeMeta(userId: String, watermark: String?, outcome: String) {
        try {
            metaDao.upsert(
                SyncMetaEntity(
                    userId = userId,
                    lastDownloadAt = watermark,
                    lastRunAt = LocalDateTime.now().toString(),
                    lastRunOutcome = outcome,
                ),
            )
        } catch (error: Throwable) {
            // Losing the bookmark costs a re-download, not data, so it must not
            // turn a successful sync into a reported failure.
            MmLog.e("Could not record the sync bookmark", error)
        }
    }

    private fun outcomeOf(report: SyncReport): String = when {
        !report.succeeded -> SyncOutcome.ERROR
        report.isEmpty -> SyncOutcome.NOTHING_TO_DO
        else -> SyncOutcome.OK
    }

    private suspend fun currentState(userId: String?): SyncState = watchState(userId).first()
}
