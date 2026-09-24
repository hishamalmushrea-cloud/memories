package com.memorymap.data.sync

import com.memorymap.data.local.dao.DailyEntryDao
import com.memorymap.data.local.dao.MemoryDao
import com.memorymap.data.local.dao.PersonDao
import com.memorymap.data.local.dao.PlaceDao
import com.memorymap.data.local.entities.DailyEntryEntity
import com.memorymap.data.local.entities.MemoryEntity
import com.memorymap.data.local.entities.PersonEntity
import com.memorymap.data.local.entities.PlaceEntity
import com.memorymap.data.remote.EntryRecord
import com.memorymap.data.remote.MemoryRecord
import com.memorymap.data.remote.PersonRecord
import com.memorymap.data.remote.PlaceRecord
import com.memorymap.data.remote.SyncApi
import com.memorymap.domain.model.SyncStatus
import com.memorymap.util.SyncTime
import java.time.LocalDateTime

/**
 * Synchronises the `memories` table.
 *
 * Rows are re-read from Room at the moment they are sent, so what reaches the
 * server is what the database holds now, not what it held when the queue was
 * listed a moment earlier.
 */
class MemorySyncTable(
    private val dao: MemoryDao,
    private val api: SyncApi,
    private val clock: () -> String = { LocalDateTime.now().toString() },
) : SyncTable<MemoryRecord> {

    override val name = "memories"

    override suspend fun pending(userId: String): List<PendingRow> = dao.pendingForSync(userId)

    override suspend fun pushUpserts(rows: List<PendingRow>) {
        push(rows)
    }

    override suspend fun pushDeletes(rows: List<PendingRow>) {
        push(rows)
    }

    override suspend fun markSynced(ids: List<String>, at: String) {
        if (ids.isNotEmpty()) dao.markSynced(ids, at)
    }

    override suspend fun markError(ids: List<String>) {
        if (ids.isNotEmpty()) dao.markSyncError(ids)
    }

    override suspend fun fetchChanged(userId: String, since: String?): List<MemoryRecord> =
        api.fetchMemories(userId, since)

    override fun remoteInfo(row: MemoryRecord): RemoteRow = RemoteRow(
        id = row.id,
        // Converted to the naive local text Room uses, so it can be compared
        // against a local row without a timezone shifting the answer.
        updatedAt = SyncTime.toLocalText(row.updatedAt) ?: row.updatedAt,
        deleted = row.deletedAt != null,
    )

    override suspend fun localSnapshot(ids: List<String>): Map<String, LocalRow> =
        if (ids.isEmpty()) emptyMap() else dao.syncSnapshot(ids).associateBy { it.id }

    override suspend fun storeRemote(rows: List<MemoryRecord>) {
        if (rows.isEmpty()) return
        val now = clock()
        dao.upsertAll(rows.map { it.toEntity(now) })
    }

    /** Both directions send the row whole; a tombstone simply carries a date. */
    private suspend fun push(rows: List<PendingRow>) {
        if (rows.isEmpty()) return
        val now = clock()
        val records = rows.mapNotNull { dao.getById(it.id) }.map { it.toRecord(now) }
        if (records.isNotEmpty()) api.upsertMemories(records)
    }

    private fun MemoryEntity.toRecord(now: String) = MemoryRecord(
        id = id,
        userId = userId,
        title = title,
        body = text,
        latitude = latitude,
        longitude = longitude,
        placeName = placeName,
        memoryDate = memoryDate,
        emotion = emotion,
        visibility = visibility,
        createdAt = SyncTime.toInstantText(createdAt) ?: now,
        updatedAt = SyncTime.toInstantText(updatedAt) ?: now,
        deletedAt = SyncTime.toInstantText(deletedAt),
        // A row that has just been accepted is, by definition, in agreement.
        syncStatus = SyncStatus.SYNCED.name,
        lastSyncedAt = SyncTime.toInstantText(now),
    )

    private fun MemoryRecord.toEntity(now: String) = MemoryEntity(
        id = id,
        userId = userId,
        title = title,
        text = body,
        latitude = latitude,
        longitude = longitude,
        placeName = placeName,
        memoryDate = memoryDate,
        emotion = emotion,
        visibility = visibility,
        createdAt = SyncTime.toLocalText(createdAt) ?: now,
        updatedAt = SyncTime.toLocalText(updatedAt) ?: now,
        deletedAt = SyncTime.toLocalText(deletedAt),
        syncStatus = SyncStatus.SYNCED.name,
        lastSyncedAt = now,
    )
}

