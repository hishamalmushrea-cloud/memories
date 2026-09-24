package com.memorymap.util.backup

import com.memorymap.data.local.entities.DailyEntryEntity
import com.memorymap.data.local.entities.MediaEntity
import com.memorymap.data.local.entities.MemoryEntity
import com.memorymap.data.local.entities.PersonEntity
import com.memorymap.data.local.entities.PlaceEntity
import com.memorymap.domain.model.SyncStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The rows of a backup archive.
 *
 * These are deliberately *not* the Room entities. A backup is a document a human
 * can open and read years from now, so it carries the content of a record and
 * none of the device state around it: no `sync_status`, no `last_synced_at`, no
 * tombstones. Those describe this phone's relationship with a server, and
 * restoring them onto a different phone would be meaningless at best.
 *
 * Timestamps stay in the naive local text Room uses, so an archive round-trips
 * byte for byte instead of shifting by a timezone on the way through.
 */

@Serializable
data class MemoryBackup(
    val id: String,
    @SerialName("user_id") val userId: String,
    val title: String,
    val text: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    @SerialName("place_name") val placeName: String? = null,
    @SerialName("memory_date") val memoryDate: String,
    val emotion: String,
    val visibility: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class EntryBackup(
    val id: String,
    @SerialName("user_id") val userId: String,
    val date: String,
    val time: String,
    val title: String,
    val text: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    @SerialName("place_id") val placeId: String? = null,
    val emotion: String? = null,
    @SerialName("linked_memory_id") val linkedMemoryId: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class PersonBackup(
    val id: String,
    @SerialName("user_id") val userId: String,
    val name: String,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class PlaceBackup(
    val id: String,
    @SerialName("user_id") val userId: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    @SerialName("created_at") val createdAt: String,
)

/**
 * An attachment as recorded in the archive.
 *
 * [archivePath] is where the bytes live inside the folder, so an import can put
 * them back without guessing from a file name.
 */
@Serializable
data class MediaBackup(
    val id: String,
    @SerialName("owner_type") val ownerType: String,
    @SerialName("owner_id") val ownerId: String,
    @SerialName("media_type") val mediaType: String,
    @SerialName("archive_path") val archivePath: String,
    @SerialName("mime_type") val mimeType: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    @SerialName("created_at") val createdAt: String,
)

/** Conversions between the archive documents and the rows Room holds. */
object BackupMappers {

    fun MemoryEntity.toBackup() = MemoryBackup(
        id = id,
        userId = userId,
        title = title,
        text = text,
        latitude = latitude,
        longitude = longitude,
        placeName = placeName,
        memoryDate = memoryDate,
        emotion = emotion,
        visibility = visibility,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    /**
     * Restored rows come back pending.
     *
     * Marking them `SYNCED` would claim an upload that never happened. A row the
     * device already had a copy of comes back as an update; a row it had never
     * seen comes back as a create.
     */
    fun MemoryBackup.toEntity(syncStatus: SyncStatus = SyncStatus.PENDING_CREATE) = MemoryEntity(
        id = id,
        userId = userId,
        title = title,
        text = text,
        latitude = latitude,
        longitude = longitude,
        placeName = placeName,
        memoryDate = memoryDate,
        emotion = emotion,
        visibility = visibility,
        createdAt = createdAt,
        updatedAt = updatedAt,
        syncStatus = syncStatus.name,
    )

    fun DailyEntryEntity.toBackup() = EntryBackup(
        id = id,
        userId = userId,
        date = date,
        time = time,
        title = title,
        text = text,
        latitude = latitude,
        longitude = longitude,
        placeId = placeId,
        emotion = emotion,
        linkedMemoryId = linkedMemoryId,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    fun EntryBackup.toEntity(syncStatus: SyncStatus = SyncStatus.PENDING_CREATE) = DailyEntryEntity(
        id = id,
        userId = userId,
        date = date,
        time = time,
        title = title,
        text = text,
        latitude = latitude,
        longitude = longitude,
        placeId = placeId,
        emotion = emotion,
        linkedMemoryId = linkedMemoryId,
        createdAt = createdAt,
        updatedAt = updatedAt,
        syncStatus = syncStatus.name,
    )

    fun PersonEntity.toBackup() = PersonBackup(
        id = id,
        userId = userId,
        name = name,
        createdAt = createdAt,
    )

    fun PersonBackup.toEntity() = PersonEntity(
        id = id,
        userId = userId,
        name = name,
        createdAt = createdAt,
    )

    fun PlaceEntity.toBackup() = PlaceBackup(
        id = id,
        userId = userId,
        name = name,
        latitude = latitude,
        longitude = longitude,
        createdAt = createdAt,
    )

    fun PlaceBackup.toEntity() = PlaceEntity(
        id = id,
        userId = userId,
        name = name,
        latitude = latitude,
        longitude = longitude,
        createdAt = createdAt,
    )

    fun MediaEntity.toBackup(archivePath: String) = MediaBackup(
        id = id,
        ownerType = ownerType,
        ownerId = ownerId,
        mediaType = mediaType,
        archivePath = archivePath,
        mimeType = mimeType,
        width = width,
        height = height,
        durationMs = durationMs,
        createdAt = createdAt,
    )

    /**
     * [storedPath] is where the bytes were copied back to on this device, which
     * is not the path in the archive and not the path on the phone that wrote it.
     */
    fun MediaBackup.toEntity(storedPath: String) = MediaEntity(
        id = id,
        ownerType = ownerType,
        ownerId = ownerId,
        mediaType = mediaType,
        uri = storedPath,
        mimeType = mimeType,
        width = width,
        height = height,
        durationMs = durationMs,
        createdAt = createdAt,
        syncStatus = SyncStatus.PENDING_CREATE.name,
    )
}
