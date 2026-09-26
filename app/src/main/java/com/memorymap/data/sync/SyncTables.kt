package com.memorymap.data.sync

import com.memorymap.data.local.dao.DailyEntryDao
import com.memorymap.data.local.MediaFileStore
import com.memorymap.data.local.dao.MediaDao
import com.memorymap.data.local.dao.MemoryDao
import com.memorymap.data.local.dao.PersonDao
import com.memorymap.data.local.dao.PlaceDao
import com.memorymap.data.local.entities.DailyEntryEntity
import com.memorymap.data.local.entities.MediaEntity
import com.memorymap.data.local.entities.MemoryEntity
import com.memorymap.data.local.entities.PersonEntity
import com.memorymap.data.local.entities.PlaceEntity
import com.memorymap.data.remote.EntryRecord
import com.memorymap.data.remote.MemoryRecord
import com.memorymap.data.remote.EntryPersonLink
import com.memorymap.data.remote.EntryPlaceLink
import com.memorymap.data.remote.MediaObjectKey
import com.memorymap.data.remote.MediaRecord
import com.memorymap.data.remote.MediaStorage
import com.memorymap.data.remote.MemoryPersonLink
import com.memorymap.data.remote.MemoryPlaceLink
import com.memorymap.data.remote.PersonRecord
import com.memorymap.data.remote.PlaceRecord
import com.memorymap.data.remote.SyncApi
import com.memorymap.domain.model.MediaType
import com.memorymap.domain.model.SyncStatus
import com.memorymap.util.ImageOptimizer
import com.memorymap.util.SyncTime
import java.time.LocalDateTime

/**
 * A server record together with the people and places it mentions.
 *
 * The link tables have no timestamps of their own, so there is nothing to
 * resolve a conflict with and no queue of their own to keep. They travel with
 * the record that owns them: that record already carries `updated_at`, it is
 * already queued when its links change, and replacing the whole set is what
 * makes an unlink reach another device instead of being merged back in.
 */
data class MemoryWithLinks(
    val record: MemoryRecord,
    val personIds: List<String> = emptyList(),
    val placeIds: List<String> = emptyList(),
)