/** Synchronises the `daily_entries` table. Same rules as [MemorySyncTable]. */
class EntrySyncTable(
    private val dao: DailyEntryDao,
    private val api: SyncApi,
    private val clock: () -> String = { LocalDateTime.now().toString() },
) : SyncTable<EntryRecord> {

    override val name = "daily_entries"

    override suspend fun pending(userId: String): List<PendingRow> = dao.pendingForSync(userId)

    override suspend fun pushUpserts(rows: List<PendingRow>) {
        push(rows)
    }

    override suspend fun pushDeletes(rows: List<PendingRow>) {
        push(rows)
    }

    override suspend fun markSynced(ids: List<String>, at: String) {
        if (ids.isNotEmpty()) dao.markSynced(ids, at)
    }

    override suspend fun markError(ids: List<String>) {
        if (ids.isNotEmpty()) dao.markSyncError(ids)
    }

    override suspend fun fetchChanged(userId: String, since: String?): List<EntryRecord> =
        api.fetchEntries(userId, since)

    override fun remoteInfo(row: EntryRecord): RemoteRow = RemoteRow(
        id = row.id,
        updatedAt = SyncTime.toLocalText(row.updatedAt) ?: row.updatedAt,
        deleted = row.deletedAt != null,
    )

    override suspend fun localSnapshot(ids: List<String>): Map<String, LocalRow> =
        if (ids.isEmpty()) emptyMap() else dao.syncSnapshot(ids).associateBy { it.id }

    override suspend fun storeRemote(rows: List<EntryRecord>) {
        if (rows.isEmpty()) return
        val now = clock()
        dao.upsertAll(rows.map { it.toEntity(now) })
    }

    private suspend fun push(rows: List<PendingRow>) {
        if (rows.isEmpty()) return
        val now = clock()
        val records = rows.mapNotNull { dao.getById(it.id) }.map { it.toRecord(now) }
        if (records.isNotEmpty()) api.upsertEntries(records)
    }

    private fun DailyEntryEntity.toRecord(now: String) = EntryRecord(
        id = id,
        userId = userId,
        entryDate = date,
        entryTime = SyncTime.toInstantText(time) ?: now,
        title = title,
        body = text,
        latitude = latitude,
        longitude = longitude,
        placeId = placeId,
        emotion = emotion,
        linkedMemoryId = linkedMemoryId,
        createdAt = SyncTime.toInstantText(createdAt) ?: now,
        updatedAt = SyncTime.toInstantText(updatedAt) ?: now,
        deletedAt = SyncTime.toInstantText(deletedAt),
        syncStatus = SyncStatus.SYNCED.name,
        lastSyncedAt = SyncTime.toInstantText(now),
    )

    private fun EntryRecord.toEntity(now: String) = DailyEntryEntity(
        id = id,
        userId = userId,
        date = entryDate,
        time = SyncTime.toLocalText(entryTime) ?: now,
        title = title,
        text = body,
        latitude = latitude,
        longitude = longitude,
        placeId = placeId,
        emotion = emotion,
        linkedMemoryId = linkedMemoryId,
        createdAt = SyncTime.toLocalText(createdAt) ?: now,
        updatedAt = SyncTime.toLocalText(updatedAt) ?: now,
        deletedAt = SyncTime.toLocalText(deletedAt),
        syncStatus = SyncStatus.SYNCED.name,
        lastSyncedAt = now,
    )
}

/**
 * Synchronises the `people` table.
 *
 * A person is a name the user reuses, so an edit to it changes every record it
 * is linked to at once - which is why it travels as a row of its own rather
 * than being copied into each memory.
 */
