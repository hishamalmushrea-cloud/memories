package com.memorymap.data.repository

import com.memorymap.data.local.Mappers.toDomain
import com.memorymap.data.local.Mappers.toEntity
import com.memorymap.data.local.dao.MediaDao
import com.memorymap.domain.model.MediaItem
import com.memorymap.domain.model.MediaOwner
import com.memorymap.domain.model.MediaSummary
import com.memorymap.domain.model.MediaType
import com.memorymap.domain.repository.MediaRepository
import com.memorymap.domain.repository.UploadRequest
import com.memorymap.util.MediaStore
import com.memorymap.util.MmLog
import com.memorymap.util.SyncTime
import com.memorymap.util.UploadLimit
import java.io.File
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room-backed attachment repository.
 *
 * Deleting an attachment keeps a tombstone row and removes the file, so an
 * offline delete is never resurrected by a later sync.
 */
@Singleton
class MediaRepositoryImpl @Inject constructor(
    private val mediaDao: MediaDao,
    /** What this project will accept in one file; see `UploadLimit`. */
    private val uploadLimit: UploadLimit = UploadLimit.FREE_PLAN,
) : MediaRepository {

    override fun watchFor(owner: MediaOwner, ownerId: String): Flow<List<MediaItem>> =
        mediaDao.watchFor(owner.name, ownerId).map { rows -> rows.map { it.toDomain() } }

    override suspend fun getFor(owner: MediaOwner, ownerId: String): List<MediaItem> =
        mediaDao.getFor(owner.name, ownerId).map { it.toDomain() }

    override suspend fun attach(item: MediaItem) {
        mediaDao.upsert(item.toEntity())
    }

    override suspend fun remove(id: String) {
        val existing = mediaDao.getById(id)
        mediaDao.softDelete(id, SyncTime.nowText())
        // The row survives as a tombstone; the bytes do not.
        existing?.let { deleteFile(it.uri) }
    }

    override suspend fun removeAllFor(owner: MediaOwner, ownerId: String) {
        val files = mediaDao.getFor(owner.name, ownerId).map { it.uri }
        mediaDao.softDeleteFor(owner.name, ownerId, SyncTime.nowText())
        files.forEach { deleteFile(it) }
    }

    override suspend fun countByType(): Map<MediaType, Int> =
        mediaDao.countByType().mapNotNull { row ->
            MediaType.fromName(row.mediaType)?.let { it to row.count }
        }.toMap()

    override fun watchSummary(owner: MediaOwner): Flow<Map<String, MediaSummary>> =
        mediaDao.watchSummary(owner.name).map { rows ->
            rows.associate { row ->
                row.ownerId to MediaSummary(
                    photos = row.photos,
                    audio = row.audio,
                    videos = row.videos,
                    coverUri = row.coverUri,
                )
            }
        }

    override suspend fun discard(item: MediaItem) {
        deleteFile(item.uri)
    }

    override suspend fun requestUpload(id: String): UploadRequest {
        val row = mediaDao.getById(id) ?: return UploadRequest.Queued
        // A photo is shrunk and stripped on the way out, so its size on this
        // device says nothing about the size that travels. Video and sound are
        // sent as they are, and a file the project will refuse is refused here.
        val type = MediaType.fromName(row.mediaType)
        if (type != MediaType.PHOTO && uploadLimit.exceeds(fileSize(row.uri))) {
            return UploadRequest.TooLarge(uploadLimit.megabytes)
        }
        // The stamp moves with the request so the queue sees it: rows are read
        // oldest-first by `updated_at`, and a request that did not move it would
        // sort as something that had already been dealt with.
        mediaDao.requestUpload(id, SyncTime.nowText())
        return UploadRequest.Queued
    }

    /** The size of the file on this device, or zero when it is not there. */
    private fun fileSize(uri: String): Long =
        runCatching { File(uri).length() }.getOrDefault(0L)

    override suspend fun cancelUpload(id: String) {
        mediaDao.cancelUpload(id)
    }

    /** Attachment URIs are app-private file paths, so the file is removed directly. */
    private fun deleteFile(uri: String) {
        runCatching { MediaStore.delete(File(uri)) }
            .onFailure { MmLog.e("Unable to delete the attachment file", it) }
    }
}