/** The same, for a diary event. */
data class EntryWithLinks(
    val record: EntryRecord,
    val personIds: List<String> = emptyList(),
    val placeIds: List<String> = emptyList(),
)

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
) : SyncTable<MemoryWithLinks> {

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

    override suspend fun fetchChanged(userId: String, since: String?): List<MemoryWithLinks> {
        val records = api.fetchMemories(userId, since)
        if (records.isEmpty()) return emptyList()
        val ids = records.map { it.id }
        // Fetched here rather than at store time, so a network failure is
        // reported as the read failure it is and not as a failed write.
        val people = api.fetchMemoryPeople(ids).groupBy({ it.memoryId }, { it.personId })
        val places = api.fetchMemoryPlaces(ids).groupBy({ it.memoryId }, { it.placeId })
        return records.map { record ->
            MemoryWithLinks(
                record = record,
                personIds = people[record.id].orEmpty(),
                placeIds = places[record.id].orEmpty(),
            )
        }
    }

    override fun remoteInfo(row: MemoryWithLinks): RemoteRow = RemoteRow(
        id = row.record.id,
        // Converted to the naive local text Room uses, so it can be compared
        // against a local row without a timezone shifting the answer.
        updatedAt = SyncTime.toLocalText(row.record.updatedAt) ?: row.record.updatedAt,
        deleted = row.record.deletedAt != null,
    )

    override suspend fun localSnapshot(ids: List<String>): Map<String, LocalRow> =
        if (ids.isEmpty()) emptyMap() else dao.syncSnapshot(ids).associateBy { it.id }

    override suspend fun storeRemote(rows: List<MemoryWithLinks>) {
        if (rows.isEmpty()) return
        val now = clock()
        dao.upsertAll(rows.map { it.record.toEntity(now) })
        // Replaced rather than merged: a link removed on another device has to
        // disappear here too, and only a whole-set replace can express that.
        rows.forEach { row ->
            dao.replacePeople(row.record.id, row.personIds)
            dao.replacePlaces(row.record.id, row.placeIds)
        }
    }

    /** Both directions send the row whole; a tombstone simply carries a date. */
    private suspend fun push(rows: List<PendingRow>) {
        if (rows.isEmpty()) return
        val now = clock()
        val stored = rows.mapNotNull { dao.getById(it.id) }
        if (stored.isEmpty()) return
        api.upsertMemories(stored.map { it.toRecord(now) })

        // The record has to exist on the server before its links can point at
        // it, which is why the links go second.
        val ids = stored.map { it.id }
        val people = dao.personLinks(ids).groupBy({ it.ownerId }, { it.refId })
        val places = dao.placeLinks(ids).groupBy({ it.ownerId }, { it.refId })
        api.replaceMemoryPeople(
            memoryIds = ids,
            links = ids.flatMap { id -> (people[id].orEmpty()).map { MemoryPersonLink(id, it) } },
        )
        api.replaceMemoryPlaces(
            memoryIds = ids,
            links = ids.flatMap { id -> (places[id].orEmpty()).map { MemoryPlaceLink(id, it) } },
        )
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
) : SyncTable<EntryWithLinks> {

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

    override suspend fun fetchChanged(userId: String, since: String?): List<EntryWithLinks> {
        val records = api.fetchEntries(userId, since)
        if (records.isEmpty()) return emptyList()
        val ids = records.map { it.id }
        val people = api.fetchEntryPeople(ids).groupBy({ it.entryId }, { it.personId })
        val places = api.fetchEntryPlaces(ids).groupBy({ it.entryId }, { it.placeId })
        return records.map { record ->
            EntryWithLinks(
                record = record,
                personIds = people[record.id].orEmpty(),
                placeIds = places[record.id].orEmpty(),
            )
        }
    }

    override fun remoteInfo(row: EntryWithLinks): RemoteRow = RemoteRow(
        id = row.record.id,
        updatedAt = SyncTime.toLocalText(row.record.updatedAt) ?: row.record.updatedAt,
        deleted = row.record.deletedAt != null,
    )

    override suspend fun localSnapshot(ids: List<String>): Map<String, LocalRow> =
        if (ids.isEmpty()) emptyMap() else dao.syncSnapshot(ids).associateBy { it.id }

    override suspend fun storeRemote(rows: List<EntryWithLinks>) {
        if (rows.isEmpty()) return
        val now = clock()
        dao.upsertAll(rows.map { it.record.toEntity(now) })
        rows.forEach { row ->
            dao.replacePeople(row.record.id, row.personIds)
            dao.replacePlaces(row.record.id, row.placeIds)
        }
    }

    private suspend fun push(rows: List<PendingRow>) {
        if (rows.isEmpty()) return
        val now = clock()
        val stored = rows.mapNotNull { dao.getById(it.id) }
        if (stored.isEmpty()) return
        api.upsertEntries(stored.map { it.toRecord(now) })

        val ids = stored.map { it.id }
        val people = dao.personLinks(ids).groupBy({ it.ownerId }, { it.refId })
        val places = dao.placeLinks(ids).groupBy({ it.ownerId }, { it.refId })
        api.replaceEntryPeople(
            entryIds = ids,
            links = ids.flatMap { id -> (people[id].orEmpty()).map { EntryPersonLink(id, it) } },
        )
        api.replaceEntryPlaces(
            entryIds = ids,
            links = ids.flatMap { id -> (places[id].orEmpty()).map { EntryPlaceLink(id, it) } },
        )
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

/**
 * Synchronises the `media` table, and the bytes its rows point at.
 *
 * An attachment differs from every other row here in one way that shapes this
 * whole class: its content lives in a bucket, not in the row. So a push does two
 * things in a fixed order. The bytes go up first and the row second, because
 * `storage_path` is not null on the server and a row naming an object that is
 * not there yet is a lie the next device would act on.
 *
 * The queue is not every attachment. Only one the user asked for is in it, which
 * is what keeps uploading optional: an attachment nobody opted into never leaves
 * this device, and there is nothing on the server to delete when it is removed.
 */
class MediaSyncTable(
    private val dao: MediaDao,
    private val api: SyncApi,
    private val storage: MediaStorage,
    private val files: MediaFileStore,
    private val images: ImageOptimizer,
    private val clock: () -> String = { LocalDateTime.now().toString() },
) : SyncTable<MediaRecord> {

    /** Not `media`: this is the word the user sees in a message about a run. */
    override val name = "attachments"

    override suspend fun pending(userId: String): List<PendingRow> = dao.pendingForSync(userId)

    override suspend fun pushUpserts(rows: List<PendingRow>) {
        val stored = rows.mapNotNull { dao.getById(it.id) }
        if (stored.isEmpty()) return
        val now = clock()
        val userId = accountOf(stored)
        // All of them are uploaded before any row is sent, so a failure part-way
        // leaves the batch queued instead of half-described on the server.
        val keyed = stored.map { row -> row to (row.storagePath ?: upload(row, userId, now)) }
        api.upsertMedia(keyed.map { (row, key) -> row.toRecord(now, key, userId) })
    }

    override suspend fun pushDeletes(rows: List<PendingRow>) {
        if (rows.isEmpty()) return
        val now = clock()
        val stored = rows.mapNotNull { dao.getById(it.id) }
        if (stored.isEmpty()) return
        val userId = accountOf(stored)
        // The row carries the tombstone, so it goes first. The object is removed
        // only once the server knows, and a failure here leaves the row queued.
        api.upsertMedia(stored.map { it.toRecord(now, it.storagePath.orEmpty(), userId) })
        for (row in stored) {
            val key = row.storagePath ?: continue
            if (!storage.remove(key)) {
                throw IllegalStateException("The bytes of an attachment could not be removed")
            }
        }
    }

    override suspend fun markSynced(ids: List<String>, at: String) {
        if (ids.isNotEmpty()) dao.markSyncedAll(ids, at)
    }

    override suspend fun markError(ids: List<String>) {
        if (ids.isNotEmpty()) dao.markSyncError(ids)
    }

    override suspend fun fetchChanged(userId: String, since: String?): List<MediaRecord> =
        api.fetchMedia(userId, since)

    override fun remoteInfo(row: MediaRecord): RemoteRow = RemoteRow(
        id = row.id,
        updatedAt = SyncTime.toLocalText(row.updatedAt) ?: row.updatedAt,
        deleted = row.deletedAt != null,
    )

    override suspend fun localSnapshot(ids: List<String>): Map<String, LocalRow> =
        if (ids.isEmpty()) emptyMap() else dao.syncSnapshot(ids).associateBy { it.id }

    /**
     * Stores the row, then fetches the bytes it points at.
     *
     * Bytes are fetched for every kind of attachment, video included. The user
     * chose to put this one in the cloud, and a copy on the server that no other
     * device can read is not the backup they asked for. The local file is never
     * removed by any of this, so a copy is added, never moved.
     */
    override suspend fun storeRemote(rows: List<MediaRecord>) {
        if (rows.isEmpty()) return
        val now = clock()
        val stored = rows.map { row ->
            // A path this device already holds a real file at stays, so an
            // attachment captured here is never repointed at a second copy.
            val existing = dao.getById(row.id)?.uri?.takeIf { files.exists(it) }
            row to row.toEntity(now, files, existing)
        }
        dao.upsertAll(stored.map { it.second })

        var failed = 0
        for ((record, entity) in stored) {
            if (record.deletedAt != null || record.storagePath.isBlank()) continue
            if (files.exists(entity.uri)) continue
            val bytes = storage.download(record.storagePath)
            if (bytes == null || !files.write(entity.uri, bytes)) failed++
        }
        if (failed > 0) {
            // The rows are stored, so nothing is lost and the run reports what
            // did not arrive rather than claiming the attachments are all here.
            throw IllegalStateException("$failed attachment(s) could not be downloaded")
        }
    }

    /**
     * Puts the bytes in the bucket and returns the key they went to.
     *
     * The account is read here rather than carried on the row because the bucket
     * policy keys on it, and an attachment reaches its account only through the
     * record that owns it.
     *
     * What leaves the device is the prepared copy, not the file: a photo is
     * shrunk and its metadata stripped on the way out, and the key is built from
     * the extension that copy actually has rather than from the local name. The
     * file on this device is only ever read.
     */
    private suspend fun upload(row: MediaEntity, userId: String, now: String): String {
        val bytes = files.read(row.uri)
            ?: throw IllegalStateException("The file of an attachment is missing")
        val prepared = images.prepare(
            path = row.uri,
            type = MediaType.fromName(row.mediaType) ?: MediaType.PHOTO,
            bytes = bytes,
            fallbackExtension = MediaObjectKey.extensionOf(row.uri),
        )
        val key = MediaObjectKey.of(userId, row.id, prepared.extension)
        if (!storage.upload(key, prepared.bytes)) {
            throw IllegalStateException("An attachment could not be uploaded")
        }
        dao.setStoragePath(row.id, key, now)
        return key
    }

    /**
     * The account an attachment belongs to.
     *
     * The queue is already scoped to one account, so every row in a batch names
     * the same one; the ids are read only because the row itself does not carry
     * it and the bucket policy requires it.
     */
    private suspend fun accountOf(rows: List<MediaEntity>): String =
        rows.map { it.ownerId }.distinct()
            .firstNotNullOfOrNull { dao.ownerUserId(it) }
            ?: throw IllegalStateException("An attachment has no account to synchronise for")
}

private fun MediaRecord.type(): MediaType = MediaType.fromName(mediaType) ?: MediaType.PHOTO

/** The extension the object kept, which is what the key was built from. */
private fun MediaRecord.extension(): String = MediaObjectKey.extensionOf(storagePath)

/** The record as it will be sent, with the key its bytes are actually at. */
private fun MediaEntity.toRecord(now: String, storagePath: String, userId: String) = MediaRecord(
    id = id,
    userId = userId,
    ownerType = ownerType,
    ownerId = ownerId,
    mediaType = mediaType,
    storagePath = storagePath,
    mimeType = mimeType,
    width = width,
    height = height,
    durationMs = durationMs,
    createdAt = SyncTime.toInstantText(createdAt) ?: now,
    updatedAt = SyncTime.toInstantText(updatedAt) ?: now,
    deletedAt = SyncTime.toInstantText(deletedAt),
    // A row that is being sent is, by definition, in agreement.
    syncStatus = SyncStatus.SYNCED.name,
    lastSyncedAt = SyncTime.toInstantText(now),
)

/**
 * A received row as this device holds it.
 *
 * [existingPath] is a file this device already has for the same attachment, and
 * it wins: pointing the row at a derived path would strand the real bytes.
 */
private fun MediaRecord.toEntity(
    now: String,
    files: MediaFileStore,
    existingPath: String?,
) = MediaEntity(
    id = id,
    ownerType = ownerType,
    ownerId = ownerId,
    mediaType = mediaType,
    uri = existingPath ?: files.pathFor(type(), ownerId, id, extension()),
    mimeType = mimeType,
    width = width,
    height = height,
    durationMs = durationMs,
    createdAt = SyncTime.toLocalText(createdAt) ?: now,
    updatedAt = SyncTime.toLocalText(updatedAt) ?: now,
    syncStatus = SyncStatus.SYNCED.name,
    lastSyncedAt = SyncTime.toLocalText(now),
    deletedAt = SyncTime.toLocalText(deletedAt),
    storagePath = storagePath.takeIf { it.isNotBlank() },
    // Already in the bucket, so it is opted in by definition; a row with no key
    // is not, and must not be queued for an upload it cannot do.
    uploadRequested = storagePath.isNotBlank(),
)