class PersonSyncTable(
    private val dao: PersonDao,
    private val api: SyncApi,
    private val clock: () -> String = { LocalDateTime.now().toString() },
) : SyncTable<PersonRecord> {

    override val name = "people"

    override suspend fun pending(userId: String): List<PendingRow> = dao.pendingForSync(userId)

    override suspend fun pushUpserts(rows: List<PendingRow>) {
        push(rows)
    }

    override suspend fun pushDeletes(rows: List<PendingRow>) {
        push(rows)
    }

    override suspend fun markSynced(ids: List<String>, at: String) {
        if (ids.isNotEmpty()) dao.markSynced(ids, at)
    }

    override suspend fun markError(ids: List<String>) {
        if (ids.isNotEmpty()) dao.markSyncError(ids)
    }

    override suspend fun fetchChanged(userId: String, since: String?): List<PersonRecord> =
        api.fetchPeople(userId, since)

    override fun remoteInfo(row: PersonRecord): RemoteRow = RemoteRow(
        id = row.id,
        updatedAt = SyncTime.toLocalText(row.updatedAt) ?: row.updatedAt,
        deleted = row.deletedAt != null,
    )

    override suspend fun localSnapshot(ids: List<String>): Map<String, LocalRow> =
        if (ids.isEmpty()) emptyMap() else dao.syncSnapshot(ids).associateBy { it.id }

    override suspend fun storeRemote(rows: List<PersonRecord>) {
        if (rows.isEmpty()) return
        val now = clock()
        dao.upsertAll(rows.map { it.toEntity(now) })
    }

    private suspend fun push(rows: List<PendingRow>) {
        if (rows.isEmpty()) return
        val now = clock()
        val records = rows.mapNotNull { dao.getById(it.id) }.map { it.toRecord(now) }
        if (records.isNotEmpty()) api.upsertPeople(records)
    }

    private fun PersonEntity.toRecord(now: String) = PersonRecord(
        id = id,
        userId = userId,
        name = name,
        createdAt = SyncTime.toInstantText(createdAt) ?: now,
        updatedAt = SyncTime.toInstantText(updatedAt) ?: now,
        deletedAt = SyncTime.toInstantText(deletedAt),
        syncStatus = SyncStatus.SYNCED.name,
        lastSyncedAt = SyncTime.toInstantText(now),
    )

    private fun PersonRecord.toEntity(now: String) = PersonEntity(
        id = id,
        userId = userId,
        name = name,
        createdAt = SyncTime.toLocalText(createdAt) ?: now,
        updatedAt = SyncTime.toLocalText(updatedAt) ?: now,
        deletedAt = SyncTime.toLocalText(deletedAt),
        syncStatus = SyncStatus.SYNCED.name,
        lastSyncedAt = now,
    )
}

/** Synchronises the `places` table. Same rules as [PersonSyncTable]. */
class PlaceSyncTable(
    private val dao: PlaceDao,
    private val api: SyncApi,
    private val clock: () -> String = { LocalDateTime.now().toString() },
) : SyncTable<PlaceRecord> {

    override val name = "places"

    override suspend fun pending(userId: String): List<PendingRow> = dao.pendingForSync(userId)

    override suspend fun pushUpserts(rows: List<PendingRow>) {
        push(rows)
    }

    override suspend fun pushDeletes(rows: List<PendingRow>) {
        push(rows)
    }

    override suspend fun markSynced(ids: List<String>, at: String) {
        if (ids.isNotEmpty()) dao.markSynced(ids, at)
    }

    override suspend fun markError(ids: List<String>) {
        if (ids.isNotEmpty()) dao.markSyncError(ids)
    }

    override suspend fun fetchChanged(userId: String, since: String?): List<PlaceRecord> =
        api.fetchPlaces(userId, since)

    override fun remoteInfo(row: PlaceRecord): RemoteRow = RemoteRow(
        id = row.id,
        updatedAt = SyncTime.toLocalText(row.updatedAt) ?: row.updatedAt,
        deleted = row.deletedAt != null,
    )

    override suspend fun localSnapshot(ids: List<String>): Map<String, LocalRow> =
        if (ids.isEmpty()) emptyMap() else dao.syncSnapshot(ids).associateBy { it.id }

    override suspend fun storeRemote(rows: List<PlaceRecord>) {
        if (rows.isEmpty()) return
        val now = clock()
        dao.upsertAll(rows.map { it.toEntity(now) })
    }

    private suspend fun push(rows: List<PendingRow>) {
        if (rows.isEmpty()) return
        val now = clock()
        val records = rows.mapNotNull { dao.getById(it.id) }.map { it.toRecord(now) }
        if (records.isNotEmpty()) api.upsertPlaces(records)
    }

    private fun PlaceEntity.toRecord(now: String) = PlaceRecord(
        id = id,
        userId = userId,
        name = name,
        latitude = latitude,
        longitude = longitude,
        createdAt = SyncTime.toInstantText(createdAt) ?: now,
        updatedAt = SyncTime.toInstantText(updatedAt) ?: now,
        deletedAt = SyncTime.toInstantText(deletedAt),
        syncStatus = SyncStatus.SYNCED.name,
        lastSyncedAt = SyncTime.toInstantText(now),
    )

    private fun PlaceRecord.toEntity(now: String) = PlaceEntity(
        id = id,
        userId = userId,
        name = name,
        latitude = latitude,
        longitude = longitude,
        createdAt = SyncTime.toLocalText(createdAt) ?: now,
        updatedAt = SyncTime.toLocalText(updatedAt) ?: now,
        deletedAt = SyncTime.toLocalText(deletedAt),
        syncStatus = SyncStatus.SYNCED.name,
        lastSyncedAt = now,
    )
}
